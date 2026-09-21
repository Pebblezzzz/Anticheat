package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class Phase8ClientModelTest {
  @org.junit.jupiter.api.Test
  void clientAndServerVelocitiesAreTrackedSeparately() {
    Player anchor = Player.initial(new Vec3(10.0, 64.0, 10.0));
    Phase8ClientModel.ClientPhysicsState state =
        Phase8ClientModel.ClientPhysicsState.initial(anchor);

    state = state.withServerAuthority(
        12L,
        new Vec3(10.0, 64.0, 10.0),
        new Vec3(0.0, -0.0784, 0.0),
        44L);
    state = state.observe(
        12L,
        anchor,
        new Vec3(0.0, 0.0830778, 0.02),
        new Vec3(0.12, 0.0830778, 0.01),
        new Vec3(0.13, 0.08, 0.011),
        state.serverVelocity(),
        state.serverTick(),
        "test-observation");

    assertEquals(new Vec3(0.12, 0.0830778, 0.01), state.clientVelocity());
    assertEquals(new Vec3(0.13, 0.08, 0.011), state.predictedVelocity());
    assertEquals(new Vec3(0.0, -0.0784, 0.0), state.serverVelocity());
    assertTrue(state.serverVelocityIsDistinctFromClientVelocity());
    assertEquals(new Vec3(0.0, 0.0830778, 0.02), state.actualMovement());
    assertEquals(44L, state.serverTick());
  }

  @org.junit.jupiter.api.Test
  void compensatedWorldRecordsCausalBoundary() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).build();

    Phase8ClientModel.CompensatedWorld valid =
        Phase8ClientModel.CompensatedWorld.forMovement(world, 20L, "test");
    assertEquals(-1L, valid.causalSequence());
    assertTrue(valid.causallyBounded());

    Phase8ClientModel.CompensatedWorld unbounded =
        new Phase8ClientModel.CompensatedWorld(world, 20L, 30L, false, "test");
    assertFalse(unbounded.causallyBounded());
  }

  @org.junit.jupiter.api.Test
  void tickReliabilitySeparatesUnknownFromPartialTiming() {
    Phase8ClientModel.TickReliabilityState unknown =
        Phase8ClientModel.TickReliabilityState.assess(
            0L, false, false, true, false, false);
    assertEquals(Phase8ClientModel.Reliability.UNRELIABLE, unknown.reliability());
    assertTrue(unknown.reasons().stream().anyMatch(r -> r.contains("not known")));

    Phase8ClientModel.TickReliabilityState partial =
        Phase8ClientModel.TickReliabilityState.assess(
            8L, true, true, true, false, false);
    assertEquals(Phase8ClientModel.Reliability.PARTIAL, partial.reliability());
    assertTrue(partial.reasons().stream().anyMatch(r -> r.contains("timing reconstruction")));

    Phase8ClientModel.TickReliabilityState reliable =
        Phase8ClientModel.TickReliabilityState.assess(
            9L, true, true, false, false, false);
    assertEquals(Phase8ClientModel.Reliability.RELIABLE, reliable.reliability());
    assertTrue(reliable.reasons().isEmpty());
  }

  @org.junit.jupiter.api.Test
  void emptyHypothesisFrontierIsRepresentedExplicitly() {
    assertEquals(List.of(), Phase8ClientModel.hypotheses(java.util.Set.of()));
  }
}
