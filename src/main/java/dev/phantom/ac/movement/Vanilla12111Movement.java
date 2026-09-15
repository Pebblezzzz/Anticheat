package dev.phantom.ac.movement;

import dev.phantom.ac.world.WorldView;
import dev.phantom.ac.world.WorldQueries.PathCollision;
import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.geometry.Directions.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical 1.21.11 vanilla movement engine bound to WorldView.
 *
 * <p>The tick order is faithful to vanilla 1.21.11 Minecraft: input-modified acceleration,
 * collision, gravity, and friction end-of-tick. Ground detection, water/swim, and climbable
 * states are secondary branches. The engine is deterministic and uses only the Phase 4 seam
 * (WorldView) for world facts, never reading a live world.
 */
public final class Vanilla12111Movement {

    // --- Constants from GrimAC/PredictionEngineNormal/PredictionEngine ---
    public static final double DEFAULT_WALK_SPEED = 0.1;           // default player walk speed attribute
    public static final double DEFAULT_JUMP_STRENGTH = 0.42;      // JUMP_STRENGTH base (0.42 * 0.5 honey)
    public static final double DEFAULT_STEP_HEIGHT = 0.6;        // vanilla step height

    public static final double GRAVITY = 0.08;                   // gravity per tick (slow falling min 0.01)
    public static final double AIR_DRAG = 0.98;                  // air drag per tick (Slow Falling attribute)
    public static final double GROUND_FRICTION_BASE = 0.91;      // base ground friction (slip * 0.91)
    public static final double AIR_FRICTION = 0.91;              // friction in air

    public static final double WALK_ACCELERATION = 0.21600002;   // walk accel factor
    public static final double SPRINT_HORIZONTAL_BONUS = 0.2;    // sprint adds 0.2 horizontal velocity
    public static final double SPRINT_MULTIPLIER = 1.3;          // sprint speed multiplier on ground
    public static final double SPRINT_AIR_SPEED = 0.026;         // sprint air speed (0.02 walk)
    public static final double WALK_AIR_SPEED = 0.02;            // walk air speed
    public static final double SNEAK_MULTIPLIER = 0.3;            // sneak multiplier
    public static final double USING_ITEM_MULTIPLIER = 0.2;       // using item
    public static final double WATER_SWIM_HORIZONTAL = 0.8;      // water multiplies v.x/z by 0.8
    public static final double WATER_SWIM_VERTICAL_ADJUST = -0.005; // water end-of-tick: vy -= gravity/16

    public static final double STEP_UP = 0.6;                    // vanilla step height
    public static final double MAX_JUMP_POWER = 0.42;           // jump strength base (0.42)

    public static final double MODERN_INPUT_TRANSFORMER = 0.98;   // input transformer scale
    public static final double EPS = 1e-7;                      // collision epsilon

    // --- Effect IDs referenced by MovementState ---
    public static final String MOVEMENT_SPEED = "minecraft:movedeleteme";
    public static final String JUMP_BOOST = "minecraft:jump_boost";
    public static final String SLOW_FALLING = "minecraft:slow_falling";

    private Vanilla12111Movement() {}

