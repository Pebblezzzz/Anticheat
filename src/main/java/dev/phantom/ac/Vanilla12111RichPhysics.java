package dev.phantom.ac;

import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldQueries;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.State.Player;

/** Sole canonical 1.21.11 movement implementation used by Phase 5 and Phase 6. */
public final class Vanilla12111RichPhysics {
    public static final String VERSION = "1.21.11";

    public static final double
            GRAVITY = 0.08,
            AIR_DRAG = 0.98f,
            AIR_HORIZONTAL_FRICTION = 0.91f,
            AIR_VERTICAL_DRAG = 0.98f,
            AIR_ACCEL = 0.02f,
            SPRINT_AIR_ACCEL = 0.025999999f,
            GROUND_FRICTION = 0.546f,
            WALK_ACCEL = 0.98f,
            JUMP = 0.42f,
            STEP_HEIGHT = 0.6,
            INPUT_FRICTION = 0.98f,
            FRICTION_SPEED_FACTOR = 0.21600002f,
            SPRINT_JUMP_HORIZONTAL_BOOST = 0.2,
            SPRINTING_SPEED_MULTIPLIER = 1.3,
            AIR_VERTICAL_FRICTION = 0.98f;

    private static final double
            CLIMB_MAX_DOWN = 0.15,
            CLIMB_MAX_UP = 0.15,
            GROUND_PROBE = 1.0e-4;

    static final double WATER_DRAG = 0.8f,
            WATER_SPRINT_DRAG = 0.9f,
            LAVA_DRAG = 0.5f;

