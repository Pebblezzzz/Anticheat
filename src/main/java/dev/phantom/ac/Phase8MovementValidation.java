package dev.phantom.ac;

import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Observation;
import dev.phantom.ac.Phase6Reachability.ObservedField;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.*;

/**
 * Phase 8 movement validation. Interprets the Phase 6 reachable set, records
 * deterministic evidence, and emits observation-only operator alerts. It never
 * simulates movement itself and never performs punishment.
 */
public final class Phase8MovementValidation {
  public static final String VERSION = "phase8-movement-validation-v1";
  public static final String PHASE5_VERSION = "1.21.11-deterministic-physics";
  public static final String PHASE6_VERSION = "phase6-reachability";
  public static final String PHASE7_VERSION = "phase7-timing";

  private Phase8MovementValidation() {}

  public enum Verdict { POSSIBLE, UNCERTAIN, IMPOSSIBLE }

  public record Config(int minimumImpossibleObservations, int alertDebounceTicks,
                       boolean alertsEnabled, boolean observationOnly) implements Serializable {
    public Config {
      if (minimumImpossibleObservations < 1) throw new IllegalArgumentException("minimumImpossibleObservations must be positive");
      if (alertDebounceTicks < 0) throw new IllegalArgumentException("alertDebounceTicks must be non-negative");
      if (!observationOnly) throw new IllegalArgumentException("Phase 8 is observation-only; punishment is not part of this phase");
    }
    public static Config defaults() { return new Config(2, 20, true, true); }
  }

  /** Immutable, replayable evidence for one movement observation. */
  public record Evidence(
      String schemaVersion,
      Verdict verdict,
      String playerId,
      long serverTick,
      long clientTickMin,
      long clientTickMax,
      Player priorState,
      Player observedState,
      String worldVersion,
      String worldReference,
      List<String> inputAssumptions,
      List<String> timingAssumptions,
      int reachableCandidateCount,
      int matchingCandidateCount,
      int candidatesEliminated,
      String eliminationReason,
      OptionalLong firstInconsistentTick,
      Optional<CandidateSummary> closestCandidate,
      List<String> simulationDiagnostics,
      List<String> uncertaintySources,
      String phase5Version,
      String phase6Version,
      String phase7Version,
      String replayReference,
      String rule
  ) implements Serializable {
    public Evidence {
      if (!VERSION.equals(schemaVersion)) throw new IllegalArgumentException("unsupported Phase 8 evidence schema");
      Objects.requireNonNull(verdict);
      if (serverTick < 0 || clientTickMin < 0 || clientTickMax < clientTickMin) throw new IllegalArgumentException("invalid evidence ticks");
      Objects.requireNonNull(priorState); Objects.requireNonNull(observedState);
      Objects.requireNonNull(worldVersion); Objects.requireNonNull(worldReference);
      inputAssumptions = List.copyOf(inputAssumptions); timingAssumptions = List.copyOf(timingAssumptions);
      simulationDiagnostics = List.copyOf(simulationDiagnostics); uncertaintySources = List.copyOf(uncertaintySources);
      Objects.requireNonNull(firstInconsistentTick); Objects.requireNonNull(closestCandidate);
      Objects.requireNonNull(phase5Version); Objects.requireNonNull(phase6Version); Objects.requireNonNull(phase7Version);
      Objects.requireNonNull(replayReference); Objects.requireNonNull(rule);
      if (reachableCandidateCount < 0 || matchingCandidateCount < 0 || candidatesEliminated < 0) throw new IllegalArgumentException("negative candidate metric");
    }
  }

  public record CandidateSummary(long candidateId, long simulationTick, String position,
                                 String velocity, boolean onGround, String pose,
                                 String provenance) implements Serializable {}

  public record Result(Verdict verdict, Evidence evidence) implements Serializable {
    public Result { Objects.requireNonNull(verdict); Objects.requireNonNull(evidence); if (evidence.verdict() != verdict) throw new IllegalArgumentException("evidence/result verdict mismatch"); }
  }

