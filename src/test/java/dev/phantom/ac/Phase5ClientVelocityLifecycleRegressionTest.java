package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase5ClientVelocityLifecycleRegressionTest {
  @Test
  void normalMovementStartsFromClientVelocityNotPostCollisionPlayerVelocity() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .build();

    Simulation.AdvancedInput input =
        new Simulation.AdvancedInput(0, 0, false, false, false);

    Player state = new Player(
        new Vec3(0.5, 64.1, 0.5),
        new Vec3(0.3, 0.0, 0.0),
        0.0f,
        0.0f,
        false,
        "survival",
        Map.of(),
        OptionalInt.empty(),
        false,
        Optional.of(input),
        Simulation.Attributes.DEFAULT,
        Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY,
        State.TickRange.exact(1),
        State.Provenance.UNKNOWN,
        Set.of());

    Phase5MovementAuthority.SimulationContext context =
        new Phase5MovementAuthority.SimulationContext(
            1L,
            state,
            input,
            world,
            Simulation.Environment.DRY,
            Simulation.Attributes.DEFAULT,
            Phase5Mechanics.MovementEffects.NONE,
            Phase5Mechanics.Pose.STANDING,
            Phase5Mechanics.MovementEnvironment.dry(false, false, false),
            false,
            false,
            EntityCollisions.of(java.util.List.of()),
            null,
            false,
            new Vec3(0.0, 0.0, 0.0));

    Phase5MovementAuthority.StepResult result =
        new Phase5MovementAuthority().simulate(context);

    assertEquals(0.5, result.state().position().x(), 1.0e-12);
    assertEquals(0.0, result.clientVelocityAfterTick().x(), 1.0e-12);
  }
}
