package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

/**
 * Canonical Phase 5 empirical-validation scenario manifest.
 *
 * <p>The catalog is deliberately broader than the currently captured subset.
 * A scenario entry describes what must be exercised by a real 1.21.11 client;
 * it does not manufacture a vanilla result.</p>
 */
public final class Phase5ScenarioCatalog {
    private Phase5ScenarioCatalog() {}

    public enum InputPattern {
        IDLE,
        FORWARD,
        BACKWARD,
        STRAFE_LEFT,
        STRAFE_RIGHT,
        DIAGONAL,
        SPRINT_FORWARD,
        SPRINT_STRAFE,
        SPRINT_DIAGONAL,
        JUMP,
        SPRINT_JUMP,
        REPEATED_JUMP,
        SNEAK,
        ASCENT_APEX_FALL,
        WATER,
        DEEP_SWIM,
        LAVA,
        CLIMB,
        CLIMB_SPRINT,
        GLIDE,
        KNOCKBACK,
        CORRECTION,
        SLEEPING
    }

    public enum WorldSetup {
        FLAT_GROUND,
        SLAB,
        STAIRS,
        STEP,
        EDGE,
        CORNER,
        PARTIAL_COLLISION,
        WATER_SURFACE,
        DEEP_WATER,
        LAVA,
        LADDER,
        VINE,
        AIRBORNE,
        NONE
    }

    public record Scenario(
            String id,
            String category,
            int minimumTicks,
            InputPattern input,
            WorldSetup world,
            Set<String> requiredObservations,
            String purpose
    ) implements Serializable {
        public Scenario {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
            if (category == null || category.isBlank()) throw new IllegalArgumentException("category is required");
            if (minimumTicks < 10) throw new IllegalArgumentException("minimumTicks must be >= 10");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(world, "world");
            requiredObservations = Set.copyOf(requiredObservations);
            if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("purpose is required");
        }
    }

    private static final Set<String> KINEMATICS = Set.of("x", "y", "z", "vx", "vy", "vz", "on_ground", "forward", "strafe", "jump");
    private static final Set<String> COLLISION = Set.of("collision", "collision_x", "collision_y", "collision_z");
    private static final Set<String> STEP = Set.of("collision", "step_attempted", "step_succeeded");
    private static final Set<String> CORRECTION = Set.of("velocity_packet", "correction_id", "correction_pending");

    private static Scenario s(String id, String category, InputPattern input, WorldSetup world, int ticks, Set<String> observations, String purpose) {
        return new Scenario(id, category, ticks, input, world, observations, purpose);
    }

