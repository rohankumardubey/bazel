// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe.rewinding;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.flogger.GoogleLogger;
import com.google.common.graph.EndpointPair;
import com.google.common.graph.ImmutableGraph;
import com.google.common.graph.MutableGraph;
import com.google.common.graph.Traverser;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputDepOwners;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.LostInputsActionExecutionException;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.collect.nestedset.ArtifactNestedSetKey;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.server.FailureDetails.ActionRewinding;
import com.google.devtools.build.lib.skyframe.ActionUtils;
import com.google.devtools.build.lib.skyframe.ArtifactFunction.ArtifactDependencies;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeAwareAction;
import com.google.devtools.build.lib.skyframe.TopLevelActionLookupKeyWrapper;
import com.google.devtools.build.lib.skyframe.proto.ActionRewind.ActionDescription;
import com.google.devtools.build.lib.skyframe.proto.ActionRewind.ActionRewindEvent;
import com.google.devtools.build.lib.skyframe.proto.ActionRewind.LostInput;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunction.Reset;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Given an action that failed to execute because of lost inputs which were generated by other
 * actions, finds the actions which generated them and the set of Skyframe nodes which must be
 * rewound in order to recreate the lost inputs.
 */
public final class ActionRewindStrategy {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  @VisibleForTesting static final int MAX_REPEATED_LOST_INPUTS = 20;
  @VisibleForTesting static final int MAX_ACTION_REWIND_EVENTS = 5;
  private static final int MAX_LOST_INPUTS_RECORDED = 5;

  private final SkyframeActionExecutor skyframeActionExecutor;
  private final BugReporter bugReporter;

  private ConcurrentHashMultiset<LostInputRecord> currentBuildLostInputRecords =
      ConcurrentHashMultiset.create();
  private ConcurrentHashMultiset<LostInputRecord> currentBuildLostOutputRecords =
      ConcurrentHashMultiset.create();
  private final List<ActionRewindEvent> rewindEventSamples =
      Collections.synchronizedList(new ArrayList<>(MAX_ACTION_REWIND_EVENTS));
  private final AtomicInteger rewindEventSampleCounter = new AtomicInteger(0);

  public ActionRewindStrategy(
      SkyframeActionExecutor skyframeActionExecutor, BugReporter bugReporter) {
    this.skyframeActionExecutor = checkNotNull(skyframeActionExecutor);
    this.bugReporter = checkNotNull(bugReporter);
  }

  /**
   * Returns a {@link Reset} specifying the Skyframe nodes to rewind to recreate lost outputs
   * observed by a top-level completion function.
   *
   * <p>Also prepares {@link SkyframeActionExecutor} for the rewind plan.
   */
  public Reset prepareRewindPlanForLostTopLevelOutputs(
      TopLevelActionLookupKeyWrapper failedKey,
      Set<SkyKey> failedKeyDeps,
      ImmutableMap<String, ActionInput> lostOutputsByDigest,
      ActionInputDepOwners depOwners,
      Environment env)
      throws ActionRewindException, InterruptedException {
    checkState(
        skyframeActionExecutor.rewindingEnabled(),
        "Unexpected lost outputs: %s",
        lostOutputsByDigest);
    ImmutableList<LostInputRecord> lostOutputRecords =
        checkIfTopLevelOutputLostTooManyTimes(failedKey, lostOutputsByDigest);

    ImmutableList.Builder<Action> depsToRewind = ImmutableList.builder();
    Reset rewindPlan =
        prepareRewindPlan(
            failedKey, failedKeyDeps, lostOutputsByDigest, depOwners, env, depsToRewind);

    if (shouldRecordRewindEventSample()) {
      rewindEventSamples.add(createLostOutputRewindEvent(failedKey, rewindPlan, lostOutputRecords));
    }

    for (Action dep : depsToRewind.build()) {
      skyframeActionExecutor.prepareDepForRewinding(failedKey, dep);
    }
    return rewindPlan;
  }

