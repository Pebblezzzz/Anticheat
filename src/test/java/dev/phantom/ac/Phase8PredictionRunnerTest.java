package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Packets.*;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;

class Phase8PredictionRunnerTest {
  private static WorldSnapshot floorWorld() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
      builder.setBlock(x, 63, z, stone);
    }
    return builder.build();
  }

  private static Player anchor() {
    return new Player(
        new Maths.Vec3(.5, 64, .5),
        Maths.Vec3.ZERO,
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(), Simulation.Attributes.DEFAULT,
        Pose.STANDING, State.Environment.DRY, State.TickRange.unknown(),
        State.Provenance.UNKNOWN, Set.of());
  }

  @Test
  void impossibleObservationDoesNotOverwritePredictionFrontier() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    Player anchor = anchor();

    var first = runner.process(
        "flight",
        List.of(
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            new RawPacket(2, 30, new ClientTickEnd()),
            new RawPacket(3, 60, new Move(
                new Maths.Vec3(20.5, 64, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor, 20L);

    assertEquals(1, first.movementObservations());
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE,
        first.results().getFirst().verdict());
    assertTrue(first.candidateFrontierRetained(), first.toString());
    assertEquals(1, runner.candidateCount());

    var second = runner.process(
        "flight",
        List.of(
            new RawPacket(4, 110, new ClientTickEnd()),
            new RawPacket(5, 160, new Move(
                new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 2L))),
        floorWorld(), anchor, 20L);

    assertFalse(second.results().stream().anyMatch(
        result -> result.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE),
        second.toString());
    assertTrue(second.candidateFrontierRetained(), second.toString());
  }

  @Test
  void stationaryObservationIsComparedAgainstRetainedPrediction() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    var report = runner.process(
        "stationary",
        List.of(
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            new RawPacket(2, 30, new ClientTickEnd()),
            new RawPacket(3, 60, new Move(
                new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 1L)),
            new RawPacket(4, 110, new ClientTickEnd()),
            new RawPacket(5, 160, new Move(
                new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 2L))),
        floorWorld(), anchor(), 20L);

    assertEquals(2, report.movementObservations());
    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() != Phase8MovementValidation.Verdict.IMPOSSIBLE),
        report.results().toString());
    assertTrue(report.candidateFrontierRetained(), report.toString());
  }

  @Test
  void sameTickSubTickMotionBecomesUncertainWithoutClearingFrontier() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    var report = runner.process(
        "subtick",
        List.of(
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                anchor().position(), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            new RawPacket(2, 30, new ClientTickEnd()),
            new RawPacket(3, 60, new Move(anchor().position(), 0f, 0f, true, 1L)),
            new RawPacket(4, 65, new Move(
                new Maths.Vec3(.6, 64, .5), 15f, 0f, true, 1L))));

    assertTrue(report.results().stream().anyMatch(
        result -> result.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN),
        report.results().toString());
    assertTrue(report.candidateFrontierRetained(), report.toString());
  }

  @Test
  void unknownWorldDoesNotDestroyPredictionState() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    var emptyWorld = WorldSnapshot.builder(Contracts.TARGET_VERSION).build();

    var report = runner.process(
        "unknown-world",
        List.of(
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                anchor().position(), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            new RawPacket(2, 30, new ClientTickEnd()),
            new RawPacket(3, 60, new Move(
                new Maths.Vec3(.6, 64, .5), 0f, 0f, true, 1L))),
        emptyWorld, anchor(), 20L);

    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN),
        report.results().toString());
    assertTrue(report.candidateFrontierRetained(), report.toString());
  }

  @Test
  void alreadyConsumedPacketsAreNotReprocessed() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    List<RawPacket> raw = List.of(
        new RawPacket(1, 10, new ClientInput(true, false, false, false, false, false, false)),
        new RawPacket(2, 20, new ClientTickEnd()));

    var first = runner.process("duplicate-call", raw, floorWorld(), anchor(), 10L);
    var second = runner.process("duplicate-call", raw, floorWorld(), anchor(), 10L);

    assertEquals(2, first.packetsProcessed());
    assertEquals(0, second.packetsProcessed());
    assertEquals(first.lastProcessedSequence(), second.lastProcessedSequence());
  }
}
