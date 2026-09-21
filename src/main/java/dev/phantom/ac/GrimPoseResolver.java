package dev.phantom.ac;

import dev.phantom.ac.Maths.Aabb;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.Objects;

/** Pose selection/fallback patterned after Grim's PlayerBaseTick. */
public final class GrimPoseResolver {
  private GrimPoseResolver() {}

  public static Pose requested(MovementEnvironment environment, boolean sleeping, Pose previous) {
    Objects.requireNonNull(environment);
    Objects.requireNonNull(previous);
    if (sleeping) return Pose.SLEEPING;
    if (environment.gliding()) return Pose.FALL_FLYING;
    if (environment.submerged() && environment.swimmingInput()) return Pose.SWIMMING;
    if (environment.sneaking()) return Pose.CROUCHING;
    return Pose.STANDING;
  }

  public static Pose resolve(Pose previous, Pose wanted, Maths.Vec3 position,
                             WorldSnapshot world, EntityCollisions entities, boolean inVehicle) {
    Objects.requireNonNull(previous);
    Objects.requireNonNull(wanted);
    Objects.requireNonNull(position);
    Objects.requireNonNull(world);
    Objects.requireNonNull(entities);

    if (wanted == previous) return previous;
    if (canEnter(wanted, position, world, entities)) return wanted;

    if (wanted == Pose.STANDING && canEnter(Pose.CROUCHING, position, world, entities)) {
      return Pose.CROUCHING;
    }

    if (inVehicle) return previous;
    return canEnter(previous, position, world, entities) ? previous : wanted;
  }

  private static boolean canEnter(Pose pose, Maths.Vec3 position,
                                  WorldSnapshot world, EntityCollisions entities) {
    Aabb box = Aabb.playerAt(position, pose);
    dev.phantom.ac.geometry.BlockBox blockBox = new dev.phantom.ac.geometry.BlockBox(
        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    var worldCollision = dev.phantom.ac.world.WorldQueries.collisions(world, blockBox);
    if (!worldCollision.isDefinite() || !worldCollision.isEmpty()) return false;
    var entityCollision = entities.boxesIn(blockBox);
    return entityCollision.isDefinite() && entityCollision.isEmpty();
  }
}
