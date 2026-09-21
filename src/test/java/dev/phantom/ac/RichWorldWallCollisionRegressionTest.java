package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the steady-state case where a walking player is
 * pushing diagonally into a wall while continuing along the wall.
 */
class RichWorldWallCollisionRegressionTest {

  @Test
  void epsilonContactClipsIntoWallWhilePreservingParallelMovement() {
    var stone = BlockCatalogue12111.decode("minecraft:stone", java.util.Map.of());
    var world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(1, 64, 0, stone)
        .build();

    // The player's right face is only 5e-8 inside the wall. This is the
    // numerical state Grim treats as horizontal collision rather than letting
    // the next diagonal movement pass through the wall.
    var player = new Maths.Aabb(
        0.4, 64.0, 0.2,
        1.00000005, 65.8, 0.8);

    var result = RichWorldCollision.resolve(
        world,
        player,
        new Maths.Vec3(0.2, 0.0, 0.15),
        0.0);

    assertTrue(result.collidedX(), result.diagnostic());
    assertTrue(result.displacement().x() <= 0.0, result.toString());
    assertTrue(result.displacement().x() >= -1.0e-7, result.toString());

    // The component parallel to the wall must remain available.
    assertEquals(0.15, result.displacement().z(), 1.0e-12, result.toString());
    assertFalse(result.uncertain(), result.diagnostic());
  }
}
