package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets.ChunkStates;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.Normalizer;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.Observation;
import dev.phantom.ac.Phase6Reachability.ObservedField;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.TimingSearchResult;
import dev.phantom.ac.Phase6Reachability.Verdict;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;

/** End-to-end regression coverage for Phase 6 -> Phase 8 verdict preservation and chaining. */
class Phase8LiveValidationTest {
  private static Timeline.Snapshot capture(List<RawPacket> packets) {
    return Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L);
  }

  private static Phase7Timing.Config exactTiming() {
    return new Phase7Timing.Config(50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0, 0), new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 0), new Phase7Timing.TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
  }

  private static WorldSnapshot floorWorld() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) builder.setBlock(x, 63, z, stone);
    return builder.build();
  }

  private static Phase6Reachability.Context phase6Start() {
    Player player = Player.initial(new Vec3(.5, 64.0, .5));
    MovementEnvironment environment = MovementEnvironment.dry(true, false, false);
    return new Phase6Reachability.Context(0, player, Simulation.Environment.DRY, Attributes.DEFAULT,
        MovementEffects.NONE, Pose.STANDING, environment, false);
  }

  private static Packets.PlayerContext dryContext() {
    return new Packets.PlayerContext("survival", Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.dry(true, false, false), false, List.of());
  }

  private static TimingSearchResult timingPossible(Candidate candidate) {
    SearchResult result = new SearchResult(Verdict.POSSIBLE, Set.of(candidate), 1, 1, 0, 0, 0, 0,
        List.of("synthetic exhaustive possible parent"));
    return new TimingSearchResult(Verdict.POSSIBLE, Set.of(candidate), Map.of(0L, result), 1, 0,
        List.of("synthetic exhaustive possible parent"));
  }

  private static TimingSearchResult timingImpossible() {
    SearchResult result = new SearchResult(Verdict.IMPOSSIBLE, Set.of(), 1, 1, 0, 0, 0, 0,
        List.of("synthetic exhaustive impossible parent"));
    return new TimingSearchResult(Verdict.IMPOSSIBLE, Set.of(), Map.of(0L, result), 1, 0,
        List.of("synthetic exhaustive impossible parent"));
  }

  private static TimingSearchResult timingUncertain() {
    SearchResult result = new SearchResult(Verdict.UNCERTAIN, Set.of(), 0, 1, 0, 0, 1, 0,
        List.of("synthetic insufficient world/input information"));
    return new TimingSearchResult(Verdict.UNCERTAIN, Set.of(), Map.of(0L, result), 1, 0,
        List.of("synthetic insufficient world/input information"));
  }

  @Test
  void phase6ImpossibleReachesPhase8AccumulatorAndAlert() {
    Phase6Reachability engine = new Phase6Reachability(new Vanilla12111RichPhysics());
    WorldSnapshot world = floorWorld();
    InputConstraint noInput = InputConstraint.exact(new Simulation.AdvancedInput(0, 0, false, false, false));
    SearchResult search = engine.search(phase6Start(), List.of(noInput),
        ignored -> List.of(new WorldBranch("floor", world, true, "fully known floor")),
        ignored -> List.of(new Phase6Reachability.None()), 256);
    Phase6Reachability.Evidence phase6Evidence = engine.compare(search,
        new Observation(Player.initial(new Vec3(6.5, 64.0, 0.5)), EnumSet.of(ObservedField.POSITION)));
    assertEquals(Verdict.IMPOSSIBLE, phase6Evidence.verdict());
    assertEquals(0, phase6Evidence.matchingCandidates());

    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
        new RawPacket(2, 0, dryContext()),
        new RawPacket(3, 0, new Packets.ClientInput(false, false, false, false, false, false, false)),
        new RawPacket(4, 0, new Move(new Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
        new RawPacket(5, 50_000_000L, new Move(new Vec3(6.5, 64, 0.5), 0f, 0f, true, 1L)),
        new RawPacket(6, 100_000_000L, new Move(new Vec3(7.5, 64, 0.5), 0f, 0f, true, 2L)));
    Phase8LiveValidation.Report report = Phase8LiveValidation.analyze(
        "phase8-test", capture(packets), 256, exactTiming(), null,
        Player.initial(new Vec3(.5, 64, .5)), 0L);
    assertEquals(3, report.movementObservations());
    assertEquals(1, report.possible());
    assertEquals(2, report.impossible());
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE, report.results().get(1).verdict());
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE, report.results().get(2).verdict());

    var accumulator = Phase8MovementValidation.Accumulator.empty();
    var config = new Phase8MovementValidation.Config(2, 0, true, true);
    int alerts = 0;
    Phase8MovementValidation.Alert emitted = null;
    for (var result : report.results()) {
      var accumulated = accumulator.accept(result.evidence(), config);
      accumulator = accumulated.state();
      if (accumulated.alert().isPresent()) { alerts++; emitted = accumulated.alert().orElseThrow(); }
    }
    var state = accumulator.players().get("phase8-test/MOVEMENT_REACHABILITY");
    assertNotNull(state);
    assertEquals(2, state.supportingImpossible());
    assertEquals(2, state.consecutiveImpossible());
    assertEquals(1, alerts);
    assertNotNull(emitted);
    assertEquals("phase8-test", emitted.playerId());
    assertEquals(2, emitted.supportingEvents());
    assertEquals("[PhantomAC] phase8-test failed MOVEMENT_REACHABILITY (VL 2)", emitted.message());
    assertTrue(emitted.debugMessage().contains("result=IMPOSSIBLE"));
  }

  @Test
  void possibleParentBranchPreventsImpossibleAndIsChained() {
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new ChunkStates(new dev.phantom.ac.world.Chunk(0, 0), floorStates())),
        new RawPacket(2, 0, dryContext()),
        new RawPacket(3, 0, new Move(new Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
        new RawPacket(4, 50_000_000L, new Move(new Vec3(.5, 64, .5), 0f, 0f, true, 1L)),
        new RawPacket(5, 100_000_000L, new Move(new Vec3(.5, 64, .5), 0f, 0f, true, 2L)));
    Phase8LiveValidation.Report report = Phase8LiveValidation.analyze(
        "phase8-possible", capture(packets), 256, exactTiming(), null,
        Player.initial(new Vec3(.5, 64, .5)), 0L);
    assertEquals(3, report.movementObservations());
    assertEquals(3, report.possible(), report.results().toString());
    assertEquals(0, report.impossible(), report.results().toString());
  }

  @Test
  void unknownWorldStationaryAnchorRemainsPossible() {
    List<RawPacket> packets = List.of(
        new RawPacket(1, 0, new Move(new Vec3(.5, 64, .5), 0f, 0f, true, 0L)),
        new RawPacket(2, 50_000_000L, new Move(new Vec3(.5, 64, .5), 0f, 0f, true, 1L)));
    Phase8LiveValidation.Report report = Phase8LiveValidation.analyze(
        "phase8-unknown", capture(packets), 256, exactTiming(), null,
        Player.initial(new Vec3(.5, 64, .5)), 0L);
    assertEquals(2, report.movementObservations());
    assertEquals(2, report.possible());
    assertEquals(0, report.uncertain());
    assertEquals(0, report.impossible(), report.results().toString());
    assertTrue(report.results().stream().allMatch(result ->
        result.evidence().matchingCandidateCount() > 0),
        report.results().toString());
  }

  @Test
  void parentAggregationPreservesThreeStateSemantics() {
    Phase6Reachability engine = new Phase6Reachability(new Vanilla12111RichPhysics());
    SearchResult seedSearch = engine.search(phase6Start(), List.of(InputConstraint.exact(
        new Simulation.AdvancedInput(0, 0, false, false, false))),
        ignored -> List.of(new WorldBranch("floor", floorWorld(), true, "fully known floor")),
        ignored -> List.of(new Phase6Reachability.None()), 64);
    Candidate candidate = seedSearch.candidates().iterator().next();

    Phase8LiveValidation.ParentAggregation mixed = Phase8LiveValidation.aggregateParentSearches(
        List.of(timingPossible(candidate), timingImpossible()), 64, 1);
    assertEquals(Verdict.POSSIBLE, mixed.result().verdict());
    assertFalse(mixed.candidates().isEmpty());

    Phase8LiveValidation.ParentAggregation impossible = Phase8LiveValidation.aggregateParentSearches(
        List.of(timingImpossible(), timingImpossible()), 64, 1);
    assertEquals(Verdict.IMPOSSIBLE, impossible.result().verdict());
    assertTrue(impossible.candidates().isEmpty());
    assertTrue(impossible.timingOffsetsExhaustive());

    Phase8LiveValidation.ParentAggregation uncertain = Phase8LiveValidation.aggregateParentSearches(
        List.of(timingImpossible(), timingUncertain()), 64, 1);
    assertEquals(Verdict.UNCERTAIN, uncertain.result().verdict());
    assertFalse(uncertain.timingOffsetsExhaustive());
  }

  @Test
  void timingAggregateDoesNotTurnExhaustiveImpossibleOffsetsIntoUncertain() {
    SearchResult impossible = new SearchResult(Verdict.IMPOSSIBLE, Set.of(), 1, 1, 0, 0, 0, 0,
        List.of("offset deterministically exhausted"));
    TimingSearchResult phase6Aggregate = new TimingSearchResult(Verdict.UNCERTAIN, Set.of(),
        Map.of(10L, impossible, 11L, impossible), 2, 0,
        List.of("internal aggregate used conservative UNCERTAIN despite exhaustive impossible offsets"));

    Phase8LiveValidation.ParentAggregation result = Phase8LiveValidation.aggregateParentSearches(
        List.of(phase6Aggregate), 64, 2);
    assertEquals(Verdict.IMPOSSIBLE, result.result().verdict());
    assertTrue(result.timingOffsetsExhaustive());
  }

  private static Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> floorStates() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> states = new LinkedHashMap<>();
    for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) states.put(new dev.phantom.ac.world.Pos(x, 63, z), stone);
    return Map.copyOf(states);
  }
}
