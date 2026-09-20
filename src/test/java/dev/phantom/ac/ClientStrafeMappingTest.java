package dev.phantom.ac;

import static dev.phantom.ac.Maths.Vec3;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Input;
import dev.phantom.ac.Simulation.Vanilla12111Physics;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.Validation.Reachability;
import dev.phantom.ac.Validation.ReachableStates;

class ClientStrafeMappingTest {
  private static World.Snapshot floor() {
    Map<World.Pos, World.Block> blocks = new HashMap<>();
    for (int x = -2; x <= 2; x++) {
      for (int z = -2; z <= 2; z++) {
        blocks.put(new World.Pos(x, -1, z), World.Block.FULL);
      }
    }
    return new World.Snapshot(blocks);
  }

  @Test
  void leftAndRightMapToVanillaStrafeDirections() {
    var left = Phase6Reachability.InputConstraint.fromClientInput(
        new ClientInput(false, false, true, false, false, false, false));
    var right = Phase6Reachability.InputConstraint.fromClientInput(
        new ClientInput(false, false, false, true, false, false, false));

    assertEquals(1, left.strafe().orElseThrow());
    assertEquals(-1, right.strafe().orElseThrow());
  }

  @Test
  void legacyClientInputProjectionUsesTheSameStrafeConvention() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    Player start = Player.initial(Vec3.ZERO);

    Reachability left = reachable.next(
        start,
        floor(),
        false,
        new ClientInput(false, false, true, false, false, false, false));
    Reachability expected = reachable.nextAdvanced(
        start,
        floor(),
        false,
        new AdvancedInput(0, 1, false, false, false));

    assertEquals(left.verdict(), expected.verdict());
    assertEquals(left.candidates(), expected.candidates());
  }

  @Test
  void timelineProjectionUsesTheSameStrafeConvention() {
    var packets = java.util.List.<Packets.RawPacket>of(
        new Packets.RawPacket(1L, 0L,
            new ClientInput(false, false, true, false, false, false, false)),
        new Packets.RawPacket(2L, 50_000_000L,
            new Packets.Move(Vec3.ZERO, 0f, 0f, true, null)));
    var timeline = Timeline.assign(
        new Packets.Normalizer().normalize(packets),
        0L,
        50_000_000L);

    Input projected = Timeline.projectInputs(timeline).getFirst().input().orElseThrow();
    assertEquals(1, projected.strafe());
  }
}
