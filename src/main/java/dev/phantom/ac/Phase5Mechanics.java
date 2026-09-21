package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Version-pinned movement inputs and mechanics shared by the Phase 5 authority.
 *
 * <p>This file contains no anti-cheat decisions. Values are inputs/transformations
 * used by the deterministic Minecraft Java 1.21.11 movement model.</p>
 */
public final class Phase5Mechanics {
  public static final String MINECRAFT_VERSION = "1.21.11";
  private Phase5Mechanics() {}

  public enum Pose { STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING }
  public enum Fluid { NONE, WATER, LAVA }
  public enum ModifierOperation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }

  public record AttributeModifier(String id, double amount, ModifierOperation operation) implements Serializable {
    public AttributeModifier {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(operation, "operation");
      if (id.isBlank() || !Double.isFinite(amount)) throw new IllegalArgumentException("invalid attribute modifier");
    }
  }

  /**
   * Resolves an attribute in the same operation order as vanilla's attribute
   * instance: additive value, base multiplication, then total multiplication.
   *
   * <p>Modifier order inside an operation is canonicalized by id. This keeps
   * replay byte-stable even when the source packet was backed by an unordered map.</p>
   */
  public static double resolveAttribute(double base, Collection<AttributeModifier> modifiers) {
    if (!Double.isFinite(base) || base < 0) throw new IllegalArgumentException("invalid base attribute");
    List<AttributeModifier> ordered = modifiers.stream()
        .sorted(Comparator.comparing(AttributeModifier::id)
            .thenComparingDouble(AttributeModifier::amount)
            .thenComparing(m -> m.operation().ordinal()))
        .toList();
    double value = base;
    for (AttributeModifier m : ordered) if (m.operation() == ModifierOperation.ADD_VALUE) value += m.amount();
    for (AttributeModifier m : ordered) if (m.operation() == ModifierOperation.ADD_MULTIPLIED_BASE) value += base * m.amount();
    for (AttributeModifier m : ordered) if (m.operation() == ModifierOperation.ADD_MULTIPLIED_TOTAL) value *= 1.0 + m.amount();
    if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("resolved attribute is invalid");
    return value;
  }

  public record MovementEffects(
      int speedAmplifier,
      int slownessAmplifier,
      int jumpBoostAmplifier,
      int levitationAmplifier,
      boolean slowFalling) implements Serializable {
    public MovementEffects {
      if (speedAmplifier < -1 || slownessAmplifier < -1 || jumpBoostAmplifier < -1 || levitationAmplifier < -1)
        throw new IllegalArgumentException("effect amplifier must be -1 or greater");
    }
    public MovementEffects(int speedAmplifier, int slownessAmplifier, int jumpBoostAmplifier,
                           boolean levitation, boolean slowFalling) {
      this(speedAmplifier, slownessAmplifier, jumpBoostAmplifier, levitation ? 0 : -1, slowFalling);
    }
    public static final MovementEffects NONE = new MovementEffects(-1, -1, -1, -1, false);

    public static MovementEffects fromStateEffects(Map<String, Integer> effects) {
      Objects.requireNonNull(effects, "effects");
      return new MovementEffects(
          amplifier(effects, "minecraft:speed", "speed"),
          amplifier(effects, "minecraft:slowness", "slowness"),
          amplifier(effects, "minecraft:jump_boost", "jump_boost"),
          amplifier(effects, "minecraft:levitation", "levitation"),
          contains(effects, "minecraft:slow_falling", "slow_falling"));
    }

    private static int amplifier(Map<String, Integer> effects, String... ids) {
      for (String id : ids) {
        Integer value = effects.get(id);
        if (value != null) return value;
      }
      return -1;
    }

    private static boolean contains(Map<String, Integer> effects, String... ids) {
      for (String id : ids) if (effects.containsKey(id)) return true;
      return false;
    }

    public double speedMultiplier() {
      double value = 1.0;
      if (speedAmplifier >= 0) value *= 1.0 + 0.2 * (speedAmplifier + 1);
      if (slownessAmplifier >= 0) value *= Math.max(0.0, 1.0 - 0.15 * (slownessAmplifier + 1));
      return value;
    }
    public double jumpVelocityAdd() { return jumpBoostAmplifier >= 0 ? 0.1 * (jumpBoostAmplifier + 1) : 0.0; }
    public boolean levitation() { return levitationAmplifier >= 0; }
    public double levitationVelocity() { return 0.05 * (levitationAmplifier + 1); }
    public double fallGravityMultiplier() { return slowFalling ? 0.2 : 1.0; }
  }

  /**
   * Environment facts are explicit. In particular, NONE is not a synonym for
   * "unknown": UNKNOWN is represented by Simulation.Environment.UNKNOWN and must
   * be rejected by the authority before physics is integrated.
   */
  public enum VehicleType { NONE, BOAT, CHEST_BOAT, MINECART, PIG, STRIDER, HORSE, CAMEL, NAUTILUS, HAPPY_GHAST, OTHER }

  public record VehicleState(
      VehicleType type,
      boolean controllingPassenger,
      Vec3Like velocity,
      float yaw,
      float pitch,
      boolean onGround,
      double movementSpeed,
      boolean cold,
      boolean dashReady) implements Serializable {
    public static final VehicleState NONE =
        new VehicleState(VehicleType.NONE, false, new Vec3Like(0, 0, 0), 0f, 0f, false, 0.0, false, false);

    public VehicleState {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(velocity, "velocity");
      if (!Float.isFinite(yaw) || !Float.isFinite(pitch))
        throw new IllegalArgumentException("vehicle rotation must be finite");
      if (!Double.isFinite(movementSpeed) || movementSpeed < 0.0)
        throw new IllegalArgumentException("vehicle movement speed must be finite and non-negative");
    }

    public boolean active() {
      return type != VehicleType.NONE && controllingPassenger;
    }
  }

  public record MovementEnvironment(
      Fluid fluid,
      boolean submerged,
      boolean climbable,
      boolean onGround,
      boolean sprinting,
      boolean sneaking,
      boolean swimmingInput,
      boolean gliding,
      double fluidSpeedMultiplier,
      double fluidDrag,
      double gravityMultiplier,
      VehicleState vehicle) implements Serializable {
    public MovementEnvironment(
        Fluid fluid,
        boolean submerged,
        boolean climbable,
        boolean onGround,
        boolean sprinting,
        boolean sneaking,
        boolean swimmingInput,
        boolean gliding,
        double fluidSpeedMultiplier,
        double fluidDrag,
        double gravityMultiplier) {
      this(fluid, submerged, climbable, onGround, sprinting, sneaking, swimmingInput, gliding,
          fluidSpeedMultiplier, fluidDrag, gravityMultiplier, VehicleState.NONE);
    }

    public MovementEnvironment {
      Objects.requireNonNull(fluid, "fluid");
      Objects.requireNonNull(vehicle, "vehicle");
      if (!Double.isFinite(fluidSpeedMultiplier) || !Double.isFinite(fluidDrag) || !Double.isFinite(gravityMultiplier))
        throw new IllegalArgumentException("non-finite environment factor");
      if (fluidSpeedMultiplier < 0 || fluidDrag < 0 || gravityMultiplier < 0)
        throw new IllegalArgumentException("negative environment factor");
    }

    public static MovementEnvironment dry(boolean onGround, boolean sprinting, boolean sneaking) {
      return new MovementEnvironment(Fluid.NONE, false, false, onGround, sprinting, sneaking, false, false, 1.0, 1.0, 1.0);
    }
    public static MovementEnvironment vanillaWater(boolean onGround, boolean sprinting, boolean sneaking, boolean swimmingInput) {
      return new MovementEnvironment(Fluid.WATER, true, false, onGround, sprinting, sneaking, swimmingInput, false, 1.0, 0.8, 1.0);
    }
    public static MovementEnvironment vanillaLava(boolean onGround, boolean sprinting, boolean sneaking) {
      return new MovementEnvironment(Fluid.LAVA, true, false, onGround, sprinting, sneaking, false, false, 1.0, 0.5, 0.25);
    }
    public static MovementEnvironment vanillaClimbable(boolean onGround, boolean sprinting, boolean sneaking) {
      return new MovementEnvironment(Fluid.NONE, false, true, onGround, sprinting, sneaking, false, false, 1.0, 1.0, 1.0);
    }
  }

  public static Pose nextPose(Pose previous, MovementEnvironment env) {
    return nextPose(previous, env, false);
  }

  public static Pose nextPose(Pose previous, MovementEnvironment env, boolean sleeping) {
    Objects.requireNonNull(previous);
    Objects.requireNonNull(env);
    if (sleeping) return Pose.SLEEPING;
    if (env.gliding()) return Pose.FALL_FLYING;
    if (env.submerged() && env.swimmingInput()) return Pose.SWIMMING;
    if (env.sneaking()) return Pose.CROUCHING;
    return Pose.STANDING;
  }

  /** Applies a server-observed velocity update as the next explicit motion state. */
  public static State.Player applyVelocityImpulse(State.Player state, Vec3Like impulse) {
    Objects.requireNonNull(state);
    Objects.requireNonNull(impulse);
    return new State.Player(state.position(),
        new Maths.Vec3(impulse.x(), impulse.y(), impulse.z()),
        state.yaw(), state.pitch(), state.onGround(), state.gamemode(), state.effects(),
        state.awaitingTeleport(), state.uncertain(), state.input(), state.attributes(), state.pose(),
        state.environment(), state.clientTickRange(), state.provenance(), state.uncertaintyReasons());
  }

  public static Knockback observedKnockback(Vec3Like impulse) {
    return new Knockback(impulse, true);
  }

  public record Knockback(Vec3Like impulse, boolean serverVelocityPacketObserved) implements Serializable {
    public Knockback { Objects.requireNonNull(impulse); }
  }
  public record Vec3Like(double x, double y, double z) implements Serializable {}

  /**
   * Correction state is deliberately stateful only at an integration boundary.
   * The physics engine itself remains immutable/pure.
   */
  public static final class CorrectionRecovery implements Serializable {
    private long generation;
    private boolean awaitingConfirmation;
    private long teleportId = -1;
    private Vec3Like authoritativePosition;
    private Vec3Like authoritativeVelocity = new Vec3Like(0, 0, 0);
    private Pose authoritativePose = Pose.STANDING;

    public synchronized void acceptCorrection(long id, Vec3Like position, Vec3Like velocity, Pose pose) {
      generation++;
      awaitingConfirmation = true;
      teleportId = id;
      authoritativePosition = Objects.requireNonNull(position);
      authoritativeVelocity = Objects.requireNonNull(velocity);
      authoritativePose = Objects.requireNonNull(pose);
    }

    public synchronized boolean confirm(long id) {
      if (!awaitingConfirmation || id != teleportId) return false;
      awaitingConfirmation = false;
      return true;
    }

    public synchronized boolean awaitingConfirmation() { return awaitingConfirmation; }
    public synchronized long generation() { return generation; }
    public synchronized Optional<Vec3Like> authoritativePosition() { return Optional.ofNullable(authoritativePosition); }
    public synchronized Vec3Like authoritativeVelocity() { return authoritativeVelocity; }
    public synchronized Pose authoritativePose() { return authoritativePose; }
  }

  public record Combination(Pose pose, Fluid fluid, boolean climbable, boolean sprint, boolean sneak,
                            boolean jump, boolean gliding, boolean knockedBack, boolean stepping, boolean corrected)
      implements Serializable {}
}
