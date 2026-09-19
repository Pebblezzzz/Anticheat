package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Packets.*;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.Provenance;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase8MovementValidation.Evidence;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Broad regression matrix for movement validation hardening.
 *
 * <p>These tests intentionally exercise families of scenarios instead of one
 * hand-written example. A failure in this class means a concrete contract of
 * the anti-cheat has been violated.</p>
 */
class Phase8AdversarialMatrixTest {

  private static final long TICK_NANOS = 50_000_000L;

  private static Phase7Timing.Config exactTiming() {
    return new Phase7Timing.Config(
        TICK_NANOS, TICK_NANOS, TICK_NANOS,
        new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
  }

  private static Player anchor() {
    return new Player(
        new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO, 0f, 0f, true,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
  }

  private static WorldSnapshot floorWorld(int minX, int maxX, int minZ, int maxZ) {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION);
    int minChunkX = Math.floorDiv(minX, 16);
    int maxChunkX = Math.floorDiv(maxX, 16);
    int minChunkZ = Math.floorDiv(minZ, 16);
    int maxChunkZ = Math.floorDiv(maxZ, 16);
    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
      for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
        builder.loadChunk(cx, cz);
      }
    }
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        builder.setBlock(x, 63, z, stone);
      }
    }
    return builder.build();
  }

  private static Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> floorStates(
      int minX, int maxX, int minZ, int maxZ) {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> states = new LinkedHashMap<>();
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        states.put(new dev.phantom.ac.world.Pos(x, 63, z), stone);
      }
    }
    return Map.copyOf(states);
  }

  private static Timeline.Snapshot capture(List<RawPacket> packets) {
    return Timeline.assign(new Normalizer().normalize(packets), 0L, TICK_NANOS);
  }

  private static Candidate candidate(Player player, long tick) {
    Context context = new Context(
        tick,
        player,
        Simulation.Environment.DRY,
        Simulation.Attributes.DEFAULT,
        MovementEffects.NONE,
        Pose.STANDING,
        MovementEnvironment.dry(player.onGround(), false, false),
        false);
    return new Candidate(
        0,
        context,
        new Provenance(
            0, -1, tick, "INPUT", "WORLD", "None",
            List.of("matrix witness"), 1, List.of()));
  }

  private static SearchResult possible(Player player, long tick) {
    return new SearchResult(
        Phase6Reachability.Verdict.POSSIBLE,
        Set.of(candidate(player, tick)),
        1, 1, 0, 0, 0, 0,
        List.of("matrix possible witness"));
  }

  private static Validation.SyncWindow stable() {
    return new Validation.SyncWindow(20, 20, false, List.of("stable timing"));
  }

  private static DynamicTest dynamic(String name, org.junit.jupiter.api.function.Executable assertion) {
    return DynamicTest.dynamicTest(name, assertion);
  }

  @TestFactory
  Stream<DynamicTest> positionNoiseMatrixRespectsTheNumericalEnvelope() {
    List<Double> deltas = List.of(
        0.0, 0.000_001, -0.000_001, 0.000_5, -0.000_5,
        0.001, -0.001, 0.003, -0.003, 0.005, -0.005,
        0.007, -0.007, 0.009, -0.009, 0.009_9, -0.009_9,
        0.010_001, -0.010_001, 0.011, -0.011, 0.02, -0.02,
        0.05, -0.05, 0.1, -0.1, 0.5, -0.5, 1.0, -1.0);
    return deltas.stream().map(delta -> dynamic(
        "position-delta-" + delta,
        () -> {
          Player base = anchor();
          Player observed = Player.initial(new Maths.Vec3(.5 + delta, 64, .5));
          var result = Phase8MovementValidation.validate(
              "matrix",
              20,
              base,
              observed,
              WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build(),
              "matrix-world",
              stable(),
              List.of("position is the only deliberately varied observation"),
              possible(base, 20),
              "matrix-position");
          boolean within = Math.abs(delta) <= Phase6Reachability.POSITION_MATCH_TOLERANCE;
          assertEquals(
              within ? Verdict.POSSIBLE : Verdict.IMPOSSIBLE,
              result.verdict(),
              "delta=" + delta + " evidence=" + result.evidence());
        }));
  }

  @TestFactory
  Stream<DynamicTest> diagonalNoiseMatrixUsesEuclideanDistance() {
    List<Double> magnitudes = List.of(
        0.001, 0.002, 0.004, 0.005, 0.006, 0.007, 0.008, 0.009);
    return magnitudes.stream().map(magnitude -> dynamic(
        "diagonal-delta-" + magnitude,
        () -> {
          Player base = anchor();
          Player observed = Player.initial(
              new Maths.Vec3(.5 + magnitude, 64 + magnitude, .5 + magnitude));
          var result = Phase8MovementValidation.validate(
              "matrix",
              20,
              base,
              observed,
              WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build(),
              "matrix-world",
              stable(),
              List.of("three-dimensional position envelope"),
              possible(base, 20),
              "matrix-diagonal");
          boolean within = 3.0 * magnitude * magnitude
              <= Phase6Reachability.POSITION_MATCH_TOLERANCE
                  * Phase6Reachability.POSITION_MATCH_TOLERANCE;
          assertEquals(within ? Verdict.POSSIBLE : Verdict.IMPOSSIBLE, result.verdict());
        }));
  }

  @TestFactory
  Stream<DynamicTest> unknownWorldMovementNeverBecomesImpossible() {
    return IntStream.rangeClosed(1, 32).mapToObj(index -> dynamic(
        "unknown-world-offset-" + index,
        () -> {
          Player start = anchor();
          double x = .5 + index * .75;
          double z = .5 + (index % 4) * .5;
          List<RawPacket> packets = List.of(
              new RawPacket(
                  1, 0L,
                  new Move(start.position(), start.yaw(), start.pitch(), true, 0L)),
              new RawPacket(
                  2, TICK_NANOS,
                  new Move(new Maths.Vec3(x, 64, z), 0f, 0f, true, 1L)));
          var report = CausalMovementPipeline.analyze(
              "unknown-world-" + index,
              capture(packets),
              4096,
              exactTiming(),
              null,
              start,
              0L);

          assertEquals(2, report.movementObservations(), report.results().toString());
          assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
              report.results().toString());
          assertEquals(0, report.results().stream()
              .filter(result -> result.verdict() == Verdict.IMPOSSIBLE)
              .count(), report.results().toString());
          assertEquals(Verdict.UNCERTAIN, report.results().get(1).verdict(),
              report.results().toString());
        }));
  }

  @TestFactory
  Stream<DynamicTest> knownWorldTeleportMatrixProvesImpossible() {
    return Stream.of(3.0, 4.0, 5.0, 6.0, 8.0, 12.0, 16.0, 24.0).map(distance -> dynamic(
        "known-world-displacement-" + distance,
        () -> {
          Player start = anchor();
          List<RawPacket> packets = List.of(
              new RawPacket(
                  1, 0L,
                  new ChunkStates(
                      new dev.phantom.ac.world.Chunk(0, 0),
                      floorStates(-8, 12, -8, 8))),
              new RawPacket(
                  2, 0L,
                  new Packets.PlayerContext(
                      "survival",
                      Simulation.Attributes.DEFAULT,
                      Map.of(),
                      Pose.STANDING,
                      MovementEnvironment.dry(true, false, false),
                      start.position(),
                      start.velocity(),
                      false,
                      false,
                      false,
                      List.of())),
              new RawPacket(
                  3, 0L,
                  new ClientInput(false, false, false, false, false, false, false)),
              new RawPacket(
                  4, 0L,
                  new Move(start.position(), 0f, 0f, true, 0L)),
              new RawPacket(
                  5, TICK_NANOS,
                  new Move(new Maths.Vec3(.5 + distance, 64, .5), 0f, 0f, true, 1L)));
          var report = Phase8LiveValidation.analyze(
              "known-teleport-" + distance,
              capture(packets),
              4096,
              exactTiming(),
              null,
              start,
              0L);

          assertEquals(2, report.movementObservations(), report.results().toString());
          assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
              report.results().toString());
          assertEquals(Verdict.IMPOSSIBLE, report.results().get(1).verdict(),
              report.results().toString());
          assertEquals(0, report.results().get(1).evidence().matchingCandidateCount(),
              report.results().toString());
        }));
  }

  @TestFactory
  Stream<DynamicTest> activeFlightMatrixIsNeverForcedThroughGroundPhysics() {
    List<Double> xPositions = List.of(8.5, 16.5, 24.5, 32.5, 40.5, 48.5);
    return xPositions.stream().map(x -> dynamic(
        "authorized-flight-x-" + x,
        () -> {
          Player current = new Player(
              new Maths.Vec3(x, 100.0, .5),
              new Maths.Vec3(0.2, 0.0, 0.0),
              90f, 0f, true,
              "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
              Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
              State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
          var world = floorWorld(-8, 64, -8, 8);
          var authority = new Packets.PlayerContext(
              "survival",
              Simulation.Attributes.DEFAULT,
              Map.of(),
              Pose.STANDING,
              MovementEnvironment.dry(true, false, false),
              current.position(),
              current.velocity(),
              true,
              true,
              false,
              List.of());

          List<RawPacket> packets = List.of(
              new RawPacket(1, 0L, new ChunkStates(
                  new dev.phantom.ac.world.Chunk(0, 0),
                  floorStates(-8, 15, -8, 8))),
              new RawPacket(2, 10L, authority),
              new RawPacket(3, 20L, new Move(current.position(), 90f, 0f, true, 1L)));

          var report = CausalMovementPipeline.analyze(
              "flight-" + x,
              capture(packets),
              4096,
              exactTiming(),
              world,
              anchor(),
              0L);

          assertEquals(1, report.movementObservations(), report.results().toString());
          assertEquals(Verdict.POSSIBLE, report.results().getFirst().verdict(),
              report.results().toString());
          assertTrue(report.frames().getFirst().trace().stream()
              .anyMatch(line -> line.contains("AUTHORIZED_FLIGHT serverCanFly=true serverFlying=true")),
              report.frames().getFirst().trace().toString());
        }));
  }

  @TestFactory
  Stream<DynamicTest> staleAndFreshChunkCoverageMatrixRemainsConservative() {
    List<Integer> targetChunks = List.of(-3, -2, -1, 1, 2, 3);
    return targetChunks.stream().map(chunkX -> dynamic(
        "live-authority-chunk-" + chunkX,
        () -> {
          double x = chunkX * 16.0 + .5;
          Player current = new Player(
              new Maths.Vec3(x, 64, .5),
              Maths.Vec3.ZERO, 0f, 0f, true,
              "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
              Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
              State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
          int worldMinX = (int) Math.floor(x) - 8;
          int worldMaxX = (int) Math.floor(x) + 8;
          var currentWorld = floorWorld(worldMinX, worldMaxX, -8, 8);
          var currentAuthority = new Packets.PlayerContext(
              "survival",
              Simulation.Attributes.DEFAULT,
              Map.of(),
              Pose.STANDING,
              MovementEnvironment.dry(true, false, false),
              current.position(),
              current.velocity(),
              false,
              false,
              false,
              List.of());

          List<RawPacket> packets = List.of(
              new RawPacket(1, 0L, new ChunkStates(
                  new dev.phantom.ac.world.Chunk(0, 0),
                  floorStates(-8, 8, -8, 8))),
              new RawPacket(2, 10L, currentAuthority),
              new RawPacket(3, 20L, new Move(current.position(), 0f, 0f, true, 1L)));

          var report = CausalMovementPipeline.analyze(
              "chunk-refresh-" + chunkX,
              capture(packets),
              4096,
              exactTiming(),
              currentWorld,
              anchor(),
              0L);

          assertEquals(1, report.movementObservations(), report.results().toString());
          assertNotEquals(Verdict.IMPOSSIBLE, report.results().getFirst().verdict(),
              report.results().toString());
          assertTrue(report.frames().getFirst().trace().stream()
              .anyMatch(line -> line.contains("ROOT LOCAL_AUTHORITATIVE")),
              report.frames().getFirst().trace().toString());
        }));
  }

  @Test
  void insufficientCandidateBudgetCannotProduceImpossible() {
    Player base = anchor();
    Evidence evidence = Phase8MovementValidation.validate(
        "budget",
        20,
        base,
        Player.initial(new Maths.Vec3(50, 64, .5)),
        WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build(),
        "budget-world",
        new Validation.SyncWindow(19, 23, true, List.of("wide timing")),
        List.of("budget deliberately exhausted"),
        new SearchResult(
            Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 1, 4097, 0, 0, 1, 0,
            List.of("candidate budget exceeded")),
        "budget-replay").evidence();
    assertEquals(Verdict.UNCERTAIN, evidence.verdict());
  }

  @TestFactory
  Stream<DynamicTest> accumulatorThresholdMatrixIsDeterministic() {
    return IntStream.rangeClosed(1, 6).mapToObj(threshold -> dynamic(
        "impossible-threshold-" + threshold,
        () -> {
          Player base = anchor();
          var evidence = Phase8MovementValidation.validate(
              "acc-" + threshold,
              40,
              base,
              Player.initial(new Maths.Vec3(20, 64, .5)),
              WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build(),
              "acc-world",
              stable(),
              List.of("known impossible movement"),
              possible(base, 40),
              "acc-replay").evidence();

          var config = new Phase8MovementValidation.Config(threshold, 0, true, true);
          var accumulator = Phase8MovementValidation.Accumulator.empty();
          Phase8MovementValidation.Alert alert = null;
          for (int i = 0; i < threshold; i++) {
            var accepted = accumulator.accept(evidence, config);
            accumulator = accepted.state();
            if (accepted.alert().isPresent()) {
              alert = accepted.alert().orElseThrow();
            }
          }
          assertNotNull(
              alert,
              "threshold=" + threshold + " should alert after exactly threshold impossible observations");
          assertEquals(threshold, accumulator.players()
              .get("acc-" + threshold + "/MOVEMENT_REACHABILITY")
              .supportingImpossible());
        }));
  }

  @Test
  void accumulatorUncertaintyBreaksEveryStreak() {
    Player base = anchor();
    var impossible = Phase8MovementValidation.validate(
        "uncertainty-break",
        40,
        base,
        Player.initial(new Maths.Vec3(20, 64, .5)),
        WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build(),
        "acc-world",
        stable(),
        List.of("known impossible movement"),
        possible(base, 40),
        "acc-replay").evidence();
    var uncertain = Phase8MovementValidation.validate(
        "uncertainty-break",
        41,
        base,
        base,
        WorldSnapshot.emptyOverworld12111(),
        "unknown-world",
        new Validation.SyncWindow(39, 42, true, List.of("world missing")),
        List.of("world coverage missing"),
        new SearchResult(
            Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 0, 1, 0, 0, 1, 0,
            List.of("world coverage incomplete")),
        "acc-replay-uncertain").evidence();

    var config = new Phase8MovementValidation.Config(3, 0, true, true);
    var accumulator = Phase8MovementValidation.Accumulator.empty();
    for (int i = 0; i < 2; i++) accumulator = accumulator.accept(impossible, config).state();
    var accepted = accumulator.accept(uncertain, config);
    assertTrue(accepted.alert().isEmpty());
    var state = accepted.state().players().get("uncertainty-break/MOVEMENT_REACHABILITY");
    assertNotNull(state);
    assertEquals(0, state.consecutiveImpossible());
    assertEquals(2, state.supportingImpossible());
    assertEquals(1, state.uncertaintyPeriods());
  }

  @TestFactory
  Stream<DynamicTest> timingPerturbationsRemainConservative() {
    record Case(String name, List<RawPacket> packets) {}
    List<Case> cases = List.of(
        new Case(
            "gap",
            List.of(
                new RawPacket(1, 0L, new Move(new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
                new RawPacket(2, 400_000_000L, new Move(new Maths.Vec3(4.5, 64, .5), 0f, 0f, true, 8L)))),
        new Case(
            "reorder",
            List.of(
                new RawPacket(1, 0L, new Move(new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
                new RawPacket(3, 50_000_000L, new Move(new Maths.Vec3(.8, 64, .5), 0f, 0f, true, 3L)),
                new RawPacket(2, 60_000_000L, new Move(new Maths.Vec3(1.0, 64, .5), 0f, 0f, true, 2L)))),
        new Case(
            "duplicate",
            List.of(
                new RawPacket(1, 0L, new Move(new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
                new RawPacket(2, 50_000_000L, new Move(new Maths.Vec3(.7, 64, .5), 0f, 0f, true, 1L)),
                new RawPacket(2, 50_000_001L, new Move(new Maths.Vec3(.7, 64, .5), 0f, 0f, true, 1L)))));

    return cases.stream().map(testCase -> dynamic(
        "timing-" + testCase.name(),
        () -> {
          var timeline = capture(testCase.packets());
          var timing = Phase7Timing.reconstruct(timeline, exactTiming());
          assertNotEquals(
              Phase7Timing.Consistency.INCONSISTENT,
              timing.consistency(),
              timing.canonicalText());
          assertTrue(
              timing.frames().stream().anyMatch(frame -> frame.timing().uncertain()
                  || !frame.timing().windows().isEmpty()),
              timing.canonicalText());
        }));
  }
}
