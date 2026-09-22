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
  void groundedEdgeTransitionFeedsFallingVelocityIntoNextClientTick() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var edgeWorld = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 63, 0, stone)
        .build();

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(.5, 64.0, .5), Maths.Vec3.ZERO,
        false, false, false, List.of());

    List<RawPacket> packets = List.of(
        new RawPacket(1, 10, authority,
            Packets.CaptureProvenance.fromAdapter(
                "test-authority", authority, 100L, 0L)),
        new RawPacket(2, 20, new Move(
            new Maths.Vec3(1.4, 64.0, .5), 0f, 0f, false, 1L)),
        new RawPacket(3, 30, new Move(
            new Maths.Vec3(1.8914000141620635, 63.92159999847412, .5),
            0f, 0f, false, 2L)));

    var report = runner.process(
        "edge-fall",
        packets,
        edgeWorld,
        anchor(),
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(), report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("FRONTIER_COMMITTED")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().predictedAfter().stream()
        .anyMatch(candidate -> !candidate.context().player().onGround()
            && Math.abs(candidate.context().player().position().y()
                - 63.92159999847412) <= 1.0E-9),
        report.frames().getLast().toString());
  }

  @Test
  void groundClaimMismatchDoesNotBecomeMovementImpossible() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var edgeWorld = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 63, 0, stone)
        .build();

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(.9, 0.0, 0.0),
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        start.position(), start.velocity(),
        false, false, false, List.of());

    var report = runner.process(
        "ground-claim",
        List.of(
            new RawPacket(1, 10, authority),
            new RawPacket(2, 20, new Move(
                new Maths.Vec3(1.4, 64.0, .5), 0f, 0f, true, 1L))),
        edgeWorld,
        start,
        0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.UNCERTAIN,
        report.results().getFirst().verdict(), report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.startsWith("GROUND_CLAIM_MISMATCH")),
        report.frames().toString());
    assertTrue(report.candidateFrontierRetained(), report.toString());
  }

  @Test
  void bootstrapIgnoresClientGroundClaimWhenPhysicalReplayIsAirborne() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();
    var environment = MovementEnvironment.dry(false, false, false);

    Player start = new Player(
        new Maths.Vec3(.5, 70.0, .5),
        new Maths.Vec3(.0, -0.1, .08),
        0f, 0f, false, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    var physics = new Vanilla12111RichPhysics();
    Player next = physics.step(new Vanilla12111RichPhysics.Context(
        0L,
        start,
        new Simulation.AdvancedInput(0, 0, false, false, false),
        world,
        Simulation.Environment.DRY,
        start.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        environment,
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of())))
        .state();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(), Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    var report = runner.process(
        "bootstrap-ground-claim",
        List.of(
            new RawPacket(1, 10L, authority),
            new RawPacket(2, 20L, new Move(
                start.position(), 0f, 0f, false, 0L)),
            new RawPacket(3, 30L, authority),
            new RawPacket(4, 40L, new Move(
                next.position(), 0f, 0f, true, 1L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertTrue(
        report.results().getLast().verdict()
            != Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().toString());
    assertTrue(
        report.results().getLast().evidence().uncertaintySources().stream()
            .anyMatch(reason -> reason.contains("client ground claim differs")),
        report.results().toString());
    assertTrue(
        report.frames().getLast().trace().stream()
            .anyMatch(line -> line.startsWith("GROUND_CLAIM_SEPARATED bootstrapPhysicalGround=false")),
        report.frames().getLast().toString());
    assertTrue(
        report.frames().getLast().predictedAfter().stream()
            .anyMatch(candidate ->
                Math.abs(candidate.context().player().position().x() - next.position().x()) <= 1.0E-9
                    && Math.abs(candidate.context().player().position().y() - next.position().y()) <= 1.0E-9
                    && Math.abs(candidate.context().player().position().z() - next.position().z()) <= 1.0E-9
                    && !candidate.context().player().onGround()),
        report.frames().getLast().toString());
  }

  @Test
  void groundClaimMismatchDoesNotPoisonFollowingAirborneTick() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 63, 0, stone)
        .build();

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(.9, 0.0, 0.0),
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    var environment = MovementEnvironment.dry(true, false, false);
    var physics = new Vanilla12111RichPhysics();

    var first = physics.step(new Vanilla12111RichPhysics.Context(
        0L, start, new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, start.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, environment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    var airborneEnvironment = MovementEnvironment.dry(false, false, false);
    var second = physics.step(new Vanilla12111RichPhysics.Context(
        1L, first, new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, first.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, airborneEnvironment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext firstAuthority = new PlayerContext(
        "survival", start.attributes(), Map.of(), Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    PlayerContext secondAuthority = new PlayerContext(
        "survival", first.attributes(), Map.of(), Pose.STANDING, airborneEnvironment,
        first.position(), first.velocity(), false, false, false, List.of());

    var report = runner.process(
        "ground-claim-followed-by-airborne",
        List.of(
            new RawPacket(1, 10L, firstAuthority),
            new RawPacket(2, 20L, new Move(
                first.position(), 0f, 0f, true, 1L)),
            new RawPacket(3, 30L, new ClientTickEnd()),
            new RawPacket(4, 40L, secondAuthority),
            new RawPacket(5, 50L, new Move(
                second.position(), 0f, 0f, false, 2L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.UNCERTAIN,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(
        report.results().getFirst().evidence().uncertaintySources().stream()
            .anyMatch(reason -> reason.contains("client ground claim differs")),
        report.results().toString());
    assertTrue(
        report.results().getLast().verdict()
            != Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().toString());
    assertTrue(
        report.frames().getLast().predictedAfter().stream()
            .anyMatch(candidate ->
                Math.abs(candidate.context().player().position().x() - second.position().x()) <= 1.0E-9
                    && Math.abs(candidate.context().player().position().y() - second.position().y()) <= 1.0E-9
                    && Math.abs(candidate.context().player().position().z() - second.position().z()) <= 1.0E-9),
        report.frames().getLast().toString());
  }

  @Test
  void airborneNeutralContinuationCanRecoverInputBoundaryFalsePositive() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();

    Player start = new Player(
        new Maths.Vec3(.5, 70.0, .5),
        new Maths.Vec3(0.0, 0.16477328182606651, -0.11550728500250669),
        0f, 0f, false, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    var environment = MovementEnvironment.dry(false, false, false);
    var first = physics.step(new Vanilla12111RichPhysics.Context(
        0, start, new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, start.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, environment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    var second = physics.step(new Vanilla12111RichPhysics.Context(
        1, first, new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, start.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        environment, false, dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(), Pose.STANDING, environment,
        first.position(), first.velocity(), false, false, false, List.of());

    var report = runner.process(
        "airborne-boundary",
        List.of(
            new RawPacket(1, 10, new PlayerContext(
                "survival", start.attributes(), Map.of(), Pose.STANDING, environment,
                start.position(), start.velocity(), false, false, false, List.of())),
            new RawPacket(2, 20, new ClientTickEnd()),
            new RawPacket(3, 30, new Move(first.position(), 0f, 0f, false, 1L)),
            new RawPacket(4, 40, new ClientInput(
                false, false, false, true, false, false, false)),
            new RawPacket(5, 50, authority),
            new RawPacket(6, 60, new Move(second.position(), 0f, 0f, false, 2L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertTrue(report.results().getLast().verdict()
            != Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().toString());
    assertTrue(report.frames().getLast().predictedAfter().stream()
        .anyMatch(candidate -> Math.abs(
            candidate.context().player().position().y() - second.position().y()) <= 1.0E-9
            && Math.abs(candidate.context().player().position().z() - second.position().z()) <= 1.0E-9),
        report.frames().getLast().toString());
  }

  @Test
  void playerInputSprintKeyDoesNotImplyActualMovementSprint() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    var world = floorWorld();
    var movementEnvironment = MovementEnvironment.dry(true, false, false);
    var effects = Phase5Mechanics.MovementEffects.NONE;
    var attrs = new Simulation.Attributes(0.1);
    var input = new Simulation.AdvancedInput(1, 0, false, false, false);

    Player start = new Player(
        new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO,
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.of(input), attrs,
        Pose.STANDING, State.Environment.DRY, State.TickRange.exact(0),
        State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    var first = physics.step(new Vanilla12111RichPhysics.Context(
        0, start, input, world, Simulation.Environment.DRY, attrs,
        effects, Pose.STANDING, movementEnvironment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    var second = physics.step(new Vanilla12111RichPhysics.Context(
        1, first, input, world, Simulation.Environment.DRY, attrs,
        effects, Pose.STANDING, movementEnvironment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext authority = new PlayerContext(
        "survival", attrs, Map.of(), Pose.STANDING, movementEnvironment,
        start.position(), start.velocity(), false, false, false, List.of());

    var report = runner.process(
        "sprint-key-vs-state",
        List.of(
            new RawPacket(1, 10, authority),
            new RawPacket(2, 20, new ClientInput(
                true, false, false, false, false, false, true)),
            new RawPacket(3, 30, new ClientTickEnd()),
            new RawPacket(4, 40, new Move(first.position(), 0f, 0f, true, 1L)),
            new RawPacket(5, 50, new ClientTickEnd()),
            new RawPacket(6, 60, new Move(second.position(), 0f, 0f, true, 2L))),
        world, start, 0L);

    assertEquals(2, report.movementObservations(), report.toString());
    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() != Phase8MovementValidation.Verdict.IMPOSSIBLE),
        report.toString());
  }

  @Test
  void bootstrapRetainsSprintCandidateWhenServerMovementStateLagsHeldSprintKey() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    var world = floorWorld();
    var sprintEnvironment = MovementEnvironment.dry(true, true, false);
    var authorityEnvironment = MovementEnvironment.dry(true, false, false);
    var effects = Phase5Mechanics.MovementEffects.NONE;
    var attrs = new Simulation.Attributes(0.1);
    var input = new Simulation.AdvancedInput(1, 0, false, true, false);

    Player start = new Player(
        new Maths.Vec3(.5, 64, .5), Maths.Vec3.ZERO,
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.of(input), attrs,
        Pose.STANDING, State.Environment.DRY, State.TickRange.exact(0),
        State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    var first = physics.step(new Vanilla12111RichPhysics.Context(
        1, start, input, world, Simulation.Environment.DRY, attrs,
        effects, Pose.STANDING, sprintEnvironment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    var second = physics.step(new Vanilla12111RichPhysics.Context(
        2, first, input, world, Simulation.Environment.DRY, attrs,
        effects, Pose.STANDING, sprintEnvironment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext authority = new PlayerContext(
        "survival", attrs, Map.of(), Pose.STANDING, authorityEnvironment,
        start.position(), start.velocity(), false, false, false, List.of());

    var report = runner.process(
        "sprint-state-lag",
        List.of(
            new RawPacket(1, 10, authority),
            new RawPacket(2, 20, new ClientTickEnd()),
            new RawPacket(3, 30, new ClientTickEnd()),
            new RawPacket(4, 40, new ClientTickEnd()),
            new RawPacket(5, 50, new ClientTickEnd()),
            new RawPacket(6, 60, new ClientInput(
                true, false, false, false, false, false, true)),
            new RawPacket(7, 70, new ClientTickEnd()),
            new RawPacket(8, 80, new Move(first.position(), 0f, 0f, true, 6L)),
            new RawPacket(9, 90, new ClientTickEnd()),
            new RawPacket(10, 100, new Move(second.position(), 0f, 0f, true, 7L))),
        world, start, 0L);

    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() != Phase8MovementValidation.Verdict.IMPOSSIBLE),
        report.toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.startsWith("BOOTSTRAP_LOCOMOTION_OPTIONS")
            && line.contains("alternatives=[MovementInputState[sprinting=false, sneaking=false], MovementInputState[sprinting=true, sneaking=false]]")),
        report.frames().toString());
  }

  @Test
  void authorityOverlayDoesNotEraseClientPhysicalSprintState() {
    MovementEnvironment client = MovementEnvironment.dry(true, true, false);
    MovementEnvironment authority = MovementEnvironment.dry(true, false, false);

    MovementEnvironment merged =
        Phase8PredictionRunner.preserveClientLocomotionState(client, authority, true);

    assertTrue(merged.sprinting(), merged.toString());
    assertFalse(merged.sneaking(), merged.toString());
    assertEquals(authority.fluid(), merged.fluid());
    assertEquals(authority.gravityMultiplier(), merged.gravityMultiplier());
  }

  @Test
  void impossibleObservationDoesNotPoisonNextFreshAuthoritativeObservation() {
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
    assertFalse(second.candidateFrontierRetained(), second.toString());
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
  void spatialRebaseBootstrapsObservedMovementInsteadOfReusingRebasedVelocity() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();
    MovementEnvironment environment = MovementEnvironment.dry(true, false, false);
    Player anchor = anchor();

    PlayerContext initialAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, environment, anchor.position(), anchor.velocity(),
        false, false, false, List.of());

    var firstObserved = new Move(
        new Maths.Vec3(0.6, 64.0, 0.5), 0f, 0f, true, 1L);

    /*
     * PlayerContext is server authority evidence and does not rewrite the live
     * client position. Use an additional position-bearing packet at the same
     * client tick to establish the observed pre-movement position that the next
     * tick must spatially rebase onto.
     */
    PlayerContext rebasedAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, environment, new Maths.Vec3(0.605, 64.0, 0.5),
        Maths.Vec3.ZERO, false, false, false, List.of());

    var intermediateObserved = new Move(
        new Maths.Vec3(0.605, 64.0, 0.5), 0f, 0f, true, 1L);
    var finalObserved = new Move(
        new Maths.Vec3(0.725, 64.0, 0.5), 0f, 0f, true, 2L);

    var report = runner.process(
        "spatial-rebase-bootstrap",
        List.of(
            new RawPacket(1, 10L, initialAuthority),
            new RawPacket(2, 20L, firstObserved),
            new RawPacket(3, 30L, intermediateObserved),
            new RawPacket(4, 35L, rebasedAuthority),
            new RawPacket(5, 40L, finalObserved)),
        world, anchor, 0L);

    assertEquals(3, report.movementObservations(), report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(), report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("FRONTIER_SPATIAL_REBASE")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("CLIENT_MOVEMENT_BOOTSTRAP")
            && line.contains("reconstructedStartVelocityVerified=true")),
        report.frames().getLast().trace().toString());
    assertFalse(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("FRONTIER_RESET reason=OBSERVATION_CONTRADICTION")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().predictedAfter().stream()
        .anyMatch(candidate -> Math.abs(
            candidate.context().player().position().x() - finalObserved.position().x()) <= 1.0E-9),
        report.frames().getLast().toString());
  }

  @Test
  void suppressedStationaryObservationDoesNotRemainUncertain() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L,
        50_000_000L,
        50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 100_000_000L),
        new Phase7Timing.LatencyBounds(0L, 100_000_000L),
        new Phase7Timing.TickDelayBounds(0L, 1L),
        new Phase7Timing.TickDelayBounds(0L, 1L),
        250_000_000L,
        3,
        128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    Player start = anchor();
    PlayerContext matchingAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        start.position(), Maths.Vec3.ZERO,
        false, false, false, List.of());

    PlayerContext unrelatedAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(5.0, 64.0, 5.0), Maths.Vec3.ZERO,
        false, false, false, List.of());

    var report = runner.process(
        "suppressed-stationary-observation",
        List.of(
            new RawPacket(1, 10L, matchingAuthority),
            new RawPacket(2, 20L, new Move(start.position(), 0f, 0f, true, 1L)),
            new RawPacket(3, 30L, unrelatedAuthority),
            new RawPacket(4, 40L, new Move(start.position(), 15f, 8f, true, 2L))),
        floorWorld(),
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(
        report.results().getLast().evidence().uncertaintySources().isEmpty(),
        report.results().getLast().evidence().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("FRONTIER_SUPPRESSED_OBSERVATION")
            && line.contains("result=POSSIBLE")
            && line.contains("positionBearing=true")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("CLIENT_TICK 2")
            && line.contains("timingUncertain=false")),
        report.frames().getLast().trace().toString());
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
  void staleResyncBootstrapsObservedMovementBeforeUsingServerVelocity() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();
    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        Maths.Vec3.ZERO,
        98.1202f, 32.717106f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(1), State.Provenance.UNKNOWN, Set.of());

    // Establish a live frontier that is deliberately stale before the next
    // position-bearing movement arrives.
    runner.process(
        "stale-resync-bootstrap",
        List.of(
            new RawPacket(1, 10L, new ClientTickEnd()),
            new RawPacket(2, 20L, new Move(
                start.position(), start.yaw(), start.pitch(), true, 1L))),
        world, start, 0L);

    Player realStart = new Player(
        start.position(),
        new Maths.Vec3(-.12, 0.0, -.03),
        start.yaw(), start.pitch(), true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        start.attributes(), Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(9), State.Provenance.UNKNOWN, Set.of());
    Vanilla12111RichPhysics.StepResult step = new Vanilla12111RichPhysics().step(
        new Vanilla12111RichPhysics.Context(
            9L,
            realStart,
            new Simulation.AdvancedInput(0, 0, false, false, false),
            world,
            Simulation.Environment.DRY,
            realStart.attributes(),
            Phase5Mechanics.MovementEffects.NONE,
            Pose.STANDING,
            MovementEnvironment.dry(true, false, false),
            false,
            dev.phantom.ac.world.EntityCollisions.of(List.of())));

    PlayerContext staleAuthority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        start.position(), Maths.Vec3.ZERO,
        false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(
        3, 30L, staleAuthority,
        Packets.CaptureProvenance.fromAdapter(
            "test-authority", staleAuthority, 100L, 10L)));
    for (int i = 4; i <= 12; i++) {
      packets.add(new RawPacket(i, 30L + i, new ClientTickEnd()));
    }
    Move observed = new Move(
        step.state().position(), step.state().yaw(), step.state().pitch(),
        step.state().onGround(), 10L);
    packets.add(new RawPacket(
        13, 200L, observed,
        Packets.CaptureProvenance.fromAdapter(
            "test-movement", observed, 100L, 10L)));

    var report = runner.process(
        "stale-resync-bootstrap",
        packets, world, start, 0L);

    assertNotEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().getLast().verdict(), report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("ROOT_REFRESH reason=PREDICTION_LAG")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("CLIENT_MOVEMENT_BOOTSTRAP")
            && line.contains("reconstructedStartVelocityVerified=true")),
        report.frames().getLast().trace().toString());
    assertTrue(report.candidateFrontierRetained(), report.toString());
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
        - new Simulation.Attributes(0.1).value()
            * Vanilla12111RichPhysics.FRICTION_SPEED_FACTOR
            / Math.pow(0.6, 3.0)
            * Vanilla12111RichPhysics.INPUT_FRICTION;
    double rootHorizontalX =
        (jumpX - .5) * (0.6 * Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION);
    double sprintAirAcceleration =
        -Vanilla12111RichPhysics.SPRINT_AIR_ACCEL * Vanilla12111RichPhysics.INPUT_FRICTION;
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
  void staleResyncBootstrapsObservedMovementAtTheSimulationTick() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    double movementSpeed = 0.1;
    PlayerContext authority = new PlayerContext(
        "survival", new Simulation.Attributes(movementSpeed), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, true, false),
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
    // movement must bootstrap the observed movement from its tick-3 boundary.
    packets.add(new RawPacket(5, 50, new ClientTickEnd()));
    packets.add(new RawPacket(6, 60, new ClientTickEnd()));
    packets.add(new RawPacket(7, 70, new ClientTickEnd()));
    packets.add(new RawPacket(8, 80, new ClientInput(
        false, true, false, false, false, false, true)));
    packets.add(new RawPacket(9, 90, authority,
        Packets.CaptureProvenance.fromAdapter(
            "test-authority", authority, 104L, 4L)));

    double expectedZ = .5 - movementSpeed * Vanilla12111RichPhysics.SPRINTING_SPEED_MULTIPLIER
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
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("BOOTSTRAP_START simulationTick=3")
            && line.contains("input=AdvancedInput[forward=-1, strafe=0, jump=false, sprint=true, sneak=false]")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("BOOTSTRAP_START simulationTick=3")
            && line.contains("selectedSeq=3")),
        report.frames().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("CLIENT_MOVEMENT_BOOTSTRAP")
            && line.contains("reconstructedStartVelocityVerified=true")),
        report.frames().getLast().trace().toString());
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
            Packets.CaptureProvenance.fromAdapter("paper-live", staleAuthority, 0L, 2L)),
        new RawPacket(2, 20, new ClientTickEnd()),
        new RawPacket(3, 30, new ClientInput(
            true, false, false, false, true, false, true)),
        new RawPacket(4, 40, new ClientTickEnd()),
        // Establish the authoritative root and deliver the actual position
        // movement in the same observed tick. Bootstrap must replace the stale
        // server velocity before physics is used for the recovered frontier.
        new RawPacket(5, 50, new Move(
            observedJump.position(), observedJump.yaw(), observedJump.pitch(),
            observedJump.onGround(), 2L)));

    var report = runner.process(
        "stale-server-velocity-sprint-jump",
        packets, world, anchor(), 0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().get(0).verdict(),
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
  void uncertainMovementStillMarksSameTickForSubTickGuard() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    var emptyWorld = WorldSnapshot.builder(Contracts.TARGET_VERSION).build();

    var report = runner.process(
        "uncertain-subtick",
        List.of(
            new RawPacket(1, 20, new PlayerContext(
                "survival", Simulation.Attributes.DEFAULT, Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                anchor().position(), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            new RawPacket(2, 30, new ClientTickEnd()),
            new RawPacket(3, 60, new Move(
                new Maths.Vec3(.6, 64, .5), 0f, 0f, true, 1L)),
            new RawPacket(4, 65, new Move(
                new Maths.Vec3(.7, 64, .5), 0f, 0f, true, 1L))),
        emptyWorld, anchor(), 20L);

    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN),
        report.results().toString());
    assertTrue(report.frames().stream().anyMatch(frame -> frame.trace().stream()
        .anyMatch(line -> line.contains("SUB_TICK_TRAJECTORY_UNMODELED"))),
        report.frames().toString());
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
    assertTrue(report.candidateFrontierRetained(), report.toString());
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
    assertTrue(second.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("FRONTIER_CLEARED")
            && line.contains("observation-witness-is-not-a-physics-root")),
        second.frames().getFirst().trace().toString());
    assertFalse(second.candidateFrontierRetained(), second.toString());
  }


  @Test
  void authoritativeObservationWitnessCannotBecomeNonAtomicPhysicsRoot() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    WorldSnapshot emptyWorld = WorldSnapshot.builder(Contracts.TARGET_VERSION).build();
    WorldSnapshot knownWorld = floorWorld();
    double witnessY = 69.25220334025373;
    double nextY = 69.17675927506424;
    double nonAtomicWitnessVelocity = 0.08307781780646721;
    double boundaryVelocity = 0.00301626150904258;

    Player start = new Player(
        new Maths.Vec3(.5, witnessY, .5),
        Maths.Vec3.ZERO,
        0f, 0f, false, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(4), State.Provenance.UNKNOWN, Set.of());

    PlayerContext witnessAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(false, false, false),
        new Maths.Vec3(.5, witnessY, .5),
        new Maths.Vec3(0.0, nonAtomicWitnessVelocity, 0.0),
        false, false, false, List.of());

    PlayerContext nextAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(false, false, false),
        new Maths.Vec3(.5, witnessY, .5),
        new Maths.Vec3(0.0, boundaryVelocity, 0.0),
        false, false, false, List.of());

    Move firstMovement = new Move(
        new Maths.Vec3(.5, witnessY, .5), 0f, 0f, false, 5L);
    Move secondMovement = new Move(
        new Maths.Vec3(.5, nextY, .5), 0f, 0f, false, 6L);

    var report = runner.processWithWorldProvider(
        "authority-witness-frontier",
        List.of(
            new RawPacket(1, 10L, new ClientTickEnd()),
            new RawPacket(2, 20L, witnessAuthority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", witnessAuthority, 10L, 5L)),
            new RawPacket(3, 30L, firstMovement,
                Packets.CaptureProvenance.fromAdapter(
                    "test-movement", firstMovement, 10L, 5L)),
            new RawPacket(4, 35L, new Move(
                null, 15f, 20f, null, 5L),
                Packets.CaptureProvenance.fromAdapter(
                    "test-look", new Move(null, 15f, 20f, null, 5L), 10L, 5L)),
            new RawPacket(5, 40L, nextAuthority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", nextAuthority, 11L, 6L)),
            new RawPacket(6, 50L, secondMovement,
                Packets.CaptureProvenance.fromAdapter(
                    "test-movement", secondMovement, 11L, 6L))),
        sequence -> sequence <= 3 ? emptyWorld : knownWorld,
        start,
        0L);

    assertEquals(3, report.movementObservations(), report.toString());
    assertEquals(3, report.results().size(), report.results().toString());
    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE),
        report.results().toString());

    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("FRONTIER_CLEARED")
            && line.contains("observation-witness-is-not-a-physics-root")),
        report.frames().getFirst().trace().toString());

    assertTrue(report.frames().get(1).trace().stream()
        .anyMatch(line -> line.contains("FRONTIER_ROOT_SUPPRESSED")
            && line.contains("positionBearing=false")),
        report.frames().get(1).trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("CLIENT_MOVEMENT_BOOTSTRAP")
            && line.contains("reconstructedStartVelocityVerified=true")),
        report.frames().getLast().trace().toString());
    assertTrue(report.candidateFrontierRetained(), report.toString());
  }

  @Test
  void inputGeneratedBeforeTickEndIsAppliedToThatEarlierSimulationTick() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L,
        50_000_000L,
        50_000_000L,
        new Phase7Timing.LatencyBounds(50_000_000L, 50_000_000L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 1L),
        new Phase7Timing.TickDelayBounds(0L, 1L),
        250_000_000L,
        3,
        128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    WorldSnapshot world = floorWorld();
    Player start = anchor();
    var input = new Simulation.AdvancedInput(1, 0, false, false, false);
    var environment = MovementEnvironment.dry(true, false, false);
    var expected = new Vanilla12111RichPhysics().step(
        new Vanilla12111RichPhysics.Context(
            0L,
            start,
            input,
            world,
            Simulation.Environment.DRY,
            start.attributes(),
            Phase5Mechanics.MovementEffects.NONE,
            Pose.STANDING,
            environment,
            false,
            dev.phantom.ac.world.EntityCollisions.of(List.of())))
        .state()
        .position();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    var report = runner.process(
        "phase7-input-timing",
        List.of(
            new RawPacket(1, 10L, authority),
            new RawPacket(2, 50_000_000L, new ClientTickEnd()),
            new RawPacket(3, 120_000_000L, new ClientInput(
                true, false, false, false, false, false, false)),
            new RawPacket(4, 130_000_000L, new Move(
                expected, 0f, 0f, true, 2L)),
            new RawPacket(5, 150_000_000L, new ClientTickEnd())),
        world,
        start,
        0L);

    assertEquals(1, report.movementObservations(), report.toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(), report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("simulationTick=1")
            && line.contains("OptionalInt[1]")),
        report.frames().getFirst().trace().toString());
  }


  @Test
  void truncatedTimingHistoryPreservesAbsoluteClientTickForHeldInput() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        250_000_000L, 3, 128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    WorldSnapshot world = floorWorld();
    Player start = anchor();
    MovementEnvironment environment = MovementEnvironment.dry(true, false, false);
    Simulation.AdvancedInput forward = new Simulation.AdvancedInput(1, 0, false, false, false);

    Maths.Vec3 expected = new Vanilla12111RichPhysics().step(
        new Vanilla12111RichPhysics.Context(
            41L,
            start,
            forward,
            world,
            Simulation.Environment.DRY,
            start.attributes(),
            Phase5Mechanics.MovementEffects.NONE,
            Pose.STANDING,
            environment,
            false,
            dev.phantom.ac.world.EntityCollisions.of(List.of())))
        .state()
        .position();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    for (int i = 1; i <= 520; i++) {
      packets.add(new RawPacket(i, i * 1_000_000L, authority));
    }
    packets.add(new RawPacket(521, 521_000_000L,
        new ClientInput(true, false, false, false, false, false, false)));
    packets.add(new RawPacket(522, 522_000_000L, new ClientTickEnd()));
    packets.add(new RawPacket(523, 523_000_000L,
        new ClientInput(true, false, false, false, true, false, false)));
    packets.add(new RawPacket(524, 524_000_000L,
        new Move(expected, 0f, 0f, true, 42L)));

    var report = runner.process(
        "truncated-input-origin",
        packets,
        world,
        start,
        0L);

    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() != Phase8MovementValidation.Verdict.IMPOSSIBLE),
        report.toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.equals("INPUT_TICK_ORIGIN known=true offset=41")),
        report.frames().toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.contains("inputSelection=selectedSeq=521,selectedTick=41")
            && line.contains("jump=Optional[false]")),
        report.frames().toString());
  }


  @Test
  void truncatedTimingHistoryRecoversOriginWhenRetainedExplicitMovePredatesBoundary() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        250_000_000L, 3, 128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    WorldSnapshot world = floorWorld();
    Player start = anchor();
    MovementEnvironment environment = MovementEnvironment.dry(true, false, false);
    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    long sequence = 1L;
    for (int i = 0; i < 520; i++) {
      packets.add(new RawPacket(sequence++, (i + 1L) * 1_000_000L, authority));
    }

    /*
     * This explicit move is the earliest retained client event after the bounded
     * history truncates. Its clientTick is absolute to the connection, while the
     * retained Phase 7 reconstruction starts a new relative boundary clock.
     */
    Move retainedAbsoluteMove = new Move(
        start.position(), 0f, 0f, true, 100L);
    packets.add(new RawPacket(
        sequence++, 2_500_000_000L, retainedAbsoluteMove));

    for (int i = 0; i < 9; i++) {
      packets.add(new RawPacket(
          sequence++, 2_550_000_000L + i * 50_000_000L, new ClientTickEnd()));
    }

    packets.add(new RawPacket(
        sequence++, 3_000_000_000L,
        new ClientInput(false, false, false, false, false, false, false)));

    Move finalMove = new Move(
        start.position(), 0f, 0f, true, 109L);
    packets.add(new RawPacket(
        sequence, 3_050_000_000L, finalMove));

    var report = runner.process(
        "truncated-explicit-anchor",
        packets,
        world,
        start,
        0L);

    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.equals("INPUT_TICK_ORIGIN known=true offset=100")),
        report.frames().getLast().trace().toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(), report.results().toString());
  }


  @Test
  void truncatedTimingHistoryWithUnrecoverableOriginCannotProduceImpossible() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        250_000_000L, 3, 128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    WorldSnapshot world = floorWorld();
    Player start = anchor();
    MovementEnvironment environment = MovementEnvironment.dry(true, false, false);
    Simulation.AdvancedInput forward = new Simulation.AdvancedInput(1, 0, false, false, false);

    Maths.Vec3 firstPosition = new Vanilla12111RichPhysics().step(
        new Vanilla12111RichPhysics.Context(
            0L, start, forward, world, Simulation.Environment.DRY,
            start.attributes(), Phase5Mechanics.MovementEffects.NONE,
            Pose.STANDING, environment, false,
            dev.phantom.ac.world.EntityCollisions.of(List.of())))
        .state()
        .position();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(1, 1_000_000L, authority));
    packets.add(new RawPacket(2, 2_000_000L,
        new ClientInput(true, false, false, false, false, false, false)));
    packets.add(new RawPacket(3, 3_000_000L, new ClientTickEnd()));
    packets.add(new RawPacket(4, 4_000_000L,
        new Move(firstPosition, 0f, 0f, true, 1L)));

    for (int i = 5; i <= 520; i++) {
      packets.add(new RawPacket(i, i * 1_000_000L, authority));
    }

    packets.add(new RawPacket(521, 521_000_000L,
        new ClientInput(true, false, false, false, false, false, false)));
    packets.add(new RawPacket(522, 522_000_000L,
        new Move(null, 0f, 0f, null, 100L)));
    packets.add(new RawPacket(523, 523_000_000L, new ClientTickEnd()));
    packets.add(new RawPacket(524, 524_000_000L,
        new Move(
            new Maths.Vec3(firstPosition.x() + 0.1, firstPosition.y(), firstPosition.z()),
            0f, 0f, true, 42L)));

    var report = runner.process(
        "truncated-unrecoverable-origin",
        packets,
        world,
        start,
        0L);

    assertEquals(Phase8MovementValidation.Verdict.UNCERTAIN,
        report.results().getLast().verdict(), report.toString());
    assertTrue(report.results().getLast().evidence().uncertaintySources().stream()
        .anyMatch(reason -> reason.contains("absolute client-tick origin is not recoverable")),
        report.results().getLast().evidence().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.equals("INPUT_TICK_ORIGIN known=false offset=0")),
        report.frames().getLast().trace().toString());
  }


  @Test
  void truncatedTimingHistoryRecoversOriginDespiteUnrelatedOutOfOrderPacket() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        250_000_000L,
        3,
        128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    WorldSnapshot world = floorWorld();
    Player start = anchor();
    MovementEnvironment environment = MovementEnvironment.dry(true, false, false);
    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        start.position(), start.velocity(), false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(1L, 1_000_000L, new ClientTickEnd()));
    packets.add(new RawPacket(2L, 2_000_000L,
        new Move(start.position(), 0f, 0f, true, 1L)));

    long sequence = 3L;
    long baseNanos = 10_000_000L;
    for (int i = 0; i < 510; i++) {
      long received = baseNanos + i;
      if (i == 250) received += 2_000L;
      if (i == 251) received -= 2_000L;
      packets.add(new RawPacket(sequence++, received, authority));
    }

    for (int i = 0; i < 10; i++) {
      packets.add(new RawPacket(
          sequence++, 30_000_000L + i * 50_000_000L, new ClientTickEnd()));
    }

    packets.add(new RawPacket(
        sequence++, 470_000_000L,
        new ClientInput(true, false, false, false, false, false, false)));

    Player expectedState = start;
    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    for (long simulationTick = 0L; simulationTick <= 10L; simulationTick++) {
      Simulation.AdvancedInput stepInput = simulationTick >= 9L
          ? new Simulation.AdvancedInput(1, 0, false, false, false)
          : new Simulation.AdvancedInput(0, 0, false, false, false);
      expectedState = physics.step(
          new Vanilla12111RichPhysics.Context(
              simulationTick,
              expectedState,
              stepInput,
              world,
              Simulation.Environment.DRY,
              expectedState.attributes(),
              Phase5Mechanics.MovementEffects.NONE,
              Pose.STANDING,
              environment,
              false,
              dev.phantom.ac.world.EntityCollisions.of(List.of())))
          .state();
    }

    Move observed = new Move(expectedState.position(), 0f, 0f, true, 11L);
    packets.add(new RawPacket(sequence, 521_000_000L, observed));

    var report = runner.process(
        "truncated-origin-out-of-order",
        packets,
        world,
        start,
        0L);

    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("INPUT_TICK_ORIGIN known=true")),
        report.frames().getLast().trace().toString());
    assertTrue(report.results().getLast().verdict()
            != Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().toString());
  }


}