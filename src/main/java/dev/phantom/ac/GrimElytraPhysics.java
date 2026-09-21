package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import java.util.Objects;

/** Clean-room 1.21.11 Elytra movement equations patterned after Grim's prediction engine. */
public final class GrimElytraPhysics {
  private GrimElytraPhysics() {}

  public static Vec3 tick(Vec3 velocity, float yawDegrees, float pitchDegrees,
                          double gravity, boolean slowFalling) {
    Objects.requireNonNull(velocity);

    double pitch = Math.toRadians(pitchDegrees);
    double yaw = Math.toRadians(yawDegrees);
    double lookX = -Math.sin(yaw) * Math.cos(pitch);
    double lookY = -Math.sin(pitch);
    double lookZ = Math.cos(yaw) * Math.cos(pitch);
    double horizontalLook = Math.sqrt(lookX * lookX + lookZ * lookZ);
    double horizontalLength = Math.hypot(velocity.x(), velocity.z());
    double lookLength = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);

    double effectiveGravity = gravity;
    if (velocity.y() <= 0.0 && slowFalling) effectiveGravity = Math.min(effectiveGravity, 0.01);

    double vertCos = Math.cos(pitch);
    vertCos = vertCos * vertCos * Math.min(1.0, lookLength / 0.4);

    Vec3 result = velocity.add(new Vec3(
        0.0, effectiveGravity * (-1.0 + vertCos * 0.75), 0.0));

    if (result.y() < 0.0 && horizontalLook > 0.0) {
      double d5 = result.y() * -0.1 * vertCos;
      result = result.add(new Vec3(
          lookX * d5 / horizontalLook, d5, lookZ * d5 / horizontalLook));
    }

    if (pitch < 0.0 && horizontalLook > 0.0) {
      double d5 = horizontalLength * (-Math.sin(pitch)) * 0.04;
      result = result.add(new Vec3(
          -lookX * d5 / horizontalLook, d5 * 3.2, -lookZ * d5 / horizontalLook));
    }

    if (horizontalLook > 0.0) {
      result = result.add(new Vec3(
          (lookX / horizontalLook * horizontalLength - result.x()) * 0.1,
          0.0,
          (lookZ / horizontalLook * horizontalLength - result.z()) * 0.1));
    }

    return result;
  }

  public static Vec3 endOfTickDrag(Vec3 velocity) {
    return new Vec3(velocity.x() * 0.99, velocity.y() * 0.98, velocity.z() * 0.99);
  }
}
