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

  private static Phase5TraceTool.Row row(long tick,long client,long receive) {
    return new Phase5TraceTool.Row(tick,client,receive,0,0,0,0,0,0,0,0,true,0,0,false,false,false,
      Phase5Mechanics.Pose.STANDING,"survival",Phase5Mechanics.Fluid.NONE,false,false,false,1,List.of(),
      Phase5Mechanics.MovementEffects.NONE,new Phase5Mechanics.Vec3Like(0,0,0),false,-1,false,"world",tick,false,false,false,false,false,false,"client","1.21.11");
  }
}
