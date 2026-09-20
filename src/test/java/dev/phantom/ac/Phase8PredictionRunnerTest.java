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
    assertFalse(first.candidateFrontierRetained(), first.toString());
    assertEquals(0, runner.candidateCount());

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
    Move staleMovement = new Move(
        new Maths.Vec3(.6, 64, .5), 0f, 0f, true, 10L);
    catchUp.add(new RawPacket(
        13, 200, staleMovement,
        Packets.CaptureProvenance.fromAdapter(
            "test-movement", staleMovement, 100L, 10L)));

    var second = runner.process(
        "stale-root", catchUp, floorWorld(), anchor(), 0L);

    assertFalse(second.candidateFrontierRetained(), second.toString());
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

    // The captured server velocity is converted to the Phase 5 client-tick boundary
    // before the next movement step is simulated.
    double observedY = authorityY
        + (authorityVy - Vanilla12111RichPhysics.GRAVITY)
            * Vanilla12111RichPhysics.AIR_VERTICAL_DRAG;

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
  void staleAirborneAuthorityConvertsCapturedVelocityToClientBoundary() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    runner.process(
        "stale-fall",
        List.of(
            new RawPacket(1, 10, new ClientTickEnd()),
            new RawPacket(2, 20, new Move(
                new Maths.Vec3(.5, 64, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor(), 0L);

    double authorityY = 66.92159999847412;
    double authorityVy = -0.15523200634002686;
    double observedY = authorityY
        + (authorityVy - Vanilla12111RichPhysics.GRAVITY)
            * Vanilla12111RichPhysics.AIR_VERTICAL_DRAG;

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(false, false, false),
        new Maths.Vec3(.5, authorityY, .5),
        new Maths.Vec3(0.0, authorityVy, 0.0),
        false, false, false, List.of());

    var report = runner.process(
        "stale-fall",
        List.of(
            new RawPacket(3, 30, authority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", authority, 200L, 7L)),
            new RawPacket(4, 200, new Move(
                new Maths.Vec3(.5, observedY, .5), 0f, 0f, false, 8L))),
        floorWorld(), anchor(), 0L);

    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(), report.results().toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.predictedAfter().stream())
        .anyMatch(candidate -> Math.abs(
            candidate.context().player().position().y() - observedY) < 1.0E-9),
        report.frames().toString());
    assertTrue(report.results().stream()
        .flatMap(result -> result.evidence().uncertaintySources().stream())
        .noneMatch(reason -> reason.contains("Phase 5 could not deterministically simulate")),
        report.results().toString());
  }

  @Test
  void jumpOnlyGroundTickDoesNotRequireSupportFrictionData() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(.5, 64.0, .5), new Maths.Vec3(0.0, -0.0784000015258789, 0.0),
        false, false, false, List.of());

    var report = runner.process(
        "jump-only",
        List.of(
            new RawPacket(1, 10, authority,
                Packets.CaptureProvenance.fromAdapter("test-authority", authority, 100L, 0L)),
            new RawPacket(2, 20, new ClientInput(
                false, false, false, false, true, false, false)),
            new RawPacket(3, 30, new ClientTickEnd()),
            new RawPacket(4, 200, new Move(
                new Maths.Vec3(.5, 64.41999998688698, .5), 0f, 0f, false, 1L))),
        floorWorld(), anchor(), 0L);

    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(), report.results().toString());
    assertTrue(report.results().getFirst().evidence().uncertaintySources().stream()
        .noneMatch(reason -> reason.contains("support block is unavailable")
            || reason.contains("Phase 5 could not deterministically simulate")),
        report.results().toString());
    assertTrue(report.candidateFrontierRetained(), report.toString());
  }

  @Test
  void staleResyncUsesClientObservedHorizontalBoundaryAfterGroundJump() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    var emptyWorld = WorldSnapshot.builder(Contracts.TARGET_VERSION).build();

    PlayerContext initialAuthority = new PlayerContext(
        "survival", new Simulation.Attributes(0.1), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(.5, 64.0, .5), Maths.Vec3.ZERO,
        false, false, false, List.of());

    // Tick 2 is a jump launched from a known stone floor. Its displacement is
    // intentionally retained as observed evidence because the stale prediction
    // cannot simulate the incomplete empty world.
    double jumpX = .5
        + new Simulation.Attributes(0.1).value()
            * Vanilla12111RichPhysics.FRICTION_SPEED_FACTOR
            / Math.pow(0.6, 3.0)
            * Vanilla12111RichPhysics.INPUT_FRICTION;
    double rootHorizontalX =
        (jumpX - .5) * (0.6 * Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION);
    double sprintAirAcceleration =
        Vanilla12111RichPhysics.SPRINT_AIR_ACCEL * Vanilla12111RichPhysics.INPUT_FRICTION;
    double authorityX = jumpX + 0.05;
    double observedX = authorityX + rootHorizontalX + sprintAirAcceleration;
    double authorityY = 64.41999998688698;
    double authorityVy = 0.41999998688697815;
    double observedY = authorityY
        + (authorityVy - Vanilla12111RichPhysics.GRAVITY)
            * Vanilla12111RichPhysics.AIR_VERTICAL_DRAG;

    PlayerContext staleAuthority = new PlayerContext(
        "survival", new Simulation.Attributes(0.1), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(false, false, false),
        new Maths.Vec3(authorityX, authorityY, .5),
        new Maths.Vec3(0.0, authorityVy, 0.0),
        false, false, false, List.of());

    List<RawPacket> packets = List.of(
        new RawPacket(1, 10, initialAuthority,
            Packets.CaptureProvenance.fromAdapter("test-authority", initialAuthority, 100L, 0L)),
        new RawPacket(2, 20, new Move(
            new Maths.Vec3(.5, 64.0, .5), 0f, 0f, true, 0L)),
        new RawPacket(3, 30, new Move(
            new Maths.Vec3(.5, 64.0, .5), 0f, 0f, true, 1L)),
        new RawPacket(4, 40, new ClientInput(
            false, false, false, true, true, false, false)),
        new RawPacket(5, 50, new Move(
            new Maths.Vec3(jumpX, authorityY, .5), 0f, 0f, false, 2L)),
        new RawPacket(6, 60, new ClientInput(
            false, false, false, true, false, false, true)),
        new RawPacket(7, 70, staleAuthority,
            Packets.CaptureProvenance.fromAdapter("test-authority", staleAuthority, 103L, 3L)),
        new RawPacket(8, 200, new Move(
            new Maths.Vec3(observedX, observedY, .5), 0f, 0f, false, 3L)));

    var report = runner.processWithWorldProvider(
        "stale-horizontal-boundary",
        packets,
        sequence -> sequence >= 8 ? floorWorld() : emptyWorld,
        anchor(),
        0L);

    assertEquals(4, report.movementObservations(), report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(), report.results().toString());
    assertFalse(report.results().get(2).evidence().uncertaintySources().isEmpty(),
        report.results().toString());
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

    double expectedZ = .5 + movementSpeed * Vanilla12111RichPhysics.SPRINTING_SPEED_MULTIPLIER
        * Vanilla12111RichPhysics.WALK_ACCEL;
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
  void staleServerVelocityDoesNotFalseFlagSprintJumpBootstrap() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    WorldSnapshot world = floorWorld();
    Player realStart = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(.25, 0.0, 0.0),
        270f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        new Simulation.Attributes(.1), Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    var jump = physics.step(new Vanilla12111RichPhysics.Context(
        1,
        realStart,
        new Simulation.AdvancedInput(1, 0, true, true, false),
        world,
        Simulation.Environment.DRY,
        realStart.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        MovementEnvironment.dry(true, true, false),
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of())));
    Player observedJump = jump.state();

    PlayerContext staleAuthority = new PlayerContext(
        "survival", new Simulation.Attributes(.1), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, true, false),
        realStart.position(), Maths.Vec3.ZERO,
        false, false, false, List.of());

    List<RawPacket> packets = List.of(
        new RawPacket(1, 10, staleAuthority,
            Packets.CaptureProvenance.fromAdapter("paper-live", staleAuthority, 100L, 0L)),
        new RawPacket(2, 20, new ClientTickEnd()),
        new RawPacket(3, 30, new ClientInput(
            true, false, false, false, true, false, true)),
        new RawPacket(4, 40, new ClientTickEnd()),
        // Establish the authoritative root at tick 1, then deliver the actual
        // position movement in the same tick. The bootstrap must replace the
        // stale server velocity before the movement is simulated.
        new RawPacket(5, 50, new Move(
            null, observedJump.yaw(), observedJump.pitch(), true, 2L)),
        new RawPacket(6, 60, new Move(
            observedJump.position(), observedJump.yaw(), observedJump.pitch(),
            observedJump.onGround(), 2L)));

    var report = runner.process(
        "stale-server-velocity-sprint-jump",
        packets, world, anchor(), 0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().get(0).verdict(),
        report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().get(1).verdict(),
        report.results().toString());
    assertTrue(report.frames().stream()
        .filter(frame -> frame.movement().position() != null)
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.startsWith("FRONTIER_BOOTSTRAPPED")),
        report.frames().toString());
  }

  @Test
  void sprintJumpAddsVanillaHorizontalImpulse() {
    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();

    Player state = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        Maths.Vec3.ZERO,
        0f, 0f, true, "survival", Map.of(), OptionalInt.empty(), false,
        Optional.empty(), new Simulation.Attributes(0.13), Pose.STANDING,
        State.Environment.DRY, State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

    var world = floorWorld();
    var noSprint = physics.step(new Vanilla12111RichPhysics.Context(
        0, state, new Simulation.AdvancedInput(1, 0, true, false, false),
        world, Simulation.Environment.DRY, state.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false,
        dev.phantom.ac.world.EntityCollisions.of(List.of())));

    var sprint = physics.step(new Vanilla12111RichPhysics.Context(
        0, state, new Simulation.AdvancedInput(1, 0, true, true, false),
        world, Simulation.Environment.DRY, state.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, true, false), false,
        dev.phantom.ac.world.EntityCollisions.of(List.of())));

    double positionDelta = sprint.state().position().z() - noSprint.state().position().z();
    double movementSpeed = state.attributes().value();
    double expectedPositionDelta = Vanilla12111RichPhysics.SPRINT_JUMP_HORIZONTAL_BOOST
        + movementSpeed * (Vanilla12111RichPhysics.SPRINTING_SPEED_MULTIPLIER - 1.0)
            * Vanilla12111RichPhysics.INPUT_FRICTION;
    double velocityDelta = sprint.state().velocity().z() - noSprint.state().velocity().z();
    assertEquals(
        expectedPositionDelta * Vanilla12111RichPhysics.GROUND_FRICTION,
        velocityDelta,
        1e-7,
        "sprint jump post-tick velocity");
    assertEquals(
        expectedPositionDelta,
        positionDelta,
        1e-8,
        "sprint jump displacement");
    assertEquals(
        Vanilla12111RichPhysics.JUMP,
        noSprint.state().position().y() - state.position().y(), 1e-7);
    assertEquals(
        Vanilla12111RichPhysics.JUMP,
        sprint.state().position().y() - state.position().y(), 1e-7);
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
  @Test
  void earlierBatchSequenceGapDoesNotPoisonLaterExplicitTickMovement() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    var report = runner.process(
        "scoped-gap",
        List.of(
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            // Sequence 2 is absent, but the movement below is itself captured
            // in-order and carries an explicit client tick.
            new RawPacket(3, 30, new ClientTickEnd()),
            new RawPacket(4, 60, new Move(
                new Maths.Vec3(20.5, 64, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor(), 20L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().getFirst().verdict(), report.results().toString());
    assertTrue(report.results().getFirst().evidence().eliminationReason()
            .contains("all exhaustively modeled legitimate candidates"),
        report.results().toString());
    assertTrue(report.results().getFirst().evidence().uncertaintySources().isEmpty(),
        report.results().toString());
  }

  @Test
  void phase7TimingUncertaintyCannotBecomeDecisiveViaExplicitPacketTick() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 0),
        250_000_000L, 3, 128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    var report = runner.process(
        "phase7-uncertainty",
        List.of(
            new RawPacket(1, 0, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            new RawPacket(2, 50_000_000L, new ClientTickEnd()),
            // Sequence 3 is intentionally missing; the explicit client tick must
            // not bypass Phase 7's chronology uncertainty.
            new RawPacket(4, 100_000_000L, new Move(
                new Maths.Vec3(.6, 64, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor(), 0L);

    assertEquals(Phase8MovementValidation.Verdict.UNCERTAIN,
        report.results().getFirst().verdict(), report.results().toString());
    assertTrue(report.results().getFirst().evidence().uncertaintySources().stream()
        .anyMatch(reason -> reason.contains("Phase 7 timing envelope")),
        report.results().toString());
  }


  @Test
  void explicitClientTickTimingRangeIsExhaustivelyEvaluated() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    var report = runner.process(
        "explicit-timing-range",
        List.of(
            new RawPacket(1, 10, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                new Maths.Vec3(.5, 64.0, .5), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            new RawPacket(2, 60, new Move(
                new Maths.Vec3(.5, 64.0, .5), 0f, 0f, true, 0L)),
            new RawPacket(3, 110, new Move(
                new Maths.Vec3(20.5, 64.0, .5), 0f, 0f, true, 1L))),
        floorWorld(), anchor(), 0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().getLast().verdict(), report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("TIMING_OFFSETS range=0..1")
            && line.contains("exhaustive=true")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("TIMING_OFFSET target=0")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("TIMING_OFFSET target=1")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("TIMING_GATE explicitRangeExhaustive=")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("PHASE7_WINDOWS ")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("PHASE7_REASONS ")),
        report.frames().getLast().trace().toString());
  }

  @Test
  void explicitClientTickRemainsExhaustiveAfterTimingHistoryTruncation() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    Player authorityState = anchor();

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        authorityState.position(), authorityState.velocity(),
        false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(
        1, 10L, authority,
        Packets.CaptureProvenance.fromAdapter("test-authority", authority, 0L, 0L)));

    // Establish a live prediction frontier at the start of the long-lived session.
    packets.add(new RawPacket(
        2, 20L, new Move(authorityState.position(), 0f, 0f, true, 1L)));

    // Force the runner's bounded 512-event timing history to truncate while keeping
    // a fresh causal authority immediately before the eventual movement observation.
    long sequence = 3L;
    for (long clientTick = 2L; clientTick <= 516L; clientTick++) {
      packets.add(new RawPacket(
          sequence++, 20L + clientTick, authority,
          Packets.CaptureProvenance.fromAdapter(
              "test-authority", authority, clientTick, clientTick)));
    }

    // One tick of legitimate movement cannot reach this position.
    Move impossibleMove = new Move(
        new Maths.Vec3(20.5, 64.0, 0.5), 0f, 0f, true, 516L);
    packets.add(new RawPacket(
        sequence, 10_000L, impossibleMove,
        Packets.CaptureProvenance.fromAdapter(
            "test-movement", impossibleMove, 517L, 516L)));

    var report = runner.process(
        "explicit-timing-after-truncation",
        packets,
        floorWorld(),
        anchor(),
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(
        report.results().getLast().evidence().uncertaintySources().stream()
            .noneMatch(reason -> reason.contains("timing envelope retains chronology uncertainty")),
        report.results().toString());
  }

  @Test
  void freshAuthorityResyncUsesMovementBoundaryNotNonAtomicClientWatermark() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    Player authorityState = anchor();
    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        authorityState.position(), authorityState.velocity(),
        false, false, false, List.of());

    runner.process(
        "fresh-authority-boundary",
        List.of(
            new RawPacket(
                1, 10L, authority,
                Packets.CaptureProvenance.fromAdapter("test-authority", authority, 0L, 0L)),
            new RawPacket(
                2, 20L, new Move(authorityState.position(), 0f, 0f, true, 1L))),
        floorWorld(),
        anchor(),
        0L);

    Move boundaryMove = new Move(
        authorityState.position(), 0f, 0f, true, 100L);
    var report = runner.process(
        "fresh-authority-boundary",
        List.of(
            new RawPacket(
                3, 30L, authority,
                Packets.CaptureProvenance.fromAdapter("test-authority", authority, 100L, 10L)),
            new RawPacket(
                4, 40L, boundaryMove,
                Packets.CaptureProvenance.fromAdapter(
                    "test-movement", boundaryMove, 100L, 100L))),
        floorWorld(),
        anchor(),
        0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(
        report.frames().stream()
            .flatMap(frame -> frame.trace().stream())
            .anyMatch(line -> line.contains("ROOT_REFRESH reason=PREDICTION_LAG")
                && line.contains("rootTick=99")
                && line.contains("clientWatermarkUsedForSpatialRoot=false")),
        report.frames().toString());
  }

  @Test
  void falseImpossibleDoesNotPoisonNextFreshAuthoritativeObservation() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    Player authorityState = anchor();
    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        authorityState.position(), authorityState.velocity(),
        false, false, false, List.of());

    var first = runner.process(
        "fresh-witness-after-contradiction",
        List.of(
            new RawPacket(
                1, 10L, authority,
                Packets.CaptureProvenance.fromAdapter("test-authority", authority, 0L, 0L)),
            new RawPacket(
                2, 20L,
                new Move(authorityState.position(), 0f, 0f, true, 1L)),
            new RawPacket(
                3, 30L,
                new Move(new Maths.Vec3(20.5, 64.0, 0.5), 0f, 0f, true, 2L))),
        floorWorld(),
        anchor(),
        0L);

    assertEquals(
        Phase8MovementValidation.Verdict.IMPOSSIBLE,
        first.results().getLast().verdict(),
        first.results().toString());
    assertEquals(0, runner.candidateCount(), first.toString());

    Move freshObservedMovement = new Move(
        authorityState.position(), 0f, 0f, true, 3L);
    var second = runner.process(
        "fresh-witness-after-contradiction",
        List.of(
            new RawPacket(
                4, 40L, authority,
                Packets.CaptureProvenance.fromAdapter("test-authority", authority, 3L, 3L)),
            new RawPacket(
                5, 50L, freshObservedMovement,
                Packets.CaptureProvenance.fromAdapter(
                    "test-movement", freshObservedMovement, 3L, 3L))),
        floorWorld(),
        anchor(),
        0L);

    assertEquals(1, second.movementObservations(), second.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        second.results().getFirst().verdict(),
        second.results().toString());
    assertTrue(
        second.frames().getFirst().trace().stream()
            .anyMatch(line -> line.contains("AUTHORITATIVE_ZERO_DELTA_WITNESS")),
        second.frames().toString());
    assertTrue(second.candidateFrontierRetained(), second.toString());
  }

}