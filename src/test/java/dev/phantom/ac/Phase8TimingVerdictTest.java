package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.Provenance;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase8TimingVerdictTest {
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

  private static Player anchor() {
    return new Player(
        new Maths.Vec3(.5, 64, .5),
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
        State.TickRange.unknown(),
        State.Provenance.UNKNOWN,
        Set.of());
  }

  private static Candidate candidate(Player player) {
    Context context = new Context(
        20,
        player,
        Simulation.Environment.DRY,
        Simulation.Attributes.DEFAULT,
        MovementEffects.NONE,
        Pose.STANDING,
        MovementEnvironment.dry(player.onGround(), false, false),
        false,
        EntityCollisions.of(List.of()));
    return new Candidate(
        1,
        context,
        new Provenance(
            1,
            -1,
            20,
            "INPUT",
            "WORLD",
            "None",
            List.of("test witness"),
            1,
            List.of()));
  }

  @Test
  void matchingCandidateRemainsPossibleUnderTimingUncertainty() {
    Player observed = Player.initial(new Maths.Vec3(.5, 65, .5));
    SearchResult reachable = new SearchResult(
        Phase6Reachability.Verdict.POSSIBLE,
        Set.of(candidate(observed)),
        1,
        1,
        0,
        0,
        0,
        0,
        List.of("controlled exhaustive candidate witness"));

    var result = Phase8MovementValidation.validate(
        "timing-witness",
        20,
        observed,
        observed,
        WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build(),
        "world:test",
        new Validation.SyncWindow(
            19,
            22,
            true,
            List.of("timing chronology remains uncertain")),
        List.of("one legitimate candidate was explicitly enumerated"),
        reachable,
        "replay:test:timing-witness",
        false);

    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE, result.verdict(), result.evidence().toString());
    assertEquals(1, result.evidence().matchingCandidateCount());
    assertTrue(result.evidence().simulationDiagnostics().stream()
        .anyMatch(reason -> reason.contains("explicitly enumerated legitimate candidate")));
  }

  @Test
  void truncatedTimingAllowsLatestHeldJumpAsConservativeBoundaryWitness() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L,
        50_000_000L,
        50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 1L),
        new Phase7Timing.TickDelayBounds(0L, 1L),
        250_000_000L,
        3,
        128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    WorldSnapshot world = floorWorld();
    Player start = anchor();

    Vanilla12111RichPhysics.StepResult jump = new Vanilla12111RichPhysics().step(
        new Vanilla12111RichPhysics.Context(
            1L,
            start,
            new Simulation.AdvancedInput(0, 0, true, false, false),
            world,
            Simulation.Environment.DRY,
            start.attributes(),
            MovementEffects.NONE,
            Pose.STANDING,
            MovementEnvironment.dry(true, false, false),
            false,
            EntityCollisions.of(List.of())));

    List<Packets.RawPacket> packets = new ArrayList<>();
    for (int i = 1; i <= 512; i++) {
      packets.add(new Packets.RawPacket(
          i,
          i,
          new Packets.WorldTransactionSend((short) i)));
    }
    packets.add(new Packets.RawPacket(513, 513, new Packets.ClientTickEnd()));
    packets.add(new Packets.RawPacket(
        514,
        514,
        new Packets.Move(start.position(), 0f, 0f, true, 1L)));
    packets.add(new Packets.RawPacket(515, 515, new Packets.ClientTickEnd()));
    packets.add(new Packets.RawPacket(
        516,
        516,
        new Packets.ClientInput(false, false, false, false, true, false, false)));
    packets.add(new Packets.RawPacket(
        517,
        517,
        new Packets.Move(
            jump.state().position(),
            jump.state().yaw(),
            jump.state().pitch(),
            jump.state().onGround(),
            2L)));

    var report = runner.process(
        "truncated-jump-witness",
        packets,
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("INPUT_BOUNDARY_WITNESS")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("observed position=")
            && line.contains("y=64.41999998688698")),
        report.frames().getLast().trace().toString());
  }
}
