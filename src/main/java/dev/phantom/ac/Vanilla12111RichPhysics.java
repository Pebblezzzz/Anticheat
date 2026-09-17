package dev.phantom.ac;

import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.Objects;

import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.State.Player;

/**
 * Phase 5 movement engine that consumes the rich, coverage-aware 1.21.11 world
 * snapshot instead of reducing block states to the legacy block enum.
 */
public final class Vanilla12111RichPhysics {
    public static final double GRAVITY = 0.08;
    public static final double AIR_DRAG = 0.98;
    public static final double AIR_HORIZONTAL_FRICTION = 0.91;
    public static final double AIR_VERTICAL_DRAG = 0.98;
    public static final double AIR_ACCEL = 0.0196;
    public static final double GROUND_FRICTION = 0.546;
    public static final double WALK_ACCEL = 0.98;
    public static final double JUMP = 0.42;
    public static final double STEP_HEIGHT = 0.6;
    private static final double DIAGONAL_ACCEL = 0.1;
    private static final double WATER_DRAG = 0.9;
    private static final double LAVA_DRAG = 0.5;
    private static final double CLIMB_MAX_DOWN = 0.15;
    private static final double CLIMB_MAX_UP = 0.15;
    private static final double GLIDE_GRAVITY = 0.035;

    public StepResult step(Context context) {
        Objects.requireNonNull(context, "context");
        Player s = context.state();
        if (s.awaitingTeleport().isPresent()) return uncertain(context, "awaiting teleport confirmation; pre-correction motion is not integrated");
        if (context.environment() == Simulation.Environment.UNKNOWN) return uncertain(context, "movement environment is unknown");
        if (s.gamemode().equals("creative") || s.gamemode().equals("spectator")) {
            Player next = new Player(s.position(), Vec3.ZERO, s.yaw(), s.pitch(), false, s.gamemode(), s.effects(), s.awaitingTeleport(), false);
            return new StepResult(context.simulationTick(), next, false, false, false, false, false, "non-physical gamemode");
        }
        if (!s.gamemode().equals("survival") && !s.gamemode().equals("adventure")) return uncertain(context, "unsupported gamemode movement model");

        Phase5Mechanics.MovementEnvironment env = context.movementEnvironment();
        Phase5Mechanics.Pose pose = Phase5Mechanics.nextPose(context.pose(), env, context.sleeping());
        Aabb start = Aabb.playerAt(s.position(), pose);
        if (context.world().hasUnknownOrUnsupported(new dev.phantom.ac.geometry.BlockBox(start.minX(), start.minY(), start.minZ(), start.maxX(), start.maxY(), start.maxZ())))
            return uncertain(context, "start collision volume is not fully known");

        double radians = Math.toRadians(s.yaw());
        double speed = context.attributes().value() * context.effects().speedMultiplier()
                * (context.input().sprint() ? 1.3 : 1.0) * (context.input().sneak() ? 0.3 : 1.0);
        boolean fluid = env.fluid() != Phase5Mechanics.Fluid.NONE;
        boolean climbing = env.climbable();
        boolean gliding = env.gliding();
        double inputMagnitude = Math.hypot(context.input().forward(), context.input().strafe());
        double inputScale = inputMagnitude > 1.0 ? 1.0 / Math.sqrt(2.0) : 1.0;
        double inputAcceleration;
        if (fluid) inputAcceleration = AIR_ACCEL;
        else if (s.onGround() && inputMagnitude > 1.0) inputAcceleration = DIAGONAL_ACCEL * context.effects().speedMultiplier() * (context.input().sprint() ? 1.3 : 1.0) * (context.input().sneak() ? 0.3 : 1.0);
        else inputAcceleration = s.onGround() ? WALK_ACCEL * speed : AIR_ACCEL;
        if (gliding) inputAcceleration = AIR_ACCEL;

        Vec3 acceleration = new Vec3(
                inputScale * (context.input().strafe() * inputAcceleration * Math.cos(radians) - context.input().forward() * inputAcceleration * Math.sin(radians)),
                0,
                inputScale * (context.input().forward() * inputAcceleration * Math.cos(radians) + context.input().strafe() * inputAcceleration * Math.sin(radians)));
        Vec3 velocity = s.velocity().add(acceleration);
        if (climbing) {
            if (context.input().forward() > 0) velocity = new Vec3(velocity.x(), CLIMB_MAX_UP, velocity.z());
            else if (context.input().forward() < 0) velocity = new Vec3(velocity.x(), -CLIMB_MAX_DOWN, velocity.z());
            else velocity = new Vec3(velocity.x(), Math.max(-CLIMB_MAX_DOWN, velocity.y()), velocity.z());
        }
        boolean jumped = context.input().jump() && s.onGround() && !fluid && !climbing && !gliding && !context.sleeping();
        if (jumped) velocity = new Vec3(velocity.x(), JUMP + context.effects().jumpVelocityAdd(), velocity.z());
        if (context.effects().levitation()) velocity = new Vec3(velocity.x(), context.effects().levitationVelocity(), velocity.z());

        RichWorldCollision.Result collision = RichWorldCollision.resolve(context.world(), start, velocity, s.onGround() && !fluid && !climbing && !gliding ? STEP_HEIGHT : 0);
        if (collision.uncertain()) return new StepResult(context.simulationTick(), new Player(s.position(), s.velocity(), s.yaw(), s.pitch(), s.onGround(), s.gamemode(), s.effects(), s.awaitingTeleport(), true), false, collision.stepAttempted(), false, collision.collidedX(), collision.collidedY(), collision.collidedZ(), collision.diagnostic());

        Vec3 displacement = collision.displacement();
        boolean grounded = collision.collidedY() && velocity.y() <= 0;
        double horizontalFactor;
        if (env.fluid() == Phase5Mechanics.Fluid.WATER) horizontalFactor = env.fluidSpeedMultiplier() * WATER_DRAG;
        else if (env.fluid() == Phase5Mechanics.Fluid.LAVA) horizontalFactor = env.fluidSpeedMultiplier() * LAVA_DRAG;
        else if (climbing) horizontalFactor = s.onGround() ? GROUND_FRICTION : AIR_HORIZONTAL_FRICTION;
        else if (gliding) horizontalFactor = AIR_DRAG;
        else horizontalFactor = s.onGround() ? GROUND_FRICTION : AIR_HORIZONTAL_FRICTION;

        double gravity = GRAVITY * env.gravityMultiplier();
        double postTickVerticalVelocity;
        if (context.effects().levitation()) postTickVerticalVelocity = context.effects().levitationVelocity();
        else if (climbing) postTickVerticalVelocity = velocity.y();
        else if (env.fluid() == Phase5Mechanics.Fluid.WATER || env.fluid() == Phase5Mechanics.Fluid.LAVA) postTickVerticalVelocity = velocity.y() * env.fluidDrag() - gravity;
        else if (gliding) postTickVerticalVelocity = velocity.y() - GLIDE_GRAVITY;
        else postTickVerticalVelocity = (jumped ? velocity.y() - gravity * context.effects().fallGravityMultiplier() : velocity.y() * AIR_VERTICAL_DRAG - gravity * context.effects().fallGravityMultiplier() * AIR_VERTICAL_DRAG);

        double groundedVerticalVelocity = context.effects().levitation() ? context.effects().levitationVelocity()
                : climbing ? velocity.y()
                : (env.fluid() == Phase5Mechanics.Fluid.WATER || env.fluid() == Phase5Mechanics.Fluid.LAVA) ? velocity.y() * env.fluidDrag() - gravity
                : -gravity * AIR_VERTICAL_DRAG * context.effects().fallGravityMultiplier();
        double nextY = grounded && !context.effects().levitation() && !climbing ? groundedVerticalVelocity : postTickVerticalVelocity;
        Vec3 nextVelocity = new Vec3(collision.collidedX() ? 0 : velocity.x() * horizontalFactor, nextY, collision.collidedZ() ? 0 : velocity.z() * horizontalFactor);
        Player next = new Player(s.position().add(displacement), nextVelocity, s.yaw(), s.pitch(), grounded, s.gamemode(), s.effects(), s.awaitingTeleport(), false);
        return new StepResult(context.simulationTick(), next, collision.collidedX() || collision.collidedY() || collision.collidedZ(), collision.stepAttempted(), collision.stepSucceeded(), collision.collidedX(), collision.collidedY(), collision.collidedZ(), collision.diagnostic());
    }

