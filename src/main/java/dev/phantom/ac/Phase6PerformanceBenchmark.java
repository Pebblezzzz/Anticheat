package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.List;
import java.util.Map;

/** Deterministic Phase 6 benchmarks. Runtime is measured externally and is not part of replay signatures. */
public final class Phase6PerformanceBenchmark {
  private Phase6PerformanceBenchmark() {}

  public record Result(
      long nanos,
      int candidates,
      int mergedStates,
      int peakCandidates,
      long generatedCandidates,
      long prunedCandidates,
      long branchEvaluations,
      long simulationSteps,
      boolean incomplete) {}

  private static WorldSnapshot floorWorld() {
    WorldSnapshot.Builder builder =
        WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 8; x++) {
      for (int z = -8; z <= 8; z++) {
        builder.setBlock(x, 64, z,
            dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of()));
      }
    }
    return builder.build();
  }

  private static Phase6Reachability.Context start() {
    Player player = Player.initial(new Maths.Vec3(0.5, 65.0, 0.5));
    return new Phase6Reachability.Context(
        0, player, Simulation.Environment.DRY, Attributes.DEFAULT,
        MovementEffects.NONE, Pose.STANDING,
        MovementEnvironment.dry(true, false, false), false);
  }

  public static Result benchmarkOneTickFullInputEnvelope() {
    WorldSnapshot world = floorWorld();
    Phase6Reachability engine = new Phase6Reachability(new Vanilla12111RichPhysics());
    long begin = System.nanoTime();
    Phase6Reachability.SearchResult result = engine.search(
        start(), List.of(Phase6Reachability.InputConstraint.any()),
        t -> List.of(new Phase6Reachability.WorldBranch(
            "floor", world, true, "loaded benchmark floor")),
        t -> List.of(new Phase6Reachability.None()),
        new Phase6Reachability.SearchConfig(1000, 8, 16_384, 250_000L, null, "benchmark-one-tick"));
    long nanos = System.nanoTime() - begin;
    return new Result(
        nanos,
        result.candidates().size(),
        result.mergedStates(),
        result.peakCandidates(),
        result.metrics().generatedCandidates(),
        result.metrics().prunedCandidates(),
        result.metrics().branchEvaluations(),
        result.metrics().simulationSteps(),
        result.verdict() == Phase6Reachability.Verdict.UNCERTAIN);
  }

  /** Adversarial growth fixture: deterministic input uncertainty across multiple ticks. */
  public static Result benchmarkAdversarialCandidateGrowth() {
    WorldSnapshot world = floorWorld();
    Phase6Reachability engine = new Phase6Reachability(new Vanilla12111RichPhysics());
    List<Phase6Reachability.InputConstraint> inputs = List.of(
        Phase6Reachability.InputConstraint.any(),
        Phase6Reachability.InputConstraint.any(),
        Phase6Reachability.InputConstraint.any(),
        Phase6Reachability.InputConstraint.any());
    long begin = System.nanoTime();
    Phase6Reachability.SearchResult result = engine.search(
        start(), inputs,
        t -> List.of(new Phase6Reachability.WorldBranch(
            "floor", world, true, "loaded benchmark floor")),
        t -> List.of(new Phase6Reachability.None()),
        new Phase6Reachability.SearchConfig(128, 8, 16_384, 20_000L, null, "benchmark-adversarial"));
    long nanos = System.nanoTime() - begin;
    return new Result(
        nanos,
        result.candidates().size(),
        result.mergedStates(),
        result.peakCandidates(),
        result.metrics().generatedCandidates(),
        result.metrics().prunedCandidates(),
        result.metrics().branchEvaluations(),
        result.metrics().simulationSteps(),
        result.verdict() == Phase6Reachability.Verdict.UNCERTAIN);
  }
}
