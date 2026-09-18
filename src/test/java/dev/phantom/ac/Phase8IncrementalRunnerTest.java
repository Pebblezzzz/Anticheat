package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Packets.*;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;

class Phase8IncrementalRunnerTest {
  private static WorldSnapshot floorWorld() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
      builder.setBlock(x, 63, z, stone);
    }
    return builder.build();
  }

  private static Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> floorStates() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> states = new LinkedHashMap<>();
    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
      states.put(new dev.phantom.ac.world.Pos(x, 63, z), stone);
    }
    return Map.copyOf(states);
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
  void firstImpossibleMovementDoesNotBecomeAClientTrustedBaseline() {
    Phase8IncrementalRunner runner = new Phase8IncrementalRunner(4096, 0);
    Player anchor = anchor();

    var first = runner.process(
        "flight",
        List.of(
            new RawPacket(0, 0, new ChunkStates(new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
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
    assertEquals(3, first.lastProcessedSequence());

    var second = runner.process(
        "flight",
        List.of(
            new RawPacket(4, 110, new ClientTickEnd()),
            new RawPacket(5, 160, new Move(
                new Maths.Vec3(.6, 64, .5), 0f, 0f, true, 2L))),
        floorWorld(), anchor, 20L);

    assertFalse(second.results().isEmpty(), second.toString());
    assertNotEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE,
        second.results().getFirst().verdict(), second.results().toString());
  }

  @Test
  void paperGroundDisagreementIsTelemetryOnly() {
    Phase8IncrementalRunner runner = new Phase8IncrementalRunner(4096, 0);
    Player anchor = anchor();

    var report = runner.process(
        "ground-corroboration",
        List.of(
            new RawPacket(0, 0, new ChunkStates(new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(false, false, false),
                anchor.position(), anchor.velocity(), false, false, false, List.of())),
            new RawPacket(2, 60, new Move(
                anchor.position(), 0f, 0f, true, 1L))),
        floorWorld(), anchor, 20L);

    assertTrue(report.results().stream().allMatch(r ->
        !r.evidence().rule().equals("AUTHORITATIVE_GROUND_CONTRADICTION")),
        report.results().toString());
  }

  @Test
  void paperPositionDivergenceIsTelemetryOnlyAndCannotMaskReachability() {
    Phase8IncrementalRunner runner = new Phase8IncrementalRunner(4096, 0);
    Player anchor = anchor();

    var report = runner.process(
        "position-corroboration",
        List.of(
            new RawPacket(0, 0, new ChunkStates(new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                anchor.position(), anchor.velocity(), false, false, false, List.of())),
            new RawPacket(2, 60, new Move(
                new Maths.Vec3(5.5, 64, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor, 20L);

    assertTrue(report.results().stream().allMatch(r ->
        !r.evidence().rule().equals("AUTHORITATIVE_SERVER_POSITION_DIVERGENCE")),
        report.results().toString());
    assertTrue(report.results().stream().anyMatch(r ->
        r.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE
            && r.evidence().rule().equals("MOVEMENT_REACHABILITY")),
        report.results().toString());
  }

  @Test
  void subTickAmbiguityDoesNotBecomeAnImpossibleContradiction() {
    Phase8IncrementalRunner runner = new Phase8IncrementalRunner(4096, 0);
    Player anchor = anchor();

    var first = runner.process(
        "subtick",
        List.of(
            new RawPacket(0, 0, new ChunkStates(new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                anchor.position(), anchor.velocity(), false, false, false, List.of())),
            new RawPacket(2, 30, new ClientTickEnd()),
            new RawPacket(3, 60, new Move(anchor.position(), 0f, 0f, true, 1L)),
            new RawPacket(4, 65, new Move(
                new Maths.Vec3(.6, 64, .5), 15f, 0f, true, 1L))),
        floorWorld(), anchor, 20L);

    assertEquals(2, first.movementObservations());
    assertTrue(first.results().stream().anyMatch(
        r -> r.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN));

    var second = runner.process(
        "subtick",
        List.of(
            new RawPacket(5, 110, new ClientTickEnd()),
            new RawPacket(6, 160, new Move(
                new Maths.Vec3(.7, 64, .5), 15f, 0f, true, 2L))),
        floorWorld(), anchor, 20L);

    assertTrue(second.results().stream().allMatch(
        r -> r.verdict() != Phase8MovementValidation.Verdict.IMPOSSIBLE),
        second.results().toString());
  }

  @Test
  void unknownWorldRemainsUncertain() {
    Phase8IncrementalRunner runner = new Phase8IncrementalRunner(4096, 0);
    var report = runner.process(
        "unknown-world",
        List.of(
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                anchor().position(), Maths.Vec3.ZERO, false, false, false, List.of())),
            new RawPacket(2, 60, new Move(
                new Maths.Vec3(.6, 64, .5), 0f, 0f, true, 1L))),
        WorldSnapshot.builder(Contracts.TARGET_VERSION).build(),
        anchor(), 20L);

    assertTrue(report.results().stream().allMatch(
        r -> r.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN),
        report.results().toString());
  }

  @Test
  void repeatedInputDoesNotReprocessAlreadySeenPackets() {
    Phase8IncrementalRunner runner = new Phase8IncrementalRunner(4096, 0);
    Player anchor = anchor();
    List<RawPacket> raw = List.of(
        new RawPacket(1, 10, new ClientInput(true, false, false, false, false, false, false)),
        new RawPacket(2, 20, new ClientTickEnd()));

    var first = runner.process("duplicate-call", raw, floorWorld(), anchor, 10L);
    var second = runner.process("duplicate-call", raw, floorWorld(), anchor, 10L);

    assertEquals(2, first.packetsProcessed());
    assertEquals(0, second.packetsProcessed());
    assertEquals(first.lastProcessedSequence(), second.lastProcessedSequence());
  }
}
