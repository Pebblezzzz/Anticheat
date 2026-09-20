package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.phantom.ac.Packets.ClientInput;

class ClientInputDirectionTest {
  @Test
  void rightInputMapsToPositiveStrafe() {
    var constraint = Phase6Reachability.InputConstraint.fromClientInput(
        new ClientInput(false, false, false, true, false, false, false));
    assertEquals(1, constraint.strafe().orElseThrow());
  }

  @Test
  void leftInputMapsToNegativeStrafe() {
    var constraint = Phase6Reachability.InputConstraint.fromClientInput(
        new ClientInput(false, false, true, false, false, false, false));
    assertEquals(-1, constraint.strafe().orElseThrow());
  }
}