  /**
   * Returns a {@link Reset} specifying the Skyframe nodes to rewind to recreate the lost inputs
   * specified by {@code lostInputsException}.
   *
   * <p>Also prepares {@link SkyframeActionExecutor} for the rewind plan and emits an {@link
   * ActionRewoundEvent} if necessary.
   *
   * @throws ActionRewindException if any lost inputs have been seen by this action as lost before
   *     too many times
   */
  public Reset prepareRewindPlanForLostInputs(
      ActionLookupData failedKey,
      Action failedAction,
      Set<SkyKey> failedActionDeps,
      LostInputsActionExecutionException lostInputsException,
      ActionInputDepOwners inputDepOwners,
      Environment env,
      long actionStartTimeNanos)
      throws ActionRewindException, InterruptedException {
    checkState(
        skyframeActionExecutor.rewindingEnabled(),
        "Unexpected lost inputs: %s",
        lostInputsException.getLostInputs());
    ImmutableMap<String, ActionInput> lostInputsByDigest = lostInputsException.getLostInputs();
    ImmutableList<LostInputRecord> lostInputRecords =
        checkIfActionLostInputTooManyTimes(failedKey, failedAction, lostInputsByDigest);

    ImmutableList.Builder<Action> depsToRewind = ImmutableList.builder();
    Reset rewindPlan =
        prepareRewindPlan(
            failedKey, failedActionDeps, lostInputsByDigest, inputDepOwners, env, depsToRewind);

    if (shouldRecordRewindEventSample()) {
      rewindEventSamples.add(
          createLostInputRewindEvent(failedAction, rewindPlan, lostInputRecords));
    }

    if (lostInputsException.isActionStartedEventAlreadyEmitted()) {
      env.getListener()
          .post(new ActionRewoundEvent(actionStartTimeNanos, BlazeClock.nanoTime(), failedAction));
    }
    skyframeActionExecutor.prepareForRewinding(failedKey, failedAction, depsToRewind.build());
    return rewindPlan;
  }

  private Reset prepareRewindPlan(
      SkyKey failedKey,
      Set<SkyKey> failedActionDeps,
      ImmutableMap<String, ActionInput> lostInputsByDigest,
      ActionInputDepOwners inputDepOwners,
      Environment env,
      ImmutableList.Builder<Action> depsToRewind)
      throws InterruptedException {
    ImmutableList<ActionInput> lostInputs = lostInputsByDigest.values().asList();

    // This graph tracks which Skyframe nodes must be rewound and the dependency relationships
    // between them.
    MutableGraph<SkyKey> rewindGraph = Reset.newRewindGraphFor(failedKey);

    // With NSOS, not all input artifacts' keys are direct deps of the action. This maps input
    // artifacts to their containing direct dep ArtifactNestedSetKey(s).
    Multimap<Artifact, ArtifactNestedSetKey> nestedSetKeys = expandNestedSetKeys(failedActionDeps);

    Set<DerivedArtifact> lostArtifacts =
        getLostInputOwningDirectDeps(
            failedKey,
            lostInputs,
            inputDepOwners,
            ImmutableSet.<SkyKey>builder()
                .addAll(failedActionDeps)
                .addAll(Artifact.keys(nestedSetKeys.keySet()))
                .build());

    for (DerivedArtifact lostArtifact : lostArtifacts) {
      SkyKey artifactKey = Artifact.key(lostArtifact);

      Map<ActionLookupData, Action> actionMap = getActionsForLostArtifact(lostArtifact, env);
      if (actionMap == null) {
        // Some deps of the artifact are not done. Another rewind must be in-flight, and there is no
        // need to rewind the shared deps twice.
        continue;
      }
      ImmutableList<ActionAndLookupData> newlyVisitedActions =
          addArtifactDepsAndGetNewlyVisitedActions(rewindGraph, lostArtifact, actionMap);

      for (ArtifactNestedSetKey nestedSetKey : nestedSetKeys.get(lostArtifact)) {
        addNestedSetToRewindGraph(rewindGraph, failedKey, lostArtifact, nestedSetKey);
      }
      // Note that artifactKey must be rewound. We do this after
      // addArtifactDepsAndGetNewlyVisitedActions so that it can track if actions are already known
      // to be in the graph. With NSOS, it is possible that artifactKey is not actually a direct dep
      // of the action, but this edge is benign since it's always a transitive dep.
      rewindGraph.putEdge(failedKey, artifactKey);
      depsToRewind.addAll(actions(newlyVisitedActions));
      checkActions(newlyVisitedActions, env, rewindGraph, depsToRewind);
    }

    return Reset.of(rewindGraph);
  }

