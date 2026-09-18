package dev.phantom.ac;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import dev.phantom.ac.Phase8MovementValidation.Verdict;

/**
 * Prevents replaying the same live validation observation into the evidence
 * accumulator on every periodic validation pass.
 *
 * <p>An observation may move from UNCERTAIN to a decisive verdict as more world
 * or timing information arrives. The inverse transition is not applied because
 * the accumulator intentionally does not retract prior evidence.</p>
 */
public final class ValidationResultGate implements Serializable {
  private final Map<String, Verdict> accepted = new HashMap<>();

  public synchronized boolean accept(String observationKey, Verdict verdict) {
    Objects.requireNonNull(observationKey, "observationKey");
    Objects.requireNonNull(verdict, "verdict");
    Verdict previous = accepted.get(observationKey);

    if (previous == null) {
      accepted.put(observationKey, verdict);
      return true;
    }

    if (previous == Verdict.UNCERTAIN && verdict != Verdict.UNCERTAIN) {
      accepted.put(observationKey, verdict);
      return true;
    }

    if (previous == Verdict.POSSIBLE && verdict == Verdict.IMPOSSIBLE) {
      accepted.put(observationKey, verdict);
      return true;
    }

    return false;
  }

  public synchronized Verdict last(String observationKey) {
    return accepted.get(observationKey);
  }

  public synchronized void clear() {
    accepted.clear();
  }
}
