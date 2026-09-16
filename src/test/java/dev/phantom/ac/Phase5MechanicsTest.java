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
  private static World.Snapshot air() { return World.Snapshot.emptyVisibleChunks(List.of(new World.Chunk(-1,-1),new World.Chunk(-1,0),new World.Chunk(0,-1),new World.Chunk(0,0))); }
  @Test void sprintAndSneakAreExplicitInputs() {
    var physics=new Vanilla12111Physics();
    var state=Player.initial(Vec3.ZERO);
    var normal=physics.step(new PhysicsContext(1,state,new AdvancedInput(1,0,false),air(),Simulation.Environment.DRY,Attributes.DEFAULT));
    var sprint=physics.step(new PhysicsContext(1,state,new AdvancedInput(1,0,false,true,false),air(),Simulation.Environment.DRY,Attributes.DEFAULT));
    var sneak=physics.step(new PhysicsContext(1, state, new AdvancedInput(1, 0, false, false, true), air(), Simulation.Environment.DRY, Attributes.DEFAULT));
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
  @Test void waterAndClimbableDoNotBecomeUnknownByConvenience() {
    var physics=new Vanilla12111Physics(); var state=Player.initial(Vec3.ZERO);
    var water=physics.step(new PhysicsContext(3,state,new AdvancedInput(0,0,false),air(),Simulation.Environment.WATER,Attributes.DEFAULT));
    var climb=physics.step(new PhysicsContext(3,state,new AdvancedInput(0,0,false),air(),Simulation.Environment.CLIMBABLE,Attributes.DEFAULT));
    assertFalse(water.state().uncertain()); assertFalse(climb.state().uncertain());
  }
}