  /**
   * Creates a {@link Reset} to recover from undone indirect inputs that are unavailable due to
   * unsuccessful rewinding.
   *
   * <p>Undone direct dependencies are handled by Skyframe (see {@link
   * com.google.devtools.build.skyframe.SkyFunctionEnvironment.UndonePreviouslyRequestedDeps}). This
   * method only exists for artifacts whose {@link Artifact#key} is <em>not</em> a direct dependency
   * of {@code failedKey} because the artifact is behind an {@link ArtifactNestedSetKey}.
   *
   * <p>Used when an indirect dependency {@link Artifact#key} was rewound and completed with an
   * error, but intermediate nested set nodes were never rewound, resulting in an inconsistent state
   * where successful nodes depend on a node in error.
   *
   * <p>The returned {@link Reset} contains the {@link Artifact#key}, but that is expected to
   * already be in error, and attempting to rewind an error is no-op.
   */
  public Reset patchNestedSetGraphToPropagateError(
      ActionLookupData failedKey,
      Action failedAction,
      ImmutableList<Artifact> undoneInputs,
      ImmutableSet<SkyKey> failedActionDeps) {
    checkState(
        skyframeActionExecutor.rewindingEnabled(), "Unexpected undone inputs: %s", undoneInputs);
    MutableGraph<SkyKey> rewindGraph = Reset.newRewindGraphFor(failedKey);
    Multimap<Artifact, ArtifactNestedSetKey> nestedSetKeys = expandNestedSetKeys(failedActionDeps);
    for (Artifact input : undoneInputs) {
      Collection<ArtifactNestedSetKey> containingNestedSets = nestedSetKeys.get(input);
      checkState(
          !containingNestedSets.isEmpty(),
          "Cannot find input %s under any nested set deps of %s",
          input,
          failedKey);
      for (ArtifactNestedSetKey nestedSetKey : containingNestedSets) {
        addNestedSetToRewindGraph(rewindGraph, failedKey, input, nestedSetKey);
      }
    }

    // An undone input may be observed either during input checking (before attempting action
    // execution) or during lost input handling (after attempting action execution). In the latter
    // case, it is necessary to obsolete the ActionExecutionState so that after rewinding, we will
    // check inputs again and discover the propagated exception. This call is a no-op in the former
    // case, since there is no ActionExecutionState to obsolete.
    skyframeActionExecutor.prepareForRewinding(
        failedKey, failedAction, /* depsToRewind= */ ImmutableList.of());

    return Reset.of(rewindGraph);
  }

  /**
   * Logs the first N action rewind events and clears the history of failed actions' lost inputs and
   * rewind plans.
   */
  public void reset(ExtendedEventHandler eventHandler) {
    ActionRewindingStats rewindingStats =
        new ActionRewindingStats(
            currentBuildLostInputRecords.size(),
            currentBuildLostOutputRecords.size(),
            ImmutableList.copyOf(rewindEventSamples));
    eventHandler.post(rewindingStats);
    currentBuildLostInputRecords = ConcurrentHashMultiset.create();
    currentBuildLostOutputRecords = ConcurrentHashMultiset.create();
    rewindEventSamples.clear();
    rewindEventSampleCounter.set(0);
  }

  private ImmutableList<LostInputRecord> checkIfTopLevelOutputLostTooManyTimes(
      TopLevelActionLookupKeyWrapper failedKey,
      ImmutableMap<String, ActionInput> lostOutputsByDigest)
      throws ActionRewindException {
    ImmutableList<LostInputRecord> lostOutputRecords =
        createLostInputRecords(failedKey, lostOutputsByDigest);
    for (LostInputRecord lostInputRecord : lostOutputRecords) {
      String digest = lostInputRecord.lostInputDigest();
      int losses = currentBuildLostOutputRecords.add(lostInputRecord, /* occurrences= */ 1) + 1;
      if (losses > MAX_REPEATED_LOST_INPUTS) {
        ActionInput output = lostOutputsByDigest.get(digest);
        String prettyPrintedOutput =
            output instanceof Artifact
                ? ((Artifact) output).prettyPrint()
                : output.getExecPathString();
        ActionRewindException e =
            new ActionRewindException(
                String.format(
                    "Lost output %s (digest %s), and rewinding was ineffective after %d attempts.",
                    prettyPrintedOutput, digest, MAX_REPEATED_LOST_INPUTS),
                ActionRewinding.Code.LOST_OUTPUT_TOO_MANY_TIMES);
        bugReporter.sendBugReport(e);
        throw e;
      } else if (losses > 1) {
        logger.atWarning().log(
            "Lost output again (losses=%s, output=%s, digest=%s, failedKey=%s)",
            losses, lostOutputsByDigest.get(digest), digest, failedKey);
      }
    }
    return lostOutputRecords;
  }

