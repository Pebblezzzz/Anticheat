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
                false, false, false, true, true, false, false)),
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