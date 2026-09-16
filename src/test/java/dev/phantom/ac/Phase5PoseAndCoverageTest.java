package dev.phantom.ac;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.Maths.Aabb;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.Simulation.PhysicsContext;
import dev.phantom.ac.Simulation.Vanilla12111Physics;
import dev.phantom.ac.State.Player;

class Phase5PoseAndCoverageTest {
  private static World.Snapshot air() {
    return World.Snapshot.emptyVisibleChunks(List.of(new World.Chunk(0,0)));
  }

  @Test void poseBoundsAreNotAlwaysStanding() {
    Aabb standing=Aabb.playerAt(Vec3.ZERO,Phase5Mechanics.Pose.STANDING);
    Aabb crouching=Aabb.playerAt(Vec3.ZERO,Phase5Mechanics.Pose.CROUCHING);
    Aabb swimming=Aabb.playerAt(Vec3.ZERO,Phase5Mechanics.Pose.SWIMMING);
    assertEquals(1.8,standing.maxY()-standing.minY(),1e-12);
    assertEquals(1.5,crouching.maxY()-crouching.minY(),1e-12);
    assertEquals(0.6,swimming.maxY()-swimming.minY(),1e-12);
  }

  @Test void unknownEnvironmentProducesUncertainResult() {
    var result=new Vanilla12111Physics().step(new PhysicsContext(1,Player.initial(Vec3.ZERO),
        new AdvancedInput(1,0,false),air(),Simulation.Environment.UNKNOWN,Attributes.DEFAULT));
    assertTrue(result.state().uncertain());
    assertEquals(Vec3.ZERO,result.state().position());
  }

  @Test void swimmingPoseIsSelectedFromExplicitEnvironment() {
    var env=Phase5Mechanics.MovementEnvironment.vanillaWater(false,false,false,true);
    var result=new Vanilla12111Physics().step(new PhysicsContext(1,Player.initial(Vec3.ZERO),
        new AdvancedInput(0,0,false),air(),Simulation.Environment.WATER,Attributes.DEFAULT,
        Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,env));
    assertEquals(Phase5Mechanics.Pose.SWIMMING,result.pose());
  }
}