    public StepResult step(Context context) {
        Objects.requireNonNull(context);
        Player s = context.state();

        if (s.awaitingTeleport().isPresent()) {
            return uncertain(context, "awaiting teleport confirmation; pre-correction motion is not integrated");
        }
        if (context.environment() == Simulation.Environment.UNKNOWN) {
            return uncertain(context, "movement environment is unknown");
        }

        boolean inVehicle = context.movementEnvironment().vehicle().active();
        PoseResolution poseResolution = resolvePose(context);
        if (poseResolution.uncertain()) {
            return uncertain(context, poseResolution.diagnostic());
        }
        Phase5Mechanics.Pose pose = poseResolution.pose();

        Aabb start = Aabb.playerAt(s.position(), pose);

        if ("spectator".equals(s.gamemode())) {
            return flightStep(context, start, true);
        }
        if (!s.gamemode().equals("survival")
                && !s.gamemode().equals("adventure")
                && !s.gamemode().equals("creative")) {
            return uncertain(context, "unsupported gamemode movement model");
        }

        EntityCollisions.EntityCollisionResult startEntities =
                context.entityCollisions().boxesIn(toBlockBox(start));
        if (!startEntities.isDefinite()) {
            return uncertain(context, "entity collision history is incomplete");
        }

        if (context.world().hasUnknownOrUnsupported(toBlockBox(start))) {
            return uncertain(
                    context,
                    "start collision volume is not fully known problems="
                            + coverageProblems(context.world(), toBlockBox(start)));
        }

        double eyeY = s.position().y() + eyeHeight(pose);
        GrimFluidPhysics.Sample fluidSample = GrimFluidPhysics.sample(
                context.world(),
                start,
                eyeY,
                pose == Phase5Mechanics.Pose.SWIMMING,
                inVehicle);

        Phase5Mechanics.Fluid fluid = context.movementEnvironment().fluid();
        if (fluidSample.hasFluid()) {
            fluid = fluidSample.type() == GrimFluidPhysics.Type.WATER
                    ? Phase5Mechanics.Fluid.WATER
                    : Phase5Mechanics.Fluid.LAVA;
        }

        /*
         * Fluid height/current are world facts. When a test/legacy caller supplies
         * an explicit fluid mode without a fluid block in the snapshot, preserve
         * that declared mode rather than manufacturing a world current.
         */
        if (fluid != Phase5Mechanics.Fluid.NONE
                && fluidSample.hasFluid()
                && !fluidSample.currentKnown()) {
            return uncertain(context,
                    "fluid interaction is incomplete: fluid current/height requires additional client-visible neighbour data");
        }

        if (inVehicle) {
            return vehicleStep(context, start, pose, fluid);
        }

        if (context.flying()) {
            return flightStep(context, start, false);
        }

        boolean climbing = context.movementEnvironment().climbable();
        boolean gliding = context.movementEnvironment().gliding() && !climbing;
        double gravity = GRAVITY * context.movementEnvironment().gravityMultiplier();

        if (gliding) {
            Vec3 glideVelocity = GrimElytraPhysics.tick(
                    s.velocity(),
                    s.yaw(),
                    s.pitch(),
                    gravity,
                    context.effects().slowFalling());

            /*
             * Mojang still permits an ordinary ground jump while wearing an Elytra.
             * Keep that discrete possibility instead of suppressing the jump branch.
             */
            boolean jumpedFromGround = context.input().jump() && s.onGround();
            if (jumpedFromGround) {
                glideVelocity = new Vec3(glideVelocity.x(), JUMP + context.effects().jumpVelocityAdd(), glideVelocity.z());
            }

            RichWorldCollision.Result collision =
                    RichWorldCollision.resolve(context.world(), start, glideVelocity, 0.0, context.entityCollisions());
            if (collision.uncertain()) return uncertain(context, collision.diagnostic());

            Vec3 displacement = collision.displacement();
            Vec3 postCollision = zeroCollidedAxes(glideVelocity, collision);
            Vec3 nextVelocity = GrimElytraPhysics.endOfTickDrag(postCollision);

            Player next = richPlayer(
                    s,
                    s.position().add(displacement),
                    nextVelocity,
                    false,
                    Phase5Mechanics.Pose.FALL_FLYING,
                    context,
                    false);

            return new StepResult(
                    context.simulationTick(),
                    next,
                    collision.collidedX() || collision.collidedY() || collision.collidedZ(),
                    collision.stepAttempted(),
                    collision.stepSucceeded(),
                    collision.collidedX(),
                    collision.collidedY(),
                    collision.collidedZ(),
                    (collision.collidedX() || collision.collidedY() || collision.collidedZ()),
                    "Grim-style 1.21.11 Elytra movement");
        }

        Vec3 velocity = s.velocity();

        if (fluid == Phase5Mechanics.Fluid.WATER) {
            velocity = GrimFluidPhysics.applyCurrent(velocity, fluidSample);
        }

        double radians = Math.toRadians(s.yaw());
        double inputMagnitude = Math.hypot(context.input().forward(), context.input().strafe());
        double inputScale = inputMagnitude > 1.0 ? 1.0 / Math.sqrt(2.0) : 1.0;

        double inputAcceleration;
        if (fluid != Phase5Mechanics.Fluid.NONE) {
            inputAcceleration = inputMagnitude > 1.0
                    ? AIR_ACCEL
                    : AIR_ACCEL * INPUT_FRICTION;
        } else if (climbing) {
            inputAcceleration = AIR_ACCEL;
        } else if (s.onGround()) {
            if (inputMagnitude == 0.0) {
                inputAcceleration = 0.0;
            } else {
                BlockState support = supportBlock(
                        context.world(),
                        (int) Math.floor(s.position().x()),
                        (int) Math.floor(s.position().y() - GROUND_PROBE),
                        (int) Math.floor(s.position().z()));
                if (support == null || support.isUnsupported()) {
                    return uncertain(
                            context,
                            "support block is unavailable for friction calculation at "
                                    + supportDiagnostic(
                                    context.world(),
                                    (int) Math.floor(s.position().x()),
                                    (int) Math.floor(s.position().y() - GROUND_PROBE),
                                    (int) Math.floor(s.position().z())));
                }
                double slipperiness = BlockCatalogue12111.slipperiness(support);
                double movementSpeed = context.attributes().value() * context.effects().speedMultiplier();
                if (context.input().sprint()) movementSpeed *= SPRINTING_SPEED_MULTIPLIER;
                if (context.input().sneak()) movementSpeed *= 0.3;
                double frictionInfluencedSpeed = movementSpeed * FRICTION_SPEED_FACTOR
                        / (slipperiness * slipperiness * slipperiness);
                inputAcceleration = inputMagnitude > 1.0
                        ? frictionInfluencedSpeed
                        : frictionInfluencedSpeed * INPUT_FRICTION;
            }
        } else {
            double offGroundSpeed = context.input().sprint()
                    ? SPRINT_AIR_ACCEL
                    : AIR_ACCEL;
            inputAcceleration = inputMagnitude > 1.0
                    ? offGroundSpeed
                    : offGroundSpeed * INPUT_FRICTION;
        }

        /*
         * Inputs are applied after the starting-vector adjustments. This mirrors
         * Grim's prediction ordering: current/knockback-like state survives until
         * the input transform rather than being replaced by packet arrival order.
         */
        double vanillaStrafe = -context.input().strafe();
        Vec3 acceleration = new Vec3(
                inputScale * (vanillaStrafe * inputAcceleration * Math.cos(radians)
                        - context.input().forward() * inputAcceleration * Math.sin(radians)),
                0.0,
                inputScale * (context.input().forward() * inputAcceleration * Math.cos(radians)
                        + vanillaStrafe * inputAcceleration * Math.sin(radians)));

        velocity = applyMovementThreshold(velocity.add(acceleration));

        if (fluid == Phase5Mechanics.Fluid.WATER
                && (fluidSample.surfaceSwimming()
                || context.movementEnvironment().swimmingInput()
                || pose == Phase5Mechanics.Pose.SWIMMING)) {
            double lookY = -Math.sin(Math.toRadians(s.pitch()));
            velocity = GrimFluidPhysics.applySwimmingSteering(velocity, lookY, true);
        }

        if (climbing) {
            double climbX = Math.max(-CLIMB_MAX_UP, Math.min(CLIMB_MAX_UP, velocity.x()));
            double climbZ = Math.max(-CLIMB_MAX_UP, Math.min(CLIMB_MAX_UP, velocity.z()));
            double climbY;
            if (context.input().forward() > 0) {
                climbY = CLIMB_MAX_UP;
            } else if (context.input().forward() < 0) {
                climbY = Math.max(-CLIMB_MAX_DOWN, velocity.y());
            } else {
                climbY = Math.max(-CLIMB_MAX_DOWN, velocity.y());
            }

            if (context.input().sneak() && climbY < 0.0
                    && !isScaffolding(context.world(), s.position())) {
                climbY = 0.0;
            }
            velocity = new Vec3(climbX, climbY, climbZ);
        }

        boolean jumped = context.input().jump() && !context.sleeping() && !climbing;
        if (jumped && s.onGround() && fluid == Phase5Mechanics.Fluid.NONE) {
            velocity = new Vec3(
                    velocity.x(),
                    JUMP + context.effects().jumpVelocityAdd(),
                    velocity.z());
            if (context.input().sprint()) {
                velocity = velocity.add(new Vec3(
                        -Math.sin(radians) * SPRINT_JUMP_HORIZONTAL_BOOST,
                        0.0,
                        Math.cos(radians) * SPRINT_JUMP_HORIZONTAL_BOOST));
            }
        } else if (jumped && fluid != Phase5Mechanics.Fluid.NONE) {
            /*
             * Grim keeps the small fluid jump branch as an alternative start
             * velocity rather than requiring onGround. It represents the client
             * swim/lava hop that happens after the movement threshold.
             */
            velocity = new Vec3(velocity.x(), velocity.y() + 0.04, velocity.z());
        }

        if (context.effects().levitation()) {
            double target = context.effects().levitationVelocity();
            velocity = new Vec3(
                    velocity.x(),
                    velocity.y() + (target - velocity.y()) * 0.2,
                    velocity.z());
        }

        SneakEdgeAdjustment sneakEdgeAdjustment =
                maybeBackOffFromEdge(context, start, velocity);
        if (sneakEdgeAdjustment.uncertain()) {
            return uncertain(context, sneakEdgeAdjustment.diagnostic());
        }
        boolean sneakEdgeConstrained = sneakEdgeAdjustment.constrained();
        velocity = sneakEdgeAdjustment.movement();

        RichWorldCollision.Result collision =
                RichWorldCollision.resolve(
                        context.world(),
                        start,
                        velocity,
                        s.onGround() && fluid == Phase5Mechanics.Fluid.NONE && !climbing
                                ? STEP_HEIGHT : 0.0,
                        context.entityCollisions(),
                        context.actualMovementReference());
        if (collision.uncertain()) return uncertain(context, collision.diagnostic());

        Vec3 displacement = collision.displacement();

        /*
         * Grim determines onGround from the post-movement collision state. A
         * support block under the start position is not sufficient: moving
         * horizontally can leave an edge during this tick. Probe beneath the
         * final bounding box so the next tick correctly becomes airborne when
         * the player has no support left.
         *
         * Keep the probe restricted to states that were already grounded so an
         * airborne player does not gain a new source of world-coverage uncertainty.
         */
        boolean supported = false;
        if (s.onGround()
                && velocity.y() <= 0
                && fluid == Phase5Mechanics.Fluid.NONE
                && !climbing) {
            Aabb end = Aabb.playerAt(s.position().add(displacement), pose);
            RichWorldCollision.Result supportProbe = RichWorldCollision.resolve(
                    context.world(),
                    end,
                    new Vec3(0, -GROUND_PROBE, 0),
                    0.0,
                    context.entityCollisions());
            if (supportProbe.uncertain()) return uncertain(context, supportProbe.diagnostic());
            supported = supportProbe.collidedY();
        }

        boolean grounded = velocity.y() <= 0 && (collision.collidedY() || supported);

        double horizontalFactor;
        if (fluid == Phase5Mechanics.Fluid.WATER) {
            horizontalFactor = fluidSample.surfaceSwimming() && context.input().sprint()
                    ? WATER_SPRINT_DRAG
                    : (context.input().sprint() ? WATER_SPRINT_DRAG : WATER_DRAG);
        } else if (fluid == Phase5Mechanics.Fluid.LAVA) {
            horizontalFactor = LAVA_DRAG;
        } else if (climbing) {
            horizontalFactor = s.onGround() ? GROUND_FRICTION : AIR_HORIZONTAL_FRICTION;
        } else if (s.onGround()) {
            if (Math.hypot(velocity.x(), velocity.z()) <= 1.0e-12) {
                horizontalFactor = 1.0;
            } else {
                BlockState support = supportBlock(
                        context.world(),
                        (int) Math.floor(s.position().x()),
                        (int) Math.floor(s.position().y() - GROUND_PROBE),
                        (int) Math.floor(s.position().z()));
                if (support == null || support.isUnsupported()) {
                    return uncertain(
                            context,
                            "support block is unavailable for friction calculation at "
                                    + supportDiagnostic(
                                    context.world(),
                                    (int) Math.floor(s.position().x()),
                                    (int) Math.floor(s.position().y() - GROUND_PROBE),
                                    (int) Math.floor(s.position().z())));
                }
                horizontalFactor = BlockCatalogue12111.slipperiness(support) * AIR_HORIZONTAL_FRICTION;
            }
        } else {
            horizontalFactor = AIR_HORIZONTAL_FRICTION;
        }

        double nextY;
        if (context.effects().levitation()) {
            nextY = context.effects().levitationVelocity();
        } else if (climbing) {
            nextY = velocity.y();
        } else if (fluid == Phase5Mechanics.Fluid.WATER) {
            nextY = GrimFluidPhysics.waterDrag(false) * velocity.y()
                    - gravity / 16.0;
        } else if (fluid == Phase5Mechanics.Fluid.LAVA) {
            if (fluidSample.hasFluid() && fluidSample.height() <= 0.4) {
                nextY = velocity.y() * 0.8 - gravity;
            } else {
                nextY = velocity.y() * 0.5 - gravity / 4.0;
            }
        } else if (grounded) {
            // A player already known to be grounded and stationary keeps zero
            // vertical velocity; a fresh landing retains the small gravity value.
            if (s.onGround() && supported && Math.abs(velocity.y()) <= 1.0e-12) {
                nextY = 0.0;
            } else {
                nextY = -gravity * AIR_VERTICAL_DRAG
                        * context.effects().fallGravityMultiplier();
            }
        } else {
            nextY = (velocity.y() - gravity * context.effects().fallGravityMultiplier())
                    * AIR_VERTICAL_DRAG;
        }

        Vec3 nextVelocity = new Vec3(
                collision.collidedX() ? 0.0 : velocity.x() * horizontalFactor,
                nextY,
                collision.collidedZ() ? 0.0 : velocity.z() * horizontalFactor);

        Phase5Mechanics.MovementEnvironment nextEnvironment = new Phase5Mechanics.MovementEnvironment(
                fluid,
                fluid != Phase5Mechanics.Fluid.NONE,
                climbing,
                grounded,
                context.movementEnvironment().sprinting(),
                context.movementEnvironment().sneaking(),
                context.movementEnvironment().swimmingInput(),
                gliding,
                context.movementEnvironment().fluidSpeedMultiplier(),
                context.movementEnvironment().fluidDrag(),
                context.movementEnvironment().gravityMultiplier(),
                context.movementEnvironment().vehicle());

        Phase5Mechanics.Pose requestedNext =
                GrimPoseResolver.requested(nextEnvironment, context.sleeping(), pose);
        Phase5Mechanics.Pose nextPose =
                GrimPoseResolver.resolve(
                        pose,
                        requestedNext,
                        s.position().add(displacement),
                        context.world(),
                        context.entityCollisions(),
                        inVehicle);

        Player next = richPlayer(
                s,
                s.position().add(displacement),
                nextVelocity,
                grounded,
                nextPose,
                context,
                false);

        String diagnostic = switch (fluid) {
            case WATER -> "Grim-style water prediction: fluid height/current + swimming steering";
            case LAVA -> "Grim-style lava prediction: fluid drag + gravity adjustment";
            case NONE -> climbing
                    ? "1.21.11 climbable movement with Grim pose/collision resolution"
                    : "1.21.11 normal movement with Grim-style pose/collision resolution";
        };

        return new StepResult(
                context.simulationTick(),
                next,
                collision.collidedX() || collision.collidedY() || collision.collidedZ(),
                collision.stepAttempted(),
                collision.stepSucceeded(),
                collision.collidedX(),
                collision.collidedY(),
                collision.collidedZ(),
                (collision.collidedX() || collision.collidedY() || collision.collidedZ()),
                diagnostic,
                sneakEdgeConstrained);
    }

