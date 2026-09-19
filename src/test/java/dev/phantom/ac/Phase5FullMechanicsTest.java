package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.Simulation.PhysicsContext;
import dev.phantom.ac.Simulation.Vanilla12111Physics;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.State.Player;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;


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