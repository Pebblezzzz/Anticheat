package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase5UnsupportedModesTest {
  private static final WorldSnapshot WORLD = WorldSnapshot.emptyOverworld12111();
  private static final EntityCollisions ENTITIES = EntityCollisions.of(List.of(), true);

  @Test void creativeMovementPropagatesUncertainty() {
    Player creative = new Player(
        new Maths.Vec3(.5, 70, .5), Maths.Vec3.ZERO, 0f, 0f, false, "creative",
        Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
    var result = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
        1, creative, new AdvancedInput(1, 0, false, true, false), WORLD,
        Simulation.Environment.DRY, Attributes.DEFAULT, MovementEffects.NONE,
        Pose.STANDING, MovementEnvironment.dry(false, true, false), false, true, ENTITIES));
    assertTrue(result.state().uncertain());
    assertTrue(result.diagnostic().contains("creative"));
  }

  @Test void spectatorMovementPropagatesUncertainty() {
    Player spectator = new Player(
        new Maths.Vec3(.5, 70, .5), Maths.Vec3.ZERO, 0f, 0f, false, "spectator",
        Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
    var result = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
        1, spectator, new AdvancedInput(1, 0, false, false, false), WORLD,
        Simulation.Environment.DRY, Attributes.DEFAULT, MovementEffects.NONE,
        Pose.STANDING, MovementEnvironment.dry(false, false, false), false, false, ENTITIES));
    assertTrue(result.state().uncertain());
    assertTrue(result.diagnostic().contains("spectator"));
  }

  @Test void flyingMovementPropagatesUncertainty() {
    Player survival = Player.initial(new Maths.Vec3(.5, 70, .5));
    var result = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
        1, survival, new AdvancedInput(1, 0, false, true, false), WORLD,
        Simulation.Environment.DRY, Attributes.DEFAULT, MovementEffects.NONE,
        Pose.STANDING, MovementEnvironment.dry(false, true, false), false, true, ENTITIES));
    assertTrue(result.state().uncertain());
  }
}
