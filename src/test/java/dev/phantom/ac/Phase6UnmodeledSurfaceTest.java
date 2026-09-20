package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Phase6UnmodeledSurfaceTest {
  private static WorldSnapshot worldWith(String blockId) {
    var state = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(blockId, Map.of());
    return WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 64, 0, state)
        .build();
  }

  private static Phase6Reachability.Context start() {
    var player = State.Player.initial(new Maths.Vec3(0.5, 64.0, 0.5));
    return new Phase6Reachability.Context(
        0, player, Simulation.Environment.DRY, Simulation.Attributes.DEFAULT,
        MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false);
  }

  @Test
  void unmodeledSlimeSurfaceIsUncertainInsteadOfFalseImpossible() {
    var result = new Phase6Reachability().search(
        start(),
        java.util.List.of(new Phase6Reachability.InputConstraint(
            java.util.OptionalInt.of(0), java.util.OptionalInt.of(0),
            java.util.Optional.of(false), java.util.Optional.of(false),
            java.util.Optional.of(false))),
        tick -> java.util.List.of(new Phase6Reachability.WorldBranch(
            "slime", worldWith("minecraft:slime_block"), true, "known slime")),
        tick -> java.util.List.of(new Phase6Reachability.None()),
        256);

    assertEquals(Phase6Reachability.Verdict.UNCERTAIN, result.verdict(), result.reasons().toString());
    assertTrue(result.reasons().stream().anyMatch(reason ->
        reason.contains("movement surface has vanilla mechanics not yet represented")),
        result.reasons().toString());
  }

  @Test
  void ordinaryStoneRemainsDeterministic() {
    var result = new Phase6Reachability().search(
        start(),
        java.util.List.of(new Phase6Reachability.InputConstraint(
            java.util.OptionalInt.of(0), java.util.OptionalInt.of(0),
            java.util.Optional.of(false), java.util.Optional.of(false),
            java.util.Optional.of(false))),
        tick -> java.util.List.of(new Phase6Reachability.WorldBranch(
            "stone", worldWith("minecraft:stone"), true, "known stone")),
        tick -> java.util.List.of(new Phase6Reachability.None()),
        256);

    assertEquals(Phase6Reachability.Verdict.POSSIBLE, result.verdict(), result.reasons().toString());
  }
}
