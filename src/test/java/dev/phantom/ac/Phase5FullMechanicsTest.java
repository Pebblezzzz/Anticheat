package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.Simulation.PhysicsContext;
import dev.phantom.ac.Simulation.Vanilla12111Physics;
import dev.phantom.ac.State.Player;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance tests for the complete Phase 5 movement feature surface. */
class Phase5FullMechanicsTest {
    private static final World.Snapshot WORLD = World.Snapshot.emptyVisibleChunks(java.util.List.of(
            new World.Chunk(0, 0), new World.Chunk(-1, 0), new World.Chunk(0, -1), new World.Chunk(-1, -1)));

    @Test void knockbackIsAnExplicitVelocityImpulse() {
        Player base = Player.initial(Vec3.ZERO);
        Player pushed = Phase5Mechanics.applyVelocityImpulse(base, new Phase5Mechanics.Vec3Like(0.4, 0.32, -0.2));
        assertEquals(0.4, pushed.velocity().x(), 1e-12);
        assertEquals(0.32, pushed.velocity().y(), 1e-12);
        assertEquals(-0.2, pushed.velocity().z(), 1e-12);
        assertFalse(pushed.uncertain());
    }

    @Test void correctionRecoveryInstallsAuthoritativeStateAndRequiresMatchingConfirmation() {
        Phase5Mechanics.CorrectionRecovery recovery = new Phase5Mechanics.CorrectionRecovery();
        Phase5Mechanics.Vec3Like p = new Phase5Mechanics.Vec3Like(5, 70, -9);
        Phase5Mechanics.Vec3Like v = new Phase5Mechanics.Vec3Like(0.1, -0.02, 0.3);
        recovery.acceptCorrection(17, p, v, Phase5Mechanics.Pose.CROUCHING);
        assertTrue(recovery.awaitingConfirmation());
        assertEquals(p, recovery.authoritativePosition().orElseThrow());
        assertFalse(recovery.confirm(16));
        assertTrue(recovery.awaitingConfirmation());
        assertTrue(recovery.confirm(17));
        assertFalse(recovery.awaitingConfirmation());
    }

    @Test void climbableMovementClampsDescentAndAllowsExplicitVerticalInput() {
        Player state = new Player(Vec3.ZERO, new Vec3(0, -0.5, 0), 0, 0, false, "survival", java.util.Map.of(), OptionalInt.empty(), false);
        var env = Phase5Mechanics.MovementEnvironment.vanillaClimbable(false, false, false);
        var physics = new Vanilla12111Physics();
        var result = physics.step(new PhysicsContext(1, state, new AdvancedInput(0, 0, false), WORLD,
                Simulation.Environment.CLIMBABLE, Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
                Phase5Mechanics.Pose.STANDING, env));
        assertTrue(result.state().velocity().y() >= -0.15 - 1e-12);

        var up = physics.step(new PhysicsContext(2, state, new AdvancedInput(1, 0, false), WORLD,
                Simulation.Environment.CLIMBABLE, Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
                Phase5Mechanics.Pose.STANDING, env));
        assertTrue(up.state().velocity().y() > result.state().velocity().y());
    }

    @Test void glidingHasItsOwnMovementModeAndPose() {
        Player state = new Player(new Vec3(0, 70, 0), new Vec3(0, -0.2, 0.1), 0, 0, false,
                "survival", java.util.Map.of(), OptionalInt.empty(), false);
        var env = new Phase5Mechanics.MovementEnvironment(Phase5Mechanics.Fluid.NONE, false, false, false,
                false, false, false, true, 1.0, 1.0, 1.0);
        var result = new Vanilla12111Physics().step(new PhysicsContext(3, state, new AdvancedInput(1, 0, false), WORLD,
                Simulation.Environment.DRY, Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
                Phase5Mechanics.Pose.FALL_FLYING, env));
        assertEquals(Phase5Mechanics.Pose.FALL_FLYING, result.pose());
        assertTrue(result.state().position().z() != state.position().z() || result.state().position().x() != state.position().x());
    }

    @Test void fluidModesAreDistinctAndExplicit() {
        var physics = new Vanilla12111Physics();
        Player state = Player.initial(Vec3.ZERO);
        var water = physics.step(new PhysicsContext(4, state, new AdvancedInput(1, 0, false), WORLD,
                Simulation.Environment.WATER, new Attributes(.1), Phase5Mechanics.MovementEffects.NONE,
                Phase5Mechanics.Pose.SWIMMING, Phase5Mechanics.MovementEnvironment.vanillaWater(false, true, false, true)));
        var lava = physics.step(new PhysicsContext(5, state, new AdvancedInput(1, 0, false), WORLD,
                Simulation.Environment.LAVA, new Attributes(.1), Phase5Mechanics.MovementEffects.NONE,
                Phase5Mechanics.Pose.STANDING, Phase5Mechanics.MovementEnvironment.vanillaLava(false, false, false)));
        assertNotEquals(water.state().velocity(), lava.state().velocity());
        assertEquals(Phase5Mechanics.Pose.SWIMMING, water.pose());
        assertEquals(Phase5Mechanics.Pose.STANDING, lava.pose());
    }

    @Test void poseTransitionIsBlockedByOccupyingGeometry() {
        var blocks = new java.util.HashMap<World.Pos, World.Block>();
        blocks.put(new World.Pos(0, 1, 0), World.Block.FULL);
        World.Snapshot world = new World.Snapshot(blocks, java.util.Set.of(new World.Chunk(0, 0)));
        Player state = new Player(Vec3.ZERO, Vec3.ZERO, 0, 0, false, "survival", java.util.Map.of(), OptionalInt.empty(), false);
        var env = new Phase5Mechanics.MovementEnvironment(Phase5Mechanics.Fluid.WATER, true, false, false,
                false, false, false, false, 1.0, 0.9, 0.0);
        var result = new Vanilla12111Physics().step(new PhysicsContext(6, state, new AdvancedInput(0, 0, false), world,
                Simulation.Environment.WATER, Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
                Phase5Mechanics.Pose.SWIMMING, env));
        assertEquals(Phase5Mechanics.Pose.STANDING, result.pose());
    }
}
