package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Phase8MovementValidation.CandidateSummary;
import dev.phantom.ac.Phase8MovementValidation.Evidence;
import dev.phantom.ac.Phase8MovementValidation.State;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class Phase8EnforcementPolicyTest {
  private static Evidence evidence(Verdict verdict, String reason, long tick, OptionalLong first) {
    Player p = Player.initial(Maths.Vec3.ZERO);
    Validation.SyncWindow timing = new Validation.SyncWindow(tick, tick, false, List.of("exact timing"));
    return new Evidence(
        Phase8MovementValidation.VERSION, verdict, "player", tick, tick, tick,
        p, p, Contracts.TARGET_VERSION, "world:test", List.of("input=known"),
        timing.reasons(), 4, verdict == Verdict.POSSIBLE ? 1 : 0,
        verdict == Verdict.IMPOSSIBLE ? 4 : 0, reason, first,
        Optional.<CandidateSummary>empty(), List.of("diagnostic"), List.of(),
        Phase8MovementValidation.PHASE5_VERSION,
        Phase8MovementValidation.PHASE6_VERSION,
        Phase8MovementValidation.PHASE7_VERSION,
        "replay:" + tick, "MOVEMENT_REACHABILITY");
  }

  private static Phase8EnforcementPolicy.Config config() {
    return new Phase8EnforcementPolicy.Config(
        true, true, true, true, 2, 1.0, "punish {player} {rule} {tick} {replay}");
  }

  @Test void nonImpossibleEvidenceNeverProducesEnforcementActions() {
    var decision = Phase8EnforcementPolicy.evaluate(
        evidence(Verdict.POSSIBLE, "match", 1, OptionalLong.empty()),
        State.empty(), config());
    assertFalse(decision.eligible());
    assertTrue(decision.actions().isEmpty());
  }

  @Test void nonExhaustiveImpossibleEvidenceIsRejectedByPolicy() {
    var decision = Phase8EnforcementPolicy.evaluate(
        evidence(Verdict.IMPOSSIBLE, "not exhaustive", 2, OptionalLong.of(2)),
        new State(2, 2, 0, 0, 2, -1), config());
    assertFalse(decision.eligible());
    assertTrue(decision.actions().isEmpty());
  }

  @Test void repeatedExhaustiveImpossibleEvidenceEnablesConfiguredActions() {
    var decision = Phase8EnforcementPolicy.evaluate(
        evidence(
            Verdict.IMPOSSIBLE,
            "all exhaustively modeled legitimate candidates disagree with the observed movement state",
            3, OptionalLong.of(3)),
        new State(2, 2, 0, 0, 3, -1), config());
    assertTrue(decision.eligible());
    assertEquals(
        java.util.Set.of(
            Phase8EnforcementPolicy.Action.SETBACK,
            Phase8EnforcementPolicy.Action.KICK,
            Phase8EnforcementPolicy.Action.PUNISHMENT_COMMAND),
        decision.actions());
    assertEquals(1.0, decision.confidence());
  }

  @Test void punishmentTemplateIsDeterministicallyExpanded() {
    String command = Phase8EnforcementPolicy.renderPunishmentCommand(
        "punish {player} {rule} tick={tick} replay={replay} first={firstInconsistentTick}",
        "Alice",
        evidence(
            Verdict.IMPOSSIBLE,
            "all exhaustively modeled legitimate candidates disagree with the observed movement state",
            9, OptionalLong.of(7)));
    assertEquals(
        "punish Alice MOVEMENT_REACHABILITY tick=9 replay=replay:9 first=7",
        command);
  }
  @Test void enforcementDoesNotRepeatAfterThresholdWithinSameEpisode() {
    Evidence e = evidence(
        Verdict.IMPOSSIBLE,
        "all exhaustively modeled legitimate candidates disagree with the observed movement state",
        4, OptionalLong.of(4));
    var decision = Phase8EnforcementPolicy.evaluate(
        e, new State(3, 3, 0, 0, 4, -1), config());
    assertFalse(decision.eligible());
    assertTrue(decision.actions().isEmpty());
    assertTrue(decision.reason().contains("already"));
  }


}