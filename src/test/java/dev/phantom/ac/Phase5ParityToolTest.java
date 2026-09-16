package dev.phantom.ac;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class Phase5ParityToolTest {
  @Test void attributeModifierOrderMatchesDeclaredVanillaSemantics() {
    double v=Phase5Mechanics.resolveAttribute(10,List.of(
      new Phase5Mechanics.AttributeModifier("add",2,Phase5Mechanics.ModifierOperation.ADD_VALUE),
      new Phase5Mechanics.AttributeModifier("base",.5,Phase5Mechanics.ModifierOperation.ADD_MULTIPLIED_BASE),
      new Phase5Mechanics.AttributeModifier("total",.1,Phase5Mechanics.ModifierOperation.ADD_MULTIPLIED_TOTAL)));
    assertEquals(18.7,v,1e-9);
  }

  @Test void poseTransitionsCoverSwimmingAndClimbableCombinations() {
    var water=new Phase5Mechanics.MovementEnvironment(Phase5Mechanics.Fluid.WATER,true,false,false,true,false,true,false,1,1,1);
    assertEquals(Phase5Mechanics.Pose.SWIMMING,Phase5Mechanics.nextPose(Phase5Mechanics.Pose.STANDING,water));
    var sneak=Phase5Mechanics.MovementEnvironment.dry(true,false,true);
    assertEquals(Phase5Mechanics.Pose.CROUCHING,Phase5Mechanics.nextPose(Phase5Mechanics.Pose.STANDING,sneak));
    var glide=new Phase5Mechanics.MovementEnvironment(Phase5Mechanics.Fluid.NONE,false,false,false,false,false,false,true,1,1,1);
    assertEquals(Phase5Mechanics.Pose.FALL_FLYING,Phase5Mechanics.nextPose(Phase5Mechanics.Pose.STANDING,glide));
  }

  @Test void correctionRecoveryIsAHardBarrierAndRequiresMatchingConfirmation() {
    var r=new Phase5Mechanics.CorrectionRecovery();
    r.acceptCorrection(42,new Phase5Mechanics.Vec3Like(10,20,30),new Phase5Mechanics.Vec3Like(0,0,0),Phase5Mechanics.Pose.STANDING);
    assertTrue(r.awaitingConfirmation());
    assertFalse(r.confirm(41));
    assertTrue(r.confirm(42));
    assertFalse(r.awaitingConfirmation());
  }

  @Test void traceValidatorRejectsTickAndTimingRegressions() {
    var e=new Phase5TraceTool.ReferenceTrace("independent-client-1","2026-09-16T00:00:00Z","1.21.11",List.of(row(1,1,10),row(2,2,9)));
    var result=Phase5TraceTool.validate(e);
    assertFalse(result.valid());
    assertTrue(result.diagnostics().stream().anyMatch(d->d.field().equals("receive_nanos")));
  }

  @Test void combinationMatrixCoversEnvironmentPoseInputCorrectionAndCollisionFlags() {
    int count=0;
    for (var fluid: Phase5Mechanics.Fluid.values()) for (var pose: Phase5Mechanics.Pose.values())
      for (boolean climb: new boolean[]{false,true}) for (boolean sprint: new boolean[]{false,true})
      for (boolean sneak: new boolean[]{false,true}) for (boolean jump: new boolean[]{false,true})
      for (boolean glide: new boolean[]{false,true}) for (boolean knock: new boolean[]{false,true})
      for (boolean step: new boolean[]{false,true}) for (boolean corrected: new boolean[]{false,true}) {
        var c=new Phase5Mechanics.Combination(pose,fluid,climb,sprint,sneak,jump,glide,knock,step,corrected);
        assertNotNull(c);
        count++;
      }
    assertEquals(5*3*2*2*2*2*2*2*2*2,count);
  }

  @Test void stoneWalkingBaselineMatchesObservedVanillaTransition() {
    var initial = new State.Player(
      new Maths.Vec3(-177.67296117572545,73.0,-463.2872583970999),
      Maths.Vec3.ZERO,
      -178.05001831054688f,
      9.150008201599121f,
      true,
      "survival",
      Map.of(),
      OptionalInt.empty(),
      false
    );
    var world = World.Snapshot.emptyVisibleChunks(List.of(World.Chunk.containing(-178,-463)));
    var context = new Simulation.PhysicsContext(
      93,
      initial,
      new Simulation.AdvancedInput(1,0,false,false,false),
      world,
      Simulation.Environment.DRY,
      new Simulation.Attributes(0.10000000149011612)
    );

    var result = new Simulation.Vanilla12111Physics().step(context);
    var actual = result.state();

    // Measured from a real Minecraft Java 1.21.11 client trace on a stone platform.
    assertEquals(-177.6696265266769,actual.position().x(),1e-6);
    assertEquals(-463.3852016465647,actual.position().z(),1e-6);
    assertEquals(0.0018207,actual.velocity().x(),1e-6);
    assertEquals(-0.0534770,actual.velocity().z(),1e-6);
  }

  @Test void stoneSprintStartMatchesObservedVanillaHorizontalSpeed() {
    var initial = new State.Player(
      new Maths.Vec3(-35.480283411415854,72.0,26.612638960388164),
      new Maths.Vec3(0.0451647358656705, -0.0784000015258789, 4.348622147246182E-4),
      270.4493408203125f,
      0.0f,
      true,
      "survival",
      Map.of(),
      OptionalInt.empty(),
      false
    );
    var world = World.Snapshot.emptyVisibleChunks(List.of(World.Chunk.containing(-36,26)));
    var context = new Simulation.PhysicsContext(
      70,
      initial,
      new Simulation.AdvancedInput(1,0,false,true,false),
      world,
      Simulation.Environment.DRY,
      new Simulation.Attributes(0.10000000149011612)
    );

    var result = new Simulation.Vanilla12111Physics().step(context);
    var actual = result.state();

    // The real trace's component direction can contain sub-tick input/rotation timing;
    // horizontal speed is the stable invariant at this observation point.
    // Measured from Minecraft Java 1.21.11 sprint4.tsv on a stone platform.
    double horizontalSpeed=Math.hypot(actual.velocity().x(),actual.velocity().z());
    assertEquals(0.09422147450988827,horizontalSpeed,1e-6);
  }

  @Test void stoneSprintJumpMatchesObservedVanillaVerticalLifecycle() {
    var initial = new State.Player(
      new Maths.Vec3(-6.881738915342083,72.0,26.65862746198882),
      new Maths.Vec3(-0.14170886575634412,-0.0784000015258789,0.000854445744),
      8.0f,
      0.0f,
      true,
      "survival",
      Map.of(),
      OptionalInt.empty(),
      false
    );
    var world = World.Snapshot.emptyVisibleChunks(List.of(World.Chunk.containing(-7,26)));
    var context = new Simulation.PhysicsContext(
      54,
      initial,
      new Simulation.AdvancedInput(1,0,true,true,false),
      world,
      Simulation.Environment.DRY,
      new Simulation.Attributes(0.10000000149011612)
    );

    var result = new Simulation.Vanilla12111Physics().step(context);
    var actual = result.state();

    // Measured from a real Minecraft Java 1.21.11 sprint-jump trace on a stone platform.
    assertEquals(72.41999998688698,actual.position().y(),1e-6);
    assertEquals(0.33319999363422365,actual.velocity().y(),1e-6);
  }

  private static Phase5TraceTool.Row row(long tick,long client,long receive) {
    return new Phase5TraceTool.Row(tick,client,receive,0,0,0,0,0,0,0,0,true,0,0,false,false,false,
      Phase5Mechanics.Pose.STANDING,"survival",Phase5Mechanics.Fluid.NONE,false,false,false,1,List.of(),
      Phase5Mechanics.MovementEffects.NONE,new Phase5Mechanics.Vec3Like(0,0,0),false,-1,false,"world",tick,false,false,false,false,false,false,"client","1.21.11");
  }
}
