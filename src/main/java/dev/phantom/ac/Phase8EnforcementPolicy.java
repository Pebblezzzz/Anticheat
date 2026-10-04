package dev.phantom.ac;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pure Phase 8 enforcement policy.
 *
 * <p>Enforcement is deliberately downstream of evidence accumulation. The policy
 * never inspects movement distance or timing itself; it only permits actions when
 * the supplied Phase 8 evidence proves exhaustive modeled elimination and the
 * configured repeated-evidence threshold has been reached. The accumulator uses
 * the same consecutive-impossible episode gate for staff alerts, so a recovered
 * or uncertain observation cannot inherit a prior episode into enforcement.</p>
 */
public final class Phase8EnforcementPolicy {
  private Phase8EnforcementPolicy() {}

  public enum Action { SETBACK, KICK, PUNISHMENT_COMMAND }

  public record Config(
      boolean setbackEnabled,
      boolean kickEnabled,
      boolean punishmentEnabled,
      boolean onlyWhenExhaustive,
      double minimumSetbackViolationLevel,
      double minimumKickViolationLevel,
      double minimumPunishmentViolationLevel,
      double minimumConfidence,
      String punishmentCommand) implements Serializable {
    public Config {
      if (!Double.isFinite(minimumSetbackViolationLevel) || minimumSetbackViolationLevel < 0.0
          || !Double.isFinite(minimumKickViolationLevel) || minimumKickViolationLevel < 0.0
          || !Double.isFinite(minimumPunishmentViolationLevel) || minimumPunishmentViolationLevel < 0.0) {
        throw new IllegalArgumentException("enforcement violation thresholds must be finite and non-negative");
      }
      if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0.0 || minimumConfidence > 1.0) {
        throw new IllegalArgumentException("minimumConfidence must be in 0..1");
      }
      Objects.requireNonNull(punishmentCommand);
      if (punishmentEnabled && punishmentCommand.isBlank()) {
        throw new IllegalArgumentException("punishmentCommand is required when punishment is enabled");
      }
    }
  }

  public record Decision(
      boolean eligible,
      double confidence,
      Set<Action> actions,
      String reason) implements Serializable {
    public Decision {
      if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
        throw new IllegalArgumentException("invalid confidence");
      }
      actions = Set.copyOf(actions);
      Objects.requireNonNull(reason);
    }
  }

  public static Decision evaluate(
      Phase8MovementValidation.Evidence evidence,
      Phase8MovementValidation.State episode,
      Config config) {
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(episode);
    Objects.requireNonNull(config);

    if (evidence.verdict() != Phase8MovementValidation.Verdict.IMPOSSIBLE) {
      return new Decision(false, 0.0, Set.of(), "evidence is not IMPOSSIBLE");
    }
    if (evidence.priorState().uncertain() || evidence.observedState().uncertain()) {
      return new Decision(false, 0.0, Set.of(), "player state is uncertain");
    }

    String reason = evidence.eliminationReason();
    boolean exhaustive = reason.startsWith("all exhaustively modeled legitimate candidates");
    if (config.onlyWhenExhaustive() && !exhaustive) {
      return new Decision(false, 0.0, Set.of(), "evidence is not an exhaustive movement-reachability proof");
    }
    /*
     * Never treat a textual elimination reason as proof by itself. Production
     * enforcement additionally requires the numeric evidence contract to show
     * that a non-empty candidate set was completely eliminated and no candidate
     * matched the observation.
     */
    if (evidence.reachableCandidateCount() <= 0) {
      return new Decision(false, 0.0, Set.of(), "evidence contains no reachable candidates to eliminate");
    }
    if (evidence.matchingCandidateCount() != 0) {
      return new Decision(false, 0.0, Set.of(), "evidence still contains matching candidates");
    }
    if (evidence.candidatesEliminated() != evidence.reachableCandidateCount()) {
      return new Decision(false, 0.0, Set.of(), "evidence did not eliminate the complete reachable candidate set");
    }
    if (evidence.firstInconsistentTick().isEmpty()) {
      return new Decision(false, 0.0, Set.of(), "evidence has no first inconsistent tick");
    }
    double violationLevel = episode.violationLevel();
    double confidence = Math.min(1.0, violationLevel / Math.max(1.0, config.minimumPunishmentViolationLevel()));
    if (violationLevel < config.minimumSetbackViolationLevel()
        && violationLevel < config.minimumKickViolationLevel()
        && violationLevel < config.minimumPunishmentViolationLevel()) {
      return new Decision(false, confidence, Set.of(),
          "active violation level has not reached any configured enforcement threshold");
    }
    if (confidence < config.minimumConfidence()) {
      return new Decision(false, confidence, Set.of(), "minimum enforcement confidence has not been reached");
    }

    EnumSet<Action> actions = EnumSet.noneOf(Action.class);
    if (config.setbackEnabled() && violationLevel >= config.minimumSetbackViolationLevel()) {
      actions.add(Action.SETBACK);
    }
    if (config.kickEnabled() && violationLevel >= config.minimumKickViolationLevel()) {
      actions.add(Action.KICK);
    }
    if (config.punishmentEnabled() && violationLevel >= config.minimumPunishmentViolationLevel()) {
      actions.add(Action.PUNISHMENT_COMMAND);
    }
    return new Decision(!actions.isEmpty(), confidence, actions,
        "exhaustive impossible movement evidence reached the configured active-VL enforcement thresholds");
  }

  public static String renderPunishmentCommand(
      String template,
      String player,
      Phase8MovementValidation.Evidence evidence) {
    Objects.requireNonNull(template);
    Objects.requireNonNull(player);
    Objects.requireNonNull(evidence);
    return template
        .replace("{player}", player)
        .replace("{playerId}", evidence.playerId())
        .replace("{tick}", Long.toString(evidence.serverTick()))
        .replace("{rule}", evidence.rule())
        .replace("{replay}", evidence.replayReference())
        .replace("{firstInconsistentTick}",
            evidence.firstInconsistentTick().isPresent()
                ? Long.toString(evidence.firstInconsistentTick().getAsLong())
                : "");
  }
}