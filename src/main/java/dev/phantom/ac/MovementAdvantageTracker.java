package dev.phantom.ac;

import java.util.Collection;
import java.util.Objects;

import static dev.phantom.ac.Maths.Vec3;

/**
 * Persistent signed movement-advantage state derived from the reachable-state
 * envelope rather than from one candidate's residual.
 *
 * <p>The horizontal value is measured along the direction of the observed
 * horizontal movement. Positive means the observation extended beyond the
 * furthest reachable projection; negative means it fell behind the nearest
 * reachable projection. Vertical advantage uses the same signed envelope rule.</p>
 *
 * <p>The accumulated state is deliberately independent from the prediction
 * candidate positions, so spatial reconciliation/rebasing cannot erase prior
 * movement advantage.</p>
 */
final class MovementAdvantageTracker {
  static final double DECAY = 0.995D;
  static final double CEILING = 4.0D;
  static final double EPSILON = 1.0E-9D;

  record Snapshot(
      boolean evaluated,
      double signedHorizontal,
      double signedVertical,
      double accumulatedHorizontal,
      double accumulatedVertical) {
    Snapshot {
      if (!Double.isFinite(signedHorizontal)
          || !Double.isFinite(signedVertical)
          || !Double.isFinite(accumulatedHorizontal)
          || !Double.isFinite(accumulatedVertical)) {
        throw new IllegalArgumentException("movement advantage must be finite");
      }
    }

    static Snapshot empty() {
      return new Snapshot(false, 0.0D, 0.0D, 0.0D, 0.0D);
    }
  }

  private double accumulatedHorizontal;
  private double accumulatedVertical;

  Snapshot observe(
      Collection<Vec3> reachablePositions,
      Vec3 origin,
      Vec3 observedAfter,
      boolean reliableEnvelope) {
    Objects.requireNonNull(reachablePositions, "reachablePositions");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(observedAfter, "observedAfter");

    EnvelopeExcess excess = measure(reachablePositions, origin, observedAfter);
    if (!reliableEnvelope || !excess.evaluated()) {
      decay();
      return new Snapshot(
          false,
          0.0D,
          0.0D,
          accumulatedHorizontal,
          accumulatedVertical);
    }

    accumulatedHorizontal = clamp(
        accumulatedHorizontal * DECAY + excess.signedHorizontal());
    accumulatedVertical = clamp(
        accumulatedVertical * DECAY + excess.signedVertical());

    return new Snapshot(
        true,
        excess.signedHorizontal(),
        excess.signedVertical(),
        accumulatedHorizontal,
        accumulatedVertical);
  }

  Snapshot decayOnly() {
    decay();
    return new Snapshot(false, 0.0D, 0.0D, accumulatedHorizontal, accumulatedVertical);
  }

  void reset() {
    accumulatedHorizontal = 0.0D;
    accumulatedVertical = 0.0D;
  }

  private void decay() {
    accumulatedHorizontal = clamp(accumulatedHorizontal * DECAY);
    accumulatedVertical = clamp(accumulatedVertical * DECAY);
  }

  private static double clamp(double value) {
    return Math.max(-CEILING, Math.min(CEILING, value));
  }

  private static EnvelopeExcess measure(
      Collection<Vec3> reachablePositions,
      Vec3 origin,
      Vec3 observedAfter) {
    if (reachablePositions.isEmpty()) {
      return new EnvelopeExcess(false, 0.0D, 0.0D);
    }

    double observedDx = observedAfter.x() - origin.x();
    double observedDy = observedAfter.y() - origin.y();
    double observedDz = observedAfter.z() - origin.z();
    double observedHorizontal = Math.hypot(observedDx, observedDz);

    double minVertical = Double.POSITIVE_INFINITY;
    double maxVertical = Double.NEGATIVE_INFINITY;
    double minHorizontalProjection = Double.POSITIVE_INFINITY;
    double maxHorizontalProjection = Double.NEGATIVE_INFINITY;

    double horizontalUx = 0.0D;
    double horizontalUz = 0.0D;
    if (observedHorizontal > EPSILON) {
      horizontalUx = observedDx / observedHorizontal;
      horizontalUz = observedDz / observedHorizontal;
    }

    boolean horizontalEvaluated = observedHorizontal > EPSILON;
    for (Vec3 reachable : reachablePositions) {
      double dx = reachable.x() - origin.x();
      double dy = reachable.y() - origin.y();
      double dz = reachable.z() - origin.z();
      if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) continue;

      minVertical = Math.min(minVertical, dy);
      maxVertical = Math.max(maxVertical, dy);

      if (horizontalEvaluated) {
        double projection = dx * horizontalUx + dz * horizontalUz;
        minHorizontalProjection = Math.min(minHorizontalProjection, projection);
        maxHorizontalProjection = Math.max(maxHorizontalProjection, projection);
      }
    }

    double signedHorizontal = horizontalEvaluated
        ? signedOutside(observedHorizontal, minHorizontalProjection, maxHorizontalProjection)
        : 0.0D;
    double signedVertical =
        Double.isFinite(minVertical) && Double.isFinite(maxVertical)
            ? signedOutside(observedDy, minVertical, maxVertical)
            : 0.0D;

    return new EnvelopeExcess(
        horizontalEvaluated || (Double.isFinite(minVertical) && Double.isFinite(maxVertical)),
        signedHorizontal,
        signedVertical);
  }

  private static double signedOutside(double observed, double min, double max) {
    if (!Double.isFinite(observed) || !Double.isFinite(min) || !Double.isFinite(max)) {
      return 0.0D;
    }
    if (observed > max) return observed - max;
    if (observed < min) return observed - min;
    return 0.0D;
  }

  private record EnvelopeExcess(
      boolean evaluated,
      double signedHorizontal,
      double signedVertical) {}
}