  /** Pure comparison of observed state against an already-computed Phase 6 result. */
  public static Result validate(String playerId, long serverTick, Player prior, Player observed,
                                WorldSnapshot world, String worldReference,
                                Validation.SyncWindow timing, List<String> inputAssumptions,
                                SearchResult reachable, String replayReference) {
    Objects.requireNonNull(playerId); Objects.requireNonNull(prior); Objects.requireNonNull(observed);
    Objects.requireNonNull(world); Objects.requireNonNull(timing); Objects.requireNonNull(inputAssumptions);
    Objects.requireNonNull(reachable); Objects.requireNonNull(replayReference);

    List<String> uncertainty = new ArrayList<>();
    if (timing.uncertain()) uncertainty.addAll(timing.reasons());
    if (reachable.verdict() == Phase6Reachability.Verdict.UNCERTAIN) {
      uncertainty.addAll(reachable.reasons());
      Evidence evidence = evidence(Verdict.UNCERTAIN, playerId, serverTick, prior, observed, world, worldReference, timing,
          inputAssumptions, reachable.candidates().size(), 0, 0, "Phase 6 could not exhaustively represent the legitimate state space",
          OptionalLong.empty(), Optional.empty(), reachable.reasons(), uncertainty, replayReference);
      return new Result(Verdict.UNCERTAIN, evidence);
    }
    if (timing.uncertain()) {
      Evidence evidence = evidence(Verdict.UNCERTAIN, playerId, serverTick, prior, observed, world, worldReference, timing,
          inputAssumptions, reachable.candidates().size(), 0, 0, "Phase 7 synchronization remains uncertain",
          OptionalLong.empty(), bestCandidate(reachable.candidates(), observed), reachable.reasons(), uncertainty, replayReference);
      return new Result(Verdict.UNCERTAIN, evidence);
    }

    Observation observation = new Observation(observed, EnumSet.of(
        ObservedField.POSITION, ObservedField.VELOCITY, ObservedField.ROTATION,
        ObservedField.GROUND, ObservedField.GAMEMODE, ObservedField.EFFECTS,
        ObservedField.TELEPORT_PENDING));
    Phase6Reachability.Evidence comparison = new Phase6Reachability(new Vanilla12111RichPhysics()).compare(reachable, observation);
    if (comparison.verdict() == Phase6Reachability.Verdict.POSSIBLE) {
      Evidence evidence = evidence(Verdict.POSSIBLE, playerId, serverTick, prior, observed, world, worldReference, timing,
          inputAssumptions, reachable.candidates().size(), comparison.matchingCandidates(),
          Math.max(0, reachable.candidates().size() - comparison.matchingCandidates()),
          "at least one complete legitimate candidate explains every declared observed field",
          OptionalLong.empty(), bestMatchingCandidate(reachable.candidates(), observed), comparison.reasons(), List.of(), replayReference);
      return new Result(Verdict.POSSIBLE, evidence);
    }

    Evidence evidence = evidence(Verdict.IMPOSSIBLE, playerId, serverTick, prior, observed, world, worldReference, timing,
        inputAssumptions, reachable.candidates().size(), 0, reachable.candidates().size(),
        "all exhaustively modeled legitimate candidates disagree with the observed movement state",
        OptionalLong.of(serverTick), bestCandidate(reachable.candidates(), observed), comparison.reasons(), List.of(), replayReference);
    return new Result(Verdict.IMPOSSIBLE, evidence);
  }

  private static Evidence evidence(Verdict verdict, String playerId, long serverTick, Player prior, Player observed,
                                   WorldSnapshot world, String worldReference, Validation.SyncWindow timing,
                                   List<String> inputs, int candidates, int matches, int eliminated,
                                   String reason, OptionalLong first, Optional<CandidateSummary> closest,
                                   List<String> diagnostics, List<String> uncertainty, String replay) {
    return new Evidence(VERSION, verdict, playerId, serverTick, timing.earliestClientTick(), timing.latestClientTick(),
        prior, observed, Contracts.TARGET_VERSION, worldReference, inputs,
        timing.reasons(), candidates, matches, eliminated, reason, first, closest,
        diagnostics, uncertainty, PHASE5_VERSION, PHASE6_VERSION, PHASE7_VERSION, replay,
        "MOVEMENT_REACHABILITY");
  }

  private static Optional<CandidateSummary> bestMatchingCandidate(Set<Candidate> candidates, Player observed) {
    return candidates.stream().filter(c -> matchesPosition(c.context().player(), observed))
        .sorted(Comparator.comparingLong(Candidate::id)).map(Phase8MovementValidation::summary).findFirst();
  }

