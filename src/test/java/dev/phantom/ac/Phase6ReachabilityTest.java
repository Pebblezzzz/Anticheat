package dev.phantom.ac;

import static dev.phantom.ac.Maths.Vec3;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Input;
import dev.phantom.ac.Simulation.Vanilla12111Physics;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.world.WorldSnapshot;
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

  private static WorldSnapshot phase6Ground() {
    WorldSnapshot.Builder builder =
        WorldSnapshot.builder(Contracts.TARGET_VERSION);
    for (int x = -1; x <= 1; x++) {
      for (int z = -1; z <= 1; z++) {
        builder.loadChunk(x, z);
      }
    }
    return builder.build();
  }

  @Test
  void equivalentCandidateStatesMergeWithoutTreatingIdsAsPhysics() {
    Player player = new Player(Vec3.ZERO, Vec3.ZERO, 0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(), Simulation.Attributes.DEFAULT,
        Phase5Mechanics.Pose.STANDING, State.Environment.DRY, State.TickRange.unknown(),
        State.Provenance.UNKNOWN, Set.of());
    var context = new Phase6Reachability.Context(
        0L, player, Simulation.Environment.DRY, Simulation.Attributes.DEFAULT,
        Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false), false);
    var first = new Phase6Reachability.Candidate(
        1L, context,
        new Phase6Reachability.Provenance(1L, -1L, 0L, "input-a", "world", "none", List.of("a"), 1, List.of()));
    var second = new Phase6Reachability.Candidate(
        2L, context,
        new Phase6Reachability.Provenance(2L, -1L, 0L, "input-b", "world", "none", List.of("b"), 1, List.of()));

    Set<Phase6Reachability.Candidate> merged =
        Phase6Reachability.mergeEquivalentCandidates(List.of(first, second));

    assertEquals(1, merged.size());
    var survivor = merged.iterator().next();
    assertEquals(2, survivor.provenance().mergedPathCount());
    assertEquals(1L, survivor.id());
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

    Player sprintStart = new Player(
        Vec3.ZERO, Vec3.ZERO, 0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false,
        Optional.empty(), Simulation.Attributes.DEFAULT, Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY, State.TickRange.unknown(), State.Provenance.UNKNOWN, Set.of());
    Reachability sprint = reachable.nextAdvanced(sprintStart, ground(), false,
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
  void sprintKeyInputDoesNotMutatePhysicalMovementState() {
    Phase6Reachability reachable = new Phase6Reachability();
    MovementEnvironment movementState =
        MovementEnvironment.dry(true, false, false);
    Player start = new Player(
        Vec3.ZERO, Vec3.ZERO, 0f, 0f, true, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY, State.TickRange.unknown(),
        State.Provenance.UNKNOWN, Set.of());
    Phase6Reachability.Context context = new Phase6Reachability.Context(
        0L, start, Simulation.Environment.DRY, Simulation.Attributes.DEFAULT,
        Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        movementState, false);

    var result = reachable.search(
        context,
        List.of(Phase6Reachability.InputConstraint.exact(
            new AdvancedInput(1, 0, false, true, false))),
        ignored -> List.of(new Phase6Reachability.WorldBranch(
            "ground", phase6Ground(), true, "known test ground")),
        ignored -> List.of(new Phase6Reachability.None()),
        Phase6Reachability.SearchConfig.defaults(16));

    assertEquals(Phase6Reachability.Verdict.POSSIBLE, result.verdict());
    assertFalse(result.candidates().isEmpty());
    var candidate = result.candidates().iterator().next();
    assertFalse(candidate.context().movementEnvironment().sprinting());
    assertFalse(candidate.context().movementEnvironment().sneaking());
  }

  @Test
  void preservedLastOnGroundReachesPhase5PhysicsContext() {
    Phase6Reachability reachable = new Phase6Reachability();

    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(
        "minecraft:stone", Map.of());
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 63, 0, stone)
        .build();

    Player airborne = new Player(
        new Vec3(0.5, 64.0, 0.5),
        new Vec3(0.1, 0.0, 0.0),
        0f, 0f, false, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY, State.TickRange.exact(0),
        State.Provenance.UNKNOWN, Set.of());

    Phase6Reachability.Context context = new Phase6Reachability.Context(
        0L,
        airborne,
        Simulation.Environment.DRY,
        Simulation.Attributes.DEFAULT,
        Phase5Mechanics.MovementEffects.NONE,
        Phase5Mechanics.Pose.STANDING,
        MovementEnvironment.dry(false, false, false),
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()),
        Set.of(),
        null,
        true);

    var result = reachable.search(
        context,
        List.of(Phase6Reachability.InputConstraint.exact(
            new AdvancedInput(0, 0, false, false, false))),
        ignored -> List.of(new Phase6Reachability.WorldBranch(
            "last-on-ground", world, true, "known test world")),
        ignored -> List.of(new Phase6Reachability.None()),
        Phase6Reachability.SearchConfig.defaults(16));

    assertEquals(Phase6Reachability.Verdict.POSSIBLE, result.verdict(),
        result.toString());
    var candidate = result.candidates().stream().findFirst().orElseThrow();
    assertTrue(candidate.context().lastOnGround(),
        candidate.context().toString());
    assertTrue(
        Math.abs(candidate.context().player().velocity().x())
            < 0.1,
        () -> "Phase 5 should apply ground friction when lastOnGround is preserved: "
            + candidate.context().player().velocity());
  }

  @Test
  void landingPropagatesPostTickGroundStateIntoNextPhase5Tick() {
    Phase6Reachability reachable = new Phase6Reachability();

    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(
        "minecraft:stone", Map.of());
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 63, 0, stone)
        .build();

    Player falling = new Player(
        new Vec3(0.5, 64.2, 0.5),
        new Vec3(0.1, -0.3, 0.0),
        0f, 0f, false, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY, State.TickRange.exact(0),
        State.Provenance.UNKNOWN, Set.of());

    Phase6Reachability.Context context = new Phase6Reachability.Context(
        0L,
        falling,
        Simulation.Environment.DRY,
        Simulation.Attributes.DEFAULT,
        Phase5Mechanics.MovementEffects.NONE,
        Phase5Mechanics.Pose.STANDING,
        MovementEnvironment.dry(false, false, false),
        false,
        dev.phantom.ac.world.EntityCollisions.of(List.of()),
        Set.of(),
        null,
        false);

    var result = reachable.search(
        context,
        List.of(
            Phase6Reachability.InputConstraint.exact(
                new AdvancedInput(0, 0, false, false, false)),
            Phase6Reachability.InputConstraint.exact(
                new AdvancedInput(0, 0, false, false, false))),
        ignored -> List.of(new Phase6Reachability.WorldBranch(
            "landing-ground", world, true, "known test ground")),
        ignored -> List.of(new Phase6Reachability.None()),
        Phase6Reachability.SearchConfig.defaults(16));

    assertEquals(Phase6Reachability.Verdict.POSSIBLE, result.verdict(), result.toString());
    var candidate = result.candidates().stream().findFirst().orElseThrow();
    assertTrue(candidate.context().player().onGround(),
        candidate.context().toString());
    assertTrue(candidate.context().movementEnvironment().onGround(),
        candidate.context().movementEnvironment().toString());
    assertFalse(candidate.context().lastOnGround(),
        "lastOnGround remains the prior tick's temporal state");
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