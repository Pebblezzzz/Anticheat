package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

/**
 * Phase 5 regression coverage for non-survival movement modes.
 *
 * <p>These are deterministic client-model tests, not empirical client traces:
 * the movement authority must model the version-pinned flight rules directly
 * rather than treating creative/spectator/flying as permanently uncertain.</p>
 */
class Phase5UnsupportedModesTest {
  private static final WorldSnapshot WORLD = WorldSnapshot.emptyOverworld12111();
  private static final EntityCollisions ENTITIES = EntityCollisions.of(List.of(), true);

  @Test void creativeFlightIsModeled() {
    Player creative = new Player(
        new Maths.Vec3(.5, 70, .5), Maths.Vec3.ZERO, 0f, 0f, false, "creative",
        Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

    var result = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
        1, creative, new AdvancedInput(1, 0, false, true, false), WORLD,
        Simulation.Environment.DRY, Attributes.DEFAULT, MovementEffects.NONE,
        Pose.STANDING, MovementEnvironment.dry(false, true, false), false, true, ENTITIES));

    assertFalse(result.state().uncertain());
    assertNotEquals(creative.position(), result.state().position());
  }

  @Test void spectatorFlightIsModeledAsNoclip() {
    Player spectator = new Player(
        new Maths.Vec3(.5, 70, .5), Maths.Vec3.ZERO, 0f, 0f, false, "spectator",
        Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());

    var result = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
        1, spectator, new AdvancedInput(1, 0, false, false, false), WORLD,
        Simulation.Environment.DRY, Attributes.DEFAULT, MovementEffects.NONE,
        Pose.STANDING, MovementEnvironment.dry(false, false, false), false, false, ENTITIES));

    assertFalse(result.state().uncertain());
    assertNotEquals(spectator.position(), result.state().position());
  }

  @Test void explicitFlyingStateIsModeledWithoutGamemodeHeuristic() {
    Player survival = Player.initial(new Maths.Vec3(.5, 70, .5));

    var result = new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
        1, survival, new AdvancedInput(1, 0, false, true, false), WORLD,
        Simulation.Environment.DRY, Attributes.DEFAULT, MovementEffects.NONE,
        Pose.STANDING, MovementEnvironment.dry(false, true, false), false, true, ENTITIES));

    assertFalse(result.state().uncertain());
    assertNotEquals(survival.position(), result.state().position());
  }
}
