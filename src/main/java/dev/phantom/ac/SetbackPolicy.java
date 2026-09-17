package dev.phantom.ac;

import java.io.Serializable;
import java.util.Objects;

import dev.phantom.ac.Phase8MovementValidation.Evidence;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;

/**
 * Pure policy for the optional strict Phase 8 debug setback.
 *
 * <p>A setback is only permitted after the movement validator has emitted an
 * IMPOSSIBLE result whose evidence says every modeled legitimate candidate was
 * exhausted. This deliberately does not inspect distances or invent a second
 * movement threshold.</p>
 */
public final class SetbackPolicy {
  private SetbackPolicy() {}

  public record Decision(boolean allowed, String reason) implements Serializable {
    public Decision {
      Objects.requireNonNull(reason, "reason");
    }
  }

  public static Decision evaluate(Evidence evidence, boolean enabled, boolean onlyExhaustive) {
    Objects.requireNonNull(evidence, "evidence");

    if (!enabled) return new Decision(false, "strict setback mode is disabled");
    if (evidence.verdict() != Verdict.IMPOSSIBLE) {
      return new Decision(false, "observation is not IMPOSSIBLE");
    }

    Player prior = evidence.priorState();
    Player observed = evidence.observedState();
    if (prior.uncertain() || observed.uncertain()) {
      return new Decision(false, "player state is uncertain");
    }

    if (onlyExhaustive) {
      String reason = evidence.eliminationReason();
      if (!reason.startsWith("all exhaustively modeled legitimate candidates")) {
        return new Decision(false, "evidence does not prove exhaustive candidate elimination");
      }
      if (evidence.firstInconsistentTick().isEmpty()) {
        return new Decision(false, "no first-inconsistent tick was recorded");
      }
    }

    return new Decision(true, "exhaustive legitimate movement candidates were eliminated");
  }
}