  /** Returns all lost input records that will cause the failed action to rewind. */
  private ImmutableList<LostInputRecord> checkIfActionLostInputTooManyTimes(
      ActionLookupData failedKey,
      Action failedAction,
      ImmutableMap<String, ActionInput> lostInputsByDigest)
      throws ActionRewindException {
    ImmutableList<LostInputRecord> lostInputRecords =
        createLostInputRecords(failedKey, lostInputsByDigest);
    for (LostInputRecord lostInputRecord : lostInputRecords) {
      // The same action losing the same input more than once is unexpected [*]. The action should
      // have waited until the depended-on action which generates the lost input is (re)run before
      // trying again.
      //
      // Note that we could enforce a stronger check: if action A, which depends on an input N
      // previously detected as lost (by any action, not just A), discovers that N is still lost,
      // and action A started after the re-evaluation of N's generating action, then something has
      // gone wrong. Administering that check would be more complex (e.g., the start/completion
      // times of actions would need tracking), so we punt on it for now.
      //
      // [*], TODO(b/123993876): To mitigate a race condition (believed to be) caused by
      // non-topological Skyframe dirtying of depended-on nodes, this check fails the build only if
      // the same input is repeatedly lost.
      String digest = lostInputRecord.lostInputDigest();
      int losses = currentBuildLostInputRecords.add(lostInputRecord, /* occurrences= */ 1) + 1;
      if (losses > MAX_REPEATED_LOST_INPUTS) {
        // This ensures coalesced shared actions aren't orphaned.
        skyframeActionExecutor.prepareForRewinding(
            failedKey, failedAction, /* depsToRewind= */ ImmutableList.of());

        String message =
            String.format(
                "lost input too many times (#%s) for the same action. lostInput: %s, "
                    + "lostInput digest: %s, failedAction: %.10000s",
                losses, lostInputsByDigest.get(digest), digest, failedAction);
        ActionRewindException e =
            new ActionRewindException(message, ActionRewinding.Code.LOST_INPUT_TOO_MANY_TIMES);
        bugReporter.sendBugReport(e);
        throw e;
      } else if (losses > 1) {
        logger.atInfo().log(
            "lost input again (#%s) for the same action. lostInput: %s, "
                + "lostInput digest: %s, failedAction: %.10000s",
            losses, lostInputsByDigest.get(digest), digest, failedAction);
      }
    }
    return lostInputRecords;
  }

  private static ImmutableList<LostInputRecord> createLostInputRecords(
      SkyKey failedKey, ImmutableMap<String, ActionInput> lostInputsByDigest) {
    return lostInputsByDigest.entrySet().stream()
        .map(e -> LostInputRecord.create(failedKey, e.getKey(), e.getValue().getExecPathString()))
        .collect(toImmutableList());
  }

  private Set<DerivedArtifact> getLostInputOwningDirectDeps(
      SkyKey failedKey,
      ImmutableList<ActionInput> lostInputs,
      ActionInputDepOwners inputDepOwners,
      Set<SkyKey> failedActionDeps) {
    Set<DerivedArtifact> lostInputOwningDirectDeps = new HashSet<>();
    for (ActionInput lostInput : lostInputs) {
      boolean foundLostInputDepOwner = false;

      Collection<Artifact> owners = inputDepOwners.getDepOwners(lostInput);
      for (Artifact owner : owners) {
        checkDerived(owner);

        // Rewinding must invalidate all Skyframe paths from the failed action to the action which
        // generates the lost input. Intermediate nodes not on the shortest path to that action may
        // have values that depend on the output of that action. If these intermediate nodes are not
        // invalidated, then their values may become stale. Therefore, this method collects not only
        // the first action dep associated with the lost input, but all of them.

        Collection<Artifact> transitiveOwners = inputDepOwners.getDepOwners(owner);
        for (Artifact transitiveOwner : transitiveOwners) {
          checkDerived(transitiveOwner);

          if (failedActionDeps.contains(Artifact.key(transitiveOwner))) {
            // The lost input is included in an aggregation artifact (e.g. a tree artifact or
            // fileset) that is included by an aggregation artifact (e.g. a middleman) that the
            // action directly depends on.
            lostInputOwningDirectDeps.add((DerivedArtifact) transitiveOwner);
            foundLostInputDepOwner = true;
          }
        }

        if (failedActionDeps.contains(Artifact.key(owner))) {
          // The lost input is included in an aggregation artifact (e.g. a tree artifact, fileset,
          // or middleman) that the action directly depends on.
          lostInputOwningDirectDeps.add((DerivedArtifact) owner);
          foundLostInputDepOwner = true;
        }
      }

      if (lostInput instanceof Artifact
          && failedActionDeps.contains(Artifact.key((Artifact) lostInput))) {
        checkDerived((Artifact) lostInput);

        lostInputOwningDirectDeps.add((DerivedArtifact) lostInput);
        foundLostInputDepOwner = true;
      }

      if (!foundLostInputDepOwner) {
        // Rewinding can't do anything about a lost input that can't be associated with a direct dep
        // of the failed action. In this case, try resetting the failed action (and no other deps)
        // just in case that helps. If it does not help, then eventually the action will fail in
        // checkIfActionLostInputTooManyTimes.
        bugReporter.sendNonFatalBugReport(
            new IllegalStateException(
                String.format(
                    "Lost input not a dep of the failed action and can't be associated with such"
                        + " a dep. lostInput: %s, owners: %s, failedKey: %s",
                    lostInput, owners, failedKey)));
      }
    }
    return lostInputOwningDirectDeps;
  }

