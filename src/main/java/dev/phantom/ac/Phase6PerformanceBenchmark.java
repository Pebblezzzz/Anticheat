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

/** Lightweight benchmark harness for the Phase 6 finite search; no external server is required. */
public final class Phase6PerformanceBenchmark {
  private Phase6PerformanceBenchmark() {}
  public record Result(long nanos,int candidates,int mergedStates,int peakCandidates) {}

  public static Result benchmarkOneTickFullInputEnvelope() {
    WorldSnapshot world=WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0,0)
        .setBlock(0,64,0,dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of())).build();
    Player player=Player.initial(new Maths.Vec3(0.5,65.0,0.5));
    MovementEnvironment env=MovementEnvironment.dry(true,false,false);
    Phase6Reachability.Context start=new Phase6Reachability.Context(0,player,Simulation.Environment.DRY,Attributes.DEFAULT,MovementEffects.NONE,Pose.STANDING,env,false);
    Phase6Reachability engine=new Phase6Reachability(new Vanilla12111RichPhysics());
    long begin=System.nanoTime();
    Phase6Reachability.SearchResult result=engine.search(start,List.of(Phase6Reachability.InputConstraint.any()),
        t->List.of(new Phase6Reachability.WorldBranch("floor",world,true,"loaded benchmark floor")),
        t->List.of(new Phase6Reachability.None()),1000);
    long elapsed=System.nanoTime()-begin;
    return new Result(elapsed,result.candidates().size(),result.mergedStates(),result.peakCandidates());
  }
}
