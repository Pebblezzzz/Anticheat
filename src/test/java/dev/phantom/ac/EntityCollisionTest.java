package dev.phantom.ac;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import dev.phantom.ac.world.*;

class EntityCollisionTest {
  private static WorldSnapshot floor(){return WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0,0).loadChunk(-1,0).loadChunk(0,-1).loadChunk(-1,-1).setBlock(0,63,0,dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of())).build();}

  @Test void incompleteEntityHistoryIsUncertain(){
    var ctx=new Vanilla12111RichPhysics.Context(0,State.Player.initial(new Maths.Vec3(.5,64,.5)),new Simulation.AdvancedInput(1,0,false,false,false),floor(),Simulation.Environment.DRY,Simulation.Attributes.DEFAULT,Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),false,EntityCollisions.NONE_TRACKED);
    assertTrue(new Vanilla12111RichPhysics().step(ctx).state().uncertain());
  }

  @Test void completeEntityHistoryParticipatesInCollisionResolution(){
    EntityCollisions entities=EntityCollisions.of(List.of(new EntityCollisions.EntityBox(42,new dev.phantom.ac.geometry.BlockBox(1.0,64,0,1.4,66,1))));
    var ctx=new Vanilla12111RichPhysics.Context(0,State.Player.initial(new Maths.Vec3(.5,64,.5)),new Simulation.AdvancedInput(1,0,false,false,false),floor(),Simulation.Environment.DRY,Simulation.Attributes.DEFAULT,Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),false,entities);
    var result=new Vanilla12111RichPhysics().step(ctx);
    assertFalse(result.state().uncertain());
    assertTrue(result.collided()||result.state().position().x()<1.0);
  }
  @Test
  void incompleteFixedEntityProviderReportsIncompleteResult() {
    var entity = new EntityCollisions.EntityBox(
        7, new dev.phantom.ac.geometry.BlockBox(0, 0, 0, 1, 2, 1));
    var provider = EntityCollisions.of(List.of(entity), false);
    var result = provider.boxesIn(new dev.phantom.ac.geometry.BlockBox(-1, -1, -1, 2, 3, 2));
    assertFalse(result.complete());
    assertEquals(1, result.boxes().size());
  }


}