    private StepResult vehicleStep(
            Context context,
            Aabb start,
            Phase5Mechanics.Pose pose,
            Phase5Mechanics.Fluid fluid) {
        Phase5Mechanics.VehicleState vehicle = context.movementEnvironment().vehicle();
        GrimVehiclePhysics.Result vehicleResult =
                GrimVehiclePhysics.tick(
                        vehicle,
                        context.input(),
                        context.state().velocity(),
                        context.movementEnvironment());

        double stepHeight = switch (vehicle.type()) {
            case PIG, STRIDER, HORSE, CAMEL, NAUTILUS -> STEP_HEIGHT;
            default -> 0.0;
        };

        RichWorldCollision.Result collision =
                RichWorldCollision.resolve(
                        context.world(),
                        start,
                        vehicleResult.velocity(),
                        stepHeight,
                        context.entityCollisions());
        if (collision.uncertain()) return uncertain(context, collision.diagnostic());

        Vec3 displacement = collision.displacement();
        Vec3 nextVelocity = zeroCollidedAxes(vehicleResult.velocity(), collision);

        Phase5Mechanics.MovementEnvironment nextEnvironment = new Phase5Mechanics.MovementEnvironment(
                fluid,
                fluid != Phase5Mechanics.Fluid.NONE,
                false,
                false,
                context.movementEnvironment().sprinting(),
                context.movementEnvironment().sneaking(),
                context.movementEnvironment().swimmingInput(),
                false,
                context.movementEnvironment().fluidSpeedMultiplier(),
                context.movementEnvironment().fluidDrag(),
                context.movementEnvironment().gravityMultiplier(),
                vehicle);

        Phase5Mechanics.Pose requestedNext =
                GrimPoseResolver.requested(nextEnvironment, context.sleeping(), pose);
        Phase5Mechanics.Pose nextPose =
                GrimPoseResolver.resolve(
                        pose,
                        requestedNext,
                        context.state().position().add(displacement),
                        context.world(),
                        context.entityCollisions(),
                        true);

        Player next = richPlayer(
                context.state(),
                context.state().position().add(displacement),
                nextVelocity,
                false,
                nextPose,
                context,
                false);

        return new StepResult(
                context.simulationTick(),
                next,
                collision.collidedX() || collision.collidedY() || collision.collidedZ(),
                collision.stepAttempted(),
                collision.stepSucceeded(),
                collision.collidedX(),
                collision.collidedY(),
                collision.collidedZ(),
                (collision.collidedX() || collision.collidedY() || collision.collidedZ()),
                vehicleResult.diagnostic());
    }

