package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.PlayerContext;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

class Phase8PersistentSprintRegressionTest {

  private static WorldSnapshot floorWorld() {
    var stone = BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 16; x++) {
      for (int z = -8; z <= 16; z++) {
        builder.setBlock(x, 63, z, stone);
      }
    }
    return builder.build();
  }

  private static Player player(Maths.Vec3 position, Maths.Vec3 velocity) {
    return new Player(
        position,
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

  @Test
  void heldSprintCanBecomeAConservativePhysicalAlternativeAfterFrontierPersists() {
    WorldSnapshot world = floorWorld();
    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Player start = player(new Maths.Vec3(0.5, 70.0, 0.5), new Maths.Vec3(0.0, 0.0, 0.2));

    var walkingEnvironment = MovementEnvironment.dry(false, false, false);
    var first = physics.step(new Vanilla12111RichPhysics.Context(
        0L,
        start,
        new Simulation.AdvancedInput(1, 0, false, false, false),
        world,
        Simulation.Environment.DRY,
        start.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        walkingEnvironment,
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    var sprintEnvironment = MovementEnvironment.dry(false, true, false);
    var second = physics.step(new Vanilla12111RichPhysics.Context(
        1L,
        first,
        new Simulation.AdvancedInput(1, 0, false, true, false),
        world,
        Simulation.Environment.DRY,
        first.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        sprintEnvironment,
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext authority = new PlayerContext(
        "survival",
        start.attributes(),
        Map.of(),
        Pose.STANDING,
        walkingEnvironment,
        start.position(),
        start.velocity(),
        false,
        false,
        false,
        List.of());

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    var report = runner.process(
        "persistent-sprint",
        List.of(
            new RawPacket(1, 10L, authority),
            new RawPacket(2, 20L, new ClientInput(
                true, false, false, false, false, false, false)),
            new RawPacket(3, 30L, new Move(
                first.position(), 0f, 0f, false, 1L)),
            new RawPacket(4, 40L, new ClientInput(
                true, false, false, false, false, false, true)),
            new RawPacket(5, 50L, new Move(
                second.position(), 0f, 0f, false, 2L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(
        report.frames().getLast().trace().stream()
            .anyMatch(line -> line.contains("movementSprint=true")),
        report.frames().getLast().trace().toString());
  }
}