    private StepResult uncertain(Context context, String message) {
        Player s = context.state();
        Player next = new Player(s.position(), s.velocity(), s.yaw(), s.pitch(), s.onGround(), s.gamemode(), s.effects(), s.awaitingTeleport(), true);
        return new StepResult(context.simulationTick(), next, false, false, false, false, false, message);
    }

    public record Context(long simulationTick, Player state, Simulation.AdvancedInput input, WorldSnapshot world,
                          Simulation.Environment environment, Simulation.Attributes attributes,
                          Phase5Mechanics.MovementEffects effects, Phase5Mechanics.Pose pose,
                          Phase5Mechanics.MovementEnvironment movementEnvironment, boolean sleeping) implements Serializable {
        public Context {
            Objects.requireNonNull(state); Objects.requireNonNull(input); Objects.requireNonNull(world); Objects.requireNonNull(environment);
            Objects.requireNonNull(attributes); Objects.requireNonNull(effects); Objects.requireNonNull(pose); Objects.requireNonNull(movementEnvironment);
            if (simulationTick < 0) throw new IllegalArgumentException("simulationTick must be non-negative");
        }
    }

    public record StepResult(long simulationTick, Player state, boolean collided, boolean stepAttempted, boolean stepSucceeded,
                             boolean collisionX, boolean collisionY, boolean collisionZ, String diagnostic) implements Serializable {
        public StepResult { Objects.requireNonNull(state); Objects.requireNonNull(diagnostic); }
    }
}
