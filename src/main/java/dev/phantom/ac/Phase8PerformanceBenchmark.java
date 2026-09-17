package dev.phantom.ac;

import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.List;
import java.util.Set;

/** Deterministic micro-workload for Phase 8 evidence/accumulator cost measurement. */
public final class Phase8PerformanceBenchmark {
  private Phase8PerformanceBenchmark() {}

  public record Result(long nanos, int observations, int candidates, int evidenceObjects, int alerts) {}

  public static Result benchmark(int observations) {
    if (observations <= 0) throw new IllegalArgumentException("observations must be positive");
    WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build();
    Player player = Player.initial(new Maths.Vec3(0.5, 65, 0.5));
    Phase6Reachability.Context context = new Phase6Reachability.Context(0, player, Simulation.Environment.DRY,
        Simulation.Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false), false);
    Phase6Reachability.Candidate candidate = new Phase6Reachability.Candidate(1, context,
        new Phase6Reachability.Provenance(1, -1, 0, "INPUT", "WORLD", "None", List.of("benchmark"), 1, List.of()));
    Phase6Reachability.SearchResult reachable = new Phase6Reachability.SearchResult(
        Phase6Reachability.Verdict.POSSIBLE, Set.of(candidate), 1, 1, 0, 0, 0, 0, List.of("benchmark"));
    Validation.SyncWindow timing = new Validation.SyncWindow(0, 0, false, List.of("stable timing"));
    Phase8MovementValidation.Accumulator accumulator = Phase8MovementValidation.Accumulator.empty();
    long start = System.nanoTime();
    int evidence = 0, alerts = 0;
    for (int i = 0; i < observations; i++) {
      Phase8MovementValidation.Result result = Phase8MovementValidation.validate("benchmark", i, player, player,
          world, "benchmark-world", timing, List.of("forward=0", "strafe=0", "jump=false"), reachable, "benchmark-replay");
      evidence++;
      Phase8MovementValidation.Accumulated accumulated = accumulator.accept(result.evidence(), Phase8MovementValidation.Config.observationOnly());
      accumulator = accumulated.state();
      if (accumulated.alert().isPresent()) alerts++;
    }
    return new Result(System.nanoTime() - start, observations, 1, evidence, alerts);
  }
}
