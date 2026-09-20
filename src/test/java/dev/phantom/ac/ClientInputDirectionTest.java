package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.phantom.ac.Packets.ClientInput;

class ClientInputDirectionTest {
  @Test
  void rightInputMapsToNegativeStrafe() {
    var constraint = Phase6Reachability.InputConstraint.fromClientInput(
        new ClientInput(false, false, false, true, false, false, false));
    assertEquals(-1, constraint.strafe().orElseThrow());
  }

  @Test
  void leftInputMapsToPositiveStrafe() {
    var constraint = Phase6Reachability.InputConstraint.fromClientInput(
        new ClientInput(false, false, true, false, false, false, false));
    assertEquals(1, constraint.strafe().orElseThrow());
  }
}
