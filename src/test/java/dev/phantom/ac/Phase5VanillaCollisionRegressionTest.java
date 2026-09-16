package dev.phantom.ac;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.phantom.ac.Maths.Aabb;
import dev.phantom.ac.Maths.Vec3;

/**
 * Regression locks for behavior observed in an independent Minecraft Java
 * 1.21.11 Survival capture (edge-corner-survival.tsv).
 *
 * The capture showed that, at a solid wall, vanilla clips the blocked horizontal
 * component while preserving an available orthogonal component. At a fully
 * contacted corner, both horizontal components can be clipped. These tests do
 * not fabricate per-axis telemetry; they lock the collision behavior that the
 * observed position/velocity trace demonstrates.
 */
class Phase5VanillaCollisionRegressionTest {
  private static World.Snapshot stoneWall() {
    return new World.Snapshot(
        java.util.Map.of(new World.Pos(1, 0, 0), World.Block.FULL,
                         new World.Pos(0, 0, 1), World.Block.FULL),
        List.of(new World.Chunk(0, 0)).stream().collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void vanillaWallTraceRequiresBlockedAxisToBeClipped() {
    Aabb player = new Aabb(0.4, 0.0, 0.2, 1.0, 1.8, 0.8);
    World.CollisionResult result = World.resolve(stoneWall(), player, new Vec3(1.0, 0.25, 0.0), false);

    assertEquals(0.0, result.resolved().x(), 1.0e-12,
        "the wall-contact setup must clip the blocked X component");
    assertEquals(0.25, result.resolved().y(), 1.0e-12,
        "Y is independently resolved by the collision routine");
    assertEquals(0.0, result.resolved().z(), 1.0e-12);
    assertTrue(result.collidedX());
  }

  @Test
  void vanillaCornerContactCanClipBothHorizontalAxes() {
    Aabb player = new Aabb(0.4, 0.0, 0.4, 1.0, 1.8, 1.0);
    World.CollisionResult result = World.resolve(stoneWall(), player, new Vec3(1.0, 0.0, 1.0), false);

    assertEquals(0.0, result.resolved().x(), 1.0e-12);
    assertEquals(0.0, result.resolved().z(), 1.0e-12);
    assertTrue(result.collidedX());
    assertTrue(result.collidedZ());
  }
}
