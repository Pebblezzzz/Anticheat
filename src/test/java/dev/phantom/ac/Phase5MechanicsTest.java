package dev.phantom.ac;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.Simulation.PhysicsContext;
import dev.phantom.ac.Simulation.Vanilla12111Physics;
import dev.phantom.ac.State.Player;

class Phase5MechanicsTest {
  private static World.Snapshot air() {
    var stone=dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",java.util.Map.of());
    Map<World.Pos,World.Block> blocks=new java.util.LinkedHashMap<>();
    for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++) blocks.put(new World.Pos(x,-1,z),World.Block.FULL);
    return new World.Snapshot(blocks,Set.of(new World.Chunk(-1,-1),new World.Chunk(-1,0),new World.Chunk(0,-1),new World.Chunk(0,0)));
  }
  @Test void sprintAndSneakAreExplicitInputs() {
    var physics=new Vanilla12111Physics();
    var state=Player.initial(Vec3.ZERO);
    var normal=physics.step(new PhysicsContext(1,state,new AdvancedInput(1,0,false),air(),Simulation.Environment.DRY,new Attributes(.1)));
    var sprint=physics.step(new PhysicsContext(1,state,new AdvancedInput(1,0,false,true,false),air(),Simulation.Environment.DRY,new Attributes(.1)));
    var sneak=physics.step(new PhysicsContext(1, state, new AdvancedInput(1, 0, false, false, true), air(), Simulation.Environment.DRY, new Attributes(.1)));
    assertTrue(sprint.state().position().z()>normal.state().position().z());
    assertTrue(sneak.state().position().z()<normal.state().position().z());
  }
  @Test void attributesAndEnvironmentAreDeterministicInputs() {
    var physics=new Vanilla12111Physics(); var state=Player.initial(Vec3.ZERO);
    var fast=physics.step(new PhysicsContext(2,state,new AdvancedInput(1,0,false),air(),Simulation.Environment.DRY,new Attributes(2)));
    var repeat=physics.step(new PhysicsContext(2,state,new AdvancedInput(1,0,false),air(),Simulation.Environment.DRY,new Attributes(2)));
    assertEquals(fast,repeat);
    assertFalse(fast.state().uncertain());
  }
  @Test void diagonalGroundInputMatchesTraceDerivedFirstStep() {
    var physics=new Vanilla12111Physics(); var state=Player.initial(Vec3.ZERO);
    var diagonal=physics.step(new PhysicsContext(3,state,new AdvancedInput(1,-1,false),air(),Simulation.Environment.DRY,new Attributes(.1)));
    assertEquals(0.038608008, Math.abs(diagonal.state().velocity().x()), 5e-8);
    assertEquals(0.038608008, Math.abs(diagonal.state().velocity().z()), 5e-8);
    assertEquals(0.054598997, Math.hypot(diagonal.state().velocity().x(), diagonal.state().velocity().z()), 2e-6);
  }
  @Test void tracedWaterAndLavaFactorsAreExplicit() {
    var physics=new Vanilla12111Physics();
    var water=physics.step(new PhysicsContext(4,Player.initial(Vec3.ZERO),new AdvancedInput(1,0,false,true,false),air(),Simulation.Environment.WATER,new Attributes(.1),Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.vanillaWater(false,true,false,true)));
    var lava=physics.step(new PhysicsContext(5,Player.initial(Vec3.ZERO),new AdvancedInput(1,0,false),air(),Simulation.Environment.LAVA,new Attributes(.1),Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.vanillaLava(true,false,false)));
    assertEquals(0.01764, water.state().velocity().z(), 1e-12);
    assertEquals(0.0098, lava.state().velocity().z(), 1e-12);
    assertEquals(0.25, Phase5Mechanics.MovementEnvironment.vanillaLava(true,false,false).gravityMultiplier(), 1e-12);
    assertEquals(0.5, Phase5Mechanics.MovementEnvironment.vanillaLava(true,false,false).fluidDrag(), 1e-12);
  }
  @Test void waterAndClimbableDoNotBecomeUnknownByConvenience() {
    var physics=new Vanilla12111Physics(); var state=Player.initial(Vec3.ZERO);
    var water=physics.step(new PhysicsContext(6,state,new AdvancedInput(0,0,false),air(),Simulation.Environment.WATER,Attributes.DEFAULT));
    var climb=physics.step(new PhysicsContext(6,state,new AdvancedInput(0,0,false),air(),Simulation.Environment.CLIMBABLE,Attributes.DEFAULT));
    assertFalse(water.state().uncertain()); assertFalse(climb.state().uncertain());
  }
}
