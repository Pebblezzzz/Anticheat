package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Phase5Mechanics.VehicleState;
import dev.phantom.ac.Phase5Mechanics.VehicleType;
import java.util.Objects;

/** Clean-room rideable/living-vehicle ticker patterned after Grim's vehicle movement split. */
public final class GrimVehiclePhysics {
  private GrimVehiclePhysics() {}

  public record Result(Vec3 velocity, String diagnostic) {
    public Result {
      Objects.requireNonNull(velocity);
      Objects.requireNonNull(diagnostic);
    }
  }

  public static Result tick(
      VehicleState vehicle,
      Simulation.AdvancedInput input,
      Vec3 playerVelocity,
      Phase5Mechanics.MovementEnvironment environment) {
    Objects.requireNonNull(vehicle);
    Objects.requireNonNull(input);
    Objects.requireNonNull(playerVelocity);
    Objects.requireNonNull(environment);

    if (!vehicle.active()) return new Result(playerVelocity, "no client-controlled vehicle");

    double baseSpeed = vehicle.movementSpeed() > 0.0 ? vehicle.movementSpeed() : 0.1;
    double forward = input.forward();
    double strafe = input.strafe();
    double yaw = Math.toRadians(vehicle.yaw());
    double accel;
    double drag;

    switch (vehicle.type()) {
      case PIG -> {
        accel = baseSpeed * 0.225;
        forward = forward > 0 ? 1.0 : forward < 0 ? -0.25 : 0.0;
        drag = 0.91;
      }
      case STRIDER -> {
        accel = baseSpeed * (vehicle.cold() ? 0.35 : 0.55);
        forward = forward > 0 ? 1.0 : forward < 0 ? -0.25 : 0.0;
        drag = environment.fluid() == Phase5Mechanics.Fluid.LAVA ? 0.5 : 0.91;
      }
      case HORSE -> {
        accel = baseSpeed;
        forward = forward > 0 ? 1.0 : forward < 0 ? -0.25 : 0.0;
        strafe *= 0.5;
        drag = 0.91;
      }
      case CAMEL -> {
        accel = baseSpeed + (vehicle.dashReady() ? 0.1 : 0.0);
        forward = forward > 0 ? 1.0 : forward < 0 ? -0.25 : 0.0;
        strafe *= 0.5;
        drag = 0.91;
      }
      case BOAT, CHEST_BOAT -> {
        accel = 0.04;
        drag = environment.fluid() == Phase5Mechanics.Fluid.WATER ? 0.90 : 0.96;
      }
      case MINECART -> {
        accel = forward == 0.0 ? 0.0 : 0.04;
        strafe = 0.0;
        drag = 0.96;
      }
      case NAUTILUS -> {
        accel = baseSpeed;
        drag = environment.fluid() == Phase5Mechanics.Fluid.WATER ? 0.80 : 0.91;
      }
      case HAPPY_GHAST -> {
        double pitch = Math.toRadians(vehicle.pitch());
        double scaled = baseSpeed * 5.0 / 3.0;
        double fwd = forward == 0.0 ? 0.0 : Math.cos(pitch) * (forward > 0 ? 1.0 : -0.5);
        double vertical = -Math.sin(pitch) * (forward > 0 ? 1.0 : -0.5);
        if (input.jump()) vertical += 0.5;
        Vec3 inputVector = rotate(strafe * scaled, fwd * scaled, vehicle.yaw());
        Vec3 result = new Vec3(
            vehicle.velocity().x() + inputVector.x(),
            vehicle.velocity().y() + vertical * scaled,
            vehicle.velocity().z() + inputVector.z());
        return new Result(new Vec3(result.x() * 0.91, result.y() * 0.98, result.z() * 0.91), "happy-ghast rideable ticker");
      }
      case OTHER, NONE -> {
        accel = baseSpeed;
        drag = 0.91;
      }
    }

    Vec3 inputVector = rotate(strafe * accel, forward * accel, Math.toDegrees(yaw));
    Vec3 result = new Vec3(vehicle.velocity().x(), vehicle.velocity().y(), vehicle.velocity().z()).add(inputVector);
    if (vehicle.type() == VehicleType.PIG || vehicle.type() == VehicleType.STRIDER
        || vehicle.type() == VehicleType.HORSE || vehicle.type() == VehicleType.CAMEL
        || vehicle.type() == VehicleType.NAUTILUS) {
      if (!vehicle.onGround()) result = result.add(new Vec3(0, -0.08, 0));
    }
    double verticalDrag = environment.fluid() == Phase5Mechanics.Fluid.NONE ? 0.98 : 0.8;
    result = new Vec3(result.x() * drag, result.y() * verticalDrag, result.z() * drag);
    return new Result(result, "vehicle ticker type=" + vehicle.type());
  }

  private static Vec3 rotate(double strafe, double forward, double yawDegrees) {
    double yaw = Math.toRadians(yawDegrees);
    return new Vec3(
        strafe * Math.cos(yaw) - forward * Math.sin(yaw),
        0.0,
        forward * Math.cos(yaw) + strafe * Math.sin(yaw));
  }
}
