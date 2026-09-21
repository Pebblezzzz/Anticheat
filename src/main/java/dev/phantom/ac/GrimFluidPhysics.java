package dev.phantom.ac;

import dev.phantom.ac.Maths.Aabb;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.FluidState;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.WorldQueries;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.Objects;

/** Clean-room 1.21.11 fluid interaction model patterned after Grim's base-tick/fluid prediction split. */
public final class GrimFluidPhysics {
  private GrimFluidPhysics() {}

  public enum Type { NONE, WATER, LAVA }

  public record Sample(Type type, double height, boolean eyeInFluid, Vec3 current,
                       boolean currentKnown, boolean submerged, boolean surfaceSwimming,
                       boolean hasFluid) {
    public Sample {
      Objects.requireNonNull(type);
      Objects.requireNonNull(current);
      if (!Double.isFinite(height) || height < 0.0) throw new IllegalArgumentException("invalid fluid height");
    }

    public static Sample none() {
      return new Sample(Type.NONE, 0.0, false, Vec3.ZERO, true, false, false, false);
    }
  }

  public static Sample sample(WorldSnapshot world, Aabb box, double eyeY,
                              boolean previousSwimming, boolean vehicle) {
    Objects.requireNonNull(world);
    Objects.requireNonNull(box);

    BlockBox region = new BlockBox(
        Math.floor(box.minX()) - 1,
        Math.floor(box.minY()) - 1,
        Math.floor(box.minZ()) - 1,
        Math.floor(box.maxX()) + 1,
        Math.floor(box.maxY()) + 1,
        Math.floor(box.maxZ()) + 1);

    double maxHeight = 0.0;
    Type type = Type.NONE;
    boolean eyeInFluid = false;
    boolean currentKnown = true;
    Vec3 currentSum = Vec3.ZERO;
    int currentSamples = 0;
    boolean hasFluid = false;

    for (Pos p : world.positionsIntersecting(region)) {
      Coverage coverage = world.coverageAt(p.x(), p.y(), p.z());
      if (coverage != Coverage.KNOWN) {
        if (boxContainsCell(box, p)) currentKnown = false;
        continue;
      }

      BlockState state = world.blockAtOrNull(p.x(), p.y(), p.z());
      if (state == null) continue;
      FluidState fluid = WorldQueries.fluidAt(world, p, state);
      if (!fluid.isFluid()) continue;

      hasFluid = true;
      Type cellType = fluid.type() == FluidState.Type.LAVA ? Type.LAVA : Type.WATER;
      if (type == Type.NONE) type = cellType;
      if (type != cellType) continue;

      if (!fluid.hasKnownHeight()) {
        currentKnown = false;
        continue;
      }

      double surface = p.y() + fluid.height();
      if (surface >= box.minY()) maxHeight = Math.max(maxHeight, surface - box.minY());
      if (eyeY >= p.y() && eyeY <= surface) eyeInFluid = true;

      if (!vehicle && cellType == Type.WATER) {
        Vec3 flow = localCurrent(world, p, fluid);
        if (flow == null) currentKnown = false;
        else {
          currentSum = currentSum.add(flow);
          currentSamples++;
        }
      }
    }

    maxHeight = Math.max(0.0, Math.min(1.0, maxHeight));
    Vec3 current = currentSamples == 0 ? Vec3.ZERO : currentSum.multiply(1.0 / currentSamples);
    double horizontal = Math.hypot(current.x(), current.z());
    if (horizontal > 1.0e-12) current = current.multiply(1.0 / horizontal);

    boolean submerged = maxHeight > 0.6 || eyeInFluid;
    boolean surfaceSwimming = type == Type.WATER && (eyeInFluid || previousSwimming);
    return new Sample(type, maxHeight, eyeInFluid, current, currentKnown,
        submerged, surfaceSwimming, hasFluid);
  }

  private static Vec3 localCurrent(WorldSnapshot world, Pos p, FluidState.Type centerType) {
    double north = neighborHeight(world, p.x(), p.y(), p.z() - 1, centerType);
    double south = neighborHeight(world, p.x(), p.y(), p.z() + 1, centerType);
    double west = neighborHeight(world, p.x() - 1, p.y(), p.z(), centerType);
    double east = neighborHeight(world, p.x() + 1, p.y(), p.z(), centerType);
    if (Double.isNaN(north) || Double.isNaN(south)
        || Double.isNaN(west) || Double.isNaN(east)) return null;
    return new Vec3(west - east, 0.0, north - south);
  }

  private static double neighborHeight(WorldSnapshot world, int x, int y, int z,
                                       FluidState.Type expected) {
    if (world.coverageAt(x, y, z) != Coverage.KNOWN) return Double.NaN;
    FluidState fluid = WorldQueries.fluidAt(world, x, y, z);
    if (fluid.type() != expected) return y;
    if (!fluid.hasKnownHeight()) return Double.NaN;
    return y + fluid.height();
  }

  private static boolean boxContainsCell(Aabb box, Pos p) {
    return p.x() <= Math.floor(box.maxX()) && p.x() >= Math.floor(box.minX())
        && p.y() <= Math.floor(box.maxY()) && p.y() >= Math.floor(box.minY())
        && p.z() <= Math.floor(box.maxZ()) && p.z() >= Math.floor(box.minZ());
  }

  public static Vec3 applyCurrent(Vec3 velocity, Sample sample) {
    Objects.requireNonNull(velocity);
    Objects.requireNonNull(sample);
    if (!sample.currentKnown() || sample.type() == Type.NONE) return velocity;
    Vec3 current = sample.current();
    double magnitude = Math.hypot(current.x(), current.z());
    if (magnitude < 1.0e-12) return velocity;

    double scale = 0.0023333333333333335;
    if (Math.hypot(velocity.x(), velocity.z()) < 0.003 && magnitude * scale < 0.0045) {
      scale = 0.0045 / magnitude;
    }
    return velocity.add(current.multiply(scale));
  }

  public static Vec3 applySwimmingSteering(Vec3 velocity, double lookY, boolean active) {
    if (!active) return velocity;
    double scalar = lookY < -0.2 ? 0.085 : 0.06;
    return new Vec3(velocity.x(), velocity.y() + (lookY - velocity.y()) * scalar, velocity.z());
  }

  public static double waterDrag(boolean sprinting) { return sprinting ? 0.9 : 0.8; }
  public static double lavaDrag() { return 0.5; }
  public static double gravityMultiplier(Type type) { return type == Type.LAVA ? 0.25 : 1.0; }
}