    /**
     * Client-side flight is modeled as a first-class movement mode rather than
     * downgraded to empirical-only uncertainty.
     */
    private StepResult flightStep(Context context, Aabb start, boolean spectator) {
        Player s = context.state();
        double yaw = Math.toRadians(s.yaw());
        double pitch = Math.toRadians(s.pitch());
        double speed = 0.05 * (context.input().sprint() ? 2.0 : 1.0);
        double forward = context.input().forward();
        double strafe = context.input().strafe();
        double magnitude = Math.hypot(forward, strafe);
        double scale = magnitude > 1.0 ? 1.0 / Math.sqrt(2.0) : 1.0;

        Vec3 velocity;
        if (spectator) {
            double horizontalForward = forward * Math.cos(pitch);
            double verticalLook = -forward * Math.sin(pitch);
            double vanillaStrafe = -strafe;
            double x = scale * speed * (
                    vanillaStrafe * Math.cos(yaw) - horizontalForward * Math.sin(yaw));
            double z = scale * speed * (
                    horizontalForward * Math.cos(yaw) + vanillaStrafe * Math.sin(yaw));
            double y = scale * speed * verticalLook;
            if (context.input().jump()) y += speed;
            if (context.input().sneak()) y -= speed;
            velocity = new Vec3(x, y, z);
        } else {
            double vanillaStrafe = -strafe;
            double x = scale * speed * (
                    vanillaStrafe * Math.cos(yaw) - forward * Math.sin(yaw));
            double z = scale * speed * (
                    forward * Math.cos(yaw) + vanillaStrafe * Math.sin(yaw));
            double y = 0.0;
            if (context.input().jump()) y += speed;
            if (context.input().sneak()) y -= speed;
            velocity = new Vec3(x, y, z);
        }

        Vec3 displacement;
        boolean collidedX = false;
        boolean collidedY = false;
        boolean collidedZ = false;
        boolean entityCollision = false;
        boolean stepAttempted = false;
        boolean stepSucceeded = false;
        String diagnostic = spectator
                ? "1.21.11 spectator noclip flight"
                : "1.21.11 creative flight";

        if (spectator) {
            displacement = velocity;
        } else {
            RichWorldCollision.Result collision =
                    RichWorldCollision.resolve(
                            context.world(),
                            start,
                            velocity,
                            0.0,
                            context.entityCollisions());
            if (collision.uncertain()) return uncertain(context, collision.diagnostic());
            displacement = collision.displacement();
            collidedX = collision.collidedX();
            collidedY = collision.collidedY();
            collidedZ = collision.collidedZ();
            entityCollision = (collision.collidedX() || collision.collidedY() || collision.collidedZ());
            stepAttempted = collision.stepAttempted();
            stepSucceeded = collision.stepSucceeded();
        }

        Player next = richPlayer(
                s,
                s.position().add(displacement),
                spectator ? velocity : zeroCollidedAxes(velocity,
                        new RichWorldCollision.Result(
                                displacement,
                                collidedX,
                                collidedY,
                                collidedZ,
                                stepAttempted,
                                stepSucceeded,
                                false,
                                diagnostic)),
                false,
                poseForFlight(context, spectator),
                context,
                false);

        return new StepResult(
                context.simulationTick(),
                next,
                collidedX || collidedY || collidedZ,
                stepAttempted,
                stepSucceeded,
                collidedX,
                collidedY,
                collidedZ,
                entityCollision,
                diagnostic);
    }

