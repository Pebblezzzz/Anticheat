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

  public record Config(double alertViolationThreshold, int alertDebounceTicks,
                       boolean alertsEnabled, boolean observationOnly,
                       double violationIncrement, double violationDecayPerTick,
                       double maximumViolationLevel, double alertInterval,
                       GrimAlertPolicy.Config alertPolicy) implements Serializable {
    public Config {
      if (!Double.isFinite(alertViolationThreshold) || alertViolationThreshold <= 0.0)
        throw new IllegalArgumentException("alertViolationThreshold must be finite and positive");
      if (alertDebounceTicks < 0) throw new IllegalArgumentException("alertDebounceTicks must be non-negative");
      if (!observationOnly) throw new IllegalArgumentException("Phase 8 is observation-only; punishment is not part of this phase");
      if (!Double.isFinite(violationIncrement) || violationIncrement <= 0) throw new IllegalArgumentException("violationIncrement must be finite and positive");
      if (!Double.isFinite(violationDecayPerTick) || violationDecayPerTick < 0) throw new IllegalArgumentException("violationDecayPerTick must be finite and non-negative");
      if (!Double.isFinite(maximumViolationLevel) || maximumViolationLevel <= 0) throw new IllegalArgumentException("maximumViolationLevel must be finite and positive");
      if (!Double.isFinite(alertInterval) || alertInterval <= 0) throw new IllegalArgumentException("alertInterval must be finite and positive");
      if (maximumViolationLevel < alertViolationThreshold) throw new IllegalArgumentException("maximumViolationLevel must cover the alert threshold");
      Objects.requireNonNull(alertPolicy);
    }
    public Config(double alertViolationThreshold, int alertDebounceTicks,
                  boolean alertsEnabled, boolean observationOnly,
                  double violationIncrement, double violationDecayPerTick,
                  double maximumViolationLevel, double alertInterval) {
      this(alertViolationThreshold, alertDebounceTicks, alertsEnabled, observationOnly,
          violationIncrement, violationDecayPerTick, maximumViolationLevel, alertInterval,
          new GrimAlertPolicy.Config(
              List.of(),
              new GrimAlertPolicy.CommandRule(alertViolationThreshold, alertInterval),
              GrimAlertPolicy.CommandRule.parse("1:1"),
              300_000L));
    }

    public Config(double alertViolationThreshold, int alertDebounceTicks,
                  boolean alertsEnabled, boolean observationOnly) {
      this(alertViolationThreshold, alertDebounceTicks, alertsEnabled, observationOnly,
          1.0, 0.005, 100.0, 40.0,
          new GrimAlertPolicy.Config(
              List.of(),
              new GrimAlertPolicy.CommandRule(alertViolationThreshold, 40.0),
              GrimAlertPolicy.CommandRule.parse("1:1"),
              300_000L));
    }
    public static Config defaults() {
      return new Config(1.0, 0, true, true,
          1.0, 0.005, 100.0, 1.0, GrimAlertPolicy.Config.defaults());
    }
    public double alertThreshold() { return alertViolationThreshold; }
  }

  public record Evidence(
      String schemaVersion, Verdict verdict, String playerId, long serverTick,
      long clientTickMin, long clientTickMax, Player priorState, Player observedState,
      String worldVersion, String worldReference, List<String> inputAssumptions,
      List<String> timingAssumptions, int reachableCandidateCount, int matchingCandidateCount,
      int candidatesEliminated, String eliminationReason, OptionalLong firstInconsistentTick,
      Optional<CandidateSummary> closestCandidate, List<String> simulationDiagnostics,
      List<String> uncertaintySources, String phase5Version, String phase6Version,
      String phase7Version, String replayReference, String rule) implements Serializable {
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
                                 float yaw, float pitch, String provenance) implements Serializable {}

  public record Result(Verdict verdict, Evidence evidence) implements Serializable {
    public Result { Objects.requireNonNull(verdict); Objects.requireNonNull(evidence); if (evidence.verdict() != verdict) throw new IllegalArgumentException("evidence/result verdict mismatch"); }
  }

  private static Set<ObservedField> allObservedFields() {
    return EnumSet.of(ObservedField.POSITION, ObservedField.ROTATION, ObservedField.GROUND);
  }

  /**
   * Records an authoritative server/client contradiction without manufacturing
   * a movement-reachability IMPOSSIBLE verdict. The movement verdict contract
   * reserves IMPOSSIBLE for exhaustive Phase 6 elimination. Authoritative
   * observations are therefore retained as explicit UNCERTAIN evidence until a
   * separate non-reachability policy channel consumes them.
   */
  public static Result authoritativeObservation(String playerId, long serverTick,
                                               Player prior, Player observed,
                                               WorldSnapshot world, String worldReference,
                                               Validation.SyncWindow timing,
                                               String rule, String eliminationReason,
                                               List<String> diagnostics,
                                               String replayReference) {
    Objects.requireNonNull(rule);
    Objects.requireNonNull(eliminationReason);
    Objects.requireNonNull(diagnostics);

    List<String> uncertainty = new ArrayList<>();
    if (timing.uncertain()) uncertainty.addAll(timing.reasons());

    Evidence evidence = new Evidence(
        VERSION, Verdict.UNCERTAIN, playerId, serverTick,
        timing.earliestClientTick(), timing.latestClientTick(),
        prior, observed, Contracts.TARGET_VERSION, worldReference,
        List.of("authoritative server state observed",
            "client packet state observed"),
        timing.reasons(), 0, 0, 0,
        eliminationReason, OptionalLong.empty(), Optional.empty(),
        diagnostics, uncertainty, PHASE5_VERSION, PHASE6_VERSION,
        PHASE7_VERSION, replayReference, rule);
    return new Result(Verdict.UNCERTAIN, evidence);
  }

  /**
   * Creates a hard, non-reachability violation for an independently deterministic
   * contradiction (for example an unauthorized flight-state toggle). These findings
   * enter the same Grim-style violation/punishment stream as exhaustive movement flags.
   */
  public static Result hardViolation(String playerId, long serverTick, Player prior, Player observed,
                                     WorldSnapshot world, String worldReference,
                                     Validation.SyncWindow timing, String rule,
                                     String eliminationReason, List<String> diagnostics,
                                     String replayReference) {
    Objects.requireNonNull(rule);
    Objects.requireNonNull(eliminationReason);
    Objects.requireNonNull(diagnostics);

    List<String> uncertainty = timing.uncertain() ? List.copyOf(timing.reasons()) : List.of();
    Evidence evidence = new Evidence(
        VERSION, Verdict.IMPOSSIBLE, playerId, serverTick,
        timing.earliestClientTick(), timing.latestClientTick(),
        prior, observed, Contracts.TARGET_VERSION, worldReference,
        List.of("authoritative server state observed", "client packet state observed"),
        timing.reasons(), 0, 0, 0,
        eliminationReason, OptionalLong.of(serverTick), Optional.empty(),
        diagnostics, uncertainty, PHASE5_VERSION, PHASE6_VERSION,
        PHASE7_VERSION, replayReference, rule);
    return new Result(Verdict.IMPOSSIBLE, evidence);
  }

  public static Result validate(String playerId, long serverTick, Player prior, Player observed,
                                WorldSnapshot world, String worldReference,
                                Validation.SyncWindow timing, List<String> inputAssumptions,
                                SearchResult reachable, String replayReference) {
    return validate(playerId, serverTick, prior, observed, world, worldReference, timing,
        inputAssumptions, reachable, replayReference, !timing.uncertain(), allObservedFields());
  }

  public static Result validate(String playerId, long serverTick, Player prior, Player observed,
                                WorldSnapshot world, String worldReference,
                                Validation.SyncWindow timing, List<String> inputAssumptions,
                                SearchResult reachable, String replayReference,
                                boolean timingExhaustivelyModeled) {
    return validate(playerId, serverTick, prior, observed, world, worldReference, timing,
        inputAssumptions, reachable, replayReference, timingExhaustivelyModeled, allObservedFields());
  }

  public static Result validate(String playerId, long serverTick, Player prior, Player observed,
                                WorldSnapshot world, String worldReference,
                                Validation.SyncWindow timing, List<String> inputAssumptions,
                                SearchResult reachable, String replayReference,
                                boolean timingExhaustivelyModeled, Set<ObservedField> observedFields) {
    Objects.requireNonNull(playerId); Objects.requireNonNull(prior); Objects.requireNonNull(observed);
    Objects.requireNonNull(world); Objects.requireNonNull(timing); Objects.requireNonNull(inputAssumptions);
    Objects.requireNonNull(reachable); Objects.requireNonNull(replayReference); Objects.requireNonNull(observedFields);
    if (observedFields.isEmpty()) throw new IllegalArgumentException("observedFields must not be empty");

    List<String> uncertainty = new ArrayList<>();
    if (timing.uncertain()) uncertainty.addAll(timing.reasons());
    if (reachable.verdict() == Phase6Reachability.Verdict.UNCERTAIN) {
      uncertainty.addAll(reachable.reasons());
      Evidence evidence = evidence(Verdict.UNCERTAIN, playerId, serverTick, prior, observed, world, worldReference, timing,
          inputAssumptions, reachable.candidates().size(), 0, 0, "Phase 6 could not exhaustively represent the legitimate state space",
          OptionalLong.empty(), bestCandidate(reachable.candidates(), observed), reachable.reasons(), uncertainty, replayReference);
      return new Result(Verdict.UNCERTAIN, evidence);
    }
    if (timing.uncertain() && !timingExhaustivelyModeled) {
      Evidence evidence = evidence(Verdict.UNCERTAIN, playerId, serverTick, prior, observed, world, worldReference, timing,
          inputAssumptions, reachable.candidates().size(), 0, 0, "Phase 7 synchronization is uncertain and its possible offsets were not exhaustively represented",
          OptionalLong.empty(), bestCandidate(reachable.candidates(), observed), reachable.reasons(), uncertainty, replayReference);
      return new Result(Verdict.UNCERTAIN, evidence);
    }

    Observation observation = new Observation(observed, observedFields);
    Phase6Reachability.Evidence comparison = new Phase6Reachability().compare(reachable, observation);
    if (comparison.verdict() == Phase6Reachability.Verdict.POSSIBLE) {
      List<String> diagnostics = new ArrayList<>(comparison.reasons());
      if (timing.uncertain() && timingExhaustivelyModeled) diagnostics.add("Phase 7 timing uncertainty was exhaustively represented across the declared client-tick window");
      Evidence evidence = evidence(Verdict.POSSIBLE, playerId, serverTick, prior, observed, world, worldReference, timing,
          inputAssumptions, reachable.candidates().size(), comparison.matchingCandidates(),
          Math.max(0, reachable.candidates().size() - comparison.matchingCandidates()),
          "at least one complete legitimate candidate explains every declared observed field",
          OptionalLong.empty(), bestMatchingCandidate(reachable.candidates(), observation), diagnostics, uncertainty, replayReference);
      return new Result(Verdict.POSSIBLE, evidence);
    }

    List<String> diagnostics = new ArrayList<>(comparison.reasons());
    if (timing.uncertain() && timingExhaustivelyModeled) diagnostics.add("Phase 7 timing uncertainty was exhaustively represented; no timing offset produced a matching candidate");

    Optional<Candidate> closest = comparison.closestCandidates().stream().findFirst();
    String detailedReason = impossibleReason(observed, reachable.candidates().size(),
        comparison.matchingCandidates(), comparison, closest);
    diagnostics.add(detailedReason);

    Evidence evidence = evidence(Verdict.IMPOSSIBLE, playerId, serverTick, prior, observed, world, worldReference, timing,
        inputAssumptions, reachable.candidates().size(), 0, reachable.candidates().size(),
        detailedReason,
        OptionalLong.of(serverTick), closest.map(Phase8MovementValidation::summary), diagnostics, uncertainty, replayReference);
    return new Result(Verdict.IMPOSSIBLE, evidence);
  }

  /**
   * An exhaustive movement mismatch cannot be promoted to IMPOSSIBLE when the
   * only causal server anchor is materially stale. Grim keeps server authority
   * and client movement/velocity as separate evidence channels; without a
   * causally fresh authority state, the retained hidden client state is not a
   * complete basis for a hard reachability contradiction.
   */
  public static Result downgradeImpossibleForStaleAuthority(
      Result result,
      String reason) {
    Objects.requireNonNull(result);
    Objects.requireNonNull(reason);
    if (result.verdict() != Verdict.IMPOSSIBLE) {
      return result;
    }

    Evidence original = result.evidence();
    List<String> diagnostics = new ArrayList<>(original.simulationDiagnostics());
    diagnostics.add(reason);
    List<String> uncertainty = new ArrayList<>(original.uncertaintySources());
    uncertainty.add(reason);
    Evidence evidence = new Evidence(
        VERSION,
        Verdict.UNCERTAIN,
        original.playerId(),
        original.serverTick(),
        original.clientTickMin(),
        original.clientTickMax(),
        original.priorState(),
        original.observedState(),
        original.worldVersion(),
        original.worldReference(),
        original.inputAssumptions(),
        original.timingAssumptions(),
        original.reachableCandidateCount(),
        original.matchingCandidateCount(),
        0,
        "exhaustive candidate mismatch observed while the causal server authority was stale",
        OptionalLong.empty(),
        original.closestCandidate(),
        diagnostics,
        uncertainty,
        original.phase5Version(),
        original.phase6Version(),
        original.phase7Version(),
        original.replayReference(),
        original.rule());
    return new Result(Verdict.UNCERTAIN, evidence);
  }

  private static Evidence evidence(Verdict verdict, String playerId, long serverTick, Player prior, Player observed,
                                   WorldSnapshot world, String worldReference, Validation.SyncWindow timing,
                                   List<String> inputs, int candidates, int matches, int eliminated,
                                   String reason, OptionalLong first, Optional<CandidateSummary> closest,
                                   List<String> diagnostics, List<String> uncertainty, String replay) {
    return new Evidence(VERSION, verdict, playerId, serverTick, timing.earliestClientTick(), timing.latestClientTick(),
        prior, observed, Contracts.TARGET_VERSION, worldReference, inputs, timing.reasons(),
        candidates, matches, eliminated, reason, first, closest, diagnostics, uncertainty,
        PHASE5_VERSION, PHASE6_VERSION, PHASE7_VERSION, replay, "MOVEMENT_REACHABILITY");
  }

  private static Optional<CandidateSummary> bestMatchingCandidate(Set<Candidate> candidates, Observation observation) {
    return candidates.stream().filter(c -> matches(c.context().player(), observation))
        .sorted(Comparator.comparingLong(Candidate::id)).map(Phase8MovementValidation::summary).findFirst();
  }

  private static String impossibleReason(
      Player observed,
      int candidateCount,
      int matchingCandidates,
      Phase6Reachability.Evidence comparison,
      Optional<Candidate> closest) {
    StringBuilder reason = new StringBuilder(
        "all exhaustively modeled legitimate candidates disagree with the observed movement state");
    reason.append("; candidates=").append(candidateCount)
        .append(" matching=").append(matchingCandidates)
        .append(" mismatchFields=")
        .append(comparison.mismatches().isEmpty()
            ? "[]"
            : comparison.mismatches().getFirst().dimensions());

    if (closest.isEmpty()) {
      reason.append("; closestCandidate=none");
      return reason.toString();
    }

    Candidate candidate = closest.orElseThrow();
    Player predicted = candidate.context().player();

    reason.append("; closestCandidate=#").append(candidate.id())
        .append("@").append(candidate.context().simulationTick());

    if (observed.position() != null && predicted.position() != null) {
      reason.append(" positionDistance=")
          .append(String.format(Locale.ROOT, "%.6f", distance(predicted.position(), observed.position())))
          .append(" observedPos=").append(observed.position())
          .append(" candidatePos=").append(predicted.position())
          .append(" deltaPos=").append(delta(predicted.position(), observed.position()));
    } else {
      reason.append(" positionDistance=not-reported");
    }

    if (predicted.velocity() != null && observed.velocity() != null) {
      reason.append(" velocityDistance=")
          .append(String.format(Locale.ROOT, "%.6f", distance(predicted.velocity(), observed.velocity())))
          .append(" observedVel=").append(observed.velocity())
          .append(" candidateVel=").append(predicted.velocity())
          .append(" deltaVel=").append(delta(predicted.velocity(), observed.velocity()));
    }

    reason.append(" observedGround=").append(observed.onGround())
        .append(" candidateGround=").append(predicted.onGround());

    if (comparison.mismatches().size() > 0) {
      reason.append(" mismatchDetails=").append(comparison.mismatches().getFirst().details());
    }
    return reason.toString();
  }

  private static double distance(Maths.Vec3 actual, Maths.Vec3 expected) {
    double dx = actual.x() - expected.x();
    double dy = actual.y() - expected.y();
    double dz = actual.z() - expected.z();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private static Maths.Vec3 delta(Maths.Vec3 actual, Maths.Vec3 expected) {
    return new Maths.Vec3(
        actual.x() - expected.x(),
        actual.y() - expected.y(),
        actual.z() - expected.z());
  }

  private static Optional<CandidateSummary> bestCandidate(Set<Candidate> candidates, Player observed) {
    return candidates.stream().sorted(Comparator.comparingDouble((Candidate c) -> distance(c.context().player(), observed))
        .thenComparingLong(Candidate::id)).map(Phase8MovementValidation::summary).findFirst();
  }

  private static boolean matches(Player candidate, Observation observation) {
    Player observed = observation.observed();
    for (ObservedField field : observation.known()) {
      switch (field) {
        case POSITION -> { if (!Phase6Reachability.positionMatches(candidate.position(), observed.position())) return false; }
        case VELOCITY -> { if (!candidate.velocity().equals(observed.velocity())) return false; }
        case ROTATION -> { if (Float.compare(candidate.yaw(), observed.yaw()) != 0 || Float.compare(candidate.pitch(), observed.pitch()) != 0) return false; }
        case GROUND -> { if (candidate.onGround() != observed.onGround()) return false; }
        case GAMEMODE -> { if (!candidate.gamemode().equals(observed.gamemode())) return false; }
        case EFFECTS -> { if (!candidate.effects().equals(observed.effects())) return false; }
        case TELEPORT_PENDING -> { if (candidate.awaitingTeleport().isPresent() != observed.awaitingTeleport().isPresent()) return false; }
      }
    }
    return true;
  }

  private static double distance(Player a, Player b) {
    double dx = a.position().x() - b.position().x();
    double dy = a.position().y() - b.position().y();
    double dz = a.position().z() - b.position().z();
    return dx * dx + dy * dy + dz * dz;
  }

  private static CandidateSummary summary(Candidate c) {
    Player p = c.context().player();
    return new CandidateSummary(c.id(), c.context().simulationTick(), p.position().toString(),
        p.velocity().toString(), p.onGround(), c.context().pose().name(),
        p.yaw(), p.pitch(), c.provenance().toString());
  }

  public record Accumulator(Map<String, State> players) implements Serializable {
    public Accumulator { players = Map.copyOf(players); }
    public static Accumulator empty() { return new Accumulator(Map.of()); }

    public Accumulated accept(Evidence evidence, Config config) {
      return accept(evidence, config, Math.max(0L, evidence.serverTick()) * 50L);
    }

    public Accumulated accept(Evidence evidence, Config config, long nowMillis) {
      Objects.requireNonNull(evidence); Objects.requireNonNull(config);
      String key = evidence.playerId() + "/" + evidence.rule();
      State old = players.getOrDefault(key, State.empty());
      State next = switch (evidence.verdict()) {
        case IMPOSSIBLE -> old.impossible(evidence.serverTick(), nowMillis, config, evidence.rule());
        case POSSIBLE -> old.recovered(evidence.serverTick(), nowMillis, config, evidence.rule());
        case UNCERTAIN -> old.uncertain(evidence.serverTick(), nowMillis, config, evidence.rule());
      };

      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(evidence.rule());
      int activeCount = next.violationTimesMillis().size();
      double level = activeCount * config.violationIncrement();
      next = new State(next.consecutiveImpossible(), next.supportingImpossible(),
          next.uncertaintyPeriods(), next.recoveries(), next.lastObservationTick(),
          next.lastAlertTick(), level, next.lastAlertViolationLevel(),
          next.violationTimesMillis());

      Optional<Alert> alert = Optional.empty();
      Optional<Alert> log = Optional.empty();

      boolean thresholdReached = findingThresholdReached(level, policy.alert());
      boolean crossedNextInterval = commandBoundaryCrossed(level, policy.alert(), next.lastAlertViolationLevel());
      if (config.alertsEnabled() && evidence.verdict() == Verdict.IMPOSSIBLE
          && thresholdReached && crossedNextInterval) {
        Alert emitted = new Alert(evidence.playerId(), evidence.serverTick(),
            evidence.firstInconsistentTick().orElse(evidence.serverTick()),
            evidence.rule(), evidence.eliminationReason(),
            Math.min(1.0, level / Math.max(1.0, policy.alert().threshold())),
            next.supportingImpossible(), level, evidence.replayReference());
        alert = Optional.of(emitted);
        next = next.alerted(evidence.serverTick(), level);
      }

      if (evidence.verdict() == Verdict.IMPOSSIBLE
          && findingThresholdReached(level, policy.log())
          && commandBoundaryCrossed(level, policy.log(), 0.0)) {
        Alert emitted = new Alert(evidence.playerId(), evidence.serverTick(),
            evidence.firstInconsistentTick().orElse(evidence.serverTick()),
            evidence.rule(), evidence.eliminationReason(),
            Math.min(1.0, level / Math.max(1.0, policy.log().threshold())),
            next.supportingImpossible(), level, evidence.replayReference());
        log = Optional.of(emitted);
      }

      Map<String, State> updated = new LinkedHashMap<>(players);
      updated.put(key, next);
      return new Accumulated(new Accumulator(updated), alert, log);
    }

    private static boolean findingThresholdReached(double level, GrimAlertPolicy.CommandRule rule) {
      return level + 1e-9 >= rule.threshold();
    }

    private static boolean commandBoundaryCrossed(double level, GrimAlertPolicy.CommandRule rule, double lastLevel) {
      if (rule.interval() == 0.0) {
        return level >= rule.threshold() && lastLevel < rule.threshold();
      }
      if (lastLevel <= 0.0) return level + 1e-9 >= rule.threshold();
      return level + 1e-9 >= lastLevel + rule.interval();
    }
  }


  public record State(int consecutiveImpossible, int supportingImpossible, int uncertaintyPeriods,
                      int recoveries, long lastObservationTick, long lastAlertTick,
                      double violationLevel, double lastAlertViolationLevel,
                      List<Long> violationTimesMillis) implements Serializable {
    public State {
      if (consecutiveImpossible < 0 || supportingImpossible < 0 || uncertaintyPeriods < 0 || recoveries < 0
          || !Double.isFinite(violationLevel) || violationLevel < 0.0
          || !Double.isFinite(lastAlertViolationLevel) || lastAlertViolationLevel < 0.0) {
        throw new IllegalArgumentException("invalid movement violation state");
      }
      violationTimesMillis = List.copyOf(violationTimesMillis);
    }

    public State(int consecutiveImpossible, int supportingImpossible, int uncertaintyPeriods,
                 int recoveries, long lastObservationTick, long lastAlertTick) {
      this(consecutiveImpossible, supportingImpossible, uncertaintyPeriods, recoveries,
          lastObservationTick, lastAlertTick, supportingImpossible, supportingImpossible, List.of());
    }

    public State(int consecutiveImpossible, int supportingImpossible, int uncertaintyPeriods,
                 int recoveries, long lastObservationTick, long lastAlertTick,
                 double violationLevel, double lastAlertViolationLevel) {
      this(consecutiveImpossible, supportingImpossible, uncertaintyPeriods, recoveries,
          lastObservationTick, lastAlertTick, violationLevel, lastAlertViolationLevel, List.of());
    }

    public static State empty() {
      return new State(0, 0, 0, 0, -1, -1, 0.0, 0.0, List.of());
    }

    State impossible(long tick, long nowMillis, Config config) {
      return impossible(tick, nowMillis, config, "MOVEMENT_REACHABILITY");
    }

    State impossible(long tick, long nowMillis, Config config, String rule) {
      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(rule);
      long cutoff = nowMillis - policy.removeViolationsAfterMillis();
      List<Long> active = new ArrayList<>();
      for (long timestamp : violationTimesMillis) if (timestamp > cutoff) active.add(timestamp);
      active.add(nowMillis);
      double level = active.size() * config.violationIncrement();
      return new State(consecutiveImpossible + 1, supportingImpossible + 1, uncertaintyPeriods, recoveries,
          tick, lastAlertTick, level, lastAlertViolationLevel, active);
    }

    State recovered(long tick, long nowMillis, Config config) {
      return recovered(tick, nowMillis, config, "MOVEMENT_REACHABILITY");
    }

    State recovered(long tick, long nowMillis, Config config, String rule) {
      return new State(0, supportingImpossible, uncertaintyPeriods, recoveries + 1, tick, lastAlertTick,
          activeLevel(nowMillis, config, rule), lastAlertViolationLevel, activeViolations(nowMillis, config, rule));
    }

    State uncertain(long tick, long nowMillis, Config config) {
      return uncertain(tick, nowMillis, config, "MOVEMENT_REACHABILITY");
    }

    State uncertain(long tick, long nowMillis, Config config, String rule) {
      return new State(0, supportingImpossible, uncertaintyPeriods + 1, recoveries, lastObservationTick, lastAlertTick,
          activeLevel(nowMillis, config, rule), lastAlertViolationLevel, activeViolations(nowMillis, config, rule));
    }

    State alerted(long tick, double level) {
      return new State(consecutiveImpossible, supportingImpossible, uncertaintyPeriods, recoveries,
          lastObservationTick, tick, level, level, violationTimesMillis);
    }

    List<Long> activeViolations(long nowMillis, Config config) {
      return activeViolations(nowMillis, config, "MOVEMENT_REACHABILITY");
    }

    List<Long> activeViolations(long nowMillis, Config config, String rule) {
      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(rule);
      long cutoff = Math.max(0L, nowMillis - policy.removeViolationsAfterMillis());
      return violationTimesMillis.stream().filter(timestamp -> timestamp > cutoff).toList();
    }

    double activeLevel(long nowMillis, Config config) {
      return activeLevel(nowMillis, config, "MOVEMENT_REACHABILITY");
    }

    double activeLevel(long nowMillis, Config config, String rule) {
      return activeViolations(nowMillis, config, rule).size() * config.violationIncrement();
    }
  }

  public record Accumulated(Accumulator state, Optional<Alert> alert, Optional<Alert> log) implements Serializable {
    public Accumulated {
      Objects.requireNonNull(state);
      Objects.requireNonNull(alert);
      Objects.requireNonNull(log);
    }
  }

  public record Alert(String playerId, long serverTick, long firstInconsistentTick, String reason,
                      String evidence, double confidence, int supportingEvents, double violationLevel,
                      String replayReference) implements Serializable {
    /**
     * Operator-facing alert, intentionally concise like a conventional anti-cheat flag.
     * Detailed forensic evidence remains available through debugMessage().
     */
    public String message() {
      return serverMessage(playerId);
    }

    public String serverMessage(String displayName) {
      String name = displayName == null || displayName.isBlank() ? playerId : displayName;
      return "[PhantomAC] " + name + " failed " + reason + " (VL " + formatViolationLevel(violationLevel) + ")";
    }

    private static String formatViolationLevel(double value) {
      if (Math.abs(value - Math.rint(value)) < 1e-9) return Long.toString(Math.round(value));
      return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Full evidence is retained for console diagnostics and future forensic tooling. */
    public String debugMessage() {
      return "[PhantomAC][PHASE8] player=" + playerId + " type=MOVEMENT result=IMPOSSIBLE tick=" + serverTick
          + " first-inconsistent-tick=" + firstInconsistentTick + " reason=" + evidence
          + " confidence=" + String.format(Locale.ROOT, "%.2f", confidence)
          + " replay=" + replayReference;
    }
  }
}