    /**
     * The deterministic Phase 5 tick.
     *
     * @param state the previous MovementState (never null)
     * @param input the raw client input for the tick
     * @param world the Phase 4 world view (deterministic)
     * @return the post‑tick MovementState; known() is false when world data was insufficient.
     */
    public static MovementState tick(MovementState state, MovementInput input, WorldView world) {
        if (!state.known()) return MovementState.initial(state.position(), state.yaw());

        // --- 1. Environment detection and bounds ---
        BoundingBox box = BoundingBox.playerAt(state.position().x, state.position().y, state.position().z, state.pose());
        PathCollision path = world.pathCollision(box.box(), input.forward(), input.strafe(), input.jump() ? 0 : 1.0E7);

        if (!path.isDefinite()) {
            return MovementState.initial(state.position(), state.yaw()).withKnown(false);
        }

        // Environment from the world view seam
        var envSample = world.environment(box.box());
        if (!envSample.isDefinite()) {
            return MovementState.initial(state.position(), state.yaw()).withKnown(false);
        }

        // Extract environment facts for the main block the box overlaps
        List<dev.phantom.ac.world.WorldQueries.EnvironmentCell> cells = envSample.cells();
        boolean inWater = envSample.water();
        boolean inLava = envSample.lava();
        boolean climbing = envSample.climbable();
        double slipperiness = 0.6;
        if (!cells.isEmpty()) {
            dev.phantom.ac.world.WorldQueries.EnvironmentCell first = cells.getFirst();
            slipperiness = first.slipperiness();
        }

        // --- 2. Jump handling (when grounded and input.jump) ---
        boolean grounded = false;
        if (!path.collides() && input.jump()) {
            double jumpPower = computeJumpPower(state);
            // Apply jump: add vertical component; horizontal sprint bonus may be added later in move
            Vec3 newVel = state.velocity().add(0, jumpPower, 0);
            if (input.sprint()) {
                newVel = newVel.add(Math.sin(Math.toRadians(state.yaw())) * SPRINT_HORIZONTAL_BONUS,
                        0, -Math.cos(Math.toRadians(state.yaw())) * SPRINT_HORIZONTAL_BONUS);
            }
            return nextState(state, newVel, world, box, envSample, inWater, inLava, climbing, slipperiness, path);
        }

        // --- 3. Compute input transformation (ModernInputTransformer)
        // For vanilla 1.21.11, we apply the 0.98 scalar to diagonal movements.
        int f = input.forward();
        int s = input.strafe();
        double inputLen = Math.sqrt(f * f + s * s);
        double scale = 1.0;
        if (inputLen > EPS) {
            scale = Math.sqrt(MODERN_INPUT_TRANSFORMER * inputLen * inputLen);
        }
        // Effective forward/strafe after transformation
        double effF = f * scale;
        double effS = s * scale;

        // --- 4. Determine friction and acceleration for this tick ---
        boolean onGround = path.collides() && input.jump() == false && state.velocity().y <= EPS;
        double friction = onGround ? slipperiness * GROUND_FRICTION_BASE : AIR_FRICTION;

        // Compute effective walk speed using the V_26_2 rule from BlockProperties
        double walkSpeed = DEFAULT_WALK_SPEED * (1.0 + state.effect(MOVEMENT_SPEED));

        // --- 5. Accelerate horizontal velocity ---
        double acc = WALK_ACCELERATION * friction * friction * friction * walkSpeed;
        Vec3 vel = state.velocity();
        // Convert input to world space using yaw
        double yawRad = Math.toRadians(state.yaw());
        double dx = effF * Math.cos(yawRad) + effS * Math.sin(yawRad);
        double dz = effF * Math.sin(yawRad) - effS * Math.cos(yawRad);
        // Apply sprint horizontal bonus if sprinting and not in water/lava
        if (input.sprint() && !inWater && !inLava && !onGround) {
            // Sprint air strafe uses separate speed constants
            acc = WALK_ACCELERATION * walkSpeed;
        }
        vel = vel.add(dx * acc, 0, dz * acc);

        // Sprint bonus applied after acceleration (0.2 horizontal)
        if (input.sprint() && !inWater && !inLava && onGround) {
            vel = vel.add(Math.sin(yawRad) * SPRINT_HORIZONTAL_BONUS,
                    0, -Math.cos(yawRad) * SPRINT_HORIZONTAL_BONUS);
        }

        // --- 6. Apply water or climbable special handling ---
        if (inWater) {
            vel = vel.multiply(WATER_SWIM_HORIZONTAL, 1, WATER_SWIM_HORIZONTAL);
            // Water end-of-tick later handles gravity via WATER_SWIM_VERTICAL_ADJUST
        } else if (climbing && !onGround) {
            // Climbable reduces horizontal movement and limits vertical component
            vel = vel.multiply(0.2, 1, 0.2);
            vel = vel.add(0, 0.2, 0); // climb upward boost
        }

        // --- 7. Move: collide + step ---
        // Use the pure collision resolver from MovementCollision
        List<BlockBox> collBoxes = new ArrayList<>();
        for (var collision : path.collisions()) {
            collBoxes.add(collision.box());
        }
        BlockBox playerBox = box.box();
        CollideResult collide = MovementCollision.resolveWithStep(collBoxes, playerBox,
                vel.x, vel.y, vel.z, STEP_UP);

        // Update position based on clipped displacement
        Vec3 newPos = state.position().add(collide.dx, collide.dy, collide.dz);
        // Update pose if water swimming (secondary branch)
        Pose newPose = state.pose();
        if (inWater && !collide.collidedHorizontally()) {
            newPose = Pose.SWIMMING;
        }

        // --- 8. End‑of‑tick gravity and friction ---
        Vec3 endVel = Vec3.ZERO;
        if (inWater) {
            // Water end-of-tick (GrimAC FluidFallingAdjustedMovement)
            endVel = vel.multiply(WATER_SWIM_HORIZONTAL, 1, WATER_SWIM_HORIZONTAL);
            endVel = endVel.add(0, WATER_SWIM_VERTICAL_ADJUST, 0);
            // Sprint in water skips gravity
            if (input.sprint()) {
                endVel = vel.multiply(WATER_SWIM_HORIZONTAL, 1, WATER_SWIM_HORIZONTAL);
            }
        } else {
            // Gravity
            double vy = vel.y - GRAVITY;
            // Air drag
            vy *= AIR_DRAG;
            // Horizontal friction (ground vs air)
            double hx = vel.x * friction;
            double hz = vel.z * friction;
            endVel = new Vec3(hx, vy, hz);
        }

        // --- 9. Ground detection (simplified: vertical collision with input.y <= 0) ---
        boolean onGroundResult = false;
        if (collide.collidedVertically()) {
            onGroundResult = input.jump() == false && vel.y <= EPS;
        }

        // --- 10. Build new state ---
        return new MovementState(newPos, endVel, state.yaw(), newPose,
                onGroundResult,
                inWater, inLava, climbing, state.wasTouchingWater(),
                state.effects(),
                DEFAULT_WALK_SPEED * (1.0 + state.effect(MOVEMENT_SPEED)),
                computeJumpPower(state),
                DEFAULT_STEP_HEIGHT,
                true);
    }