    private record PoseResolution(Phase5Mechanics.Pose pose, boolean uncertain, String diagnostic) {}

    private PoseResolution resolvePose(Context context) {
        Phase5Mechanics.Pose wanted =
                GrimPoseResolver.requested(
                        context.movementEnvironment(),
                        context.sleeping(),
                        context.pose());
        Aabb box = Aabb.playerAt(context.state().position(), wanted);
        var worldCollision = WorldQueries.collisions(context.world(), toBlockBox(box));
        var entityCollision = context.entityCollisions().boxesIn(toBlockBox(box));
        if (!worldCollision.isDefinite() || !entityCollision.isDefinite()) {
            /*
             * A blocked transition and an unknown transition are intentionally
             * different. Only uncertainty in the collision facts becomes
             * uncertain; a known obstruction triggers Grim's pose fallback.
             */
            if (worldCollision.isUncertain() || !entityCollision.isDefinite()) {
                return new PoseResolution(
                        context.pose(),
                        true,
                        "pose transition crosses incomplete collision coverage");
            }
        }
        Phase5Mechanics.Pose resolved =
                GrimPoseResolver.resolve(
                        context.pose(),
                        wanted,
                        context.state().position(),
                        context.world(),
                        context.entityCollisions(),
                        context.movementEnvironment().vehicle().active());
        return new PoseResolution(resolved, false, "pose resolution");
    }

