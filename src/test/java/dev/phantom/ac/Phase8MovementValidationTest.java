package dev.phantom.ac;

import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.Provenance;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Phase8MovementValidationTest {
  private static WorldSnapshot world() {
    return WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build();
  }

  private static Candidate candidate(Player player) {
    Context context = new Context(20, player, Simulation.Environment.DRY, Simulation.Attributes.DEFAULT,
        MovementEffects.NONE, Pose.STANDING, MovementEnvironment.dry(player.onGround(), false, false), false);
    return new Candidate(1, context, new Provenance(1, -1, 20, "INPUT", "WORLD", "None", List.of("test witness"), 1, List.of()));
  }

  private static SearchResult possible(Player player) {
    return new SearchResult(Phase6Reachability.Verdict.POSSIBLE, Set.of(candidate(player)), 1, 1, 0, 0, 0, 0,
        List.of("controlled exhaustive test envelope"));
  }

  private static Validation.SyncWindow stable() {
    return new Validation.SyncWindow(20, 20, false, List.of("stable timing"));
  }

  @Test void legitimateObservationIsPossible() {
    Player observed = Player.initial(new Maths.Vec3(0.5, 65, 0.5));
    var result = Phase8MovementValidation.validate("alice", 20, observed, observed, world(), "world:test:20", stable(),
        List.of("forward=0", "strafe=0", "jump=false"), possible(observed), "replay:test:20");
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE, result.verdict());
    assertEquals(1, result.evidence().matchingCandidateCount());
    assertEquals(0, result.evidence().candidatesEliminated());
  }

  @Test void impossibleObservationProducesFirstDivergenceAndClosestWitness() {
    Player prior = Player.initial(new Maths.Vec3(0.5, 65, 0.5));
    Player observed = Player.initial(new Maths.Vec3(100.5, 65, 0.5));
    var result = Phase8MovementValidation.validate("alice", 21, prior, observed, world(), "world:test:21", stable(),
        List.of("complete input observation"), possible(prior), "replay:test:21");
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE, result.verdict());
    assertEquals(21, result.evidence().firstInconsistentTick().orElseThrow());
    assertTrue(result.evidence().closestCandidate().isPresent());
    assertEquals(1, result.evidence().candidatesEliminated());
  }

  @Test void timingUncertaintyCannotBecomeImpossibleWithoutExhaustiveProof() {
    Player prior = Player.initial(new Maths.Vec3(0.5, 65, 0.5));
    Player observed = Player.initial(new Maths.Vec3(100.5, 65, 0.5));
    Validation.SyncWindow uncertain = new Validation.SyncWindow(19, 22, true, List.of("jitter", "delayed movement packet"));
    var result = Phase8MovementValidation.validate("alice", 21, prior, observed, world(), "world:test:21", uncertain,
        List.of("input known"), possible(prior), "replay:test:21");
    assertEquals(Phase8MovementValidation.Verdict.UNCERTAIN, result.verdict());
    assertFalse(result.evidence().uncertaintySources().isEmpty());
  }

  @Test void exhaustivelyModeledTimingCanStillProducePossibleEvidence() {
    Player prior = Player.initial(new Maths.Vec3(0.5, 65, 0.5));
    Player observed = prior;
    Validation.SyncWindow uncertain = new Validation.SyncWindow(19, 22, true, List.of("latency bounded", "jitter"));
    var result = Phase8MovementValidation.validate("alice", 21, prior, observed, world(), "world:test:21", uncertain,
        List.of("input known", "all client-tick offsets searched"), possible(observed), "replay:test:21", true);
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE, result.verdict());
    assertEquals(1, result.evidence().matchingCandidateCount());
    assertTrue(result.evidence().simulationDiagnostics().stream().anyMatch(s -> s.contains("timing uncertainty was exhaustively represented")));
  }

  @Test void exhaustivelyModeledTimingCanStillProveImpossible() {
    Player prior = Player.initial(new Maths.Vec3(0.5, 65, 0.5));
    Player observed = Player.initial(new Maths.Vec3(100.5, 65, 0.5));
    Validation.SyncWindow uncertain = new Validation.SyncWindow(19, 22, true, List.of("latency bounded", "jitter"));
    var result = Phase8MovementValidation.validate("alice", 21, prior, observed, world(), "world:test:21", uncertain,
        List.of("input known", "all client-tick offsets searched"), possible(prior), "replay:test:21", true);
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE, result.verdict());
    assertEquals(21, result.evidence().firstInconsistentTick().orElseThrow());
    assertFalse(result.evidence().uncertaintySources().isEmpty(), "timing uncertainty should remain visible in evidence");
    assertTrue(result.evidence().simulationDiagnostics().stream().anyMatch(s -> s.contains("timing uncertainty was exhaustively represented")));
  }

  @Test void phase6BudgetUncertaintyCannotBecomeImpossible() {
    Player observed = Player.initial(new Maths.Vec3(0.5, 65, 0.5));
    SearchResult uncertain = new SearchResult(Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 1, 4097, 0, 0, 1, 0,
        List.of("candidate budget exceeded"));
    var result = Phase8MovementValidation.validate("alice", 20, observed, observed, world(), "world:test:20", stable(),
        List.of("input unknown"), uncertain, "replay:test:budget");
    assertEquals(Phase8MovementValidation.Verdict.UNCERTAIN, result.verdict());
  }

  @Test void accumulatorRequiresRepeatedImpossibleEvidenceAndIgnoresUncertainty() {
    Player p = Player.initial(new Maths.Vec3(0, 65, 0));
    var impossible = Phase8MovementValidation.validate("alice", 30, p, Player.initial(new Maths.Vec3(20, 65, 0)), world(), "world:test:30", stable(),
        List.of("input known"), possible(p), "replay:30").evidence();
    var uncertain = Phase8MovementValidation.validate("alice", 31, p, p, world(), "world:test:31",
        new Validation.SyncWindow(30, 32, true, List.of("jitter")), List.of("timing unknown"), possible(p), "replay:31").evidence();

    var accumulator = Phase8MovementValidation.Accumulator.empty();
    var config = new Phase8MovementValidation.Config(2, 0, true, true);
    var first = accumulator.accept(impossible, config);
    assertTrue(first.alert().isEmpty());
    var second = first.state().accept(impossible, config);
    assertTrue(second.alert().isPresent());
    var afterUncertain = second.state().accept(uncertain, config);
    var state = afterUncertain.state().players().get("alice/MOVEMENT_REACHABILITY");
    assertNotNull(state);
    assertEquals(0, state.consecutiveImpossible(), "uncertainty must break the consecutive-impossible streak");
    assertEquals(2, state.supportingImpossible(), "uncertainty must not erase prior impossible evidence");
    assertEquals(1, state.uncertaintyPeriods(), "uncertainty must be tracked separately");
    assertEquals(30, state.lastObservationTick(), "an uncertain observation must not masquerade as a clean observation tick");
    assertEquals(30, state.lastAlertTick(), "uncertainty must not create or advance an alert");
  }

  @Test void replayReproducesSameResultAndEvidence() {
    Player p = Player.initial(new Maths.Vec3(0.5, 65, 0.5));
    Phase8Replay replay = Phase8Replay.of("alice", 20, p, p, world(), "world:test:20", stable(),
        List.of("forward=0", "strafe=0", "jump=false"), possible(p), "replay:deterministic");
    assertEquals(replay.replay(), replay.replay());
    assertEquals(replay.replay().evidence(), replay.replay().evidence());
  }

  @Test void alertIsObservationOnlyAndHasOperatorFormat() {
    Player p = Player.initial(new Maths.Vec3(0, 65, 0));
    var evidence = Phase8MovementValidation.validate("alice", 40, p, Player.initial(new Maths.Vec3(20, 65, 0)), world(), "world:test:40", stable(),
        List.of("input known"), possible(p), "replay:40").evidence();
    var config = new Phase8MovementValidation.Config(1, 0, true, true);
    var alert = Phase8MovementValidation.Accumulator.empty().accept(evidence, config).alert().orElseThrow();
    assertTrue(alert.message().contains("[PhantomAC][PHASE8] player=alice type=MOVEMENT result=IMPOSSIBLE"));
    assertTrue(alert.message().contains("reason=all exhaustively modeled legitimate candidates disagree with the observed movement state"));
    assertEquals("replay:40", alert.replayReference());
  }
}