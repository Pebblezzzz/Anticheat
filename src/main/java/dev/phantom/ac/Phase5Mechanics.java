package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

/** Explicit Phase 5 movement mechanics. Unknown empirical values must be supplied by trace data, never guessed at validation time. */
public final class Phase5Mechanics {
  private Phase5Mechanics() {}
  public enum Pose { STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING }
  public enum Fluid { NONE, WATER, LAVA }
  public enum ModifierOperation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }

  public record AttributeModifier(String id,double amount,ModifierOperation operation) implements Serializable {
    public AttributeModifier { Objects.requireNonNull(id); Objects.requireNonNull(operation); if(id.isBlank()||!Double.isFinite(amount))throw new IllegalArgumentException("invalid attribute modifier"); }
  }
  public static double resolveAttribute(double base,Collection<AttributeModifier> modifiers) {
    if(!Double.isFinite(base)||base<0)throw new IllegalArgumentException("invalid base attribute");
    double value=base;
    for(AttributeModifier m:modifiers)if(m.operation()==ModifierOperation.ADD_VALUE)value+=m.amount();
    for(AttributeModifier m:modifiers)if(m.operation()==ModifierOperation.ADD_MULTIPLIED_BASE)value+=base*m.amount();
    for(AttributeModifier m:modifiers)if(m.operation()==ModifierOperation.ADD_MULTIPLIED_TOTAL)value*=1.0+m.amount();
    return value;
  }

  public record MovementEffects(int speedAmplifier,int slownessAmplifier,int jumpBoostAmplifier,int levitationAmplifier,boolean slowFalling) implements Serializable {
    public MovementEffects { if(speedAmplifier<-1||slownessAmplifier<-1||jumpBoostAmplifier<-1||levitationAmplifier<-1)throw new IllegalArgumentException("effect amplifier must be -1 or greater"); }
    public MovementEffects(int speedAmplifier,int slownessAmplifier,int jumpBoostAmplifier,boolean levitation,boolean slowFalling){this(speedAmplifier,slownessAmplifier,jumpBoostAmplifier,levitation?0:-1,slowFalling);}
    public static final MovementEffects NONE=new MovementEffects(-1,-1,-1,-1,false);
    public double speedMultiplier(){double value=1.0;if(speedAmplifier>=0)value*=1.0+0.2*(speedAmplifier+1);if(slownessAmplifier>=0)value*=Math.max(0.0,1.0-0.15*(slownessAmplifier+1));return value;}
    public double jumpVelocityAdd(){return jumpBoostAmplifier>=0?0.1*(jumpBoostAmplifier+1):0.0;}
    public boolean levitation(){return levitationAmplifier>=0;}
    public double levitationVelocity(){return 0.05*(levitationAmplifier+1);}
    public double fallGravityMultiplier(){return slowFalling?0.2:1.0;}
  }

  public record MovementEnvironment(Fluid fluid,boolean submerged,boolean climbable,boolean onGround,boolean sprinting,boolean sneaking,boolean swimmingInput,boolean gliding,double fluidSpeedMultiplier,double fluidDrag,double gravityMultiplier) implements Serializable {
    public MovementEnvironment { Objects.requireNonNull(fluid);if(!Double.isFinite(fluidSpeedMultiplier)||!Double.isFinite(fluidDrag)||!Double.isFinite(gravityMultiplier))throw new IllegalArgumentException("non-finite environment factor");if(fluidSpeedMultiplier<0||fluidDrag<0||gravityMultiplier<0)throw new IllegalArgumentException("negative environment factor"); }
    public static MovementEnvironment dry(boolean onGround,boolean sprinting,boolean sneaking){return new MovementEnvironment(Fluid.NONE,false,false,onGround,sprinting,sneaking,false,false,1.0,1.0,1.0);}
    public static MovementEnvironment vanillaWater(boolean onGround,boolean sprinting,boolean sneaking,boolean swimmingInput){return new MovementEnvironment(Fluid.WATER,true,false,onGround,sprinting,sneaking,swimmingInput,false,1.0,0.9,0.0);}
    public static MovementEnvironment vanillaLava(boolean onGround,boolean sprinting,boolean sneaking){return new MovementEnvironment(Fluid.LAVA,true,false,onGround,sprinting,sneaking,false,false,1.0,0.5,0.25);}
    public static MovementEnvironment vanillaClimbable(boolean onGround,boolean sprinting,boolean sneaking){return new MovementEnvironment(Fluid.NONE,false,true,onGround,sprinting,sneaking,false,false,1.0,1.0,1.0);}
  }
  public static Pose nextPose(Pose previous,MovementEnvironment env){return nextPose(previous,env,false);}
  public static Pose nextPose(Pose previous,MovementEnvironment env,boolean sleeping){Objects.requireNonNull(previous);Objects.requireNonNull(env);if(sleeping)return Pose.SLEEPING;if(env.gliding())return Pose.FALL_FLYING;if(env.submerged()&&env.swimmingInput())return Pose.SWIMMING;if(env.sneaking())return Pose.CROUCHING;return Pose.STANDING;}

  /** Applies an authoritative server velocity packet; it replaces the tracked velocity. */
  public static State.Player applyVelocityImpulse(State.Player state, Vec3Like impulse) {
    Objects.requireNonNull(state); Objects.requireNonNull(impulse);
    return new State.Player(state.position(), new Maths.Vec3(impulse.x(), impulse.y(), impulse.z()), state.yaw(), state.pitch(), state.onGround(), state.gamemode(), state.effects(), state.awaitingTeleport(), state.uncertain(), state.input(), state.attributes(), state.pose(), state.environment(), state.clientTickRange(), state.provenance(), state.uncertaintyReasons());
  }
  public static Knockback observedKnockback(Vec3Like impulse) { return new Knockback(impulse, true); }
  public record Knockback(Vec3Like impulse,boolean serverVelocityPacketObserved) implements Serializable {public Knockback{Objects.requireNonNull(impulse);}}
  public record Vec3Like(double x,double y,double z) implements Serializable {}

  public static final class CorrectionRecovery implements Serializable {
    private long generation;private boolean awaitingConfirmation;private long teleportId=-1;private Vec3Like authoritativePosition;private Vec3Like authoritativeVelocity=new Vec3Like(0,0,0);private Pose authoritativePose=Pose.STANDING;
    public synchronized void acceptCorrection(long id,Vec3Like position,Vec3Like velocity,Pose pose){generation++;awaitingConfirmation=true;teleportId=id;authoritativePosition=Objects.requireNonNull(position);authoritativeVelocity=Objects.requireNonNull(velocity);authoritativePose=Objects.requireNonNull(pose);}
    public synchronized boolean confirm(long id){if(!awaitingConfirmation||id!=teleportId)return false;awaitingConfirmation=false;return true;}
    public synchronized boolean awaitingConfirmation(){return awaitingConfirmation;}public synchronized long generation(){return generation;}public synchronized Optional<Vec3Like> authoritativePosition(){return Optional.ofNullable(authoritativePosition);}public synchronized Vec3Like authoritativeVelocity(){return authoritativeVelocity;}public synchronized Pose authoritativePose(){return authoritativePose;}
  }
  public record Combination(Pose pose,Fluid fluid,boolean climbable,boolean sprint,boolean sneak,boolean jump,boolean gliding,boolean knockedBack,boolean stepping,boolean corrected) implements Serializable {}
}