  private static Optional<CandidateSummary> bestCandidate(Set<Candidate> candidates, Player observed) {
    return candidates.stream().sorted(Comparator.comparingDouble((Candidate c) -> distance(c.context().player(), observed))
        .thenComparingLong(Candidate::id)).map(Phase8MovementValidation::summary).findFirst();
  }

  private static boolean matchesPosition(Player a, Player b) { return a.position().equals(b.position()); }

  private static double distance(Player a, Player b) {
    double dx = a.position().x() - b.position().x();
    double dy = a.position().y() - b.position().y();
    double dz = a.position().z() - b.position().z();
    return dx * dx + dy * dy + dz * dz;
  }

  private static CandidateSummary summary(Candidate c) {
    Player p = c.context().player();
    return new CandidateSummary(c.id(), c.context().simulationTick(), p.position().toString(),
        p.velocity().toString(), p.onGround(), c.context().pose().name(), c.provenance().toString());
  }

  /** Deterministic evidence accumulator. UNCERTAIN and POSSIBLE never increase violation confidence. */
  public record Accumulator(Map<String, State> players) implements Serializable {
    public Accumulator { players = Map.copyOf(players); }
    public static Accumulator empty() { return new Accumulator(Map.of()); }

    public Accumulated accept(Evidence evidence, Config config) {
      Objects.requireNonNull(evidence); Objects.requireNonNull(config);
      String key = evidence.playerId() + "/" + evidence.rule();
      State old = players.getOrDefault(key, State.empty());
      State next = switch (evidence.verdict()) {
        case IMPOSSIBLE -> old.impossible(evidence.serverTick());
        case POSSIBLE -> old.recovered(evidence.serverTick());
        case UNCERTAIN -> old.uncertain();
      };
      Map<String, State> updated = new LinkedHashMap<>(players); updated.put(key, next);
      Optional<Alert> alert = Optional.empty();
      if (config.alertsEnabled() && evidence.verdict() == Verdict.IMPOSSIBLE
          && next.consecutiveImpossible() >= config.minimumImpossibleObservations()
          && (next.lastAlertTick() < 0 || evidence.serverTick() - next.lastAlertTick() >= config.alertDebounceTicks())) {
        double confidence = Math.min(1.0, (double) next.supportingImpossible() / config.minimumImpossibleObservations());
        alert = Optional.of(new Alert(evidence.playerId(), evidence.serverTick(), evidence.firstInconsistentTick().orElse(evidence.serverTick()),
            evidence.rule(), evidence.eliminationReason(), confidence, next.supportingImpossible(), evidence.replayReference()));
        updated.put(key, next.alerted(evidence.serverTick()));
      }
      return new Accumulated(new Accumulator(updated), alert);
    }
  }

  public record State(int consecutiveImpossible, int supportingImpossible, int uncertaintyPeriods,
                      int recoveries, long lastObservationTick, long lastAlertTick) implements Serializable {
    public static State empty() { return new State(0, 0, 0, 0, -1, -1); }
    State impossible(long tick) { return new State(consecutiveImpossible + 1, supportingImpossible + 1, uncertaintyPeriods, recoveries, tick, lastAlertTick); }
    State recovered(long tick) { return new State(0, supportingImpossible, uncertaintyPeriods, recoveries + 1, tick, lastAlertTick); }
    State uncertain() { return new State(0, supportingImpossible, uncertaintyPeriods + 1, recoveries, lastObservationTick, lastAlertTick); }
    State alerted(long tick) { return new State(consecutiveImpossible, supportingImpossible, uncertaintyPeriods, recoveries, lastObservationTick, tick); }
  }

  public record Accumulated(Accumulator state, Optional<Alert> alert) implements Serializable {}

  public record Alert(String playerId, long serverTick, long firstInconsistentTick, String reason,
                      String evidence, double confidence, int supportingEvents, String replayReference) implements Serializable {
    public String message() {
      return "[AntiCheat] player=" + playerId + " type=MOVEMENT result=IMPOSSIBLE tick=" + serverTick
          + " first-inconsistent-tick=" + firstInconsistentTick + " reason=" + reason
          + " confidence=" + String.format(Locale.ROOT, "%.2f", confidence)
          + " replay=" + replayReference;
    }
  }
}
