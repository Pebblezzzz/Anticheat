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
 * configured repeated-evidence threshold has been reached.</p>
 */
public final class Phase8EnforcementPolicy {
  private Phase8EnforcementPolicy() {}

  public enum Action { SETBACK, KICK, PUNISHMENT_COMMAND }

  public record Config(
      boolean setbackEnabled,
      boolean kickEnabled,
      boolean punishmentEnabled,
      boolean onlyWhenExhaustive,
      int minimumImpossibleObservations,
      double minimumConfidence,
      String punishmentCommand) implements Serializable {
    public Config {
      if (minimumImpossibleObservations < 1) {
        throw new IllegalArgumentException("minimumImpossibleObservations must be positive");
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
    if (evidence.firstInconsistentTick().isEmpty()) {
      return new Decision(false, 0.0, Set.of(), "evidence has no first inconsistent tick");
    }
    if (episode.consecutiveImpossible() < config.minimumImpossibleObservations()) {
      return new Decision(false,
          Math.min(1.0, (double) episode.consecutiveImpossible() / config.minimumImpossibleObservations()),
          Set.of(),
          "configured consecutive impossible-evidence threshold has not been reached");
    }

    double confidence = Math.min(
        1.0,
        (double) episode.consecutiveImpossible() / config.minimumImpossibleObservations());
    if (confidence < config.minimumConfidence()) {
      return new Decision(false, confidence, Set.of(), "minimum enforcement confidence has not been reached");
    }

    EnumSet<Action> actions = EnumSet.noneOf(Action.class);
    if (config.setbackEnabled()) actions.add(Action.SETBACK);
    if (config.kickEnabled()) actions.add(Action.KICK);
    if (config.punishmentEnabled()) actions.add(Action.PUNISHMENT_COMMAND);
    return new Decision(true, confidence, actions, "exhaustive impossible movement evidence met the configured enforcement policy");
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
