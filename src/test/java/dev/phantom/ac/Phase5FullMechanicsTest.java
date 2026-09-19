        assertEquals(Phase5Mechanics.Pose.STANDING, lava.pose());
    }

    @Test void poseTransitionIsBlockedByOccupyingGeometry() {
        var blocks = new java.util.HashMap<World.Pos, World.Block>();
        blocks.put(new World.Pos(0, 1, 0), World.Block.FULL);
        World.Snapshot world = new World.Snapshot(blocks, java.util.Set.of(new World.Chunk(0, 0)));
        Player state = new Player(Vec3.ZERO, Vec3.ZERO, 0, 0, false, "survival", java.util.Map.of(), OptionalInt.empty(), false);
        var env = new Phase5Mechanics.MovementEnvironment(Phase5Mechanics.Fluid.WATER, true, false, false,
                false, false, false, false, 1.0, 0.9, 0.0);
        var result = new Vanilla12111Physics().step(new PhysicsContext(6, state, new AdvancedInput(0, 0, false), world,
                Simulation.Environment.WATER, Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
                Phase5Mechanics.Pose.SWIMMING, env));
        assertEquals(Phase5Mechanics.Pose.STANDING, result.pose());
    }
    @Test void unsupportedCreativeSpectatorAndFlyingModesPropagateUncertainty() {
        State.Player creative = new State.Player(
            new Maths.Vec3(.5, 70, .5), Maths.Vec3.ZERO, 0f, 0f, false, "creative",
            Map.of(), OptionalInt.empty(), false, Optional.empty(),
            Attributes.DEFAULT, Phase5Mechanics.Pose.STANDING, State.Environment.DRY,
            State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

        var creativeResult = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
            1, creative, new AdvancedInput(1, 0, false, true, false), dev.phantom.ac.world.WorldSnapshot.emptyOverworld12111(),
            Simulation.Environment.DRY, Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
            Phase5Mechanics.Pose.STANDING,
            Phase5Mechanics.MovementEnvironment.dry(false, true, false), false, true,
            dev.phantom.ac.world.EntityCollisions.of(List.of(), true)));
        assertTrue(creativeResult.state().uncertain());
        assertTrue(creativeResult.diagnostic().contains("creative"));

        State.Player spectator = new State.Player(
            creative.position(), creative.velocity(), creative.yaw(), creative.pitch(), false, "spectator",
            creative.effects(), creative.awaitingTeleport(), false, creative.input(),
            creative.attributes(), creative.pose(), creative.environment(),
            creative.clientTickRange(), creative.provenance(), creative.uncertaintyReasons());

        var spectatorResult = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
            1, spectator, new AdvancedInput(1, 0, false, false, false), dev.phantom.ac.world.WorldSnapshot.emptyOverworld12111(),
            Simulation.Environment.DRY, Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
            Phase5Mechanics.Pose.STANDING,
            Phase5Mechanics.MovementEnvironment.dry(false, false, false), false, false,
            dev.phantom.ac.world.EntityCollisions.of(List.of(), true)));
        assertTrue(spectatorResult.state().uncertain());
        assertTrue(spectatorResult.diagnostic().contains("spectator"));

        var flyingResult = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
            1, State.Player.initial(new Maths.Vec3(.5, 70, .5)),
            new AdvancedInput(1, 0, false, true, false), dev.phantom.ac.world.WorldSnapshot.emptyOverworld12111(),
            Simulation.Environment.DRY, Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
            Phase5Mechanics.Pose.STANDING,
            Phase5Mechanics.MovementEnvironment.dry(false, true, false), false, true,
            dev.phantom.ac.world.EntityCollisions.of(List.of(), true)));
        assertTrue(flyingResult.state().uncertain());
    }

}