  private static void checkDerived(Artifact artifact) {
    checkState(!artifact.isSourceArtifact(), "Unexpected source artifact: %s", artifact);
  }

  /**
   * Looks at each action in {@code actionsToCheck} and determines whether additional artifacts,
   * actions, and (in the case of {@link SkyframeAwareAction}s) other Skyframe nodes need to be
   * rewound. If this finds more actions to rewind, those actions are recursively checked too.
   */
  private void checkActions(
      ImmutableList<ActionAndLookupData> actionsToCheck,
      Environment env,
      MutableGraph<SkyKey> rewindGraph,
      ImmutableList.Builder<Action> depsToRewind)
      throws InterruptedException {
    ArrayDeque<ActionAndLookupData> uncheckedActions = new ArrayDeque<>(actionsToCheck);
    while (!uncheckedActions.isEmpty()) {
      ActionAndLookupData actionAndLookupData = uncheckedActions.removeFirst();
      ActionLookupData actionKey = actionAndLookupData.lookupData();
      Action action = actionAndLookupData.action();
      ArrayList<DerivedArtifact> artifactsToCheck = new ArrayList<>();
      ArrayList<ActionLookupData> newlyDiscoveredActions = new ArrayList<>();

      if (action instanceof SkyframeAwareAction) {
        // This action depends on more than just its input artifact values. We need to also rewind
        // the Skyframe subgraph it depends on, up to and including any artifacts, which may
        // aggregate multiple actions.
        addSkyframeAwareDepsAndGetNewlyVisitedArtifactsAndActions(
            rewindGraph,
            actionKey,
            (SkyframeAwareAction) action,
            artifactsToCheck,
            newlyDiscoveredActions);
      }

      if (action.mayInsensitivelyPropagateInputs()) {
        // Rewinding this action won't recreate the missing input. We need to also rewind this
        // action's non-source inputs and the actions which created those inputs.
        addPropagatingActionDepsAndGetNewlyVisitedArtifactsAndActions(
            rewindGraph, actionKey, action, artifactsToCheck, newlyDiscoveredActions);
      }

      for (ActionLookupData actionLookupData : newlyDiscoveredActions) {
        Action additionalAction =
            checkNotNull(
                ActionUtils.getActionForLookupData(env, actionLookupData), actionLookupData);
        depsToRewind.add(additionalAction);
        uncheckedActions.add(ActionAndLookupData.create(actionLookupData, additionalAction));
      }
      for (DerivedArtifact artifact : artifactsToCheck) {
        Map<ActionLookupData, Action> actionMap = getActionsForLostArtifact(artifact, env);
        if (actionMap == null) {
          continue;
        }
        ImmutableList<ActionAndLookupData> newlyVisitedActions =
            addArtifactDepsAndGetNewlyVisitedActions(rewindGraph, artifact, actionMap);
        depsToRewind.addAll(actions(newlyVisitedActions));
        uncheckedActions.addAll(newlyVisitedActions);
      }
    }
  }

