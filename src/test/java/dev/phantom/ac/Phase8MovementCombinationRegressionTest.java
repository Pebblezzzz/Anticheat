package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.PlayerContext;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

// Coverage is intentionally broader than individual bug reproductions so movement regressions are caught centrally.
class Phase8MovementCombinationRegressionTest {

  private static final double VANILLA_BASE_MOVEMENT_SPEED = 0.1D;

  private static WorldSnapshot floorWorld() {
    var stone = BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -16; x <= 24; x++) {
      for (int z = -16; z <= 24; z++) {
        builder.setBlock(x, 63, z, stone);
      }
    }
    return builder.build();
  }

  private static Player startPlayer() {
    return new Player(
        new Maths.Vec3(0.5, 64.0, 0.5),
        Maths.Vec3.ZERO,
        0f,
        0f,
        true,
        "survival",
        Map.of(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        new Simulation.Attributes(VANILLA_BASE_MOVEMENT_SPEED),
        Pose.STANDING,
        State.Environment.DRY,
        State.TickRange.exact(0),
        State.Provenance.UNKNOWN,
        Set.of());
  }

  private static ClientInput packet(Simulation.AdvancedInput input) {
    return new ClientInput(
        input.forward() > 0,
        input.forward() < 0,
        input.strafe() < 0,
        input.strafe() > 0,
        input.jump(),
        input.sneak(),
        input.sprint());
  }

  private static Player advance(
      Vanilla12111RichPhysics physics,
      WorldSnapshot world,
      Player state,
      long simulationTick,
      Simulation.AdvancedInput input) {
    return physics.step(
        new Vanilla12111RichPhysics.Context(
            simulationTick,
            state,
            input,
            world,
            Simulation.Environment.DRY,
            state.attributes(),
            MovementEffects.NONE,
            state.pose(),
            MovementEnvironment.dry(
                state.onGround(),
                input.sprint(),
                input.sneak()),
            false,
            false,
            EntityCollisions.NONE_TRACKED)).state();
  }

  private static Phase8PredictionRunner.Report runSequence(
      String id,
      List<Simulation.AdvancedInput> inputs) {
    WorldSnapshot world = floorWorld();
    Player start = startPlayer();
    Vanilla12111RichPhysics physics = new Vanilla12111RichPhysics();
    Player state = start;

    PlayerContext authority = new PlayerContext(
        "survival",
        start.attributes(),
        Map.of(),
        Pose.STANDING,
        MovementEnvironment.dry(true, false, false),
        start.position(),
        start.velocity(),
        false,
        false,
        false,
        List.of());

    List<RawPacket> packets = new ArrayList<>();
    packets.add(new RawPacket(1L, 10L, authority));

    long sequence = 2L;
    long serverTick = 20L;
    long clientTick = 1L;
    long simulationTick = 0L;

    for (Simulation.AdvancedInput input : inputs) {
      packets.add(new RawPacket(sequence++, serverTick, packet(input)));
      state = advance(physics, world, state, simulationTick++, input);
      packets.add(new RawPacket(
          sequence++,
          serverTick + 10L,
          new Move(
              state.position(),
              0f,
              0f,
              state.onGround(),
              clientTick++)));
      serverTick += 20L;
    }

    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    return runner.process(id, packets, world, start, 0L);
  }

  private static List<Simulation.AdvancedInput> allAdvancedInputs() {
    List<Simulation.AdvancedInput> inputs = new ArrayList<>(72);
    for (int forward = -1; forward <= 1; forward++) {
      for (int strafe = -1; strafe <= 1; strafe++) {
        for (boolean jump : new boolean[] {false, true}) {
          for (boolean sprint : new boolean[] {false, true}) {
            for (boolean sneak : new boolean[] {false, true}) {
              inputs.add(new Simulation.AdvancedInput(
                  forward, strafe, jump, sprint, sneak));
            }
          }
        }
      }
    }
    return List.copyOf(inputs);
  }

  @Test
  void completeAdvancedInputEnvelopeHasNoMovementFalseFlags() {
    int checked = 0;
    for (Simulation.AdvancedInput input : allAdvancedInputs()) {
      var report = runSequence("movement-matrix-" + checked, List.of(input));
      assertEquals(
          1,
          report.movementObservations(),
          "input=" + input + " report=" + report);

      assertFalse(
          report.results().isEmpty(),
          "missing validation result for input=" + input);

      assertTrue(
          report.results().stream().allMatch(
              result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE),
          "legitimate input combination was rejected: input=" + input
              + " results=" + report.results());

      checked++;
    }

    assertEquals(72, checked);
  }

  @Test
  void sprintJumpWithSneakFalseRemainsPossibleAcrossAirborneTicks() {
    Simulation.AdvancedInput sprint = new Simulation.AdvancedInput(1, 0, false, true, false);
    Simulation.AdvancedInput sprintJump = new Simulation.AdvancedInput(1, 0, true, true, false);
    Simulation.AdvancedInput sprintStrafeRight = new Simulation.AdvancedInput(1, 1, false, true, false);
    Simulation.AdvancedInput sprintStrafeLeft = new Simulation.AdvancedInput(1, -1, false, true, false);

    var report = runSequence(
        "sprint-jump-shift-false",
        List.of(
            sprint,
            sprintJump,
            sprint,
            sprintStrafeRight,
            sprintStrafeLeft,
            sprint));

    assertEquals(6, report.movementObservations(), report.toString());
    assertEquals(
        6,
        report.results().size(),
        report.toString());
    assertTrue(
        report.results().stream().allMatch(
            result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE),
        report.results().toString());
  }

  @Test
  void sneakingMovementToggleAndJumpRemainPossible() {
    Simulation.AdvancedInput sneakWalk = new Simulation.AdvancedInput(1, 0, false, false, true);
    Simulation.AdvancedInput sneakStrafe = new Simulation.AdvancedInput(1, 1, false, false, true);
    Simulation.AdvancedInput sneakJump = new Simulation.AdvancedInput(1, 0, true, false, true);
    Simulation.AdvancedInput normalWalk = new Simulation.AdvancedInput(1, 0, false, false, false);
    Simulation.AdvancedInput sprint = new Simulation.AdvancedInput(1, 0, false, true, false);

    var report = runSequence(
        "sneak-toggle-jump",
        List.of(
            sneakWalk,
            sneakWalk,
            sneakStrafe,
            normalWalk,
            sneakJump,
            normalWalk,
            sprint));

    assertEquals(7, report.movementObservations(), report.toString());
    assertTrue(
        report.results().stream().allMatch(
            result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE),
        report.results().toString());
  }

  @Test
  void sprintDiagonalJumpAndDirectionChangesRemainPossible() {
    Simulation.AdvancedInput sprintDiagonal = new Simulation.AdvancedInput(1, 1, false, true, false);
    Simulation.AdvancedInput sprintDiagonalJump = new Simulation.AdvancedInput(1, 1, true, true, false);
    Simulation.AdvancedInput sprintBackDiagonal = new Simulation.AdvancedInput(-1, -1, false, true, false);
    Simulation.AdvancedInput sprintBack = new Simulation.AdvancedInput(-1, 0, false, true, false);
    Simulation.AdvancedInput strafe = new Simulation.AdvancedInput(0, -1, false, false, false);

    var report = runSequence(
        "sprint-diagonal-jump-direction",
        List.of(
            sprintDiagonal,
            sprintDiagonalJump,
            sprintDiagonal,
            sprintBackDiagonal,
            sprintBack,
            strafe));

    assertEquals(6, report.movementObservations(), report.toString());
    assertTrue(
        report.results().stream().allMatch(
            result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE),
        report.results().toString());
  }

  @Test
  void heldJumpDoesNotTurnAValidSprintJumpIntoAFalseFlag() {
    Simulation.AdvancedInput heldSprintJump = new Simulation.AdvancedInput(1, 0, true, true, false);
    Simulation.AdvancedInput heldSprint = new Simulation.AdvancedInput(1, 0, false, true, false);

    var report = runSequence(
        "held-sprint-jump",
        List.of(
            heldSprint,
            heldSprintJump,
            heldSprintJump,
            heldSprint,
            heldSprint));

    assertEquals(5, report.movementObservations(), report.toString());
    assertTrue(
        report.results().stream().allMatch(
            result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE),
        report.results().toString());
  }
}