    /** Every scenario required to close the empirical Phase 5 movement corpus. */
    public static List<Scenario> required() {
        return List.of(
                s("idle", "basic", InputPattern.IDLE, WorldSetup.FLAT_GROUND, 60, KINEMATICS, "No-input baseline and passive friction."),
                s("walk-forward", "basic", InputPattern.FORWARD, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Forward walking from a settled ground state."),
                s("walk-backward", "basic", InputPattern.BACKWARD, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Backward walking without sprint."),
                s("strafe-left", "basic", InputPattern.STRAFE_LEFT, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Pure left strafe."),
                s("strafe-right", "basic", InputPattern.STRAFE_RIGHT, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Pure right strafe."),
                s("diagonal", "basic", InputPattern.DIAGONAL, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Forward plus strafe diagonal normalization."),
                s("sprint-forward", "sprint", InputPattern.SPRINT_FORWARD, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Forward sprint acceleration and friction."),
                s("sprint-strafe", "sprint", InputPattern.SPRINT_STRAFE, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Sprint while strafing."),
                s("sprint-diagonal", "sprint", InputPattern.SPRINT_DIAGONAL, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Sprint with simultaneous forward and strafe input."),
                s("jump", "jump", InputPattern.JUMP, WorldSetup.FLAT_GROUND, 80, KINEMATICS, "Single jump launch, ascent and landing."),
                s("sprint-jump", "jump", InputPattern.SPRINT_JUMP, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Sprint jump trajectory."),
                s("repeated-jumps", "jump", InputPattern.REPEATED_JUMP, WorldSetup.FLAT_GROUND, 180, KINEMATICS, "Repeated jump/landing cycles."),
                s("ascent-apex-fall", "jump", InputPattern.ASCENT_APEX_FALL, WorldSetup.FLAT_GROUND, 90, KINEMATICS, "Explicit sampled ascent, apex and descent."),
                s("landing", "jump", InputPattern.ASCENT_APEX_FALL, WorldSetup.FLAT_GROUND, 100, KINEMATICS, "Controlled fall followed by landing and ground friction."),
                s("sneak", "pose", InputPattern.SNEAK, WorldSetup.FLAT_GROUND, 80, KINEMATICS, "Sneaking movement and crouch pose transition."),
                s("slab-up", "collision", InputPattern.FORWARD, WorldSetup.SLAB, 100, KINEMATICS, "Half-block collision and upward movement."),
                s("stairs-up", "collision", InputPattern.FORWARD, WorldSetup.STAIRS, 110, KINEMATICS, "Straight stair traversal."),
                s("step-up", "collision", InputPattern.FORWARD, WorldSetup.STEP, 100, STEP, "Vanilla step-height attempt/result."),
                s("partial-collision", "collision", InputPattern.FORWARD, WorldSetup.PARTIAL_COLLISION, 100, COLLISION, "Per-axis clipping around a partial obstacle."),
                s("edge", "collision", InputPattern.FORWARD, WorldSetup.EDGE, 100, COLLISION, "Edge approach and partial support."),
                s("corner", "collision", InputPattern.DIAGONAL, WorldSetup.CORNER, 120, COLLISION, "Corner approach with simultaneous X/Z contact."),
                s("corner-sprint", "collision", InputPattern.SPRINT_DIAGONAL, WorldSetup.CORNER, 120, COLLISION, "Sprint diagonal corner contact."),
                s("water-surface", "fluid", InputPattern.WATER, WorldSetup.WATER_SURFACE, 100, KINEMATICS, "Water entry and surface movement."),
                s("deep-swimming", "fluid", InputPattern.DEEP_SWIM, WorldSetup.DEEP_WATER, 120, KINEMATICS, "Fully submerged swimming and vertical control."),
                s("water-sprint", "fluid", InputPattern.WATER, WorldSetup.DEEP_WATER, 100, KINEMATICS, "Submerged sprint movement."),
                s("swim-transition", "fluid", InputPattern.WATER, WorldSetup.WATER_SURFACE, 100, KINEMATICS, "Alternating air/water/swimming pose transitions."),
                s("lava", "fluid", InputPattern.LAVA, WorldSetup.LAVA, 100, KINEMATICS, "Lava drag and gravity multiplier."),
                s("ladder", "climbable", InputPattern.CLIMB, WorldSetup.LADDER, 100, KINEMATICS, "Basic ladder ascent/descent."),
                s("ladder-sprint", "climbable", InputPattern.CLIMB_SPRINT, WorldSetup.LADDER, 100, KINEMATICS, "Ladder interaction while sprint key is held."),
                s("vines", "climbable", InputPattern.CLIMB, WorldSetup.VINE, 100, KINEMATICS, "Vine climbable behavior."),
                s("speed-effect", "effects", InputPattern.FORWARD, WorldSetup.FLAT_GROUND, 100, Set.of("x", "z", "vx", "vz", "speed_amp"), "Speed effect movement multiplier."),
                s("slowness-effect", "effects", InputPattern.FORWARD, WorldSetup.FLAT_GROUND, 100, Set.of("x", "z", "vx", "vz", "slowness_amp"), "Slowness effect movement multiplier."),
                s("jump-boost", "effects", InputPattern.JUMP, WorldSetup.FLAT_GROUND, 100, Set.of("y", "vy", "jump_boost_amp"), "Jump Boost launch height/velocity."),
                s("slow-falling", "effects", InputPattern.ASCENT_APEX_FALL, WorldSetup.AIRBORNE, 100, Set.of("y", "vy", "slow_falling"), "Reduced fall gravity after leaving ground."),
                s("levitation", "effects", InputPattern.ASCENT_APEX_FALL, WorldSetup.AIRBORNE, 100, Set.of("y", "vy", "levitation"), "Levitation upward velocity."),
                s("attribute-modifier", "attributes", InputPattern.FORWARD, WorldSetup.FLAT_GROUND, 100, Set.of("x", "z", "vx", "vz", "base_movement_speed", "modifiers"), "Movement-speed attribute modifier ordering and value."),
                s("knockback-ground", "impulse", InputPattern.KNOCKBACK, WorldSetup.FLAT_GROUND, 80, Set.of("vx", "vy", "vz", "velocity_packet"), "Observed server velocity impulse on ground."),
                s("knockback-air", "impulse", InputPattern.KNOCKBACK, WorldSetup.AIRBORNE, 80, Set.of("vx", "vy", "vz", "velocity_packet"), "Observed server velocity impulse while airborne."),
                s("teleport-correction", "correction", InputPattern.CORRECTION, WorldSetup.FLAT_GROUND, 100, CORRECTION, "Teleport/correction, confirmation barrier and resumed simulation."),
                s("teleport-water", "correction", InputPattern.CORRECTION, WorldSetup.WATER_SURFACE, 100, CORRECTION, "Teleport/correction crossing into water."),
                s("glide", "flight", InputPattern.GLIDE, WorldSetup.AIRBORNE, 120, KINEMATICS, "Fall-flying/elytra movement."),
                s("sleeping", "pose", InputPattern.SLEEPING, WorldSetup.NONE, 40, Set.of("pose", "on_ground"), "Sleeping pose as a non-standard movement state."),
                s("step-sprint-jump", "combination", InputPattern.SPRINT_JUMP, WorldSetup.STEP, 140, KINEMATICS, "Combined sprint, jump and step-up traversal."),
                s("water-jump", "combination", InputPattern.JUMP, WorldSetup.WATER_SURFACE, 100, KINEMATICS, "Jump/input behavior at a fluid boundary."),
                s("climb-jump", "combination", InputPattern.CLIMB, WorldSetup.LADDER, 100, KINEMATICS, "Transition between climbable and normal movement."),
                s("correction-after-knockback", "combination", InputPattern.CORRECTION, WorldSetup.FLAT_GROUND, 120, Set.of("vx", "vy", "vz", "velocity_packet", "correction_id", "correction_pending"), "Correction immediately following an authoritative velocity event.")
        );
    }

    public static Set<String> requiredIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Scenario scenario : required()) {
            if (!ids.add(scenario.id())) throw new IllegalStateException("duplicate Phase 5 scenario id: " + scenario.id());
        }
        return Collections.unmodifiableSet(ids);
    }

    public static Optional<Scenario> find(String id) {
        return required().stream().filter(scenario -> scenario.id().equals(id)).findFirst();
    }

    public static List<String> ids() {
        return required().stream().map(Scenario::id).toList();
    }
}
