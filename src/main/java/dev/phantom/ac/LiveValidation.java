package dev.phantom.ac;

import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;

/**
 * Legacy-compatible facade over the canonical causal Phase 8 movement pipeline.
 *
 * <p>This class intentionally owns no physics, timing reconstruction, candidate
 * frontier, or Paper integration. It exists only for older replay APIs that
 * still consume {@link Finding} records.</p>
 */
public final class LiveValidation {
  private LiveValidation() {}

  public record Finding(
      long tick,
      Validation.Verdict verdict,
      int candidateCount,
      List<String> reasons) {
    public Finding {
      reasons = List.copyOf(reasons);
    }
  }

  public record Report(
      List<Finding> findings,
      int movementObservations,
      int timelineEvents,
      int anchoredObservations,
      int possibleFindings,
      int uncertainFindings,
      int impossibleFindings) {
    public Report {
      findings = List.copyOf(findings);
    }

    public Report(List<Finding> findings, int movementObservations) {
      this(findings, movementObservations, 0, 0, 0, 0, 0);
    }
  }

  public static Report analyze(
      Timeline.Snapshot timeline,
      int maximumCandidates) {
    return analyze(
        timeline,
        maximumCandidates,
        Phase7Timing.Config.defaultConfig(),
        null,
        null,
        -1L);
  }

  public static Report analyze(
      Timeline.Snapshot timeline,
      int maximumCandidates,
      Phase7Timing.Config timingConfig) {
    return analyze(
        timeline,
        maximumCandidates,
        timingConfig,
        null,
        null,
        -1L);
  }

  /**
   * Replays the canonical causal pipeline from an explicit authoritative anchor.
   * This overload is the preferred bridge for replay artifacts carrying a seed.
   */
  public static Report analyze(
      Timeline.Snapshot timeline,
      int maximumCandidates,
      Phase7Timing.Config timingConfig,
      Player initialAnchor,
      WorldSnapshot liveWorld,
      long initialAnchorReceivedNanos) {
    Objects.requireNonNull(timeline);
    CausalMovementPipeline.Report report = CausalMovementPipeline.analyze(
        "legacy-live-validation",
        timeline,
        maximumCandidates,
        timingConfig,
        liveWorld,
        initialAnchor,
        initialAnchorReceivedNanos);

    List<Finding> findings = new ArrayList<>();
    int possible = 0;
    int uncertain = 0;
    int impossible = 0;
    int anchored = 0;

    for (CausalMovementPipeline.Frame frame : report.frames()) {
      if (frame.authority().quality() == CausalMovementPipeline.AuthorityQuality.EXACT) {
        anchored++;
      }
    }

    for (Phase8MovementValidation.Result result : report.results()) {
      Validation.Verdict verdict = switch (result.verdict()) {
        case POSSIBLE -> Validation.Verdict.POSSIBLE;
        case UNCERTAIN -> Validation.Verdict.UNCERTAIN;
        case IMPOSSIBLE -> Validation.Verdict.IMPOSSIBLE;
      };
      int candidates = result.evidence().reachableCandidateCount();
      List<String> reasons = new ArrayList<>(result.evidence().simulationDiagnostics());
      reasons.addAll(result.evidence().uncertaintySources());
      if (reasons.isEmpty()) reasons.add(result.evidence().eliminationReason());
      findings.add(new Finding(
          result.evidence().serverTick(),
          verdict,
          candidates,
          reasons));
      switch (verdict) {
        case POSSIBLE -> possible++;
        case UNCERTAIN -> uncertain++;
        case IMPOSSIBLE -> impossible++;
      }
    }

    return new Report(
        findings,
        report.movementObservations(),
        timeline.events().size(),
        anchored,
        possible,
        uncertain,
        impossible);
  }
}
