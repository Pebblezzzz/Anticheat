package dev.phantom.ac;

import dev.phantom.ac.physics.PhysicsProfile12111;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhysicsProfile12111Test {
  @Test void profileMatchesCanonicalPhysicsAuthority() {
    var p = PhysicsProfile12111.current();
    assertEquals(Vanilla12111RichPhysics.GRAVITY, p.gravity());
    assertEquals(Vanilla12111RichPhysics.JUMP, p.jumpVelocity());
    assertEquals(Vanilla12111RichPhysics.STEP_HEIGHT, p.stepHeight());
    assertEquals(Vanilla12111RichPhysics.WATER_DRAG, p.waterDrag());
    assertEquals(Vanilla12111RichPhysics.LAVA_DRAG, p.lavaDrag());
  }

  @Test void profileIsPinnedToTargetVersion() {
    assertEquals("1.21.11", PhysicsProfile12111.VERSION);
  }
}