  /**
   * For a {@link SkyframeAwareAction} {@code action} with key {@code actionKey}, add its Skyframe
   * subgraph to {@code rewindGraph}, any {@link Artifact}s to {@code newlyVisitedArtifacts}, and
   * any {@link ActionLookupData}s to {@code newlyVisitedActions}.
   */
  private static void addSkyframeAwareDepsAndGetNewlyVisitedArtifactsAndActions(
      MutableGraph<SkyKey> rewindGraph,
      ActionLookupData actionKey,
      SkyframeAwareAction action,
      ArrayList<DerivedArtifact> newlyVisitedArtifacts,
      ArrayList<ActionLookupData> newlyVisitedActions) {

    ImmutableGraph<SkyKey> graph = action.getSkyframeDependenciesForRewinding(actionKey);
    if (graph.nodes().isEmpty()) {
      return;
    }
    assertSkyframeAwareRewindingGraph(graph, actionKey);

    Set<EndpointPair<SkyKey>> edges = graph.edges();
    for (EndpointPair<SkyKey> edge : edges) {
      SkyKey target = edge.target();
      if (target instanceof Artifact && rewindGraph.addNode(target)) {
        newlyVisitedArtifacts.add(((DerivedArtifact) target));
      }
      if (target instanceof ActionLookupData && rewindGraph.addNode(target)) {
        newlyVisitedActions.add(((ActionLookupData) target));
      }
      rewindGraph.putEdge(edge.source(), edge.target());
    }
  }

  /**
   * For a propagating {@code action} with key {@code actionKey}, add its generated inputs' keys to
   * {@code rewindGraph}, add edges from {@code actionKey} to those keys, add any {@link Artifact}s
   * to {@code newlyVisitedArtifacts}, and add any {@link ActionLookupData}s to {@code
   * newlyVisitedActions}.
   */
  private void addPropagatingActionDepsAndGetNewlyVisitedArtifactsAndActions(
      MutableGraph<SkyKey> rewindGraph,
      ActionLookupData actionKey,
      Action action,
      ArrayList<DerivedArtifact> newlyVisitedArtifacts,
      ArrayList<ActionLookupData> newlyVisitedActions) {

    for (Artifact input : action.getInputs().toList()) {
      if (input.isSourceArtifact()) {
        continue;
      }
      SkyKey artifactKey = Artifact.key(input);
      // Rewinding all derived inputs of propagating actions is overkill. Preferably, we'd want to
      // only rewind the inputs which correspond to the known lost outputs. The information to do
      // this is probably present in the data available to #prepareRewindPlan.
      //
      // Rewinding is expected to be rare, so refining this may not be necessary.
      boolean newlyVisited = rewindGraph.addNode(artifactKey);
      if (newlyVisited) {
        if (artifactKey instanceof Artifact) {
          newlyVisitedArtifacts.add((DerivedArtifact) artifactKey);
        } else if (artifactKey instanceof ActionLookupData) {
          newlyVisitedActions.add((ActionLookupData) artifactKey);
        }
      }
      rewindGraph.putEdge(actionKey, artifactKey);
    }

    // Rewinding ignores artifacts returned by Action#getAllowedDerivedInputs because:
    // 1) the set of actions with non-throwing implementations of getAllowedDerivedInputs,
    // 2) the set of actions that "mayInsensitivelyPropagateInputs",
    // should have no overlap. Log a bug report if we see such an action:
    if (action.discoversInputs()) {
      bugReporter.sendBugReport(
          new IllegalStateException(
              String.format(
                  "Action insensitively propagates and discovers inputs. actionKey: %s, action: "
                      + "%.10000s",
                  actionKey, action)));
    }
  }

  /**
   * For an artifact {@code artifact} with generating actions (and their associated {@link
   * ActionLookupData}) {@code actionMap}, add those actions' keys to {@code rewindGraph} and add
   * edges from {@code artifact} to those keys.
   *
   * <p>Returns a list of key+action pairs for each action whose key was newly added to the graph.
   */
  private static ImmutableList<ActionAndLookupData> addArtifactDepsAndGetNewlyVisitedActions(
      MutableGraph<SkyKey> rewindGraph,
      Artifact artifact,
      Map<ActionLookupData, Action> actionMap) {

    ImmutableList.Builder<ActionAndLookupData> newlyVisitedActions =
        ImmutableList.builderWithExpectedSize(actionMap.size());
    SkyKey artifactKey = Artifact.key(artifact);
    for (Map.Entry<ActionLookupData, Action> actionEntry : actionMap.entrySet()) {
      ActionLookupData actionKey = actionEntry.getKey();
      if (rewindGraph.addNode(actionKey)) {
        newlyVisitedActions.add(ActionAndLookupData.create(actionKey, actionEntry.getValue()));
      }
      if (!artifactKey.equals(actionKey)) {
        rewindGraph.putEdge(artifactKey, actionKey);
      }
    }
    return newlyVisitedActions.build();
  }

