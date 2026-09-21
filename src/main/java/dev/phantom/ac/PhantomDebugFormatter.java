package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase8MovementValidation.Result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Compact, decision-oriented prediction debugging. */
public final class PhantomDebugFormatter {
  private PhantomDebugFormatter() {}

  public static String movement(
      String player,
      long sequence,
      long clientTick,
      Result result,
      SearchResult search,
      Vec3 observed,
      Vec3 prior,
      List<String> contextReasons) {
    Objects.requireNonNull(player);
    Objects.requireNonNull(result);
    Objects.requireNonNull(search);
    List<String> reasons = new ArrayList<>();
    reasons.addAll(result.evidence().uncertaintySources());
    reasons.addAll(search.reasons());
    reasons.addAll(contextReasons);
    reasons = reasons.stream().filter(Objects::nonNull).distinct().limit(8).toList();

    String closest = search.candidates().stream()
        .sorted(Comparator.comparingDouble(c -> distanceSquared(c.context().player().position(), observed)))
        .limit(2)
        .map(c -> "#" + c.id() + "@" + c.context().simulationTick()
            + " p=" + vec(c.context().player().position())
            + " v=" + vec(c.context().player().velocity())
            + " mode=" + c.movementMode())
        .toList().toString();

    return "[DECISION] player=" + player
        + " seq=" + sequence
        + " tick=" + clientTick
        + " verdict=" + result.verdict()
        + " candidates=" + search.candidates().size()
        + " exhaustive=" + search.exhaustive()
        + " observed=" + vec(observed)
        + " prior=" + vec(prior)
        + " closest=" + closest
        + " reasons=" + reasons;
  }

  public static String movement(
      String player,
      Phase8PredictionRunner.PredictionFrame frame,
      Result result) {
    Objects.requireNonNull(player);
    Objects.requireNonNull(frame);
    Objects.requireNonNull(result);

    Phase8MovementValidation.Evidence evidence = result.evidence();
    List<String> reasons = new ArrayList<>();
    reasons.addAll(evidence.uncertaintySources());
    reasons.addAll(evidence.simulationDiagnostics());
    if (!evidence.eliminationReason().isBlank()) reasons.add(evidence.eliminationReason());
    if (evidence.firstInconsistentTick().isPresent()) {
      reasons.add("firstInconsistentTick=" + evidence.firstInconsistentTick().getAsLong());
    }
    reasons = reasons.stream().filter(Objects::nonNull).distinct().limit(8).toList();

    String closest = evidence.closestCandidate()
        .map(c -> "#" + c.candidateId()
            + "@" + c.simulationTick()
            + " p=" + c.position()
            + " v=" + c.velocity()
            + " g=" + c.onGround()
            + " pose=" + c.pose()
            + " cause=" + c.provenance())
        .orElse("none");

    return "[DECISION] player=" + player
        + " seq=" + frame.sequence()
        + " tick=" + frame.clientTick()
        + " serverTick=" + frame.serverTick()
        + " verdict=" + result.verdict()
        + " reachable=" + evidence.reachableCandidateCount()
        + " matching=" + evidence.matchingCandidateCount()
        + " eliminated=" + evidence.candidatesEliminated()
        + " observed=" + vec(frame.observedAfter().position())
        + " observedVel=" + vec(frame.observedAfter().velocity())
        + " closest=" + closest
        + " reasons=" + reasons;
  }

  public static String branch(Candidate candidate) {
    return "[PATH] id=" + candidate.id()
        + " parent=" + candidate.provenance().parentId()
        + " tick=" + candidate.context().simulationTick()
        + " mode=" + candidate.movementMode()
        + " input=" + candidate.inputAssumption()
        + " cause=" + candidate.provenance().causes()
        + " state=p:" + vec(candidate.context().player().position())
        + ",v:" + vec(candidate.context().player().velocity())
        + ",g:" + candidate.context().player().onGround()
        + " world=" + candidate.worldReference()
        + " external=" + candidate.provenance().externalTransition();
  }

  public static String candidateSummary(Phase8MovementValidation.CandidateSummary candidate) {
    return "[PATH] id=" + candidate.candidateId()
        + " tick=" + candidate.simulationTick()
        + " mode=" + candidate.pose()
        + " state=p:" + candidate.position()
        + ",v:" + candidate.velocity()
        + ",g:" + candidate.onGround()
        + " cause=" + candidate.provenance();
  }

  private static String vec(Vec3 v) {
    return String.format(Locale.ROOT, "(%.6f,%.6f,%.6f)", v.x(), v.y(), v.z());
  }

  private static double distanceSquared(Vec3 a, Vec3 b) {
    double dx = a.x() - b.x();
    double dy = a.y() - b.y();
    double dz = a.z() - b.z();
    return dx * dx + dy * dy + dz * dz;
  }
}