    private static Phase5Mechanics.Pose poseForFlight(Context context, boolean spectator) {
        if (context.sleeping()) return Phase5Mechanics.Pose.SLEEPING;
        return Phase5Mechanics.Pose.STANDING;
    }

    private static Player richPlayer(
            Player source,
            Vec3 position,
            Vec3 velocity,
            boolean onGround,
            Phase5Mechanics.Pose pose,
            Context context,
            boolean uncertain) {
        State.Environment environment;
        if (context.environment() == Simulation.Environment.UNKNOWN) {
            environment = State.Environment.UNKNOWN;
        } else if (context.movementEnvironment().fluid() == Phase5Mechanics.Fluid.WATER) {
            environment = State.Environment.WATER;
        } else if (context.movementEnvironment().fluid() == Phase5Mechanics.Fluid.LAVA) {
            environment = State.Environment.LAVA;
        } else if (context.movementEnvironment().climbable()) {
            environment = State.Environment.CLIMBABLE;
        } else {
            environment = State.Environment.DRY;
        }

        return new Player(
                position,
                velocity,
                source.yaw(),
                source.pitch(),
                onGround,
                source.gamemode(),
                source.effects(),
                source.awaitingTeleport(),
                uncertain,
                Optional.of(context.input()),
                context.attributes(),
                pose,
                environment,
                source.clientTickRange(),
                source.provenance(),
                source.uncertaintyReasons());
    }

    private static Vec3 zeroCollidedAxes(Vec3 velocity, RichWorldCollision.Result collision) {
        return new Vec3(
                collision.collidedX() ? 0.0 : velocity.x(),
                collision.collidedY() ? 0.0 : velocity.y(),
                collision.collidedZ() ? 0.0 : velocity.z());
    }

    /** Grim 1.21.5+ zeros tiny movement before collision prediction. */
    private static Vec3 applyMovementThreshold(Vec3 velocity) {
        double horizontalSquared = velocity.x() * velocity.x() + velocity.z() * velocity.z();
        double x = horizontalSquared < 9.0E-6 ? 0.0 : velocity.x();
        double z = horizontalSquared < 9.0E-6 ? 0.0 : velocity.z();
        double y = Math.abs(velocity.y()) < 0.003 ? 0.0 : velocity.y();
        return new Vec3(x, y, z);
    }

