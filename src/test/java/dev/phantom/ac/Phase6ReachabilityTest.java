package dev.phantom.ac;

import static dev.phantom.ac.Maths.Vec3;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Input;
import dev.phantom.ac.Simulation.Vanilla12111Physics;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.Validation.*;

class Phase6ReachabilityTest {
  private static World.Snapshot ground() {
    Map<World.Pos, World.Block> blocks = new HashMap<>();
    for (int x = -8; x <= 8; x++) {
      for (int z = -8; z <= 8; z++) {
        blocks.put(new World.Pos(x, -1, z), World.Block.FULL);
      }
    }
    return new World.Snapshot(blocks);
  }

  @Test
  void completeInputEnvelopeIsFiniteAndDeterministic() {
    List<AdvancedInput> inputs = Validation.allInputs();
    assertEquals(72, inputs.size());
    assertEquals(72, new HashSet<>(inputs).size());
    assertEquals(inputs, Validation.allInputs());
  }

  @Test
  void advancedSprintAndSneakInputsAreReachable() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    Player start = Player.initial(Vec3.ZERO);

    Reachability sprint = reachable.nextAdvanced(start, ground(), false,
        new AdvancedInput(1, 0, false, true, false));
    Reachability sneak = reachable.nextAdvanced(start, ground(), false,
        new AdvancedInput(1, 0, false, false, true));

    assertEquals(Verdict.POSSIBLE, sprint.verdict());
    assertEquals(Verdict.POSSIBLE, sneak.verdict());
    assertEquals(1, sprint.candidates().size());
    assertEquals(1, sneak.candidates().size());
    assertNotEquals(sprint.candidates(), sneak.candidates());
  }

  @Test
  void basicUnknownTickStillUsesLegacyEighteenStateEnvelope() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    Reachability result = reachable.next(Player.initial(Vec3.ZERO), ground(), false);
    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(18, result.candidates().size());
  }

  @Test
  void candidateBudgetNeverLeaksAProvisionalSubset() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    SearchResult result = reachable.advanceAdvanced(
        Player.initial(Vec3.ZERO), 0,
        List.of(Optional.empty()), ignored -> ground(), 8);

    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertTrue(result.candidates().isEmpty());
    assertTrue(result.prunedCandidates() > 0);
    assertTrue(result.reasons().getFirst().contains("budget exceeded"));
  }

  @Test
  void knownAdvancedSequenceRemainsExactlyReachable() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    Player start = Player.initial(Vec3.ZERO);
    AdvancedInput input = new AdvancedInput(1, 0, false, true, false);
    SearchResult result = reachable.advanceAdvanced(start, 10,
        List.of(Optional.of(input), Optional.of(input)), ignored -> ground(), 16);

    Player first = new Vanilla12111Physics().step(
        new Simulation.PhysicsContext(10, start, input, ground(), Simulation.Environment.DRY, Simulation.Attributes.DEFAULT)).state();
    Player expected = new Vanilla12111Physics().step(
        new Simulation.PhysicsContext(11, first, input, ground(), Simulation.Environment.DRY, Simulation.Attributes.DEFAULT)).state();

    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(Set.of(expected), result.candidates());
    assertEquals(2, result.simulatedTicks());
  }

  @Test
  void uncertainTimingWindowCannotBecomeImpossible() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    Player start = Player.initial(Vec3.ZERO);
    AdvancedInput input = new AdvancedInput(1, 0, false, false, false);
    SyncWindow timing = new SyncWindow(10, 11, true, List.of("jitter"));

    TimingSearchResult result = reachable.advanceWithinWindow(start, timing,
        List.of(Optional.of(input)), ignored -> ground(), 16);

    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertEquals(2, result.evaluatedOffsets());
    assertEquals(0, result.skippedOffsets());
    assertEquals(2, result.byFirstTick().size());
  }

  @Test
  void stableTimingWindowCanProducePossibleEvidence() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    Player start = Player.initial(Vec3.ZERO);
    AdvancedInput input = new AdvancedInput(1, 0, false, false, false);
    SyncWindow timing = new SyncWindow(10, 10, false, List.of("stable"));

    TimingSearchResult result = reachable.advanceWithinWindow(start, timing,
        List.of(Optional.of(input)), ignored -> ground(), 16);
    Player observed = result.candidates().iterator().next();

    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(Verdict.POSSIBLE,
        Validation.validate(observed,
            new Reachability(Verdict.POSSIBLE, result.candidates(), result.reasons()),
            timing).verdict());
  }

  @Test
  void validationPromotesSynchronizationAmbiguityToUncertain() {
    Player observed = Player.initial(Vec3.ZERO);
    Reachability reachable = new Reachability(Verdict.POSSIBLE, Set.of(observed), List.of("exact candidate"));
    SyncWindow uncertain = new SyncWindow(4, 5, true, List.of("jitter"));

    Evidence evidence = Validation.validate(observed, reachable, uncertain);
    assertEquals(Verdict.UNCERTAIN, evidence.verdict());
    assertTrue(evidence.reasons().getFirst().contains("4..5"));
  }

  @Test
  void overwideTimingWindowIsDeclaredUncertainWithoutSampling() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    TimingSearchResult result = reachable.advanceWithinWindow(
        Player.initial(Vec3.ZERO),
        new SyncWindow(0, 128, true, List.of("large jitter")),
        List.of(Optional.of(new AdvancedInput(0, 0, false, false, false))),
        ignored -> ground(), 16);

    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertEquals(0, result.evaluatedOffsets());
    assertEquals(129, result.skippedOffsets());
    assertTrue(result.reasons().getFirst().contains("timing-search envelope"));
  }

  @Test
  void observedInputMapsOpposingDirectionsToZero() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    Reachability result = reachable.next(Player.initial(Vec3.ZERO), ground(), false,
        new ClientInput(true, true, false, false, false, false, false));
    Reachability zero = reachable.nextAdvanced(Player.initial(Vec3.ZERO), ground(), false,
        new AdvancedInput(0, 0, false, false, false));

    assertEquals(zero.candidates(), result.candidates());
  }

  @Test
  void basicAdvanceRemainsCompatibleWithExistingContract() {
    ReachableStates reachable = new ReachableStates(new Vanilla12111Physics());
    Input input = new Input(1, 0, false);
    SearchResult result = reachable.advance(Player.initial(Vec3.ZERO), 2,
        List.of(Optional.of(input), Optional.of(input)), ignored -> ground(), 4);
    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(2, result.simulatedTicks());
    assertEquals(1, result.candidates().size());
  }
}
