package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Phase8MovementValidation.Evidence;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

class GrimAlertPolicyTest {
  private static Evidence impossible(String rule, long tick) {
    Player p = Player.initial(Maths.Vec3.ZERO);
    return new Evidence(
        Phase8MovementValidation.VERSION, Verdict.IMPOSSIBLE, "player", tick, tick, tick,
        p, p, Contracts.TARGET_VERSION, "world:test",
        List.of("input=known"), List.of(), 1, 0, 1,
        "deterministic contradiction", OptionalLong.of(tick), Optional.empty(),
        List.of("test"), List.of(), Phase8MovementValidation.PHASE5_VERSION,
        Phase8MovementValidation.PHASE6_VERSION, Phase8MovementValidation.PHASE7_VERSION,
        "replay:" + tick, rule);
  }

  @Test void defaultGroupsMatchGrimThresholds() {
    GrimAlertPolicy.Config config = GrimAlertPolicy.Config.defaults();

    assertEquals(100.0, config.forRule("MOVEMENT_REACHABILITY").alert().threshold());
    assertEquals(40.0, config.forRule("MOVEMENT_REACHABILITY").alert().interval());
    assertEquals(1.0, config.forRule("MOVEMENT_REACHABILITY").log().threshold());

    assertEquals(1.0, config.forRule("Reach").alert().threshold());
    assertEquals(1.0, config.forRule("Reach").alert().interval());

    assertEquals(10.0, config.forRule("FarBreak").alert().threshold());
    assertEquals(5.0, config.forRule("FarBreak").alert().interval());
    assertEquals(300_000L, config.forRule("FarBreak").removeViolationsAfterMillis());
  }

  @Test void movementViolationsExpireFromTheRollingWindow() {
    GrimAlertPolicy.CommandRule alert = GrimAlertPolicy.CommandRule.parse("2:2");
    GrimAlertPolicy.Group group = new GrimAlertPolicy.Group(
        "Simulation", 100L, List.of("MOVEMENT_REACHABILITY"), alert,
        GrimAlertPolicy.CommandRule.parse("1:1"));
    GrimAlertPolicy.Config policy = new GrimAlertPolicy.Config(
        List.of(group), alert, GrimAlertPolicy.CommandRule.parse("1:1"), 100L);
    Phase8MovementValidation.Config config = new Phase8MovementValidation.Config(
        100.0, 0, true, true, 1.0, 0.0, 100.0, 40.0, policy);

    var accumulator = Phase8MovementValidation.Accumulator.empty();

    var first = accumulator.accept(impossible("MOVEMENT_REACHABILITY", 1), config, 0L);
    assertTrue(first.log().isPresent());
    assertTrue(first.alert().isEmpty());
    accumulator = first.state();

    var second = accumulator.accept(impossible("MOVEMENT_REACHABILITY", 2), config, 10L);
    assertTrue(second.alert().isPresent());
    assertEquals(2.0, second.alert().orElseThrow().violationLevel());
    accumulator = second.state();

    var expired = accumulator.accept(impossible("MOVEMENT_REACHABILITY", 3), config, 110L);
    assertTrue(expired.alert().isEmpty(), "old flags must expire from the active window");
    assertEquals(1.0,
        expired.state().players().get("player/MOVEMENT_REACHABILITY").violationLevel());
  }

  @Test void logChannelRunsOnEveryHardFlagWithoutWaitingForStaffAlertThreshold() {
    GrimAlertPolicy.Group group = new GrimAlertPolicy.Group(
        "Reach", 300_000L, List.of("Reach"),
        GrimAlertPolicy.CommandRule.parse("1:1"),
        GrimAlertPolicy.CommandRule.parse("1:1"));
    GrimAlertPolicy.Config policy = new GrimAlertPolicy.Config(
        List.of(group),
        GrimAlertPolicy.CommandRule.parse("100:40"),
        GrimAlertPolicy.CommandRule.parse("1:1"),
        300_000L);
    Phase8MovementValidation.Config config = new Phase8MovementValidation.Config(
        100.0, 0, true, true, 1.0, 0.0, 100.0, 40.0, policy);

    var result = Phase8MovementValidation.Accumulator.empty().accept(
        impossible("Reach", 20), config, 1_000L);

    assertTrue(result.log().isPresent());
    assertTrue(result.alert().isPresent());
  }
}
