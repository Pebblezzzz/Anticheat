package dev.phantom.ac;

import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Packets.EntityAction;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.PlayerContext;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Phase8EntityActionSprintRegressionTest {

  private static WorldSnapshot floorWorld() {
    var stone = BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -16; x <= 32; x++) {
      for (int z = -16; z <= 32; z++) {
        builder.setBlock(x, 63, z, stone);
      }
    }
    return builder.build();
  }

  private static Player start() {
    return new Player(
        new Maths.Vec3(0.5, 64.0, 0.5),
        Maths.Vec3.ZERO,
        0f,
        0f,
        true,
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
  void startSprintingEntityActionMustDriveLongRunningPrediction() {
    WorldSnapshot world = floorWorld();
    Player start = start();
    MovementEnvironment walking = MovementEnvironment.dry(true, false, false);
    MovementEnvironment sprinting = MovementEnvironment.dry(true, true, false);
    Simulation.AdvancedInput forwardHeld =
        new Simulation.AdvancedInput(1, 0, false, false, false);

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(
        1L,
        10L,
        new PlayerContext(
            "survival",
            start.attributes(),
            Map.of(),
            Pose.STANDING,
            walking,
            start.position(),
            start.velocity(),
            false,
            false,
            false,
            List.of())));
    packets.add(new RawPacket(
        2L,
        20L,
        new ClientInput(true, false, false, false, false, false, false)));
    packets.add(new RawPacket(
        3L,
        30L,
        new EntityAction("START_SPRINTING", 0)));

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Player state = start;
    Maths.Vec3 clientVelocity = start.velocity();
    for (long tick = 0L; tick < 12L; tick++) {
      Vanilla12111RichPhysics.StepResult step = physics.step(
          new Vanilla12111RichPhysics.Context(
              tick,
              state,
              forwardHeld,
              world,
              Simulation.Environment.DRY,
              state.attributes(),
              Phase5Mechanics.MovementEffects.NONE,
              Pose.STANDING,
              sprinting,
              false,
              false,
              EntityCollisions.of(List.of()),
              null,
              true,
              clientVelocity));
      assertFalse(step.state().uncertain(), step.toString());
      state = step.state();
      clientVelocity = step.clientVelocityAfterTick();
      packets.add(new RawPacket(
          4L + tick,
          40L + tick * 50L,
          new Move(state.position(), 0f, 0f, state.onGround(), tick + 1L)));
    }

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    Phase8PredictionRunner.Report report = runner.process(
        "entity-action-sprint",
        packets,
        world,
        start,
        0L);

    assertEquals(12, report.movementObservations(), report.toString());
    assertEquals(0, report.impossible(), report.results().toString());
    assertTrue(
        report.results().stream().allMatch(result ->
            result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE
                || result.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN),
        report.results().toString());
    assertTrue(
        report.frames().stream()
            .flatMap(frame -> frame.trace().stream())
            .anyMatch(line -> line.contains("physicalSprint=true")),
        report.frames().toString());
  }
}
