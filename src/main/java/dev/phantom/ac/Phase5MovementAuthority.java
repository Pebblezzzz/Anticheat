package dev.phantom.ac;

import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import java.io.Serializable;
import java.util.Objects;

/**
 * Stable Phase 5 boundary for callers that need the movement authority.
 *
 * <p>The context is a complete deterministic snapshot: no wall clock, global
 * mutable state, server TPS, Bukkit physics, or unordered iteration is consulted
 * by the authority.</p>
 */
public final class Phase5MovementAuthority {
  public static final String VERSION = Vanilla12111RichPhysics.VERSION;
  private final Vanilla12111RichPhysics physics;

  public Phase5MovementAuthority() {
    this(new Vanilla12111RichPhysics());
  }

  public Phase5MovementAuthority(Vanilla12111RichPhysics physics) {
    this.physics = Objects.requireNonNull(physics, "physics");
  }

  public StepResult simulate(SimulationContext context) {
    Objects.requireNonNull(context, "context");
    Vanilla12111RichPhysics.StepResult result = physics.step(new Vanilla12111RichPhysics.Context(
        context.simulationTick(), context.state(), context.input(), context.world(),
        context.environment(), context.attributes(), context.effects(), context.pose(),
        context.movementEnvironment(), context.sleeping(), context.flying(),
        context.entityCollisions(), context.actualMovementReference()));
    return new StepResult(result);
  }

  public record SimulationContext(
      long simulationTick,
      State.Player state,
      Simulation.AdvancedInput input,
      WorldSnapshot world,
      Simulation.Environment environment,
      Simulation.Attributes attributes,
      Phase5Mechanics.MovementEffects effects,
      Phase5Mechanics.Pose pose,
      Phase5Mechanics.MovementEnvironment movementEnvironment,
      boolean sleeping,
      boolean flying,
      EntityCollisions entityCollisions,
      Maths.Vec3 actualMovementReference) implements Serializable {
    public SimulationContext {
      if (simulationTick < 0) throw new IllegalArgumentException("simulationTick must be non-negative");
      Objects.requireNonNull(state);
      Objects.requireNonNull(input);
      Objects.requireNonNull(world);
      Objects.requireNonNull(environment);
      Objects.requireNonNull(attributes);
      Objects.requireNonNull(effects);
      Objects.requireNonNull(pose);
      Objects.requireNonNull(movementEnvironment);
      Objects.requireNonNull(entityCollisions);
      if (actualMovementReference != null
          && (!Double.isFinite(actualMovementReference.x())
          || !Double.isFinite(actualMovementReference.y())
          || !Double.isFinite(actualMovementReference.z()))) {
        throw new IllegalArgumentException("actualMovementReference must be finite");
      }
      if (!VERSION.equals(world.version()) && !Contracts.TARGET_VERSION.equals(world.version()))
        throw new IllegalArgumentException("Phase 5 requires Minecraft " + VERSION + " world data");
    }
    public SimulationContext(
        long simulationTick,
        State.Player state,
        Simulation.AdvancedInput input,
        WorldSnapshot world,
        Simulation.Environment environment,
        Simulation.Attributes attributes,
        Phase5Mechanics.MovementEffects effects,
        Phase5Mechanics.Pose pose,
        Phase5Mechanics.MovementEnvironment movementEnvironment,
        boolean sleeping,
        boolean flying,
        EntityCollisions entityCollisions) {
      this(simulationTick, state, input, world, environment, attributes, effects, pose,
          movementEnvironment, sleeping, flying, entityCollisions, null);
    }
  }

  public record StepResult(Vanilla12111RichPhysics.StepResult delegate) implements Serializable {
    public StepResult {
      Objects.requireNonNull(delegate);
    }
    public State.Player state() { return delegate.state(); }
    public long simulationTick() { return delegate.simulationTick(); }
    public boolean collided() { return delegate.collided(); }
    public boolean stepAttempted() { return delegate.stepAttempted(); }
    public boolean stepSucceeded() { return delegate.stepSucceeded(); }
    public boolean collisionX() { return delegate.collisionX(); }
    public boolean collisionY() { return delegate.collisionY(); }
    public boolean collisionZ() { return delegate.collisionZ(); }
    public String diagnostic() { return delegate.diagnostic(); }
    public boolean sneakEdgeConstrained() { return delegate.sneakEdgeConstrained(); }
  }
}