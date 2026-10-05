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

  @Test void itemUseSpeedMultiplierScalesMovementInput() {
    var physics = new Vanilla12111Physics();
    var state = Player.initial(Vec3.ZERO);

    var normal = physics.step(new PhysicsContext(
        2, state, new AdvancedInput(1, 0, false), air(),
        Simulation.Environment.DRY, new Attributes(.1), Phase5Mechanics.MovementEffects.NONE,
        Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false)));

    var itemUse = physics.step(new PhysicsContext(
        2, state, new AdvancedInput(1, 0, false), air(),
        Simulation.Environment.DRY, new Attributes(.1), Phase5Mechanics.MovementEffects.NONE,
        Phase5Mechanics.Pose.STANDING,
        new Phase5Mechanics.MovementEnvironment(
            Phase5Mechanics.Fluid.NONE, false, false, true,
            false, false, false, false, 1.0, 1.0, 1.0, 0.2)));

    assertTrue(
        Math.abs(itemUse.state().velocity().z()) < Math.abs(normal.state().velocity().z()),
        "item use must reduce the vanilla movement input");
  }
  @Test void diagonalGroundInputMatchesTraceDerivedFirstStep() {
    var physics=new Vanilla12111Physics(); var state=Player.initial(Vec3.ZERO);
    var diagonal=physics.step(new PhysicsContext(3,state,new AdvancedInput(1,1,false),air(),Simulation.Environment.DRY,new Attributes(.1)));
    assertTrue(diagonal.state().velocity().x()<0);
    assertTrue(diagonal.state().velocity().z()>0);
    assertEquals(0.038608008, Math.abs(diagonal.state().velocity().x()), 5e-8);
    assertEquals(0.038608008, Math.abs(diagonal.state().velocity().z()), 5e-8);
    assertEquals(0.054598997, Math.hypot(diagonal.state().velocity().x(), diagonal.state().velocity().z()), 2e-6);
  }
  @Test void tracedWaterAndLavaFactorsAreExplicit() {
    var physics=new Vanilla12111Physics();
    var water=physics.step(new PhysicsContext(4,Player.initial(Vec3.ZERO),new AdvancedInput(1,0,false,true,false),air(),Simulation.Environment.WATER,new Attributes(.1),Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.vanillaWater(false,true,false,true)));
    var lava=physics.step(new PhysicsContext(5,Player.initial(Vec3.ZERO),new AdvancedInput(1,0,false),air(),Simulation.Environment.LAVA,new Attributes(.1),Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.vanillaLava(true,false,false)));
    assertEquals(0.017639999481737608, water.state().velocity().z(), 1e-12);
    assertEquals(0.0098, lava.state().velocity().z(), 1e-9);
    assertEquals(0.25, Phase5Mechanics.MovementEnvironment.vanillaLava(true,false,false).gravityMultiplier(), 1e-12);
    assertEquals(0.5, Phase5Mechanics.MovementEnvironment.vanillaLava(true,false,false).fluidDrag(), 1e-12);
  }
  @Test void waterAndClimbableDoNotBecomeUnknownByConvenience() {
    var physics=new Vanilla12111Physics(); var state=Player.initial(Vec3.ZERO);
    var water=physics.step(new PhysicsContext(6,state,new AdvancedInput(0,0,false),air(),Simulation.Environment.WATER,Attributes.DEFAULT));
    var climb=physics.step(new PhysicsContext(6,state,new AdvancedInput(0,0,false),air(),Simulation.Environment.CLIMBABLE,Attributes.DEFAULT));
    assertFalse(water.state().uncertain()); assertFalse(climb.state().uncertain());
  }
  @Test void modernMovementModifiersAreExplicit() {
    var effects = Phase5Mechanics.MovementEffects.fromStateEffects(
        java.util.Map.of(
            "phantom:swift_sneak", 3,
            "phantom:depth_strider", 3,
            "phantom:soul_speed", 1,
            "minecraft:dolphins_grace", 0));
    assertEquals(0.75, effects.sneakingSpeedMultiplier(), 1e-12);
    assertEquals(1.0, effects.depthStriderFraction(), 1e-12);
    assertTrue(effects.soulSpeedActive());
    assertTrue(effects.dolphinsGrace());
  }

  @Test void depthStriderAndDolphinsGraceExpandWaterMovementEnvelope() {
    var physics = new Vanilla12111Physics();
    var state = Player.initial(Vec3.ZERO);
    var baseEffects = Phase5Mechanics.MovementEffects.NONE;
    var enhancedEffects = Phase5Mechanics.MovementEffects.fromStateEffects(
        java.util.Map.of("phantom:depth_strider", 3, "minecraft:dolphins_grace", 0));

    var base = physics.step(new PhysicsContext(
        7, state, new AdvancedInput(1, 0, false), air(),
        Simulation.Environment.WATER, new Attributes(.1), baseEffects,
        Phase5Mechanics.Pose.SWIMMING,
        Phase5Mechanics.MovementEnvironment.vanillaWater(false, false, false, true)));
    var enhanced = physics.step(new PhysicsContext(
        7, state, new AdvancedInput(1, 0, false), air(),
        Simulation.Environment.WATER, new Attributes(.1), enhancedEffects,
        Phase5Mechanics.Pose.SWIMMING,
        Phase5Mechanics.MovementEnvironment.vanillaWater(false, false, false, true)));

    assertTrue(Math.abs(enhanced.state().velocity().z()) > Math.abs(base.state().velocity().z()));
  }


}
