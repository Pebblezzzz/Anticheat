package dev.phantom.ac;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RichWorldPhysicsTest {
    private static dev.phantom.ac.world.BlockState stone() { return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of()); }
    @Test
    void exactSnapshotCollisionClipsVerticalMotionWithoutInventingUnloadedAir() {
        WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).setBlock(0,64,0,stone()).build();
        Maths.Aabb player = new Maths.Aabb(0.2,65.0,0.2,0.8,66.8,0.8);
        RichWorldCollision.Result result = RichWorldCollision.resolve(world, player, new Maths.Vec3(0,-0.2,0), 0);
        assertFalse(result.uncertain()); assertTrue(result.collidedY()); assertEquals(0.0,result.displacement().y(),1.0e-12);
        assertFalse(world.hasUnknownOrUnsupported(new BlockBox(0.2,64.8,0.2,0.8,66.8,0.8)));
    }
    @Test
    void groundedStationaryPlayerRemainsGroundedWithZeroVerticalVelocity() {
        WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).setBlock(0,63,0,stone()).build();
        var player = State.Player.initial(new Maths.Vec3(0.5,64.0,0.5));
        var input = new Simulation.AdvancedInput(0, 0, false, false, false);
        var env = Phase5Mechanics.MovementEnvironment.dry(true, false, false);
        var context = new Vanilla12111RichPhysics.Context(0, player, input, world, Simulation.Environment.DRY,
            Simulation.Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
            env, false, dev.phantom.ac.world.EntityCollisions.of(java.util.List.of()));
        var result = new Vanilla12111RichPhysics().step(context);
        assertFalse(result.state().uncertain());
        assertTrue(result.state().onGround());
        assertEquals(player.position(), result.state().position());
        assertEquals(0.0, result.state().velocity().y(), 1.0e-12);
    }
    @Test
    void knownAirSupportCellDoesNotBecomePhase5Uncertainty() {
        WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
            .loadChunk(0, 0)
            .build();
        var player = State.Player.initial(new Maths.Vec3(0.5, 64.0, 0.5));
        var input = new Simulation.AdvancedInput(1, 0, false, true, false);
        var env = Phase5Mechanics.MovementEnvironment.dry(true, true, false);
        var context = new Vanilla12111RichPhysics.Context(
            0, player, input, world, Simulation.Environment.DRY,
            Simulation.Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
            Phase5Mechanics.Pose.STANDING, env, false,
            dev.phantom.ac.world.EntityCollisions.of(java.util.List.of()));

        assertEquals(Coverage.KNOWN, world.coverageAt(0, 63, 0));
        assertTrue(world.requireBlockAt(0, 63, 0).isAir());

        var result = new Vanilla12111RichPhysics().step(context);

        assertFalse(result.state().uncertain(), result.diagnostic());
        assertEquals(0.6,
            BlockCatalogue12111.slipperiness(world.requireBlockAt(0, 63, 0)),
            1.0e-12);
    }

    @Test
    void overlappingStartDoesNotTurnUpwardVelocityIntoLargeDownwardMotion() {
        WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
            .loadChunk(0, 0)
            .loadChunk(-1, 0)
            .loadChunk(0, -1)
            .loadChunk(-1, -1)
            .setBlock(0, 70, 0, stone())
            .build();
        var player = new State.Player(
            new Maths.Vec3(0.1, 70.795, 0.1),
            new Maths.Vec3(0.0, 0.3332, 0.0),
            0.0f,
            0.0f,
            false,
            "survival",
            Map.of(),
            java.util.OptionalInt.empty(),
            false
        );
        var input = new Simulation.AdvancedInput(0, 0, false, false, false);
        var env = Phase5Mechanics.MovementEnvironment.dry(false, false, false);
        var context = new Vanilla12111RichPhysics.Context(
            390, player, input, world, Simulation.Environment.DRY,
            Simulation.Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
            Phase5Mechanics.Pose.STANDING, env, false,
            dev.phantom.ac.world.EntityCollisions.of(java.util.List.of()));

        var result = new Vanilla12111RichPhysics().step(context);

        assertFalse(result.state().uncertain(), result.diagnostic());
        assertTrue(result.state().position().y() > player.position().y(),
            () -> "upward start velocity was reversed by overlap: " + result.state().position());
        assertTrue(result.state().position().y() < 71.5,
            () -> "overlap produced an implausibly large vertical displacement: " + result.state().position());
    }

    @Test
    void exactSnapshotCollisionRefusesAnUnloadedSweep() {
        WorldSnapshot world=WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0,0).setBlock(0,64,0,stone()).build();
        Maths.Aabb player=new Maths.Aabb(15.7,65,0.2,16.3,66.8,0.8);
        RichWorldCollision.Result result=RichWorldCollision.resolve(world,player,new Maths.Vec3(1.0,0,0),0);
        assertTrue(result.uncertain()); assertEquals(Coverage.UNLOADED,world.coverageAt(16,64,0));
    }
    @Test
    void sleepingIsARealPoseTransitionRatherThanStanding() {
        var env=Phase5Mechanics.MovementEnvironment.dry(true,false,false);
        assertEquals(Phase5Mechanics.Pose.SLEEPING,Phase5Mechanics.nextPose(Phase5Mechanics.Pose.STANDING,env,true));
        assertEquals(Phase5Mechanics.Pose.STANDING,Phase5Mechanics.nextPose(Phase5Mechanics.Pose.SLEEPING,env,false));
    }
}