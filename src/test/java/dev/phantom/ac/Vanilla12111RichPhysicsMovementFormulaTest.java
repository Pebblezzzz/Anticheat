package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Environment;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Vanilla12111RichPhysicsMovementFormulaTest {
  private static WorldSnapshot floor(String supportId) {
    var support=dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(supportId,Map.of());
    var b=WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0,0).loadChunk(-1,0).loadChunk(0,-1).loadChunk(-1,-1);
    for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)b.setBlock(x,63,z,support);
    return b.build();
  }

  private static Vanilla12111RichPhysics.StepResult step(Player player,AdvancedInput input,WorldSnapshot world) {
    return step(player,input,world,Attributes.DEFAULT);
  }

  private static Vanilla12111RichPhysics.StepResult step(Player player,AdvancedInput input,WorldSnapshot world,Attributes attributes) {
    return new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
        0,player,input,world,Environment.DRY,attributes,MovementEffects.NONE,
        Pose.STANDING,MovementEnvironment.dry(player.onGround(),input.sprint(),input.sneak()),
        false,dev.phantom.ac.world.EntityCollisions.of(java.util.List.of())));
  }

  @Test
  void stoneGroundUsesFrictionInfluencedMovementSpeed() {
    var result=step(Player.initial(new Vec3(.5,64,.5)),
        new AdvancedInput(1,0,false,false,false),floor("minecraft:stone"));
    assertEquals(.6,result.state().position().z(),1e-8);
    assertEquals(.0546,result.state().velocity().z(),1e-8);
  }

  @Test
  void sprintGroundUsesMovementSpeedSprintModifier() {
    var sprintAttributes=new Attributes(.1,java.util.List.of(new Phase5Mechanics.AttributeModifier(
        "vanilla:sprinting",0.3,Phase5Mechanics.ModifierOperation.ADD_MULTIPLIED_TOTAL)));
    var result=step(Player.initial(new Vec3(.5,64,.5)),
        new AdvancedInput(1,0,false,true,false),floor("minecraft:stone"),sprintAttributes);
    assertEquals(.63,result.state().position().z(),1e-8);
    assertEquals(.07098,result.state().velocity().z(),1e-8);
  }

  @Test
  void iceGroundChangesInputSpeedThroughSlipperiness() {
    var result=step(Player.initial(new Vec3(.5,64,.5)),
        new AdvancedInput(1,0,false,false,false),floor("minecraft:ice"));
    assertEquals(.5229496234562,result.state().position().z(),1e-10);
    assertEquals(.0205481624564,result.state().velocity().z(),1e-10);
  }

  @Test
  void airUsesSeparateNormalAcceleration() {
    Player player=new Player(new Vec3(.5,70,.5),Vec3.ZERO,0,0,false,
        "survival",Map.of(),java.util.OptionalInt.empty(),false,
        java.util.Optional.empty(),new Attributes(.1),Pose.STANDING,State.Environment.DRY,
        State.TickRange.exact(0),State.Provenance.UNKNOWN,java.util.Set.of());
    var result=step(player,new AdvancedInput(1,0,false,false,false),floor("minecraft:stone"));
    assertEquals(.52,result.state().position().z(),1e-10);
    assertEquals(.0182,result.state().velocity().z(),1e-10);
  }

  @Test
  void airSprintUsesModernSprintAcceleration() {
    Player player=new Player(new Vec3(.5,70,.5),Vec3.ZERO,0,0,false,
        "survival",Map.of(),java.util.OptionalInt.empty(),false,
        java.util.Optional.empty(),new Attributes(.1),Pose.STANDING,State.Environment.DRY,
        State.TickRange.exact(0),State.Provenance.UNKNOWN,java.util.Set.of());
    var result=step(player,new AdvancedInput(1,0,false,true,false),floor("minecraft:stone"));
    assertEquals(.526,result.state().position().z(),1e-10);
    assertEquals(.02366,result.state().velocity().z(),1e-10);
  }
}
