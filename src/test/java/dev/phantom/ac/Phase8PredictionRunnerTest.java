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
                new Maths.Vec3(.5, 64, .5), 45f, 15f, true, 2L))),
        floorWorld(), anchor, 20L);

    assertFalse(second.results().stream().anyMatch(
        result -> result.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE),
        second.toString());
    assertTrue(second.candidateFrontierRetained(), second.toString());
  }

  @Test
  void stalePredictionRebasesToFreshCausalAuthority() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    var first = runner.process(
        "stale-root",
        List.of(
            new RawPacket(1, 10, new ClientTickEnd()),
            new RawPacket(2, 20, new Move(
                new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor(), 0L);

    assertEquals(1, first.movementObservations());
    assertTrue(first.candidateFrontierRetained(), first.toString());

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO,
        false, false, false, List.of());

    List<RawPacket> catchUp = new ArrayList<>();
    catchUp.add(new RawPacket(3, 30, authority,
        Packets.CaptureProvenance.fromAdapter("test-authority", authority, 100L, 10L)));
    for (int i = 4; i <= 12; i++) {
      catchUp.add(new RawPacket(i, 30L + i, new ClientTickEnd()));
    }
    catchUp.add(new RawPacket(13, 200, new Move(
        new Maths.Vec3(.6, 64, .5), 0f, 0f, true, 10L)));

    var second = runner.process(
        "stale-root", catchUp, floorWorld(), anchor(), 0L);

    assertTrue(second.candidateFrontierRetained(), second.toString());
    assertTrue(second.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.startsWith("ROOT_REFRESH reason=PREDICTION_LAG")),
        second.frames().toString());
    assertTrue(second.results().stream()
        .flatMap(result -> result.evidence().uncertaintySources().stream())
        .noneMatch(reason -> reason.contains("initial state carries explicit uncertainty")),
        second.results().toString());
  }

  @Test
  void stationaryObservationRebasesStalePredictionBeforeComparing() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    runner.process(
        "stale-stationary",
        List.of(
            new RawPacket(1, 10, new ClientTickEnd()),
            new RawPacket(2, 20, new Move(
                new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor(), 0L);

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO,
        false, false, false, List.of());

    List<RawPacket> catchUp = new ArrayList<>();
    catchUp.add(new RawPacket(3, 30, authority,
        Packets.CaptureProvenance.fromAdapter("test-authority", authority, 100L, 10L)));
    for (int i = 4; i <= 12; i++) {
      catchUp.add(new RawPacket(i, 30L + i, new ClientTickEnd()));
    }
    catchUp.add(new RawPacket(13, 200, new Move(
        new Maths.Vec3(.5, 64, .5), 25f, 12f, true, 10L)));

    var report = runner.process(
        "stale-stationary", catchUp, floorWorld(), anchor(), 0L);

    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE),
        report.results().toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.startsWith("ROOT_REFRESH reason=PREDICTION_LAG")),
        report.frames().toString());
    assertTrue(report.results().stream()
        .flatMap(result -> result.evidence().uncertaintySources().stream())
        .noneMatch(reason -> reason.contains("Phase 5 could not deterministically simulate")),
        report.results().toString());
  }

  @Test
  void staleAirborneAuthorityResyncPredictsObservedNextTick() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    runner.process(
        "stale-airborne",
        List.of(
            new RawPacket(1, 10, new ClientTickEnd()),
            new RawPacket(2, 20, new Move(
                new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor(), 0L);

    double authorityY = 72.7531999805212;
    double authorityVy = 0.33319999363422365;
    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(false, false, false),
        new Maths.Vec3(.5, authorityY, .5),
        new Maths.Vec3(0.0, authorityVy, 0.0),
        false, false, false, List.of());

    double observedY = authorityY + authorityVy * Vanilla12111RichPhysics.AIR_VERTICAL_DRAG
        - Vanilla12111RichPhysics.GRAVITY * Vanilla12111RichPhysics.AIR_VERTICAL_DRAG;

    var report = runner.process(
        "stale-airborne",
        List.of(
            new RawPacket(3, 30, authority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", authority, 100L, 152L)),
            new RawPacket(4, 200, new Move(
                new Maths.Vec3(.5, observedY, .5), 25f, 8f, false, 152L))),
        floorWorld(), anchor(), 0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(), report.results().toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.startsWith("ROOT_REFRESH reason=PREDICTION_LAG")),
        report.frames().toString());
    assertTrue(report.results().stream()
        .flatMap(result -> result.evidence().uncertaintySources().stream())
        .noneMatch(reason -> reason.contains("support block is unavailable")
            || reason.contains("Phase 5 could not deterministically simulate")),
        report.results().toString());
    assertTrue(report.candidateFrontierRetained(), report.toString());
  }

  @Test
  void staleAuthorityDoesNotEraseRetainedHorizontalMomentum() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    Player movingAnchor = new Player(
        new Maths.Vec3(.5, 71.0, .5),
        new Maths.Vec3(.1, -0.0784000015258789, 0.0),
        0f, 0f, false, "survival", Map.of(), OptionalInt.empty(), false,
        Optional.empty(), Simulation.Attributes.DEFAULT, Pose.STANDING,
        State.Environment.DRY, State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

    var first = runner.process(
        "stale-horizontal",
        List.of(
            new RawPacket(1, 10, new ClientTickEnd()),
            new RawPacket(2, 20, new Move(
                new Maths.Vec3(.6, 70.92159999847412, .5), 0f, 0f, false, 1L))),
        floorWorld(), movingAnchor, 0L);

    assertEquals(1, first.movementObservations());
    assertTrue(first.candidateFrontierRetained(), first.toString());

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(false, false, false),
        new Maths.Vec3(.6, 70.92159999847412, .5),
        new Maths.Vec3(0.0, -0.0784000015258789, 0.0),
        false, false, false, List.of());

    double retainedVx = first.frames().getFirst().predictedAfter().iterator().next()
        .context().player().velocity().x();
    double observedX = .6 + retainedVx;

    var second = runner.process(
        "stale-horizontal",
        List.of(
            new RawPacket(3, 30, authority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", authority, 100L, 10L)),
            new RawPacket(4, 200, new Move(
                new Maths.Vec3(observedX, 70.76636799697876, .5), 0f, 0f, false, 10L))),
        floorWorld(), movingAnchor, 0L);

    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        second.results().getFirst().verdict(), second.results().toString());
    assertTrue(second.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.startsWith("ROOT_REFRESH reason=PREDICTION_LAG")),
        second.frames().toString());
  }

  @Test
  void staleResyncUsesInputFromTheSimulatedTickNotTheLatestInput() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    double movementSpeed = 0.1;
    PlayerContext authority = new PlayerContext(
        "survival", new Simulation.Attributes(movementSpeed), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(.5, 64.0, .5), Maths.Vec3.ZERO,
        false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(1, 10, authority,
        Packets.CaptureProvenance.fromAdapter(
            "test-authority", authority, 100L, 0L)));
    packets.add(new RawPacket(2, 20, new ClientTickEnd()));

    // This input is held from client tick 1 through tick 3.
    packets.add(new RawPacket(3, 30, new ClientInput(
        true, false, false, false, false, false, true)));
    packets.add(new RawPacket(4, 40, new Move(
        new Maths.Vec3(.5, 64.0, .5), 0f, 0f, true, 1L)));

    // Advance to tick 4, then change input. The stale resync for the tick-4
    // movement must simulate tick 3 with the earlier forward+sprint input.
    packets.add(new RawPacket(5, 50, new ClientTickEnd()));
    packets.add(new RawPacket(6, 60, new ClientTickEnd()));
    packets.add(new RawPacket(7, 70, new ClientTickEnd()));
    packets.add(new RawPacket(8, 80, new ClientInput(
        false, true, false, false, false, false, true)));
    packets.add(new RawPacket(9, 90, authority,
        Packets.CaptureProvenance.fromAdapter(
            "test-authority", authority, 104L, 4L)));

    double expectedZ = .5 + movementSpeed * 1.3 * Vanilla12111RichPhysics.WALK_ACCEL;
    packets.add(new RawPacket(10, 100, new Move(
        new Maths.Vec3(.5, 64.0, expectedZ), 0f, 0f, true, 4L)));

    var report = runner.process(
        "input-history",
        packets,
        floorWorld(), anchor(), 0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(), report.results().toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.startsWith("ROOT_REFRESH reason=PREDICTION_LAG")),
        report.frames().toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.contains("FRONTIER_COMMITTED")),
        report.frames().toString());
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
        result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE),
        report.results().toString());
    assertTrue(report.results().stream().noneMatch(
        result -> result.evidence().uncertaintySources().stream()
            .anyMatch(source -> source.contains("Phase 5 could not deterministically simulate"))),
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
                new Maths.Vec3(.6, 64, .5), 15f, 0f, true, 1L))),
        floorWorld(), anchor(), 20L);

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
