package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Packets.*;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;

class CausalMovementPipelineTest {
  private static WorldSnapshot floorWorld() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
      builder.setBlock(x, 63, z, stone);
    }
    return builder.build();
  }

  private static Player anchor() {
    return new Player(
        new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO, 0f, 0f, true,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
  }

  private static Packets.PlayerContext authority() {
    Player p = anchor();
    return new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        p.position(), p.velocity(), false, false, false, List.of());
  }

  private static Phase7Timing.Config exactTiming() {
    return new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
  }

  @Test
  void phantomCanProduceImpossibleWithoutPaperRejectingTheMovement() {
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(
            new dev.phantom.ac.world.Chunk(0, 0),
            floorStates())),
        new RawPacket(2, 10, authority()),
        new RawPacket(3, 20, new Move(
            new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
        new RawPacket(4, 70, new Move(
            new Maths.Vec3(20.5, 64, .5), 0f, 0f, true, 1L)));

    var report = CausalMovementPipeline.analyze(
        "independent",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        null,
        anchor(),
        0L);

    assertEquals(2, report.movementObservations());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict());
    assertEquals(Verdict.IMPOSSIBLE, report.results().get(1).verdict());

    var evidence = report.results().get(1).evidence();
    assertEquals("MOVEMENT_REACHABILITY", evidence.rule());
    assertTrue(evidence.reachableCandidateCount() > 0);
    assertEquals(0, evidence.matchingCandidateCount());
    assertTrue(evidence.eliminationReason().contains("all exhaustively modeled legitimate candidates"));
    assertTrue(report.frames().get(1).trace().stream()
        .anyMatch(line -> line.contains("EVIDENCE REACHABILITY_CONTRADICTION")));
  }

  @Test
  void PaperMovementRejectionIsPreservedAsCorroborationOnly() {
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(
            new dev.phantom.ac.world.Chunk(0, 0),
            floorStates())),
        new RawPacket(2, 10, authority()),
        new RawPacket(3, 20, new Move(
            new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
        new RawPacket(4, 30, new PaperMovementRejection("MOVED_TOO_QUICKLY", false)));

    var report = CausalMovementPipeline.analyze(
        "paper-inverse",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        floorWorld(),
        anchor(),
        0L);

    assertEquals(1, report.movementObservations());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("PACKET seq=3")));
    assertFalse(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("Phantom IMPOSSIBLE")));
  }

  private static Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> floorStates() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> states = new LinkedHashMap<>();
    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
      states.put(new dev.phantom.ac.world.Pos(x, 63, z), stone);
    }
    return Map.copyOf(states);
  }

  @Test
  void unauthorizedFlightIsIndependentAuthoritativeEvidence() {
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(
            new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
        new RawPacket(2, 10, authority()),
        new RawPacket(3, 20, new FlightToggle(true, false)));

    var report = CausalMovementPipeline.analyze(
        "flight-evidence",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        floorWorld(),
        anchor(),
        0L);

    assertTrue(report.results().stream().anyMatch(result ->
        result.evidence().rule().equals("UNAUTHORIZED_FLIGHT_TOGGLE_ATTEMPT")
            && result.verdict() == Verdict.IMPOSSIBLE),
        report.results().toString());
  }

  @Test
  void untimedVelocityTransitionForcesUncertainInsteadOfFalseReachabilityContradiction() {
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(
            new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
        new RawPacket(2, 10, authority()),
        new RawPacket(3, 20, new Move(
            new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
        new RawPacket(4, 30, new Velocity(new Maths.Vec3(0.0, 1.0, 0.0))),
        new RawPacket(5, 70, new Move(
            new Maths.Vec3(.5, 64.1, .5), 0f, 0f, false, 1L)));

    var report = CausalMovementPipeline.analyze(
        "velocity-timing",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        Phase7Timing.Config.defaultConfig(),
        floorWorld(),
        anchor(),
        0L);

    assertTrue(report.results().stream().anyMatch(result ->
        result.verdict() == Verdict.UNCERTAIN
            && result.evidence().uncertaintySources().stream()
                .anyMatch(reason -> reason.contains("velocity/teleport")
                    || reason.contains("simulation tick"))),
        report.results().toString());
    assertFalse(report.results().stream().anyMatch(result ->
        result.verdict() == Verdict.IMPOSSIBLE
            && result.evidence().rule().equals("MOVEMENT_REACHABILITY")),
        report.results().toString());
  }

}
