package dev.phantom.ac;

import java.util.Objects;

/**
 * Grim-style movement-tick lifecycle boundary for Phantom's pinned physics.
 *
 * <p>The ticker owns the boundary between candidate prediction state and the
 * canonical world-bound physics implementation. Packet chronology and server
 * authority stay outside this layer.</p>
 */
public final class GrimMovementTicker {
  private final Vanilla12111RichPhysics physics;

  public GrimMovementTicker() {
    this(new Vanilla12111RichPhysics());
  }

  public GrimMovementTicker(Vanilla12111RichPhysics physics) {
    this.physics = Objects.requireNonNull(physics, "physics");
  }

  public TickResult tick(Phase5MovementAuthority.SimulationContext context) {
    Objects.requireNonNull(context, "context");
    Vanilla12111RichPhysics.StepResult result = physics.step(
        new Vanilla12111RichPhysics.Context(
            context.simulationTick(),
            context.state(),
            context.input(),
            context.world(),
            context.environment(),
            context.attributes(),
            context.effects(),
            context.pose(),
            context.movementEnvironment(),
            context.sleeping(),
            context.flying(),
            context.entityCollisions(),
            context.actualMovementReference(),
            context.lastOnGround(),
            context.clientVelocity()));
    return new TickResult(
        result,
        context.clientVelocity(),
        result.state().velocity(),
        result.clientVelocityAfterTick(),
        context.actualMovementReference());
  }

  private static State.Player withVelocity(State.Player player, Maths.Vec3 velocity) {
    return new State.Player(
        player.position(), velocity, player.yaw(), player.pitch(), player.onGround(),
        player.gamemode(), player.effects(), player.awaitingTeleport(), player.uncertain(),
        player.input(), player.attributes(), player.pose(), player.environment(),
        player.clientTickRange(), player.provenance(), player.uncertaintyReasons());
  }

  public record TickResult(
      Vanilla12111RichPhysics.StepResult delegate,
      Maths.Vec3 clientVelocityBeforeTick,
      Maths.Vec3 predictedVelocityAfterCollision,
      Maths.Vec3 clientVelocityAfterTick,
      Maths.Vec3 actualMovementReference) {
    public TickResult {
      Objects.requireNonNull(delegate);
      Objects.requireNonNull(clientVelocityBeforeTick);
      Objects.requireNonNull(predictedVelocityAfterCollision);
      Objects.requireNonNull(clientVelocityAfterTick);
    }

    public State.Player state() {
      return delegate.state();
    }
  }
}