    private SneakEdgeAdjustment maybeBackOffFromEdge(
            Context context,
            Aabb boundingBox,
            Vec3 requested) {
        Player s = context.state();
        if (!context.input().sneak()
                || context.flying()
                || !context.movementEnvironment().sneaking()
                || !s.onGround()
                || context.movementEnvironment().fluid() != Phase5Mechanics.Fluid.NONE
                || context.movementEnvironment().climbable()
                || context.movementEnvironment().gliding()
                || (requested.x() == 0.0 && requested.z() == 0.0)) {
            return new SneakEdgeAdjustment(requested, false, false, "");
        }

        double x = requested.x();
        double z = requested.z();

        SneakEdgeProbe probe = sneakEdgeProbe(context, boundingBox, x, 0.0);
        while (!probe.uncertain() && probe.empty() && x != 0.0) {
            double next = reduceSneakEdgeComponent(x);
            if (next == x) break;
            x = next;
            probe = sneakEdgeProbe(context, boundingBox, x, 0.0);
        }
        if (probe.uncertain()) {
            return new SneakEdgeAdjustment(requested, false, true, probe.diagnostic());
        }
        if (probe.empty() && x == 0.0) {
            // zero is already the safest X component
            x = 0.0;
        }

        probe = sneakEdgeProbe(context, boundingBox, 0.0, z);
        while (!probe.uncertain() && probe.empty() && z != 0.0) {
            double next = reduceSneakEdgeComponent(z);
            if (next == z) break;
            z = next;
            probe = sneakEdgeProbe(context, boundingBox, 0.0, z);
        }
        if (probe.uncertain()) {
            return new SneakEdgeAdjustment(requested, false, true, probe.diagnostic());
        }
        if (probe.empty() && z == 0.0) {
            z = 0.0;
        }

        while (x != 0.0 && z != 0.0) {
            probe = sneakEdgeProbe(context, boundingBox, x, z);
            if (probe.uncertain()) {
                return new SneakEdgeAdjustment(requested, false, true, probe.diagnostic());
            }
            if (!probe.empty()) break;

            double nextX = reduceSneakEdgeComponent(x);
            double nextZ = reduceSneakEdgeComponent(z);
            if (nextX == x && nextZ == z) break;
            x = nextX;
            z = nextZ;
        }

        Vec3 adjusted = new Vec3(x, requested.y(), z);
        boolean constrained = !adjusted.equals(requested);
        return new SneakEdgeAdjustment(
                requested,
                constrained,
                false,
                constrained ? "Grim-style sneak edge constraint detected; preserving full physics vector and exposing bounded position uncertainty" : "");
    }

    private double reduceSneakEdgeComponent(double value) {
        if (value < 0.05 && value >= -0.05) {
            return 0.0;
        }
        return value > 0.0 ? value - 0.05 : value + 0.05;
    }

    private SneakEdgeProbe sneakEdgeProbe(
            Context context,
            Aabb boundingBox,
            double x,
            double z) {
        double maxStepDown = STEP_HEIGHT;
        Aabb probeBox = new Aabb(
                boundingBox.minX() + x,
                boundingBox.minY() - maxStepDown,
                boundingBox.minZ() + z,
                boundingBox.maxX() + x,
                boundingBox.maxY() - maxStepDown,
                boundingBox.maxZ() + z);
        dev.phantom.ac.geometry.BlockBox blockBox =
                new dev.phantom.ac.geometry.BlockBox(
                        probeBox.minX(), probeBox.minY(), probeBox.minZ(),
                        probeBox.maxX(), probeBox.maxY(), probeBox.maxZ());

        var blockCollision = WorldQueries.collisions(context.world(), blockBox);
        if (!blockCollision.isDefinite()) {
            return new SneakEdgeProbe(false, true,
                    "sneak edge backoff world collision probe is incomplete coverage="
                            + blockCollision.coverage());
        }
        if (!blockCollision.isEmpty()) {
            return new SneakEdgeProbe(false, false, "");
        }

        var entityCollision = context.entityCollisions().boxesIn(blockBox);
        if (!entityCollision.isDefinite()) {
            return new SneakEdgeProbe(false, true,
                    "sneak edge backoff entity collision probe is incomplete");
        }
        return new SneakEdgeProbe(entityCollision.isEmpty(), false, "");
    }

    private record SneakEdgeAdjustment(
            Vec3 movement,
            boolean constrained,
            boolean uncertain,
            String diagnostic) {}

    private record SneakEdgeProbe(
            boolean empty,
            boolean uncertain,
            String diagnostic) {}

    private static BlockState supportBlock(WorldSnapshot world, int x, int y, int z) {
        if (world.coverageAt(x, y, z) != Coverage.KNOWN) return null;
        return world.requireBlockAt(x, y, z);
    }

    private static boolean isScaffolding(WorldSnapshot world, Vec3 position) {
        int x = (int) Math.floor(position.x());
        int y = (int) Math.floor(position.y());
        int z = (int) Math.floor(position.z());
        if (world.coverageAt(x, y, z) != Coverage.KNOWN) return false;
        BlockState state = world.blockAtOrNull(x, y, z);
        return state != null && "minecraft:scaffolding".equals(state.blockId());
    }

    private static double eyeHeight(Phase5Mechanics.Pose pose) {
        return switch (pose) {
            case STANDING -> 1.62;
            case CROUCHING -> 1.27;
            case SWIMMING, FALL_FLYING -> 0.4;
            case SLEEPING -> 0.2;
        };
    }

