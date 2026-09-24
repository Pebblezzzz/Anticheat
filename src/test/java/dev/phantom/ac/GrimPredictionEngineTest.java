package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.Provenance;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GrimPredictionEngineTest {

  private static WorldSnapshot floorWorld() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 16; x++) {
      for (int z = -8; z <= 16; z++) {
        builder.setBlock(x, 63, z, stone);
      }
    }
    return builder.build();
  }

  @Test
  void explicitNeutralInputIsNotRewrittenIntoForwardMovementBySprintState() {
    WorldSnapshot world = floorWorld();
    MovementEnvironment environment = MovementEnvironment.dry(false, true, false);
    Simulation.AdvancedInput neutral =
        new Simulation.AdvancedInput(0, 0, false, false, false);
    Player start = new Player(
        new Maths.Vec3(.5, 70.0, .5),
        new Maths.Vec3(.11245690494119422, .08307781780646778, .24289307805465754),
        0f,
        0f,
        false,
        "survival",
        Map.of(),
        OptionalInt.empty(),
        false,
        Optional.of(neutral),
        Simulation.Attributes.DEFAULT,
        Pose.STANDING,
        State.Environment.DRY,
        State.TickRange.exact(353),
        State.Provenance.UNKNOWN,
        Set.of());

    Context context = new Context(
        353L,
        start,
        Simulation.Environment.DRY,
        start.attributes(),
        MovementEffects.NONE,
        Pose.STANDING,
        environment,
        false,
        EntityCollisions.of(List.of()),
        Set.of(),
        new Maths.Vec3(
            .11245690494119422,
            .08307781780646778,
            .24289307805465754),
        false);
    Candidate candidate = new Candidate(
        1L,
        context,
        new Provenance(1L, -1L, 353L, "INPUT", "packet-world@353", "None",
            List.of("explicit-neutral-sprint-regression"), 1, List.of()));

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Player expected = physics.step(new Vanilla12111RichPhysics.Context(
        353L,
        start,
        neutral,
        world,
        Simulation.Environment.DRY,
        start.attributes(),
        MovementEffects.NONE,
        Pose.STANDING,
        environment,
        false,
        EntityCollisions.of(List.of()),
        new Maths.Vec3(
            .11245690494119422,
            .08307781780646778,
            .24289307805465754),
        false)).state();

    GrimPredictionEngine engine = new GrimPredictionEngine();
    var result = engine.tick(
        Set.of(candidate),
        List.of(InputConstraint.exact(neutral)),
        world,
        64,
        10L,
        353L,
        354L,
        new Maths.Vec3(
            .11245690494119422,
            .08307781780646778,
            .24289307805465754),
        false,
        environment,
        false);

    assertTrue(
        result.candidates().stream().anyMatch(c ->
            Math.abs(c.context().player().position().x() - expected.position().x()) <= 1.0E-9
                && Math.abs(c.context().player().position().y() - expected.position().y()) <= 1.0E-9
                && Math.abs(c.context().player().position().z() - expected.position().z()) <= 1.0E-9),
        result.trace().toString());
  }
}
