package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GrimPhysicsArchitectureTest {
  private static WorldSnapshot world() {
    return WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .build();
  }

  private static Player player(Vec3 velocity) {
    return new Player(
        new Vec3(0.5, 70.0, 0.5),
        velocity,
        0f,
        0f,
        false,
        "survival",
        Map.of(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        Simulation.Attributes.DEFAULT,
        Pose.STANDING,
        State.Environment.DRY,
        State.TickRange.exact(0),
        State.Provenance.UNKNOWN,
        Set.of());
  }

  private static Vanilla12111RichPhysics.Context context(
      Player player,
      MovementEnvironment movementEnvironment,
      Simulation.AdvancedInput input,
      Vec3 actualMovementReference) {
    return new Vanilla12111RichPhysics.Context(
        0L,
        player,
        input,
        world(),
        Simulation.Environment.DRY,
        player.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        movementEnvironment,
        false,
        false,
        EntityCollisions.of(List.of()),
        actualMovementReference,
        false);
  }

  @Test
  void physicalSprintStateChangesPhysicsEvenWhenHeldInputIsNotSprint() {
    Player start = player(new Vec3(0.0, 0.0, 0.2));
    Simulation.AdvancedInput forwardOnly =
        new Simulation.AdvancedInput(1, 0, false, false, false);

    var walking = new Vanilla12111RichPhysics().step(
        context(start, MovementEnvironment.dry(false, false, false), forwardOnly, null));
    var sprinting = new Vanilla12111RichPhysics().step(
        context(start, MovementEnvironment.dry(false, true, false), forwardOnly, null));

    assertFalse(walking.state().uncertain(), walking.state().toString());
    assertFalse(sprinting.state().uncertain(), sprinting.state().toString());
    assertTrue(
        Math.abs(sprinting.state().velocity().z())
            > Math.abs(walking.state().velocity().z()),
        () -> "physical sprint state must control acceleration independently of held-input sprint"
            + " walking=" + walking.state().velocity()
            + " sprinting=" + sprinting.state().velocity());
  }

  @Test
  void movementTickerPreservesClientVelocityAndObservedMovementAsSeparateInputs() {
    Player start = player(new Vec3(0.0, 0.0, 0.2));
    Vec3 observed = new Vec3(0.0, 0.0, 0.03);
    var movement = MovementEnvironment.dry(false, false, false);

    GrimMovementTicker.TickResult result = new GrimMovementTicker()
        .tick(new Phase5MovementAuthority.SimulationContext(
            0L,
            start,
            new Simulation.AdvancedInput(0, 0, false, false, false),
            world(),
            Simulation.Environment.DRY,
            start.attributes(),
            Phase5Mechanics.MovementEffects.NONE,
            Pose.STANDING,
            movement,
            false,
            false,
            EntityCollisions.of(List.of()),
            observed,
            false));

    assertEquals(start.velocity(), result.clientVelocityBeforeTick());
    assertEquals(observed, result.actualMovementReference());
    assertFalse(
        result.clientVelocityBeforeTick().equals(result.predictedVelocityAfterCollision())
            && !result.state().uncertain(),
        "ticker should expose the distinct pre-tick and post-tick velocity boundaries");
  }
}
