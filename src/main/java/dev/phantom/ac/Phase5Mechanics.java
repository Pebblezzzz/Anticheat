package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

/**
 * Phase 5 mechanics that must be represented explicitly before parity claims.
 * Values are deliberately data-driven where 1.21.11 behavior still needs an
 * independent client trace; no guessed constants are silently presented as vanilla.
 */
public final class Phase5Mechanics {
  private Phase5Mechanics() {}

  public enum Pose { STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING }
  public enum Fluid { NONE, WATER, LAVA }
  public enum ModifierOperation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }

  public record AttributeModifier(String id, double amount, ModifierOperation operation) implements Serializable {
    public AttributeModifier {
      Objects.requireNonNull(id); Objects.requireNonNull(operation);
      if (id.isBlank() || !Double.isFinite(amount)) throw new IllegalArgumentException("invalid attribute modifier");
    }
  }

  /** Vanilla AttributeInstance modifier order: additive, base-multiplicative, then total-multiplicative. */
  public static double resolveAttribute(double base, Collection<AttributeModifier> modifiers) {
    if (!Double.isFinite(base) || base < 0) throw new IllegalArgumentException("invalid base attribute");
    double value = base;
    for (AttributeModifier m : modifiers) if (m.operation() == ModifierOperation.ADD_VALUE) value += m.amount();
    double baseStage = value;
    for (AttributeModifier m : modifiers) if (m.operation() == ModifierOperation.ADD_MULTIPLIED_BASE) value += baseStage * m.amount();
    for (AttributeModifier m : modifiers) if (m.operation() == ModifierOperation.ADD_MULTIPLIED_TOTAL) value *= 1.0 + m.amount();
    return value;
  }

  /** Movement-affecting effects are represented by their already-decoded vanilla amplifier. */
  public record MovementEffects(int speedAmplifier, int slownessAmplifier, int jumpBoostAmplifier,
                                boolean levitation, boolean slowFalling) implements Serializable {
    public MovementEffects {
      if (speedAmplifier < -1 || slownessAmplifier < -1 || jumpBoostAmplifier < -1) throw new IllegalArgumentException("effect amplifier must be -1 or greater");
    }
    public static final MovementEffects NONE = new MovementEffects(-1,-1,-1,false,false);
    public double speedMultiplier() {
      double value = 1.0;
      if (speedAmplifier >= 0) value *= 1.0 + 0.2 * (speedAmplifier + 1);
      if (slownessAmplifier >= 0) value *= Math.max(0.0, 1.0 - 0.15 * (slownessAmplifier + 1));
      return value;
    }
    public double jumpVelocityAdd() { return jumpBoostAmplifier >= 0 ? 0.1 * (jumpBoostAmplifier + 1) : 0.0; }
  }

  public record MovementEnvironment(Fluid fluid, boolean submerged, boolean climbable, boolean onGround,
                                    boolean sprinting, boolean sneaking, boolean swimmingInput, boolean gliding,
                                    double fluidSpeedMultiplier, double fluidDrag, double gravityMultiplier) implements Serializable {
    public MovementEnvironment {
      Objects.requireNonNull(fluid);
      if (!Double.isFinite(fluidSpeedMultiplier) || !Double.isFinite(fluidDrag) || !Double.isFinite(gravityMultiplier)) throw new IllegalArgumentException("non-finite environment factor");
      if (fluidSpeedMultiplier < 0 || fluidDrag < 0 || gravityMultiplier < 0) throw new IllegalArgumentException("negative environment factor");
    }
    public static MovementEnvironment dry(boolean onGround, boolean sprinting, boolean sneaking) {
      return new MovementEnvironment(Fluid.NONE,false,false,onGround,sprinting,sneaking,false,false,1.0,1.0,1.0);
    }
  }

  /** Pose transition is separated from dimensions so a failed collision expansion can be represented as UNKNOWN by the caller. */
  public static Pose nextPose(Pose previous, MovementEnvironment env) {
    Objects.requireNonNull(previous); Objects.requireNonNull(env);
    if (env.gliding()) return Pose.FALL_FLYING;
    if (env.submerged() && env.swimmingInput()) return Pose.SWIMMING;
    if (env.sneaking()) return Pose.CROUCHING;
    return Pose.STANDING;
  }

  public record Knockback(Vec3Like impulse, boolean serverVelocityPacketObserved) implements Serializable {
    public Knockback { Objects.requireNonNull(impulse); }
  }
  public record Vec3Like(double x, double y, double z) implements Serializable {}

  /**
   * A correction is a hard simulation barrier. Until the matching confirmation
   * (or a later server correction) arrives, pre-correction velocity must not be
   * integrated into the post-correction state.
   */
  public static final class CorrectionRecovery implements Serializable {
    private long generation;
    private boolean awaitingConfirmation;
    private long teleportId = -1;
    private Vec3Like authoritativePosition;
    private Vec3Like authoritativeVelocity = new Vec3Like(0,0,0);
    private Pose authoritativePose = Pose.STANDING;

    public synchronized void acceptCorrection(long id, Vec3Like position, Vec3Like velocity, Pose pose) {
      generation++; awaitingConfirmation=true; teleportId=id; authoritativePosition=Objects.requireNonNull(position);
      authoritativeVelocity=Objects.requireNonNull(velocity); authoritativePose=Objects.requireNonNull(pose);
    }
    public synchronized boolean confirm(long id) {
      if (!awaitingConfirmation || id != teleportId) return false;
      awaitingConfirmation=false; return true;
    }
    public synchronized boolean awaitingConfirmation() { return awaitingConfirmation; }
    public synchronized long generation() { return generation; }
    public synchronized Optional<Vec3Like> authoritativePosition() { return Optional.ofNullable(authoritativePosition); }
    public synchronized Vec3Like authoritativeVelocity() { return authoritativeVelocity; }
    public synchronized Pose authoritativePose() { return authoritativePose; }
  }

  /** Deterministic combination key used by the Phase 5 matrix to prevent coverage gaps. */
  public record Combination(Pose pose, Fluid fluid, boolean climbable, boolean sprint, boolean sneak,
                            boolean jump, boolean gliding, boolean knockedBack, boolean stepping, boolean corrected) implements Serializable {}
}