  /**
   * Returns the map of {@code lostInput}'s execution-phase dependencies (i.e. generating actions),
   * keyed by their {@link ActionLookupData} keys, or {@code null} if any of those dependencies are
   * not done.
   */
  @Nullable
  private static Map<ActionLookupData, Action> getActionsForLostArtifact(
      DerivedArtifact lostInput, Environment env) throws InterruptedException {
    Set<ActionLookupData> actionExecutionDeps = getActionExecutionDeps(lostInput, env);
    if (actionExecutionDeps == null) {
      return null;
    }

    Map<ActionLookupData, Action> actions =
        Maps.newHashMapWithExpectedSize(actionExecutionDeps.size());
    for (ActionLookupData dep : actionExecutionDeps) {
      actions.put(dep, checkNotNull(ActionUtils.getActionForLookupData(env, dep)));
    }
    return actions;
  }

  /**
   * Returns the set of {@code lostInput}'s execution-phase dependencies (i.e. generating actions),
   * or {@code null} if any of those dependencies are not done.
   */
  @Nullable
  private static ImmutableSet<ActionLookupData> getActionExecutionDeps(
      DerivedArtifact lostInput, Environment env) throws InterruptedException {
    if (!lostInput.isTreeArtifact()) {
      return ImmutableSet.of(lostInput.getGeneratingActionKey());
    }
    ArtifactDependencies artifactDependencies =
        ArtifactDependencies.discoverDependencies(lostInput, env);
    if (artifactDependencies == null) {
      return null;
    }

    if (!artifactDependencies.isTemplateActionForTreeArtifact()) {
      return ImmutableSet.of(lostInput.getGeneratingActionKey());
    }

    // This ignores the ActionTemplateExpansionKey dependency of the template artifact because we
    // expect to never need to rewind that.
    ImmutableList<ActionLookupData> actionTemplateExpansionKeys =
        artifactDependencies.getActionTemplateExpansionKeys(env);
    if (actionTemplateExpansionKeys == null) {
      return null;
    }
    return ImmutableSet.copyOf(actionTemplateExpansionKeys);
  }

  private static void assertSkyframeAwareRewindingGraph(
      ImmutableGraph<SkyKey> graph, ActionLookupData actionKey) {
    checkArgument(
        graph.isDirected(),
        "SkyframeAwareAction's rewinding graph is undirected. graph: %s, actionKey: %s",
        graph,
        actionKey);
    checkArgument(
        !graph.allowsSelfLoops(),
        "SkyframeAwareAction's rewinding graph allows self loops. graph: %s, actionKey: %s",
        graph,
        actionKey);
    checkArgument(
        graph.nodes().contains(actionKey),
        "SkyframeAwareAction's rewinding graph does not contain its action root. graph: %s, "
            + "actionKey: %s",
        graph,
        actionKey);
    checkArgument(
        Iterables.size(Traverser.forGraph(graph).breadthFirst(actionKey)) == graph.nodes().size(),
        "SkyframeAwareAction's rewinding graph has nodes unreachable from its action root. "
            + "graph: %s, actionKey: %s",
        graph,
        actionKey);

    for (EndpointPair<SkyKey> edge : graph.edges()) {
      SkyKey target = edge.target();
      checkArgument(
          !(target instanceof Artifact && ((Artifact) target).isSourceArtifact()),
          "SkyframeAwareAction's rewinding graph contains source artifact. graph: %s, "
              + "rootActionNode: %s, sourceArtifact: %s",
          graph,
          actionKey,
          target);
      checkState(
          !(target instanceof Artifact) || target instanceof DerivedArtifact,
          "A non-source artifact must be derived. graph: %s, rootActionNode: %s, sourceArtifact:"
              + " %s",
          graph,
          actionKey,
          target);
    }
  }

  private boolean shouldRecordRewindEventSample() {
    return rewindEventSampleCounter.getAndIncrement() < MAX_ACTION_REWIND_EVENTS;
  }

  private static ActionRewindEvent createLostOutputRewindEvent(
      TopLevelActionLookupKeyWrapper failedKey,
      Reset rewindPlan,
      ImmutableList<LostInputRecord> lostOutputRecords) {
    return createRewindEventBuilder(rewindPlan, lostOutputRecords)
        .setTopLevelActionLookupKeyDescription(failedKey.actionLookupKey().toString())
        .build();
  }

