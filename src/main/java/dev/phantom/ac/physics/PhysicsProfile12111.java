package dev.phantom.ac.physics;

import java.io.Serializable;

/**
 * Version-pinned 1.21.11 movement constants.
 *
 * Values mirror the current Phantom 1.21.11 physics authority. This type is deliberately
 * a data profile: movement algorithms remain in Vanilla12111RichPhysics.
 *
 * Do not treat these values as proof of vanilla parity. Differential vanilla-client
 * traces are the verification authority.
 */
public record PhysicsProfile12111(
    double gravity,
    double airDrag,
    double airHorizontalFriction,
    double airVerticalDrag,
    double airAcceleration,
    double sprintAirAcceleration,
    double groundFriction,
    double walkAcceleration,
    double jumpVelocity,
    double stepHeight,
    double inputFriction,
    double frictionSpeedFactor,
    double sprintJumpHorizontalBoost,
    double sprintingSpeedMultiplier,
    double sneakSpeedMultiplier,
    double waterDrag,
    double waterSprintDrag,
    double lavaDrag,
    double climbMaxDown,
    double climbMaxUp,
    double glidingGravity
) implements Serializable {

  public static final String VERSION = "1.21.11";

  public static PhysicsProfile12111 current() {
    return new PhysicsProfile12111(
        dev.phantom.ac.Vanilla12111RichPhysics.GRAVITY,
        dev.phantom.ac.Vanilla12111RichPhysics.AIR_DRAG,
        dev.phantom.ac.Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION,
        dev.phantom.ac.Vanilla12111RichPhysics.AIR_VERTICAL_DRAG,
        dev.phantom.ac.Vanilla12111RichPhysics.AIR_ACCEL,
        dev.phantom.ac.Vanilla12111RichPhysics.SPRINT_AIR_ACCEL,
        dev.phantom.ac.Vanilla12111RichPhysics.GROUND_FRICTION,
        dev.phantom.ac.Vanilla12111RichPhysics.WALK_ACCEL,
        dev.phantom.ac.Vanilla12111RichPhysics.JUMP,
        dev.phantom.ac.Vanilla12111RichPhysics.STEP_HEIGHT,
        dev.phantom.ac.Vanilla12111RichPhysics.INPUT_FRICTION,
        dev.phantom.ac.Vanilla12111RichPhysics.FRICTION_SPEED_FACTOR,
        dev.phantom.ac.Vanilla12111RichPhysics.SPRINT_JUMP_HORIZONTAL_BOOST,
        dev.phantom.ac.Vanilla12111RichPhysics.SPRINTING_SPEED_MULTIPLIER,
        0.3,
        dev.phantom.ac.Vanilla12111RichPhysics.WATER_DRAG,
        dev.phantom.ac.Vanilla12111RichPhysics.WATER_SPRINT_DRAG,
        dev.phantom.ac.Vanilla12111RichPhysics.LAVA_DRAG,
        0.15,
        0.15,
        0.035
    );
  }

  public PhysicsProvenance provenance(String field) {
    return PhysicsProvenance.unverified(VERSION,
        "Current Phantom constant; verify against vanilla 1.21.11 source and real-client traces: " + field);
  }
}