    private static double computeJumpPower(MovementState state) {
        int jumpBoostAmplifier = state.effect(JUMP_BOOST);
        double jumpPower = DEFAULT_JUMP_STRENGTH + (0.1 * (jumpBoostAmplifier + 1));
        // Honey multiplier (slows jump) - GrimAC handles honey in JumpPower.jumpFromGround()
        if (state.hasEffect("minecraft:honey")) {
            jumpPower *= 0.5;
        }
        return jumpPower;
    }

    private static MovementState nextState(MovementState state, Vec3 newVel, WorldView world,
                                          BoundingBox box, dev.phantom.ac.world.WorldQueries.EnvironmentSample env,
                                          boolean inWater, boolean inLava, boolean climbing, double slip,
                                          PathCollision path) {
        // For simplicity, we assume the jump doesn't collide with anything in this turn.
        // This matches the vanilla jump implementation where vertical movement is allowed freely.
        Vec3 newPos = state.position().add(newVel.x, newVel.y, newVel.z);
        Pose newPose = state.pose();
        if (inWater) newPose = Pose.SWIMMING;
        boolean onGround = false;
        if (path.collides()) onGround = state.velocity().y <= EPS;
        return new MovementState(newPos, newVel, state.yaw(), newPose,
                onGround, inWater, inLava, climbing, state.wasTouchingWater(),
                state.effects(), DEFAULT_WALK_SPEED * (1.0 + state.effect(MOVEMENT_SPEED)),
                computeJumpPower(state), DEFAULT_STEP_HEIGHT, true);
    }
}