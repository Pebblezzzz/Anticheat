package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.Evidence;
import dev.phantom.ac.Phase6Reachability.ExternalPath;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.MovementMode;
import dev.phantom.ac.Phase6Reachability.Observation;
import dev.phantom.ac.Phase6Reachability.ObservedField;
import dev.phantom.ac.Phase6Reachability.SearchConfig;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.Verdict;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import java.util.*;
import org.junit.jupiter.api.Test;

class Phase6CompletionTest {
  private static final AdvancedInput STILL = new AdvancedInput(0, 0, false, false, false);
  private static final AdvancedInput WALK = new AdvancedInput(1, 0, false, false, false);
  private static final Phase6Reachability ENGINE =
      new Phase6Reachability(new Vanilla12111RichPhysics());

  private static WorldSnapshot floorWorld() {
    WorldSnapshot.Builder builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 8; x++) {
      for (int z = -8; z <= 8; z++) {
        builder.setBlock(x, 64, z, BlockCatalogue12111.decode("minecraft:stone", Map.of()));
      }
    }
    return builder.build();
  }

  private static WorldSnapshot worldWithBlock(int x, int y, int z, String id, Map<String, String> props) {
    WorldSnapshot.Builder builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int bx = -8; bx <= 8; bx++) {
      for (int bz = -8; bz <= 8; bz++) {
        builder.setBlock(bx, 64, bz, BlockCatalogue12111.decode("minecraft:stone", Map.of()));
      }
    }
    builder.setBlock(x, y, z, BlockCatalogue12111.decode(id, props));
    return builder.build();
  }

  private static WorldSnapshot fluidWorld(String id) {
    WorldSnapshot.Builder builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -2; x <= 2; x++) {
      for (int z = -2; z <= 2; z++) {
        builder.setBlock(x, 64, z, BlockCatalogue12111.decode("minecraft:stone", Map.of()));
        builder.setBlock(x, 65, z, BlockCatalogue12111.decode(id, Map.of("level", "0")));
      }
    }
    return builder.build();
  }

  private static WorldSnapshot ladderWorld() {
    return worldWithBlock(
        0, 65, 0, "minecraft:ladder", Map.of("facing", "north"));
  }

  private static Context start() {
    Player player = Player.initial(new Maths.Vec3(0.5, 65.0, 0.5));
    return new Context(
        0, player, Simulation.Environment.DRY, Attributes.DEFAULT,
        MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false,
        EntityCollisions.of(List.of()));
  }

  private static Context startAt(Maths.Vec3 position, boolean onGround, float yaw, Pose pose,
                                 Simulation.Environment environment, MovementEnvironment movementEnvironment,
                                 Attributes attributes, MovementEffects effects) {
    Player player = new Player(
        position, Maths.Vec3.ZERO, yaw, 0.0f, onGround, "survival", Map.of(),
        OptionalInt.empty(), false, Optional.empty(), attributes, pose,
        environment == Simulation.Environment.WATER
            ? State.Environment.WATER
            : environment == Simulation.Environment.LAVA
                ? State.Environment.LAVA
                : environment == Simulation.Environment.CLIMBABLE
                    ? State.Environment.CLIMBABLE
                    : State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());
    return new Context(
        0, player, environment, attributes, effects, pose, movementEnvironment, false,
        EntityCollisions.of(List.of()));
  }

  private static SearchResult exact(Context start, List<AdvancedInput> sequence,
                                    WorldSnapshot world) {
    List<InputConstraint> constraints = sequence.stream().map(InputConstraint::exact).toList();
    return ENGINE.search(
        start, constraints,
        tick -> List.of(new WorldBranch("known", world, true, "fully known test world")),
        tick -> List.of(new Phase6Reachability.None()), 4096);
  }

  private static Player runPhase5(Context context, AdvancedInput input, WorldSnapshot world) {
    MovementEnvironment env = context.movementEnvironment();
    Simulation.Environment simEnv = context.environment();
    Vanilla12111RichPhysics.StepResult result =
        new Vanilla12111RichPhysics().step(new Vanilla12111RichPhysics.Context(
            context.simulationTick(), context.player(), input, world, simEnv,
            context.attributes(), context.effects(), context.pose(), env,
            context.sleeping(), context.entityCollisions()));
    assertFalse(result.state().uncertain(), result.diagnostic());
    return result.state();
  }

  @Test
  void exactInputProducesOnlyAuthenticPhase5States() {
    SearchResult result = exact(start(), List.of(WALK), floorWorld());
    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(1, result.candidates().size());
    Candidate candidate = result.candidates().iterator().next();
    assertEquals(runPhase5(start(), WALK, floorWorld()), candidate.context().player());
    assertEquals(MovementMode.SURVIVAL_GROUND, candidate.movementMode());
    assertEquals(1, result.metrics().simulationSteps());
    assertTrue(result.metrics().exhaustive());
  }

  @Test
  void multipleInitialStatesRemainIndependentReachabilityRoots() {
    SearchResult result = ENGINE.search(
        List.of(start(), startAt(new Maths.Vec3(2.5, 65.0, 0.5), true, 0.0f, Pose.STANDING,
            Simulation.Environment.DRY, MovementEnvironment.dry(true, false, false),
            Attributes.DEFAULT, MovementEffects.NONE)),
        List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.None()),
        SearchConfig.defaults(64));
    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(2, result.candidates().size());
  }

  @Test
  void unknownInputBranchesAllLegitimateInputDimensionsAndBecomesUncertain() {
    SearchResult result = ENGINE.search(
        start(), List.of(InputConstraint.any()),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.None()), 128);
    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertEquals(72, result.candidates().size());
    assertTrue(result.metrics().generatedCandidates() >= 72);
    assertTrue(result.metrics().exhaustive() == false);
    assertTrue(result.candidates().stream()
        .allMatch(candidate -> candidate.provenance().assumptions().stream()
            .anyMatch(assumption -> assumption.contains("input="))));
  }

  @Test
  void partialInputOnlyBranchesTheUnknownFields() {
    InputConstraint constraint = new InputConstraint(
        OptionalInt.empty(), OptionalInt.empty(), Optional.empty(),
        Optional.of(true), Optional.empty());
    SearchResult result = ENGINE.search(
        start(), List.of(constraint),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.None()), 128);
    assertEquals(36, result.candidates().size());
    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertTrue(result.metrics().generatedCandidates() >= 9);
  }

  @Test
  void nonExhaustiveWorldRetainsKnownBranchCandidatesButPropagatesUncertainty() {
    SearchResult result = exact(start(), List.of(STILL), floorWorld());
    SearchResult uncertain = ENGINE.search(
        start(), List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("partial", floorWorld(), false, "missing alternate client-world hypotheses")),
        tick -> List.of(new Phase6Reachability.None()), 64);
    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(Verdict.UNCERTAIN, uncertain.verdict());
    assertFalse(uncertain.candidates().isEmpty());
    assertTrue(uncertain.candidates().iterator().next().context().uncertainty()
        .contains(Phase6Reachability.UncertainDimension.WORLD));
    assertEquals(1, uncertain.nonExhaustiveWorldBranches());
  }

  @Test
  void unloadedWorldIsNotAirAndIsReportedAsUnloaded() {
    SearchResult result = exact(start(), List.of(STILL), WorldSnapshot.emptyOverworld12111());
    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertTrue(result.candidates().isEmpty());
    assertTrue(result.eliminations().stream()
        .anyMatch(e -> e.worldKnowledge() == Phase6Reachability.WorldKnowledge.UNLOADED));
  }

  @Test
  void unsupportedBlockIsNotAirAndIsReportedAsUnsupported() {
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0)
        .setUnsupportedBlock(0, 65, 0, "minecraft:test_unknown_state")
        .setBlock(0, 64, 0, BlockCatalogue12111.decode("minecraft:stone", Map.of()))
        .build();
    SearchResult result = exact(start(), List.of(STILL), world);
    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertTrue(result.eliminations().stream()
        .anyMatch(e -> e.worldKnowledge() == Phase6Reachability.WorldKnowledge.UNSUPPORTED));
  }

  @Test
  void differentWorldBranchesAreNeverMergedByPositionAlone() {
    SearchResult result = ENGINE.search(
        start(), List.of(InputConstraint.exact(STILL)),
        tick -> List.of(
            new WorldBranch("air-hypothesis", floorWorld(), true, "same geometry outcome A"),
            new WorldBranch("block-hypothesis", floorWorld(), true, "same geometry outcome B")),
        tick -> List.of(new Phase6Reachability.None()), 64);
    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(2, result.candidates().size());
    assertEquals(0, result.mergedStates());
  }

  @Test
  void identicalStateFromAlternativeExternalPathsMergesAndPreservesProvenance() {
    SearchResult result = ENGINE.searchWithTransitionBranches(
        List.of(start()), List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(
            new ExternalPath("path-a", List.of(), "no-op A"),
            new ExternalPath("path-b", List.of(new Phase6Reachability.None()), "no-op B")),
        SearchConfig.defaults(64));
    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(1, result.candidates().size());
    Candidate candidate = result.candidates().iterator().next();
    assertEquals(2, candidate.provenance().mergedPathCount());
    assertFalse(candidate.provenance().assumptions().isEmpty());
  }

  @Test
  void branchBudgetIsDeterministicAndNeverClaimsImpossible() {
    SearchResult result = ENGINE.search(
        start(), List.of(InputConstraint.any()),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.None()),
        new SearchConfig(8, 4, 4, 10_000L, null, "budget-test"));
    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertTrue(result.metrics().budgetReached());
    assertEquals(4, result.candidates().size());
    assertTrue(result.eliminations().stream().anyMatch(e -> e.stage().equals("BUDGET")));
  }

  @Test
  void simulationStepBudgetStopsAtUncertainRatherThanImpossible() {
    SearchResult result = ENGINE.search(
        start(), List.of(InputConstraint.exact(STILL), InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.None()),
        new SearchConfig(32, 4, 64, 1L, null, "step-budget"));
    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertTrue(result.metrics().budgetReached());
    assertEquals(1, result.metrics().simulationSteps());
    assertFalse(result.candidates().isEmpty());
  }

  @Test
  void timingWindowConsumesEverySuppliedClientTickWithoutAssumingServerTickEquality() {
    var result = ENGINE.searchWithinTimingWindow(
        start(), 10, 11, false, List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.None()), 64);
    assertEquals(Verdict.POSSIBLE, result.verdict());
    assertEquals(2, result.evaluatedOffsets());
    assertEquals(2, result.candidates().size());
  }

  @Test
  void timingUncertaintyCannotBecomeImpossible() {
    var result = ENGINE.searchWithinTimingWindow(
        start(), 10, 11, true, List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.None()), 64);
    assertEquals(Verdict.UNCERTAIN, result.verdict());
    Evidence evidence = ENGINE.compare(
        new SearchResult(
            result.verdict(), result.candidates(), 1, result.candidates().size(),
            result.metrics().mergedCandidates() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) result.metrics().mergedCandidates(),
            0, 0, 0, result.reasons(), result.metrics(), result.eliminations()),
        new Observation(Player.initial(new Maths.Vec3(999, 65, 999)), EnumSet.of(ObservedField.POSITION)));
    assertEquals(Verdict.UNCERTAIN, evidence.verdict());
  }

  @Test
  void impossibleObservationRequiresAnExhaustiveSearch() {
    SearchResult result = exact(start(), List.of(STILL), floorWorld());
    Observation impossible = new Observation(
        new Player(new Maths.Vec3(100, 65, 100), Maths.Vec3.ZERO, 0, 0,
            true, "survival", Map.of(), OptionalInt.empty(), false),
        EnumSet.of(ObservedField.POSITION));
    Evidence evidence = ENGINE.compare(result, impossible);
    assertEquals(Verdict.IMPOSSIBLE, evidence.verdict());
    assertEquals(0, evidence.matchingCandidates());
    assertFalse(evidence.closestCandidates().isEmpty());
    assertFalse(evidence.mismatches().isEmpty());
    assertTrue(evidence.firstDivergence().isPresent());
  }

  @Test
  void uncertaintyPlusNoMatchCanNeverBecomeImpossible() {
    SearchResult uncertain = ENGINE.search(
        start(), List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("partial", floorWorld(), false, "incomplete world hypothesis")),
        tick -> List.of(new Phase6Reachability.None()), 64);
    Evidence evidence = ENGINE.compare(
        uncertain,
        new Observation(
            new Player(new Maths.Vec3(100, 65, 100), Maths.Vec3.ZERO, 0, 0,
                true, "survival", Map.of(), OptionalInt.empty(), false),
            EnumSet.of(ObservedField.POSITION)));
    assertEquals(Verdict.UNCERTAIN, evidence.verdict());
  }

  @Test
  void firstDivergenceIdentifiesTheEarliestImpossibleObservation() {
    SearchResult first = exact(start(), List.of(STILL), floorWorld());
    SearchResult second = exact(
        first.candidates().iterator().next().context(),
        List.of(STILL), floorWorld());
    Player reachable = first.candidates().iterator().next().context().player();
    Player observedFirst = reachable;
    Player observedSecond = new Player(
        reachable.position().add(new Maths.Vec3(20, 0, 0)),
        reachable.velocity(), reachable.yaw(), reachable.pitch(), reachable.onGround(),
        reachable.gamemode(), reachable.effects(), reachable.awaitingTeleport(),
        false);
    var divergence = ENGINE.firstDivergence(
        List.of(first, second),
        List.of(
            new Observation(observedFirst, EnumSet.of(ObservedField.POSITION)),
            new Observation(observedSecond, EnumSet.of(ObservedField.POSITION))));
    assertTrue(divergence.isPresent());
    assertEquals(1, divergence.get().observationIndex());
    assertTrue(divergence.get().lastReachableCandidate().isPresent());
    assertTrue(divergence.get().mismatchDimensions().contains(ObservedField.POSITION));
  }

  @Test
  void replayReproducesTheExactSearchSignature() {
    SearchResult result = exact(start(), List.of(WALK), floorWorld());
    Phase6Replay replay = Phase6Replay.of(
        start(), List.of(InputConstraint.exact(WALK)), 0, 0, false,
        Map.of(0L, List.of(new WorldBranch("known", floorWorld(), true, "known floor"))),
        Map.of(0L, List.of(new Phase6Reachability.None())), result);
    SearchResult replayed = replay.replay(new Vanilla12111RichPhysics());
    assertEquals(Phase6Reachability.canonicalSignature(result),
        Phase6Reachability.canonicalSignature(replayed));
    assertTrue(replay.canonicalText().contains("metrics="));
  }

  @Test
  void correctionAndConfirmationPreserveRichCandidateState() {
    Attributes attributes = new Attributes(2.0);
    MovementEffects effects = new MovementEffects(1, -1, -1, -1, false);
    Context richStart = startAt(
        new Maths.Vec3(0.5, 65, 0.5), true, 0, Pose.STANDING,
        Simulation.Environment.DRY,
        MovementEnvironment.dry(true, false, false),
        attributes, effects);
    Candidate corrected = ENGINE.search(
        richStart, List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.TeleportCorrection(
            7, new Maths.Vec3(3.5, 65, 3.5), Maths.Vec3.ZERO,
            Pose.STANDING, false, 30.0f, 10.0f)),
        new SearchConfig(64, 4, 64, 1000L, 42L, "correction")).candidates()
        .stream().findFirst().orElseThrow();
    assertEquals(attributes, corrected.context().attributes(), "candidate context must retain rich attributes");
    assertEquals(MovementMode.SURVIVAL_AIR, corrected.movementMode());
    assertEquals(Long.valueOf(42L), corrected.serverTickAssociation());
    assertEquals("correction", corrected.timingReference());

    Player pending = new Player(
        new Maths.Vec3(0.5, 65, 0.5), Maths.Vec3.ZERO, 0, 0, false,
        "survival", Map.of(), OptionalInt.of(7), false,
        Optional.of(STILL), attributes, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());
    Context pendingContext = new Context(
        0, pending, Simulation.Environment.DRY, attributes, effects, Pose.STANDING,
        MovementEnvironment.dry(false, false, false), false,
        EntityCollisions.of(List.of()));
    SearchResult confirmed = ENGINE.search(
        pendingContext, List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.TeleportConfirmation(7)), 64);
    assertEquals(Verdict.POSSIBLE, confirmed.verdict());
    assertTrue(confirmed.candidates().iterator().next().context().player().awaitingTeleport().isEmpty());
  }

  @Test
  void awaitingCorrectionWithoutConfirmationIsUncertain() {
    Player pending = new Player(
        new Maths.Vec3(0.5, 65, 0.5), Maths.Vec3.ZERO, 0, 0, false,
        "survival", Map.of(), OptionalInt.of(7), false,
        Optional.of(STILL), Attributes.DEFAULT, Pose.STANDING, State.Environment.DRY,
        State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());
    Context context = new Context(
        0, pending, Simulation.Environment.DRY, Attributes.DEFAULT,
        MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(false, false, false), false);
    SearchResult result = exact(context, List.of(STILL), floorWorld());
    assertEquals(Verdict.UNCERTAIN, result.verdict());
    assertTrue(result.eliminations().stream().anyMatch(e -> e.stage().equals("PHASE5")));
  }

  @Test
  void velocityKnockbackIsARealPhase5TransitionWithProvenance() {
    SearchResult result = ENGINE.search(
        start(), List.of(InputConstraint.exact(STILL)),
        tick -> List.of(new WorldBranch("known", floorWorld(), true, "known floor")),
        tick -> List.of(new Phase6Reachability.VelocityImpulse(
            new Maths.Vec3(0.4, 0.2, 0.1), "synthetic knockback")),
        64);
    assertEquals(Verdict.POSSIBLE, result.verdict());
    Candidate candidate = result.candidates().iterator().next();
    assertTrue(candidate.provenance().externalTransition().contains("ordered-external"));
    assertNotEquals(Maths.Vec3.ZERO, candidate.context().player().velocity());
  }

  @Test
  void phase5BackedMovementMatrixCoversCoreLegitimateMovementAndEdgeMechanics() {
    record Scenario(String name, Context context, List<AdvancedInput> inputs, WorldSnapshot world) {}
    WorldSnapshot floor = floorWorld();
    List<Scenario> scenarios = List.of(
        new Scenario("walking", start(), List.of(new AdvancedInput(1, 0, false, false, false)), floor),
        new Scenario("sprinting", start(), List.of(new AdvancedInput(1, 0, false, true, false)), floor),
        new Scenario("sneaking", start(), List.of(new AdvancedInput(1, 0, false, false, true)), floor),
        new Scenario("strafing", start(), List.of(new AdvancedInput(0, 1, false, false, false)), floor),
        new Scenario("diagonal", start(), List.of(new AdvancedInput(1, 1, false, false, false)), floor),
        new Scenario("jump", start(), List.of(new AdvancedInput(0, 0, true, false, false)), floor),
        new Scenario("fall", startAt(new Maths.Vec3(0.5, 70, 0.5), false, 0, Pose.STANDING,
            Simulation.Environment.DRY, MovementEnvironment.dry(false, false, false), Attributes.DEFAULT, MovementEffects.NONE),
            List.of(STILL, STILL), floor),
        new Scenario("landing", startAt(new Maths.Vec3(0.5, 66.0, 0.5), false, 0, Pose.STANDING,
            Simulation.Environment.DRY, MovementEnvironment.dry(false, false, false), Attributes.DEFAULT, MovementEffects.NONE),
            List.of(STILL, STILL, STILL, STILL), floor),
        new Scenario("slab", start(),
            List.of(new AdvancedInput(1, 0, false, false, false)),
            worldWithBlock(0, 64, 1, "minecraft:oak_slab", Map.of("type", "bottom"))),
        new Scenario("stairs", start(),
            List.of(new AdvancedInput(1, 0, false, false, false)),
            worldWithBlock(0, 64, 2, "minecraft:oak_stairs",
                Map.of("facing", "south", "half", "bottom", "shape", "straight"))),
        new Scenario("corner", start(),
            List.of(new AdvancedInput(1, 0, false, false, false)),
            worldWithBlock(1, 65, 1, "minecraft:stone", Map.of())),
        new Scenario("edge", start(),
            List.of(new AdvancedInput(0, 1, false, false, false)),
            worldWithBlock(1, 64, 0, "minecraft:stone", Map.of())),
        new Scenario("water", startAt(new Maths.Vec3(0.5, 65.2, 0.5), true, 0, Pose.STANDING,
            Simulation.Environment.WATER, MovementEnvironment.vanillaWater(true, false, false, false),
            Attributes.DEFAULT, MovementEffects.NONE),
            List.of(WALK), fluidWorld("minecraft:water")),
        new Scenario("lava", startAt(new Maths.Vec3(0.5, 65.2, 0.5), true, 0, Pose.STANDING,
            Simulation.Environment.LAVA, MovementEnvironment.vanillaLava(true, false, false),
            Attributes.DEFAULT, MovementEffects.NONE),
            List.of(WALK), fluidWorld("minecraft:lava")),
        new Scenario("climbable", startAt(new Maths.Vec3(0.5, 65.0, 0.5), false, 0, Pose.STANDING,
            Simulation.Environment.CLIMBABLE, MovementEnvironment.vanillaClimbable(false, false, false),
            Attributes.DEFAULT, MovementEffects.NONE),
            List.of(WALK), ladderWorld()),
        new Scenario("swimming", startAt(new Maths.Vec3(0.5, 65.2, 0.5), false, 0, Pose.SWIMMING,
            Simulation.Environment.WATER, MovementEnvironment.vanillaWater(false, false, false, true),
            Attributes.DEFAULT, MovementEffects.NONE),
            List.of(WALK), fluidWorld("minecraft:water")),
        new Scenario("effects", startAt(new Maths.Vec3(0.5, 65.0, 0.5), true, 0, Pose.STANDING,
            Simulation.Environment.DRY, MovementEnvironment.dry(true, false, false),
            Attributes.DEFAULT, new MovementEffects(1, -1, 0, -1, false)),
            List.of(WALK), floor),
        new Scenario("attributes", startAt(new Maths.Vec3(0.5, 65.0, 0.5), true, 0, Pose.STANDING,
            Simulation.Environment.DRY, MovementEnvironment.dry(true, false, false),
            new Attributes(2.0), MovementEffects.NONE),
            List.of(WALK), floor));

    for (Scenario scenario : scenarios) {
      SearchResult result = exact(scenario.context(), scenario.inputs(), scenario.world());
      assertEquals(Verdict.POSSIBLE, result.verdict(), scenario.name());
      assertFalse(result.candidates().isEmpty(), scenario.name());
      Player expected = scenario.context().player();
      Context expectedContext = scenario.context();
      for (AdvancedInput input : scenario.inputs()) {
        expected = runPhase5(
            new Context(
                expectedContext.simulationTick(),
                expected,
                input,
                scenario.world(),
                expectedContext.environment(),
                expectedContext.attributes(),
                expectedContext.effects(),
                expected.pose(),
                expectedContext.movementEnvironment(),
                expectedContext.sleeping(),
                expectedContext.entityCollisions()),
            input, scenario.world());
        expectedContext = new Context(
            expectedContext.simulationTick() + 1,
            expected,
            input,
            scenario.world(),
            expectedContext.environment(),
            expectedContext.attributes(),
            expectedContext.effects(),
            expected.pose(),
            expectedContext.movementEnvironment(),
            expectedContext.sleeping(),
            expectedContext.entityCollisions());
      }
      Player actual = result.candidates().stream()
          .min(Comparator.comparingLong(Candidate::id))
          .orElseThrow().context().player();
      assertEquals(expected, actual, scenario.name());
      assertTrue(result.metrics().simulationSteps() >= scenario.inputs().size(), scenario.name());
    }
  }

}
