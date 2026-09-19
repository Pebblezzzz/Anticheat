package dev.phantom.ac;

import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.EntityCollisions;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Phase5AuthorityTest {
  private static WorldSnapshot world() {
    return WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(-1,-1).loadChunk(-1,0).loadChunk(0,-1).loadChunk(0,0)
        .build();
  }

  private static Phase5MovementAuthority.SimulationContext context(State.Player player, Simulation.AdvancedInput input,
      Phase5Mechanics.MovementEnvironment environment, Phase5Mechanics.Pose pose) {
    return new Phase5MovementAuthority.SimulationContext(1, player, input, world(),
        Simulation.Environment.DRY, player.attributes(), Phase5Mechanics.MovementEffects.fromStateEffects(player.effects()),
        pose, environment, false, false, EntityCollisions.of(List.of()));
  }

  @Test void identicalContextProducesEquivalentResult() {
    var authority = new Phase5MovementAuthority();
    var state = State.Player.initial(new Maths.Vec3(0.5,65,0.5));
    var input = new Simulation.AdvancedInput(1, -1, false, false, false);
    var c = context(state, input, Phase5Mechanics.MovementEnvironment.dry(false,false,false), Phase5Mechanics.Pose.STANDING);
    assertEquals(authority.simulate(c).delegate(), authority.simulate(c).delegate());
  }

  @Test void fallingSpendsGravityBeforeCollisionMove() {
    var state = new State.Player(new Maths.Vec3(0.5,65,0.5), Maths.Vec3.ZERO, 0,0,false,
        "survival", java.util.Map.of(), java.util.OptionalInt.empty(), false);
    var c = context(state, new Simulation.AdvancedInput(0,0,false),
        Phase5Mechanics.MovementEnvironment.dry(false,false,false), Phase5Mechanics.Pose.STANDING);
    var result = new Phase5MovementAuthority().simulate(c);
    assertEquals(-0.08, result.state().position().y()-state.position().y(), 1e-12);
    assertEquals(-0.0784, result.state().velocity().y(), 1e-12);
  }

  @Test void waterUsesVersionPinnedEightTenthsDrag() {
    assertEquals(0.8, Vanilla12111RichPhysics.WATER_DRAG, 1e-12);
    assertEquals(0.5, Vanilla12111RichPhysics.LAVA_DRAG, 1e-12);
  }

  @Test void spectatorIsExplicitNoPhysicsMode() {
    var state = new State.Player(new Maths.Vec3(0.5,65,0.5), Maths.Vec3.ZERO, 0,0,false,
        "spectator", java.util.Map.of(), java.util.OptionalInt.empty(), false);
    var c = new Phase5MovementAuthority.SimulationContext(1, state,
        new Simulation.AdvancedInput(1,0,false,true,false), world(), Simulation.Environment.DRY,
        state.attributes(), Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(false,true,false), false, false, EntityCollisions.of(List.of()));
    var result = new Phase5MovementAuthority().simulate(c);
    assertFalse(result.state().onGround());
    assertEquals(0.0, result.state().velocity().x(), 1e-12);
  }

  @Test void unknownWorldIsUncertainInsteadOfAir() {
    WorldSnapshot unknown = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadUnknownChunk(0,0).build();
    var state = State.Player.initial(new Maths.Vec3(0.5,65,0.5));
    var c = new Phase5MovementAuthority.SimulationContext(1,state,new Simulation.AdvancedInput(1,0,false),
        unknown,Simulation.Environment.DRY,state.attributes(),Phase5Mechanics.MovementEffects.NONE,
        Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),
        false,false,EntityCollisions.of(List.of()));
    var result = new Phase5MovementAuthority().simulate(c);
    assertTrue(result.state().uncertain());
    assertEquals(state.position(), result.state().position());
  }
}