    private static dev.phantom.ac.geometry.BlockBox toBlockBox(Aabb box) {
        return new dev.phantom.ac.geometry.BlockBox(
                box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ());
    }

    private static String supportDiagnostic(WorldSnapshot world, int x, int y, int z) {
        return "(" + x + "," + y + "," + z + ") coverage=" + world.coverageAt(x, y, z)
                + " detail=" + world.coverageDetailAt(x, y, z);
    }

    private static String coverageProblems(WorldSnapshot world, dev.phantom.ac.geometry.BlockBox query) {
        List<WorldSnapshot.CoverageProblem> problems = world.coverageProblemsIn(query);
        if (problems.isEmpty()) return "[]";
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) result.append(", ");
            WorldSnapshot.CoverageProblem problem = problems.get(i);
            result.append(problem.coverage()).append('@').append(problem.position())
                    .append(' ').append(problem.detail());
        }
        return result.append(']').toString();
    }

    private StepResult uncertain(Context context, String message) {
        return new StepResult(
                context.simulationTick(),
                richPlayer(
                        context.state(),
                        context.state().position(),
                        context.state().velocity(),
                        context.state().onGround(),
                        context.pose(),
                        context,
                        true),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                message);
    }

    public record Context(
            long simulationTick,
            Player state,
            Simulation.AdvancedInput input,
            WorldSnapshot world,
            Simulation.Environment environment,
            Simulation.Attributes attributes,
            Phase5Mechanics.MovementEffects effects,
            Phase5Mechanics.Pose pose,
            Phase5Mechanics.MovementEnvironment movementEnvironment,
            boolean sleeping,
            boolean flying,
            EntityCollisions entityCollisions,
            Vec3 actualMovementReference) implements Serializable {

        public Context(
                long tick,
                Player state,
                Simulation.AdvancedInput input,
                WorldSnapshot world,
                Simulation.Environment environment,
                Simulation.Attributes attributes,
                Phase5Mechanics.MovementEffects effects,
                Phase5Mechanics.Pose pose,
                Phase5Mechanics.MovementEnvironment movementEnvironment,
                boolean sleeping,
                EntityCollisions entityCollisions) {
            this(
                    tick,
                    state,
                    input,
                    world,
                    environment,
                    attributes,
                    effects,
                    pose,
                    movementEnvironment,
                    sleeping,
                    false,
                    entityCollisions,
                    null);
        }

        public Context(
                long tick,
                Player state,
                Simulation.AdvancedInput input,
                WorldSnapshot world,
                Simulation.Environment environment,
                Simulation.Attributes attributes,
                Phase5Mechanics.MovementEffects effects,
                Phase5Mechanics.Pose pose,
                Phase5Mechanics.MovementEnvironment movementEnvironment,
                boolean sleeping) {
            this(
                    tick,
                    state,
                    input,
                    world,
                    environment,
                    attributes,
                    effects,
                    pose,
                    movementEnvironment,
                    sleeping,
                    false,
                    EntityCollisions.NONE_TRACKED,
                    null);
        }

        public Context {
            Objects.requireNonNull(state);
            Objects.requireNonNull(input);
            Objects.requireNonNull(world);
            Objects.requireNonNull(environment);
            Objects.requireNonNull(attributes);
            Objects.requireNonNull(effects);
            Objects.requireNonNull(pose);
            Objects.requireNonNull(movementEnvironment);
            Objects.requireNonNull(entityCollisions);
            if (actualMovementReference != null
                    && (!Double.isFinite(actualMovementReference.x())
                    || !Double.isFinite(actualMovementReference.y())
                    || !Double.isFinite(actualMovementReference.z()))) {
                throw new IllegalArgumentException("actualMovementReference must be finite");
            }
            if (simulationTick < 0) {
                throw new IllegalArgumentException("simulationTick must be non-negative");
            }
        }
    }

    public record StepResult(
            long simulationTick,
            Player state,
            boolean collided,
            boolean stepAttempted,
            boolean stepSucceeded,
            boolean collisionX,
            boolean collisionY,
            boolean collisionZ,
            boolean entityCollision,
            String diagnostic,
            boolean sneakEdgeConstrained) implements Serializable {
        public StepResult {
            Objects.requireNonNull(state);
            Objects.requireNonNull(diagnostic);
        }

        public StepResult(
                long simulationTick,
                Player state,
                boolean collided,
                boolean stepAttempted,
                boolean stepSucceeded,
                boolean collisionX,
                boolean collisionY,
                boolean collisionZ,
                boolean entityCollision,
                String diagnostic) {
            this(simulationTick, state, collided, stepAttempted, stepSucceeded,
                    collisionX, collisionY, collisionZ, entityCollision, diagnostic, false);
        }
    }
}
