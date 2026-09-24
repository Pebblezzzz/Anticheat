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
  void stationaryPositionObservationUsesObservedWitnessNotStalePhysicsFrontier() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();

    Player start = new Player(
        new Maths.Vec3(5.5, 64.0, 5.5),
        Maths.Vec3.ZERO,
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(), Simulation.Attributes.DEFAULT,
        Pose.STANDING, State.Environment.DRY, State.TickRange.exact(0),
        State.Provenance.UNKNOWN, Set.of());

    PlayerContext staleAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(.5, 64.0, .5), Maths.Vec3.ZERO,
        false, false, false, List.of());

    var report = runner.process(
        "stationary-observation-witness",
        List.of(
            new RawPacket(1L, 10L, staleAuthority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", staleAuthority, 100L, 0L)),
            new RawPacket(2L, 20L, new Move(
                new Maths.Vec3(5.5, 64.0, 5.5),
                0f, 0f, true, 1L))),
        world,
        start,
        0L);

    assertEquals(1, report.movementObservations(), report.toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("OBSERVATION stationary-position packet retained=")),
        report.frames().getFirst().trace().toString());
    assertTrue(report.frames().getFirst().predictedBefore().stream()
        .anyMatch(candidate -> candidate.provenance().input()
            .equals("AUTHORITATIVE_ANCHOR")),
        report.frames().getFirst().predictedBefore().toString());
    assertTrue(report.frames().getFirst().predictedAfter().isEmpty(),
        report.frames().getFirst().predictedAfter().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .noneMatch(line -> line.contains("FRONTIER_RESET reason=OBSERVATION_CONTRADICTION")),
        report.frames().getFirst().trace().toString());
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
  void uncertainMovementBoundaryKeepsPreviousHeldInputAsAlternative() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();

    Player start = new Player(
        new Maths.Vec3(.5, 70.0, .5),
        new Maths.Vec3(0.0, -0.1, 0.08),
        0f, 0f, false, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    var environment = MovementEnvironment.dry(false, false, false);
    var entityCollisions = dev.phantom.ac.world.EntityCollisions.of(List.of());

    Player first = physics.step(new Vanilla12111RichPhysics.Context(
        0L, start,
        new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, start.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, environment, false,
        entityCollisions)).state();

    Player second = physics.step(new Vanilla12111RichPhysics.Context(
        1L, first,
        new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, first.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, environment, false,
        entityCollisions)).state();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(), Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    var report = runner.process(
        "uncertain-input-continuation",
        List.of(
            new RawPacket(1, 10L, authority),
            new RawPacket(2, 20L, new ClientTickEnd()),
            new RawPacket(3, 30L, new Move(
                first.position(), 0f, 0f, false, 1L)),
            new RawPacket(4, 40L, new ClientInput(
                false, false, false, false, true, false, true)),
            // Missing packet sequence 5 deliberately leaves the movement
            // boundary chronologically uncertain.
            new RawPacket(6, 60L, new Move(
                second.position(), 0f, 0f, false, 2L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertNotEquals(
        Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(report.results().getLast().evidence().uncertaintySources().stream()
        .anyMatch(reason -> reason.contains("Phase 7 timing")),
        report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("INPUT_STATE")
            && line.contains("forward=OptionalInt[0]")
            && line.contains("sprint=Optional[true]")),
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
  void jumpInputIsNotAppliedToThePrecedingExplicitMovementTick() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();
    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(0.0, 0.0, 0.1),
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    var neutral = new Simulation.AdvancedInput(0, 0, false, false, false);
    Player groundedStep = physics.step(new Vanilla12111RichPhysics.Context(
        0L,
        start,
        neutral,
        world,
        Simulation.Environment.DRY,
        start.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    var jumpInput = new Simulation.AdvancedInput(0, 0, true, false, false);
    Player jumped = physics.step(new Vanilla12111RichPhysics.Context(
        1L,
        groundedStep,
        jumpInput,
        world,
        Simulation.Environment.DRY,
        start.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        start.position(), start.velocity(), false, false, false, List.of());

    var report = runner.process(
        "jump-boundary",
        List.of(
            new RawPacket(1L, 10L, authority),
            new RawPacket(2L, 20L, new ClientInput(
                false, false, false, false, true, false, false)),
            new RawPacket(3L, 30L, new Move(
                groundedStep.position(), 0f, 0f, true, 1L)),
            new RawPacket(4L, 40L, new Move(
                jumped.position(), 0f, 0f, false, 2L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertEquals(groundedStep.position(),
        report.frames().getFirst().observedAfter().position());
    assertEquals(jumped.position(),
        report.frames().getLast().observedAfter().position());
  }

  @Test
  void jumpBoundaryRetainsPreviousHeldStateWhenInputAndMovementShareTheBoundary() {
    Phase7Timing.Config exactTiming = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        250_000_000L, 3, 128);
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, exactTiming);
    WorldSnapshot world = floorWorld();

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(.1, 0.0, 0.0),
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        start.position(), start.velocity(), false, false, false, List.of());

    Simulation.AdvancedInput neutral = new Simulation.AdvancedInput(0, 0, false, false, false);
    Player observed = new Vanilla12111RichPhysics().step(
        new Vanilla12111RichPhysics.Context(
            0L, start, neutral, world, Simulation.Environment.DRY,
            start.attributes(), Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
            MovementEnvironment.dry(true, false, false), false,
            dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    var report = runner.process(
        "jump-boundary-shared-tick",
        List.of(
            new RawPacket(1L, 10L, authority),
            new RawPacket(2L, 20L, new ClientInput(
                false, false, false, false, true, false, false)),
            new RawPacket(3L, 30L, new ClientTickEnd()),
            new RawPacket(4L, 40L, new Move(
                observed.position(), 0f, 0f, true, 1L))),
        world, start, 0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(
        report.frames().getFirst().trace().stream()
            .anyMatch(line -> line.contains("SIM_INPUT_OPTIONS")
                && line.contains("jump=Optional[true]")
                && line.contains("jump=Optional[false]")),
        report.frames().getFirst().trace().toString());
  }

  @Test
  void lateClientInputUsesItsPhase7SimulationTickEvenWhenItArrivesAfterMovement() {
    Phase7Timing.Config exactTiming = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        250_000_000L, 3, 128);
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, exactTiming);
    WorldSnapshot world = floorWorld();

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(.1, 0.0, 0.0),
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        start.position(), start.velocity(), false, false, false, List.of());

    Simulation.AdvancedInput neutral = new Simulation.AdvancedInput(0, 0, false, false, false);
    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Player first = physics.step(new Vanilla12111RichPhysics.Context(
        0L, start, neutral, world, Simulation.Environment.DRY,
        start.attributes(), Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    Simulation.AdvancedInput jump = new Simulation.AdvancedInput(0, 0, true, false, false);
    Player second = physics.step(new Vanilla12111RichPhysics.Context(
        1L, first, jump, world, Simulation.Environment.DRY,
        start.attributes(), Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    CaptureProvenance lateInputProvenance =
        new CaptureProvenance("test", "CLIENT_TO_SERVER", "ClientInput", 0L, 1L);

    var report = runner.process(
        "late-input-causal-tick",
        List.of(
            new RawPacket(1L, 10L, authority),
            new RawPacket(2L, 20L, new ClientTickEnd()),
            new RawPacket(3L, 30L, new Move(
                first.position(), 0f, 0f, true, 1L)),
            new RawPacket(4L, 40L, new Move(
                second.position(), 0f, 0f, false, 2L)),
            new RawPacket(5L, 50L, new ClientInput(
                false, false, false, false, true, false, false),
                lateInputProvenance)),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(
        report.frames().getLast().trace().stream()
            .anyMatch(line -> line.contains("SIM_INPUT_OPTIONS")
                && line.contains("jump=Optional[true]")),
        report.frames().getLast().trace().toString());
  }

  @Test
  void jumpInputGeneratedOnMovementTickIsRetainedAsFinalBoundaryAlternative() {
    Phase7Timing.Config exactTiming = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        250_000_000L, 3, 128);
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, exactTiming);
    WorldSnapshot world = floorWorld();

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(.1, 0.0, 0.0),
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Simulation.AdvancedInput neutral = new Simulation.AdvancedInput(0, 0, false, false, false);
    Player first = physics.step(new Vanilla12111RichPhysics.Context(
        0L, start, neutral, world, Simulation.Environment.DRY,
        start.attributes(), Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    Simulation.AdvancedInput jump = new Simulation.AdvancedInput(0, 0, true, false, false);
    Player second = physics.step(new Vanilla12111RichPhysics.Context(
        1L, first, jump, world, Simulation.Environment.DRY,
        start.attributes(), Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(), Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        start.position(), start.velocity(), false, false, false, List.of());

    var report = runner.process(
        "jump-input-same-client-tick",
        List.of(
            new RawPacket(1L, 10L, authority),
            new RawPacket(2L, 20L, new Move(
                first.position(), 0f, 0f, true, 1L)),
            new RawPacket(3L, 30L, new ClientTickEnd()),
            new RawPacket(4L, 40L, new ClientInput(
                false, false, false, false, true, false, false)),
            new RawPacket(5L, 50L, new Move(
                second.position(), 0f, 0f, false, 2L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(
        report.frames().getLast().trace().stream()
            .anyMatch(line -> line.contains("SIM_INPUT_OPTIONS")
                && line.contains("jump=Optional[true]")
                && line.contains("jump=Optional[false]")),
        report.frames().getLast().trace().toString());
  }

  @Test
  void latestHeldJumpIsRetainedAtFinalBoundaryWhenSimulationEnvelopeIsUnmaterialized() {
    assertTrue(
        Phase8PredictionRunner.shouldOverlayCurrentJumpInput(
            2831L,
            2832L,
            10L,
            11L,
            List.of(),
            Phase7Timing.Range.empty(),
            true,
            List.of(),
            Phase7Timing.Range.empty(),
            true,
            false),
        "a currently held jump received before the movement must remain a final-boundary candidate");
  }

  @Test
  void latestHeldInputIsNotAppliedBeforeItsPhase7SimulationTick() {
    assertFalse(
        Phase8PredictionRunner.shouldOverlayCurrentInput(
            0L, 2L, 4L, 5L, List.of(1L, 2L)),
        "an input that can begin at simulation tick 1 must not be replayed onto tick 0");

    assertTrue(
        Phase8PredictionRunner.shouldOverlayCurrentInput(
            1L, 2L, 4L, 5L, List.of(1L, 2L)),
        "the same held input is valid at the final simulated tick once Phase 7 admits it");

    assertFalse(
        Phase8PredictionRunner.shouldOverlayCurrentInput(
            1L, 2L, 4L, 5L, List.of()),
        "an input with no exhaustively enumerated Phase 7 simulation tick must not be overlaid");
  }

  @Test
  void uncertainMovementTimingKeepsNewestHeldInputAsFinalTickAlternative() {
    assertFalse(
        Phase8PredictionRunner.shouldOverlayCurrentInput(
            0L, 2L, 4L, 5L, List.of(), true),
        "timing uncertainty must not make the held input retroactive");

    assertTrue(
        Phase8PredictionRunner.shouldOverlayCurrentInput(
            1L, 2L, 4L, 5L, List.of(), true),
        "an unresolved movement boundary must retain the newest held input at the final simulated tick");

    assertFalse(
        Phase8PredictionRunner.shouldOverlayCurrentInput(
            1L, 2L, 4L, 5L, List.of(), false),
        "reliable timing still requires the held input's Phase 7 simulation tick");
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
  void closeExhaustiveMismatchReconcilesRetainedFrontierWithoutCascading() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    Player anchor = anchor();

    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        anchor.position(), Maths.Vec3.ZERO,
        false, false, false, List.of());

    var firstObserved = new Move(
        new Maths.Vec3(0.510, 64.0, 0.5), 0f, 0f, true, 1L);

    var first = runner.process(
        "close-reconciliation",
        List.of(
            new RawPacket(1, 10L, authority),
            new RawPacket(2, 20L, firstObserved)),
        floorWorld(),
        anchor,
        0L);

    assertEquals(1, first.movementObservations(), first.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        first.results().getFirst().verdict(),
        first.results().toString());
    assertTrue(first.candidateFrontierRetained(), first.toString());


    var closeObserved = new Move(
        new Maths.Vec3(0.535, 64.0, 0.5), 0f, 0f, true, 2L);

    var second = runner.process(
        "close-reconciliation",
        List.of(new RawPacket(3, 30L, closeObserved)),
        floorWorld(),
        anchor,
        0L);

    assertEquals(1, second.movementObservations(), second.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.UNCERTAIN,
        second.results().getFirst().verdict(),
        second.results().toString());
    assertTrue(second.candidateFrontierRetained(), second.toString());
    assertTrue(second.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("FRONTIER_RECONCILED reason=CLOSE_EXHAUSTIVE_MISMATCH")),
        second.frames().getLast().trace().toString());

    var reconciled =
        second.frames().getLast().predictedAfter().stream().findFirst()
            .orElseThrow();
    assertEquals(closeObserved.position(), reconciled.context().player().position());
    assertTrue(
        !reconciled.context().clientVelocity().equals(Maths.Vec3.ZERO),
        "reconciliation must not re-root the persistent client state from zero server velocity");
    assertNotEquals(
        "AUTHORITATIVE_ANCHOR",
        reconciled.provenance().input(),
        "reconciliation must retain the predicted candidate rather than replacing it with a fresh authority root");
    assertEquals(2L, reconciled.context().simulationTick());
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
  void staleAuthorityDoesNotRelabelRetainedPredictionFrontier() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();
    MovementEnvironment environment = MovementEnvironment.dry(true, false, false);

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(.3, 0.0, 0.0),
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Player first = physics.step(new Vanilla12111RichPhysics.Context(
        0L, start, new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, start.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, environment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    Player second = physics.step(new Vanilla12111RichPhysics.Context(
        1L, first, new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, first.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, environment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    Player third = physics.step(new Vanilla12111RichPhysics.Context(
        2L, second, new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, second.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, environment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    Player fourth = physics.step(new Vanilla12111RichPhysics.Context(
        3L, third, new Simulation.AdvancedInput(0, 0, false, false, false),
        world, Simulation.Environment.DRY, third.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING, environment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext initialAuthority = new PlayerContext(
        "survival", start.attributes(), Map.of(), Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());
    PlayerContext staleAuthority = new PlayerContext(
        "survival", first.attributes(), Map.of(), Pose.STANDING, environment,
        first.position(), first.velocity(), false, false, false, List.of());

    Move firstMove = new Move(first.position(), 0f, 0f, first.onGround(), 1L);
    Move finalMove = new Move(fourth.position(), 0f, 0f, fourth.onGround(), 4L);

    var report = runner.process(
        "stale-authority-frontier",
        List.of(
            new RawPacket(1L, 10L, initialAuthority,
                Packets.CaptureProvenance.fromAdapter("authority", initialAuthority, 0L, 0L)),
            new RawPacket(2L, 20L, firstMove,
                Packets.CaptureProvenance.fromAdapter("move", firstMove, 1L, 1L)),
            new RawPacket(3L, 30L, staleAuthority,
                Packets.CaptureProvenance.fromAdapter("authority", staleAuthority, 0L, 1L)),
            new RawPacket(4L, 40L, new ClientTickEnd()),
            new RawPacket(5L, 50L, new ClientTickEnd()),
            new RawPacket(6L, 60L, new ClientTickEnd()),
            new RawPacket(7L, 70L, finalMove,
                Packets.CaptureProvenance.fromAdapter("move", finalMove, 4L, 4L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(
        report.frames().getLast().trace().stream()
            .anyMatch(line -> line.contains("ROOT_REFRESH_SKIPPED")
                && line.contains("CAUSAL_AUTHORITY_STALE")),
        report.frames().getLast().trace().toString());
    assertTrue(
        report.frames().getLast().trace().stream()
            .noneMatch(line -> line.startsWith("ROOT_REFRESH reason=PREDICTION_LAG")),
        report.frames().getLast().trace().toString());
    assertTrue(
        report.frames().getLast().predictedAfter().stream()
            .anyMatch(candidate ->
                Math.abs(candidate.context().player().position().x() - fourth.position().x()) <= 1.0E-9
                    && Math.abs(candidate.context().player().position().y() - fourth.position().y()) <= 1.0E-9
                    && Math.abs(candidate.context().player().position().z() - fourth.position().z()) <= 1.0E-9),
        report.frames().getLast().toString());
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
  void spatiallyDisconnectedFrontierWithoutFreshAuthorityIsUncertain() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();
    Player start = anchor();

    PlayerContext initialAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        start.position(), start.velocity(), false, false, false, List.of());

    Move firstMove = new Move(
        new Maths.Vec3(.6, 64.0, .5), 0f, 0f, true, 1L);
    Move sameTickCorrection = new Move(
        new Maths.Vec3(1.8, 64.0, .5), 0f, 0f, true, 1L);
    Move nextTickMove = new Move(
        new Maths.Vec3(1.9, 64.0, .5), 0f, 0f, true, 2L);

    var report = runner.process(
        "spatially-disconnected-frontier",
        List.of(
            new RawPacket(
                1L, 10L, initialAuthority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", initialAuthority, 1L, 0L)),
            new RawPacket(
                2L, 20L, firstMove,
                Packets.CaptureProvenance.fromAdapter(
                    "test-movement", firstMove, 1L, 1L)),
            /*
             * This second position-bearing packet is the client-side correction/
             * reconciliation boundary. Phase 8 already treats the sub-tick change
             * as unresolved, so the retained physics frontier is intentionally not
             * rewritten to this observed position.
             */
            new RawPacket(
                3L, 30L, sameTickCorrection,
                Packets.CaptureProvenance.fromAdapter(
                    "test-correction", sameTickCorrection, 1L, 1L)),
            /*
             * The next movement arrives with an older causal authority than the
             * packet itself. An exact client tick must not turn the disconnected
             * spatial frontier into exhaustive IMPOSSIBLE evidence.
             */
            new RawPacket(
                4L, 40L, nextTickMove,
                Packets.CaptureProvenance.fromAdapter(
                    "test-movement", nextTickMove, 10L, 2L))),
        world,
        start,
        0L);

    assertEquals(3, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.UNCERTAIN,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(
        report.frames().getLast().trace().stream()
            .anyMatch(line -> line.startsWith("FRONTIER_SPATIAL_DISCONNECTED")
                && line.contains("UNCERTAIN_UNTIL_CAUSAL_ANCHOR")),
        report.frames().getLast().trace().toString());
    assertTrue(
        report.frames().getLast().trace().stream()
            .anyMatch(line -> line.contains("age=")),
        report.frames().getLast().trace().toString());
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
        new Maths.Vec3(0.6, 64.0, 0.5), Maths.Vec3.ZERO,
        false, false, false, List.of());

    PlayerContext unrelatedAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(5.0, 64.0, 5.0), Maths.Vec3.ZERO,
        false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(1, 10L, matchingAuthority));
    packets.add(new RawPacket(
        2, 20L, new Move(new Maths.Vec3(0.6, 64.0, 0.5), 0f, 0f, true, 1L)));

    for (int i = 0; i < 520; i++) {
      packets.add(new RawPacket(
          3L + i, 30L + i, unrelatedAuthority));
    }

    packets.add(new RawPacket(
        523, 600L, new Move(
            new Maths.Vec3(0.6, 64.0, 0.5), 15f, 8f, true, 2L)));

    var report = runner.process(
        "suppressed-stationary-observation",
        packets,
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
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("TICK_RELIABILITY")),
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
  void futureClientBlockBreakAppliesToEarlierMovementInSameBatch() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var solidWorld = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 63, 0, stone)
        .setBlock(0, 64, 1, stone)
        .build();
    var airWorld = solidWorld.withBlockOverride(0, 64, 1, dev.phantom.ac.world.BlockState.air());

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .65),
        Maths.Vec3.ZERO,
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    var input = new Simulation.AdvancedInput(1, -1, false, true, false);
    Player observed = new Vanilla12111RichPhysics().step(
        new Vanilla12111RichPhysics.Context(
            1L, start, input, airWorld, Simulation.Environment.DRY, start.attributes(),
            Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
            MovementEnvironment.dry(true, true, false), false,
            dev.phantom.ac.world.EntityCollisions.of(List.of())))
        .state();

    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, true, false),
        start.position(), Maths.Vec3.ZERO,
        false, false, false, List.of());

    var breakPacket = new ClientBlockBreak(new dev.phantom.ac.world.Pos(0, 64, 1), 17, 1L);

    var report = runner.processWithWorldProvider(
        "future-block-break",
        List.of(
            new RawPacket(1, 10L, authority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", authority, 100L, 0L)),
            new RawPacket(2, 20L, new ClientInput(
                true, false, true, false, false, false, true)),
            new RawPacket(3, 30L, new Move(
                observed.position(), observed.yaw(), observed.pitch(), observed.onGround(), 1L)),
            new RawPacket(4, 40L, breakPacket)),
        sequence -> solidWorld,
        start,
        0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertNotEquals(
        Phase8MovementValidation.Verdict.IMPOSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("CLIENT_BLOCK_BREAK_PREDICTION")),
        report.frames().getFirst().trace().toString());
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
  void staleResyncUsesRetainedClientVelocityAtMatchingBoundary() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();
    Simulation.AdvancedInput input = new Simulation.AdvancedInput(0, 0, false, false, false);

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        new Maths.Vec3(.2, 0.0, 0.0),
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.of(input),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Player firstObserved = physics.step(new Vanilla12111RichPhysics.Context(
        1L, start, input, world, Simulation.Environment.DRY, start.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    Player nextObserved = physics.step(new Vanilla12111RichPhysics.Context(
        2L, firstObserved, input, world, Simulation.Environment.DRY, firstObserved.attributes(),
        Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext lateAuthority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        firstObserved.position(), Maths.Vec3.ZERO,
        false, false, false, List.of());

    var report = runner.process(
        "stale-resync-retained-client-velocity",
        List.of(
            new RawPacket(1, 10L, new PlayerContext(
                "survival", start.attributes(), Map.of(),
                Pose.STANDING, MovementEnvironment.dry(true, false, false),
                start.position(), Maths.Vec3.ZERO,
                false, false, false, List.of())),
            new RawPacket(2, 20L, new Move(
                firstObserved.position(), 0f, 0f, true, 1L)),
            new RawPacket(3, 30L, new Move(
                firstObserved.position(), 0f, 0f, true, 151L)),
            new RawPacket(
                4, 40L, lateAuthority,
                Packets.CaptureProvenance.fromAdapter(
                    "test-authority", lateAuthority, 100L, 152L)),
            new RawPacket(5, 50L, new Move(
                nextObserved.position(), 0f, 0f, true, 152L))),
        world, start, 0L);

    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getLast().verdict(), report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("ROOT_REFRESH reason=PREDICTION_LAG")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("ROOT_VELOCITY source=retained-client-physics-state")
            && line.contains("tick=151")),
        report.frames().getLast().trace().toString());
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
            && line.contains("input=AdvancedInput[forward=-1, strafe=0, jump=false, sprint=true, sneak=false]")),
        report.frames().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("inputSelection=grim-held-state")),
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
  void stationaryObservationSuppressesRetainedPredictionFrontier() {
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
    assertFalse(report.candidateFrontierRetained(), report.toString());
    assertTrue(report.frames().stream()
        .flatMap(frame -> frame.trace().stream())
        .anyMatch(line -> line.contains("FRONTIER_CLEARED source=STATIONARY_OBSERVATION")),
        report.frames().toString());
  }

  @Test
  void heldJumpRespectsGrimGroundJumpDelay() {
    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    WorldSnapshot world = floorWorld();

    Player state = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        Maths.Vec3.ZERO,
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of(), 2);

    MovementEnvironment environment = MovementEnvironment.dry(true, false, false);
    Simulation.AdvancedInput heldJump =
        new Simulation.AdvancedInput(0, 0, true, false, false);

    Player delayed = physics.step(new Vanilla12111RichPhysics.Context(
        0L,
        state,
        heldJump,
        world,
        Simulation.Environment.DRY,
        state.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        environment,
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    assertEquals(64.0, delayed.position().y(), 1.0E-9);
    assertEquals(1, delayed.jumpDelay());

    Player jumped = physics.step(new Vanilla12111RichPhysics.Context(
        1L,
        delayed,
        heldJump,
        world,
        Simulation.Environment.DRY,
        delayed.attributes(),
        Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING,
        environment,
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    assertEquals(64.42, jumped.position().y(), 1.0E-7);
    assertEquals(10, jumped.jumpDelay());
  }

  @Test
  void heldJumpBootstrapEnumeratesGrimJumpDelay() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    WorldSnapshot world = floorWorld();

    Player start = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        Maths.Vec3.ZERO,
        0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of(), 2);

    MovementEnvironment environment = MovementEnvironment.dry(true, true, false);
    Simulation.AdvancedInput heldJump =
        new Simulation.AdvancedInput(1, 0, true, true, false);
    Player observed = new Vanilla12111RichPhysics().step(
        new Vanilla12111RichPhysics.Context(
            0L,
            start,
            heldJump,
            world,
            Simulation.Environment.DRY,
            start.attributes(),
            Phase5Mechanics.MovementEffects.NONE,
            Pose.STANDING,
            environment,
            false,
            dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    PlayerContext staleAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(), Pose.STANDING,
        environment,
        start.position(), Maths.Vec3.ZERO,
        true, false, false, List.of());

    var report = runner.process(
        "held-jump-bootstrap-delay",
        List.of(
            new RawPacket(1, 10L, staleAuthority,
                Packets.CaptureProvenance.fromAdapter(
                    "paper-live", staleAuthority, 0L, 0L)),
            new RawPacket(2, 20L, new ClientTickEnd()),
            new RawPacket(3, 30L, new ClientInput(
                true, false, false, false, true, false, true)),
            new RawPacket(4, 40L, new Move(
                observed.position(), observed.yaw(), observed.pitch(),
                observed.onGround(), 1L))),
        world,
        start,
        0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("BOOTSTRAP_JUMP_DELAY_OPTIONS range=0..10")),
        report.frames().getFirst().trace().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("input=AdvancedInput[forward=1, strafe=0, jump=true, sprint=true, sneak=false]")
            && line.contains("reconstructedStartVelocity=")),
        report.frames().getFirst().trace().toString());
  }

  @Test
  void stationaryGroundTransitionDoesNotBecomeImpossible() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);

    Player airborneAnchor = new Player(
        new Maths.Vec3(.5, 64.0, .5),
        Maths.Vec3.ZERO,
        0f, 0f, false, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());

    PlayerContext airborneAuthority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(false, false, false),
        airborneAnchor.position(), Maths.Vec3.ZERO,
        false, false, false, List.of());

    var report = runner.process(
        "stationary-ground-transition",
        List.of(
            new RawPacket(1, 20, airborneAuthority),
            new RawPacket(2, 30, new ClientTickEnd()),
            new RawPacket(3, 60, new Move(
                airborneAnchor.position(), 0f, 0f, true, 1L))),
        floorWorld(),
        airborneAnchor,
        20L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getFirst().trace().stream()
        .anyMatch(line -> line.contains("OBSERVATION stationary-position packet")),
        report.frames().toString());
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
  void explicitClientTickRemainsExhaustiveAfterLongTimingHistory() {
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

    // Keep the session comfortably beyond the old Phase 8 512-event journal cutoff.
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
  void longTimingHistoryPreservesAbsoluteClientTickForHeldInput() {
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
    Simulation.AdvancedInput forward =
        new Simulation.AdvancedInput(1, 0, false, false, false);

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Player expectedState = physics.step(
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
        .state();

    List<RawPacket> packets = new ArrayList<>();
    long sequence = 1L;
    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    for (int i = 0; i < 520; i++) {
      packets.add(new RawPacket(sequence++, sequence * 1_000_000L, authority));
    }

    for (int tick = 0; tick < 41; tick++) {
      packets.add(new RawPacket(
          sequence++, sequence * 1_000_000L, new ClientTickEnd()));
    }
    packets.add(new RawPacket(
        sequence++, sequence * 1_000_000L,
        new ClientInput(true, false, false, false, false, false, false)));

    packets.add(new RawPacket(
        sequence,
        sequence * 1_000_000L,
        new Move(expectedState.position(), 0f, 0f, true, 42L)));

    var report = runner.process(
        "long-timing-history",
        packets,
        world,
        start,
        0L);

    assertEquals(1, report.movementObservations(), report.toString());
    assertEquals(
        Phase8MovementValidation.Verdict.POSSIBLE,
        report.results().getFirst().verdict(),
        report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("CLIENT_TICK 42")
            && line.contains("exact=true")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("TICK_RELIABILITY")
            && line.contains("historyTruncated") == false),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .noneMatch(line -> line.contains("historyTruncated")),
        report.frames().getLast().trace().toString());
  }


  @Test
  void inputChronologyAlternativesAreNotArtificiallyCapped() {
    Phase7Timing.Config timing = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.LatencyBounds(0L, 0L),
        new Phase7Timing.TickDelayBounds(0L, 1L),
        new Phase7Timing.TickDelayBounds(0L, 0L),
        250_000_000L, 3, 128);

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096, timing);
    Player start = anchor();
    PlayerContext authority = new PlayerContext(
        "survival", start.attributes(), Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false),
        start.position(), start.velocity(), false, false, false, List.of());

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(
        1L, 0L, authority,
        Packets.CaptureProvenance.fromAdapter(
            "test-authority", authority, 0L, 0L)));
    packets.add(new RawPacket(2L, 50_000_000L, new ClientTickEnd()));

    long sequence = 3L;
    for (int i = 0; i < 257; i++) {
      packets.add(new RawPacket(
          sequence++, 100_000_000L,
          new ClientInput(true, false, false, false, false, false, false),
          Packets.CaptureProvenance.fromAdapter(
              "test-input",
              new ClientInput(true, false, false, false, false, false, false),
              0L)));
    }

    Move observed = new Move(
        new Maths.Vec3(20.5, 64.0, 0.5), 0f, 0f, true, 3L);
    packets.add(new RawPacket(
        sequence, 150_000_000L, observed,
        Packets.CaptureProvenance.fromAdapter(
            "test-movement", observed, 3L, 10L)));

    var report = runner.process(
        "input-chronology-cap-regression",
        packets,
        floorWorld(),
        start,
        0L);

    assertEquals(1, report.movementObservations(), report.results().toString());
    assertTrue(
        report.results().getFirst().evidence().uncertaintySources().stream()
            .noneMatch(reason -> reason.contains("causal input chronology combinations exceeded")),
        report.results().getFirst().evidence().toString());
    assertTrue(
        report.frames().getFirst().trace().stream()
            .noneMatch(line -> line.contains("causal input chronology combinations exceeded")),
        report.frames().getFirst().trace().toString());
  }

  @Test
  void currentHeldInputMayAffectTheFinalExplicitMovementBoundary() {
    assertTrue(Phase8PredictionRunner.shouldOverlayCurrentInput(
        577L, 578L, 10L, 20L, List.of(578L), false));
    assertTrue(Phase8PredictionRunner.shouldOverlayCurrentInput(
        577L, 578L, 10L, 20L, List.of(577L), false));
    assertFalse(Phase8PredictionRunner.shouldOverlayCurrentInput(
        576L, 578L, 10L, 20L, List.of(578L), false));
    assertFalse(Phase8PredictionRunner.shouldOverlayCurrentInput(
        577L, 578L, 21L, 20L, List.of(578L), false));
  }

  @Test
  void inputGenerationTickCanBridgeOnlyTheFinalExplicitMovementBoundary() {
    assertTrue(Phase8PredictionRunner.currentInputGenerationAllowsFinalBoundary(
        577L, 578L, 10L, 20L, List.of(578L),
        Phase7Timing.Range.exact(578L), true));
    assertTrue(Phase8PredictionRunner.currentInputGenerationAllowsFinalBoundary(
        577L, 578L, 10L, 20L, List.of(),
        new Phase7Timing.Range(577L, 578L), false));

    assertFalse(Phase8PredictionRunner.currentInputGenerationAllowsFinalBoundary(
        576L, 578L, 10L, 20L, List.of(578L),
        Phase7Timing.Range.exact(578L), true));
    assertFalse(Phase8PredictionRunner.currentInputGenerationAllowsFinalBoundary(
        577L, 578L, 10L, 20L, List.of(579L),
        Phase7Timing.Range.exact(579L), true));
  }

  @Test
  void boundedUnmaterializedInputRangeCanReachFinalMovementBoundary() {
    assertFalse(Phase8PredictionRunner.shouldOverlayCurrentInput(
        738L, 739L, 10L, 20L, List.of(),
        new Phase7Timing.Range(740L, 741L), false, false),
        "input timing proven after the boundary must not be overlaid");

    assertTrue(Phase8PredictionRunner.shouldOverlayCurrentInput(
        738L, 739L, 10L, 20L, List.of(),
        new Phase7Timing.Range(738L, 739L), false, false),
        "a bounded but unmaterialized envelope that reaches the boundary remains a valid hypothesis");
  }

  @Test
  void explicitClientTickRemainsExactAcrossServerTickGaps() {
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
        "survival", start.attributes(), Map.of(), Pose.STANDING, environment,
        start.position(), start.velocity(), false, false, false, List.of());

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Simulation.AdvancedInput forward = new Simulation.AdvancedInput(1, 0, false, false, false);
    Player first = physics.step(new Vanilla12111RichPhysics.Context(
        0L, start, forward, world, Simulation.Environment.DRY,
        start.attributes(), Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        environment, false, dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    Player second = physics.step(new Vanilla12111RichPhysics.Context(
        1L, first, forward, world, Simulation.Environment.DRY,
        first.attributes(), Phase5Mechanics.MovementEffects.NONE, Pose.STANDING,
        environment, false, dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    Move firstMove = new Move(first.position(), 0f, 0f, true, 2L);
    Move secondMove = new Move(second.position(), 0f, 0f, true, 3L);
    var report = runner.process(
        "explicit-tick-server-gap",
        List.of(
            new RawPacket(1L, 1_000_000L, authority,
                Packets.CaptureProvenance.fromAdapter("authority", authority, 100L, 1L)),
            new RawPacket(2L, 2_000_000L, firstMove,
                Packets.CaptureProvenance.fromAdapter("move", firstMove, 101L, 2L)),
            new RawPacket(3L, 3_000_000L, secondMove,
                Packets.CaptureProvenance.fromAdapter("move", secondMove, 103L, 3L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("CLIENT_TICK 3")
            && line.contains("exact=true")
            && line.contains("timingUncertain=false")),
        report.frames().getLast().toString());
  }

  @Test
  void explicitClientTickWithRealPacketGapRemainsUncertain() {
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

    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Simulation.AdvancedInput forward =
        new Simulation.AdvancedInput(1, 0, false, false, false);
    Player first = physics.step(new Vanilla12111RichPhysics.Context(
        0L, start, forward, world, Simulation.Environment.DRY,
        start.attributes(), Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING, environment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();
    Player second = physics.step(new Vanilla12111RichPhysics.Context(
        1L, first, forward, world, Simulation.Environment.DRY,
        first.attributes(), Phase5Mechanics.MovementEffects.NONE,
        Pose.STANDING, environment, false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()))).state();

    var report = runner.process(
        "explicit-tick-packet-gap",
        List.of(
            new RawPacket(1L, 1_000_000L, authority),
            new RawPacket(2L, 2_000_000L,
                new ClientInput(true, false, false, false, false, false, false)),
            new RawPacket(3L, 3_000_000L,
                new Move(first.position(), 0f, 0f, true, 1L)),
            new RawPacket(5L, 5_000_000L,
                new Move(second.position(), 0f, 0f, true, 2L))),
        world,
        start,
        0L);

    assertEquals(2, report.movementObservations(), report.toString());
    assertEquals(
        Phase8MovementValidation.Verdict.UNCERTAIN,
        report.results().getLast().verdict(),
        report.results().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.startsWith("CLIENT_TICK 2")
            && line.contains("exact=true")
            && line.contains("timingUncertain=true")),
        report.frames().getLast().trace().toString());
    assertTrue(report.frames().getLast().trace().stream()
        .anyMatch(line -> line.contains("TICK_RELIABILITY")
            && line.contains("sequenceGap=true")),
        report.frames().getLast().trace().toString());
  }


  @Test
  void serverAuthorityIsInvisibleUntilTransactionAcknowledgement() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    Player start = anchor();

    PlayerContext authority = new PlayerContext(
        "survival",
        start.attributes(),
        Map.of(),
        Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(8.5, 64.0, 8.5),
        new Maths.Vec3(0.3, 0.0, 0.0),
        false,
        false,
        false,
        List.of());

    short transaction = -1;
    PlayerContext barrierAuthority = authority.withTransactionBarrier(transaction);

    runner.process(
        "authority-barrier",
        List.of(
            new RawPacket(1L, 1_000L, new WorldTransactionSend(transaction)),
            new RawPacket(2L, 2_000L, barrierAuthority)),
        floorWorld(),
        start,
        0L);

    assertTrue(
        runner.latestClientVisibleAuthority().isEmpty(),
        "server authority must remain hidden until the client crosses the barrier");

    runner.process(
        "authority-barrier",
        List.of(new RawPacket(3L, 3_000L, new WorldTransactionAck(transaction))),
        floorWorld(),
        start,
        0L);

    assertEquals(
        barrierAuthority,
        runner.latestClientVisibleAuthority().orElseThrow(),
        "the acknowledged snapshot becomes the newest client-visible authority");
  }

}