  private static ActionRewindEvent createLostInputRewindEvent(
      Action failedAction, Reset rewindPlan, ImmutableList<LostInputRecord> lostInputRecords) {
    return createRewindEventBuilder(rewindPlan, lostInputRecords)
        .setActionDescription(
            ActionDescription.newBuilder()
                .setType(failedAction.getMnemonic())
                .setRuleLabel(failedAction.getOwner().getLabel().toString()))
        .build();
  }

  private static ActionRewindEvent.Builder createRewindEventBuilder(
      Reset rewindPlan, ImmutableList<LostInputRecord> lostInputRecords) {
    return ActionRewindEvent.newBuilder()
        .addAllLostInputs(
            lostInputRecords.stream()
                .limit(MAX_LOST_INPUTS_RECORDED)
                .map(
                    lostInputRecord ->
                        LostInput.newBuilder()
                            .setPath(lostInputRecord.lostInputPath())
                            .setDigest(lostInputRecord.lostInputDigest())
                            .build())
                .collect(toImmutableList()))
        .setTotalLostInputsCount(lostInputRecords.size())
        .setInvalidatedNodesCount(rewindPlan.rewindGraph().nodes().size());
  }

  /**
   * A record indicating that {@link #failedKey} failed because it lost an input with the specified
   * digest.
   */
  @AutoValue
  abstract static class LostInputRecord {

    abstract SkyKey failedKey();

    abstract String lostInputDigest();

    abstract String lostInputPath();

    static LostInputRecord create(SkyKey failedKey, String lostInputDigest, String lostInputPath) {
      return new AutoValue_ActionRewindStrategy_LostInputRecord(
          failedKey, lostInputDigest, lostInputPath);
    }
  }

  @AutoValue
  abstract static class ActionAndLookupData {

    abstract ActionLookupData lookupData();

    abstract Action action();

    static ActionAndLookupData create(ActionLookupData lookupData, Action action) {
      return new AutoValue_ActionRewindStrategy_ActionAndLookupData(lookupData, action);
    }
  }

  private static List<Action> actions(List<ActionAndLookupData> newlyVisitedActions) {
    return Lists.transform(newlyVisitedActions, ActionAndLookupData::action);
  }

  /**
   * Constructs a mapping from input artifact to all direct dep {@link ArtifactNestedSetKey}s that
   * transitively contain the artifact.
   *
   * <p>More formally, a key-value pair {@code (Artifact k, ArtifactNestedSetKey v)} is present in
   * the returned map iff {@code deps.contains(v) && v.expandToArtifacts().contains(k)}.
   *
   * <p>When {@link com.google.devtools.build.lib.skyframe.ActionExecutionFunction} requests input
   * deps, it unwraps a single layer of {@linkplain Action#getInputs the action's inputs}, thus
   * requesting an {@link ArtifactNestedSetKey} for each of {@code
   * action.getInputs().getNonLeaves()}.
   */
  private static Multimap<Artifact, ArtifactNestedSetKey> expandNestedSetKeys(Set<SkyKey> deps) {
    Multimap<Artifact, ArtifactNestedSetKey> map =
        MultimapBuilder.hashKeys().arrayListValues().build();
    for (ArtifactNestedSetKey key : Iterables.filter(deps, ArtifactNestedSetKey.class)) {
      for (Artifact artifact : key.expandToArtifacts()) {
        map.put(artifact, key);
      }
    }
    return map;
  }

  /**
   * Adds a skyframe dependency chain from {@code failedKey} to {@code lostArtifactKey} to the
   * rewind graph.
   *
   * <p>Each edge along the path from {@code failedKey} to {@code lostArtifactKey} is added to the
   * rewind graph. This necessarily includes the initial edge {@code (failedKey, directDep)}.
   *
   * <p>Although {@code lostArtifact} may be reachable via multiple distinct paths, it is only
   * necessary to rewind one such path to ensure successful completion of {@code failedKey}. Other
   * failing actions that depend on {@code lostArtifact} via a different path may initiate their own
   * rewind strategy.
   */
  private static void addNestedSetToRewindGraph(
      MutableGraph<SkyKey> rewindGraph,
      SkyKey failedKey,
      Artifact lostArtifact,
      ArtifactNestedSetKey directDep) {
    SkyKey current = failedKey;
    for (ArtifactNestedSetKey key : directDep.findPathToArtifact(lostArtifact)) {
      rewindGraph.putEdge(current, key);
      current = key;
    }
    rewindGraph.putEdge(current, Artifact.key(lostArtifact));
  }
}
