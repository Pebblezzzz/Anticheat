package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Phase5AttributeScaleTest {
  @Test
  void defaultMovementSpeedUsesVanillaPlayerAttributeScale() {
    assertEquals(0.1, Simulation.Attributes.DEFAULT.movementSpeed(), 0.0);
  }
}
