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
  void positionMovementUsesPacketRotationBeforePhysicsAndMatching() {
    Packets.PlayerContext auth = authority();
    Packets.CaptureProvenance provenance =
        Packets.CaptureProvenance.fromAdapter("test-authority", auth, 0L, 1L);
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, auth, provenance),
        new RawPacket(2, 10, new Packets.ClientTickEnd()),
        new RawPacket(3, 20, new Move(new Maths.Vec3(.5, 64, .5), 90f, 0f, true, 1L)));

    var report = CausalMovementPipeline.analyze(
        "rotation-before-physics",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        floorWorld(),
        anchor(),
        0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("ROOT LOCAL_AUTHORITATIVE")),
        report.frames().getFirst().trace().toString());
    assertTrue(report.results().getFirst().evidence().closestCandidate().isPresent());
    var closest = report.results().getFirst().evidence().closestCandidate().orElseThrow();
    assertEquals(90f, closest.yaw(), 0.0f);
    assertEquals(0f, closest.pitch(), 0.0f);
  }

  @Test
  void boundedExplicitMovementAcceptsAuthoritativeZeroStepCandidate() {
    Packets.PlayerContext liveAuthority = authority();
    Packets.Move liveMove = new Move(new Maths.Vec3(.5, 64, .5), 90f, 0f, true, 1L);
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, liveAuthority,
            Packets.CaptureProvenance.fromAdapter("paper-live", liveAuthority, 0L, 1L)),
        new RawPacket(2, 10, liveMove,
            Packets.CaptureProvenance.fromAdapter("paper-live", liveMove, 0L, 1L)));

    var report = CausalMovementPipeline.analyze(
        "bounded-explicit-zero-step",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        Phase7Timing.Config.defaultConfig(),
        floorWorld(),
        null,
        0L);

    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("CANDIDATES count=") && line.contains("exhaustive=true")),
        report.frames().getFirst().trace().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("MATCHING candidates=")),
        report.frames().getFirst().trace().toString());
    assertFalse(report.results().getFirst().evidence().uncertaintySources().stream()
        .anyMatch(reason -> reason.contains("intermediate rotation chronology")),
        report.results().getFirst().evidence().uncertaintySources().toString());
  }

  @Test
  void explicitMovementRefusesUnwatermarkedAuthorityRoot() {
    Packets.PlayerContext liveAuthority = authority();
    Packets.Move liveMove = new Move(new Maths.Vec3(.5, 64, .5), 90f, 0f, true, 1L);
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, liveAuthority,
            Packets.CaptureProvenance.fromAdapter("paper-live", liveAuthority, 0L, null)),
        new RawPacket(2, 10, liveMove,
            Packets.CaptureProvenance.fromAdapter("paper-client-tick-boundary", liveMove, 0L, null)));

    var report = CausalMovementPipeline.analyze(
        "unwatermarked-authority",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        floorWorld(),
        null,
        0L);

    assertEquals(Verdict.UNCERTAIN, report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.results().getFirst().evidence().uncertaintySources().stream()
        .anyMatch(reason -> reason.contains("authoritative") || reason.contains("anchor")),
        report.results().getFirst().evidence().uncertaintySources().toString());
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
            new Maths.Vec3(10.5, 64, .5), 0f, 0f, true, 1L)));

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
  void inputArrivingAfterGroundMovementCannotCauseBackwardJumpFlag() {
    Player jumping = new Player(
        new Maths.Vec3(.5, 64.42, .5),
        new Maths.Vec3(0.0, 0.3332, 0.0),
        0f, 0f, false,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

    Packets.PlayerContext firstAuthority = authority();
    Packets.PlayerContext secondAuthority = new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        jumping.position(), jumping.velocity(), false, false, false, List.of());

    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(
            new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
        new RawPacket(2, 10, firstAuthority),
        // This movement is still grounded and arrives before the jump input packet.
        new RawPacket(3, 20, new Move(
            new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
        // Same server tick, but later on the wire: it must not be applied backwards
        // to the earlier movement observation.
        new RawPacket(4, 30, new ClientInput(false, false, false, false, true, false, false)),
        // Next client/server tick contains the actual first airborne jump position.
        new RawPacket(5, 50_000_020L, secondAuthority),
        new RawPacket(6, 50_000_030L, new Move(
            jumping.position(), 0f, 0f, false, 1L)));

    var report = CausalMovementPipeline.analyze(
        "jump-ordering",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        floorWorld(),
        anchor(),
        0L);

    assertEquals(2, report.movementObservations());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
        report.results().toString());
    assertEquals(Verdict.POSSIBLE, report.results().get(1).verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("INPUT_FUTURE_EXCLUDED seq=4")),
        report.frames().getFirst().trace().toString());
  }

  @Test
  void staleInitialAnchorRefreshesFromExactLocalAuthorityWhenOldChunkIsGone() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var worldBuilder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(2, 0);
    for (int x = 32; x <= 47; x++) for (int z = 0; z <= 15; z++) {
      worldBuilder.setBlock(x, 63, z, stone);
    }
    WorldSnapshot currentWorld = worldBuilder.build();

    Player current = new Player(
        new Maths.Vec3(32.5, 64, 0.5), Maths.Vec3.ZERO, 0f, 0f, true,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
    Packets.PlayerContext currentAuthority = new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        current.position(), current.velocity(), false, false, false, List.of());

    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(
            new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
        new RawPacket(2, 10, currentAuthority),
        new RawPacket(3, 20, new Move(
            new Maths.Vec3(32.5, 64, 0.5), 0f, 0f, true, 1L)));

    var report = CausalMovementPipeline.analyze(
        "stale-anchor-refresh",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        currentWorld,
        anchor(),
        0L);

    assertEquals(1, report.movementObservations());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("ROOT LOCAL_AUTHORITATIVE")));
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("ROOT_REFRESH reason=INITIAL_ANCHOR_WORLD_STALE")));
  }

  @Test
  void populatedFrontierRefreshesWhenFreshAuthorityIsFarAway() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var worldBuilder = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .loadChunk(2, 0);
    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
      worldBuilder.setBlock(x, 63, z, stone);
    }
    for (int x = 32; x <= 47; x++) for (int z = 0; z <= 15; z++) {
      worldBuilder.setBlock(x, 63, z, stone);
    }
    WorldSnapshot currentWorld = worldBuilder.build();

    Player initial = anchor();
    Player current = new Player(
        new Maths.Vec3(40.5, 64, 0.5), Maths.Vec3.ZERO, 0f, 0f, true,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

    Packets.PlayerContext firstAuthority = authority();
    Packets.PlayerContext secondAuthority = new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        current.position(), current.velocity(), false, false, false, List.of());

    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(
            new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
        new RawPacket(2, 10, firstAuthority),
        new RawPacket(3, 20, new Move(
            new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
        new RawPacket(4, 1_950_000_000L, secondAuthority),
        new RawPacket(5, 2_000_000_000L, new Move(
            current.position(), 0f, 0f, true, 40L)));

    var report = CausalMovementPipeline.analyze(
        "frontier-refresh",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        currentWorld,
        initial,
        0L);

    assertEquals(2, report.movementObservations());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
        report.results().toString());
    assertEquals(Verdict.POSSIBLE, report.results().get(1).verdict(),
        report.results().toString());
    assertTrue(report.frames().get(1).trace().stream()
        .anyMatch(line -> line.contains("FRONTIER_REFRESH reason=FRONTIER_FAR_FROM_LOCAL_AUTHORITY")),
        report.frames().get(1).trace().toString());
  }

  @Test
  void simulationRootPrefersPrecedingAuthoritativeTickOverSameTickSnapshot() {
    Player previous = anchor();
    Player sameTick = new Player(
        new Maths.Vec3(20.5, 64, 0.5), Maths.Vec3.ZERO, 0f, 0f, true,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

    Packets.PlayerContext previousAuthority = new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        previous.position(), previous.velocity(), false, false, false, List.of());
    Packets.PlayerContext sameTickAuthority = new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        sameTick.position(), sameTick.velocity(), false, false, false, List.of());

    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, previousAuthority),
        new RawPacket(2, 60_000_000L, sameTickAuthority),
        new RawPacket(3, 70_000_000L, new Move(
            previous.position(), 0f, 0f, true, 1L)));

    WorldSnapshot world = floorWorld();
    var report = CausalMovementPipeline.analyze(
        "preceding-authority-root",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        world,
        previous,
        0L);

    assertEquals(1, report.movementObservations());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("serverTick=0") && line.contains("ROOT LOCAL_AUTHORITATIVE")),
        report.frames().getFirst().trace().toString());
  }

  @Test
  void liveExplicitTickUsesPrecedingServerAuthorityAsPhysicsRoot() {
    Player previous = anchor();
    Packets.PlayerContext previousAuthority = new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        previous.position(), previous.velocity(), false, false, false, List.of());
    Player sameTick = new Player(
        new Maths.Vec3(20.5, 64, 0.5), Maths.Vec3.ZERO, 0f, 0f, true,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
    Packets.PlayerContext sameTickAuthority = new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        sameTick.position(), sameTick.velocity(), false, false, false, List.of());
    Packets.Move move = new Packets.Move(previous.position(), 0f, 0f, true, 1L);

    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1, 0, previousAuthority,
            Packets.CaptureProvenance.fromAdapter("paper-live", previousAuthority, 0L, 1L)),
        new Packets.RawPacket(2, 60_000_000L, sameTickAuthority,
            Packets.CaptureProvenance.fromAdapter("paper-live", sameTickAuthority, 1L, 1L)),
        new Packets.RawPacket(3, 70_000_000L, move,
            Packets.CaptureProvenance.fromAdapter("paper-client-tick-boundary", move, 1L, 1L)));

    var report = CausalMovementPipeline.analyze(
        "live-preceding-authority",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        floorWorld(),
        null,
        0L);

    assertEquals(1, report.movementObservations());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("SIMULATION_AUTHORITY seq=1 serverTick=0 clientTick=1")),
        report.frames().getFirst().trace().toString());
  }

  @Test
  void liveExplicitTickIgnoresNonAtomicAuthorityClientWatermark() {
    Player previous = new Player(
        new Maths.Vec3(.5, 64, .5),
        Maths.Vec3.ZERO,
        0f, 0f, true,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
    Packets.PlayerContext previousAuthority = new Packets.PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        previous.position(), previous.velocity(), false, false, false, List.of());

    // This is a deterministic one-tick state from the same root under one
    // concrete input branch; the test isolates timing-root selection rather than
    // relying on a guessed movement constant.
    Packets.Move move = new Move(
        new Maths.Vec3(.47242283553372466, 64, .5275771644662753), 0f, 0f, true, 1L);

    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(
            1, 0, previousAuthority,
            Packets.CaptureProvenance.fromAdapter("paper-live", previousAuthority, 0L, 999L)),
        new Packets.RawPacket(
            2, 50_000_000L, move,
            Packets.CaptureProvenance.fromAdapter("paper-client-tick-boundary", move, 1L, 999L)));

    var report = CausalMovementPipeline.analyze(
        "live-watermark-ignored",
        Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L),
        4096,
        exactTiming(),
        floorWorld(),
        null,
        0L);

    assertEquals(1, report.movementObservations());
    assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("ROOT LOCAL_AUTHORITATIVE")
            && line.contains("simulationTick=0")),
        report.frames().getFirst().trace().toString());
    assertTrue(report.results().getFirst().evidence().matchingCandidateCount() > 0,
        report.results().toString());
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

    var timeline = Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L);
    var timing = Phase7Timing.reconstruct(timeline, exactTiming());
    var event = timeline.events().stream()
        .filter(e -> e.packet().packet() instanceof FlightToggle)
        .findFirst()
        .orElseThrow();
    var result = CausalMovementPipeline.evaluateFlightToggle(
        "flight-evidence", event, timeline, timing, floorWorld(), anchor());
    assertTrue(result.isPresent());
    assertEquals(Verdict.IMPOSSIBLE, result.get().verdict());
    assertEquals("UNAUTHORIZED_FLIGHT_TOGGLE_ATTEMPT", result.get().evidence().rule());
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
