package dev.phantom.ac;

import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.Verdict;
import dev.phantom.ac.Phase6Reachability.ExternalTransition;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import java.util.*;

public final class Phase8LiveValidation {
  private Phase8LiveValidation() {}

  /**
   * Compatibility facade. All movement causality is implemented by
   * {@link CausalMovementPipeline}; this class remains as the stable API used
   * by tests, the Paper adapter, and existing replay tooling.
   */
  public record Report(
      List<Phase8MovementValidation.Result> results,
      int movementObservations,
      int possible,
      int uncertain,
      int impossible) {
    public Report {
      results = List.copyOf(results);
    }
  }

  record ParentAggregation(
      SearchResult result,
      Set<Candidate> candidates,
      boolean timingOffsetsExhaustive) {
    ParentAggregation {
      Objects.requireNonNull(result);
      candidates = Set.copyOf(candidates);
    }
  }

  static ParentAggregation aggregateParentSearches(
      List<Phase6Reachability.TimingSearchResult> searches,
      int maximumCandidates,
      long timingSpan) {
    Objects.requireNonNull(searches);
    Contracts.requireCandidateBudget(maximumCandidates);
    if (timingSpan < 1) throw new IllegalArgumentException("timing span must be positive");

    Set<Candidate> nextAll = new LinkedHashSet<>();
    boolean hasPossible = false;
    boolean hasImpossible = false;
    boolean hasUncertain = false;
    boolean timingExhaustive = true;
    int simulatedTicks = 0;
    int peakCandidates = 0;
    int merged = 0;
    int nonExhaustiveWorldBranches = 0;
    int uncertainTransitions = 0;
    int provenanceMerges = 0;
    LinkedHashSet<String> reasons = new LinkedHashSet<>();

    for (Phase6Reachability.TimingSearchResult search : searches) {
      boolean offsetPossible = false;
      boolean offsetUncertain = false;
      boolean offsetImpossible = true;
      int offsetResults = 0;

      for (SearchResult perOffset : search.byFirstTick().values()) {
        offsetResults++;
        simulatedTicks = Math.max(simulatedTicks, perOffset.simulatedTicks());
        peakCandidates = Math.max(peakCandidates, perOffset.peakCandidates());
        merged += perOffset.mergedStates();
        nonExhaustiveWorldBranches += perOffset.nonExhaustiveWorldBranches();
        uncertainTransitions += perOffset.uncertainTransitions();
        provenanceMerges += perOffset.provenanceMerges();

        switch (perOffset.verdict()) {
          case POSSIBLE -> {
            offsetPossible = true;
            offsetImpossible = false;
            nextAll.addAll(perOffset.candidates());
          }
          case IMPOSSIBLE -> {
          }
          case UNCERTAIN -> {
            offsetUncertain = true;
            offsetImpossible = false;
            nextAll.addAll(perOffset.candidates());
          }
        }
      }

      boolean evaluatedAllOffsets =
          search.evaluatedOffsets() == timingSpan
              && search.skippedOffsets() == 0
              && offsetResults == timingSpan;

      if (!evaluatedAllOffsets || offsetUncertain) timingExhaustive = false;
      if (offsetUncertain) hasUncertain = true;
      else if (offsetPossible) hasPossible = true;
      else if (evaluatedAllOffsets && offsetImpossible) hasImpossible = true;
      else hasUncertain = true;

      reasons.addAll(search.reasons());
      if (nextAll.size() > maximumCandidates) {
        timingExhaustive = false;
        hasUncertain = true;
        nextAll.clear();
        reasons.add("combined Phase 6 timing candidate budget exceeded; provisional candidates are not safe to continue");
        break;
      }
    }

    Phase6Reachability.Verdict verdict;
    if (hasUncertain) verdict = Phase6Reachability.Verdict.UNCERTAIN;
    else if (hasPossible && !nextAll.isEmpty()) verdict = Phase6Reachability.Verdict.POSSIBLE;
    else if (hasImpossible) verdict = Phase6Reachability.Verdict.IMPOSSIBLE;
    else {
      verdict = Phase6Reachability.Verdict.UNCERTAIN;
      timingExhaustive = false;
      reasons.add("no parent branch produced an evaluable result");
    }

    if (verdict == Phase6Reachability.Verdict.IMPOSSIBLE && reasons.isEmpty()) {
      reasons.add("all exhaustively modeled parent branches are impossible");
    }

    SearchResult aggregate = new SearchResult(
        verdict, Set.copyOf(nextAll), simulatedTicks, peakCandidates, merged,
        nonExhaustiveWorldBranches, uncertainTransitions, provenanceMerges,
        List.copyOf(reasons));
    return new ParentAggregation(aggregate, nextAll, timingExhaustive);
  }

  public static Report analyze(
      String playerId,
      Timeline.Snapshot timeline,
      int maximumCandidates,
      Phase7Timing.Config timingConfig) {
    return adapt(CausalMovementPipeline.analyze(
        playerId, timeline, maximumCandidates, timingConfig,
        null, null, -1L));
  }

  public static Report analyze(
      String playerId,
      Timeline.Snapshot timeline,
      int maximumCandidates,
      Phase7Timing.Config timingConfig,
      dev.phantom.ac.world.WorldSnapshot liveWorld) {
    return analyze(playerId, timeline, maximumCandidates, timingConfig, liveWorld, null, -1L);
  }

  public static Report analyze(
      String playerId,
      Timeline.Snapshot timeline,
      int maximumCandidates,
      Phase7Timing.Config timingConfig,
      dev.phantom.ac.world.WorldSnapshot liveWorld,
      State.Player initialAnchor) {
    return analyze(playerId, timeline, maximumCandidates, timingConfig,
        liveWorld, initialAnchor, -1L);
  }

  public static Report analyze(
      String playerId,
      Timeline.Snapshot timeline,
      int maximumCandidates,
      Phase7Timing.Config timingConfig,
      dev.phantom.ac.world.WorldSnapshot liveWorld,
      State.Player initialAnchor,
      long initialAnchorReceivedNanos) {
    return adapt(CausalMovementPipeline.analyze(
        playerId, timeline, maximumCandidates, timingConfig,
        liveWorld, initialAnchor, initialAnchorReceivedNanos));
  }

  private static Report adapt(CausalMovementPipeline.Report report) {
    return new Report(
        report.results(),
        report.movementObservations(),
        report.possible(),
        report.uncertain(),
        report.impossible());
  }
}
