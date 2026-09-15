package dev.phantom.ac;

import java.util.*; import java.util.function.LongFunction; import static dev.phantom.ac.Maths.*; import static dev.phantom.ac.State.*; import static dev.phantom.ac.Simulation.*;
public final class Validation {
  private Validation() {}
  public enum Verdict { POSSIBLE, UNCERTAIN, IMPOSSIBLE }
  public record Reachability(Verdict verdict, Set<Player> candidates, List<String> reasons) { public Reachability {candidates=Set.copyOf(candidates);reasons=List.copyOf(reasons);} }
  public static final class ReachableStates implements Contracts.ReachabilityEngine {
    private final Vanilla12111Physics physics;
    public ReachableStates(Vanilla12111Physics physics){this.physics=physics;}
    public Reachability next(Player state,World.Snapshot world,boolean timingUncertain) {
      if(state.uncertain()||timingUncertain||world.hasUnsupported(Aabb.playerAt(state.position())))return new Reachability(Verdict.UNCERTAIN,Set.of(),List.of("missing or unsupported state prevents sound exhaustive simulation"));
      Set<Player> out=new HashSet<>(); for(int f=-1;f<=1;f++)for(int s=-1;s<=1;s++)for(boolean j:List.of(false,true))out.add(physics.tick(state,new Input(f,s,j),world)); return new Reachability(Verdict.POSSIBLE,out,List.of("enumerated 18 discrete input combinations"));
    }
    public Reachability next(Player state,World.Snapshot world,boolean timingUncertain, Packets.ClientInput observedInput) {
      if(observedInput.sneak()||observedInput.sprint()) return new Reachability(Verdict.UNCERTAIN,Set.of(),List.of("observed sneak or sprint input is not implemented by the version simulator"));
      if(state.uncertain()||timingUncertain||world.hasUnsupported(Aabb.playerAt(state.position())))return new Reachability(Verdict.UNCERTAIN,Set.of(),List.of("missing or unsupported state prevents sound exhaustive simulation"));
      int forward=axis(observedInput.forward(),observedInput.backward()); int strafe=axis(observedInput.right(),observedInput.left());
      return new Reachability(Verdict.POSSIBLE,Set.of(physics.tick(state,new Input(forward,strafe,observedInput.jump()),world)),List.of("constrained by observed client input packet"));
    }
    private static int axis(boolean positive,boolean negative) { return positive==negative?0:positive?1:-1; }

    /**
     * Multi-tick reachable-state search. Every candidate remains an exact state;
     * when the configured state budget is exhausted, the answer becomes
     * UNCERTAIN instead of silently dropping legitimate branches.
     */
    public SearchResult advance(Player start, long firstTick, List<Optional<Input>> inputs, LongFunction<World.Snapshot> worlds, int maximumCandidates) {
      Contracts.requireCandidateBudget(maximumCandidates);
      if (start.uncertain()) return new SearchResult(Verdict.UNCERTAIN, Set.of(), 0, List.of("initial state is uncertain"));
      Set<Player> current=Set.of(start);
      for(int offset=0; offset<inputs.size(); offset++) {
        World.Snapshot world=Objects.requireNonNull(worlds.apply(firstTick+offset),"world snapshot");
        Set<Player> next=new HashSet<>();
        for(Player candidate:current) {
          if(candidate.uncertain()||world.hasUnsupported(Aabb.playerAt(candidate.position()))) return new SearchResult(Verdict.UNCERTAIN,Set.of(),offset,List.of("unsupported environment or uncertain state at tick "+(firstTick+offset)));
          if(inputs.get(offset).isPresent()) next.add(physics.tick(candidate,inputs.get(offset).orElseThrow(),world));
          else for(int forward=-1;forward<=1;forward++) for(int strafe=-1;strafe<=1;strafe++) for(boolean jump:List.of(false,true)) next.add(physics.tick(candidate,new Input(forward,strafe,jump),world));
          if(next.size()>maximumCandidates) return new SearchResult(Verdict.UNCERTAIN,Set.copyOf(next),offset+1,List.of("reachable-state budget exceeded; branches were not discarded"));
        }
        current=Set.copyOf(next);
      }
      return new SearchResult(Verdict.POSSIBLE,current,inputs.size(),List.of("searched "+inputs.size()+" ticks without pruning candidates"));
    }
  }
  public record SearchResult(Verdict verdict, Set<Player> candidates, int simulatedTicks, List<String> reasons) { public SearchResult { candidates=Set.copyOf(candidates); reasons=List.copyOf(reasons); } }
  public record SyncWindow(long earliestClientTick,long latestClientTick,boolean uncertain,List<String> reasons) { public SyncWindow {reasons=List.copyOf(reasons);} }
  public static final class DefaultSynchronizer implements Contracts.Synchronizer { @Override public SyncWindow reconstruct(long serverTick,long roundTripNanos,long jitterNanos,boolean awaitingTeleport) { return synchronize(serverTick,roundTripNanos,jitterNanos,awaitingTeleport); } }
  public static SyncWindow synchronize(long serverTick,long rttNanos,long jitterNanos,boolean awaitingTeleport) { long half=Math.max(0,(rttNanos+jitterNanos)/2/50_000_000L); boolean u=jitterNanos>0||awaitingTeleport; return new SyncWindow(Math.max(0,serverTick-half),serverTick+half,u,u?List.of("latency/jitter or teleport acknowledgement widens timeline"):List.of()); }
  public record Evidence(Verdict verdict, String rule, double nearestHorizontalDistance, List<String> reasons) { public Evidence {reasons=List.copyOf(reasons);} }
  public static final class ReachabilityValidator implements Contracts.MovementValidator { @Override public Evidence compare(Player observed,Reachability reachable) { return validate(observed,reachable); } }
  public static Evidence validate(Player observed,Reachability reachable) { if(reachable.verdict()==Verdict.UNCERTAIN)return new Evidence(Verdict.UNCERTAIN,"MOVEMENT_REACHABILITY",Double.NaN,reachable.reasons()); if(reachable.candidates().stream().anyMatch(s->sameState(s,observed)))return new Evidence(Verdict.POSSIBLE,"MOVEMENT_REACHABILITY",0,List.of("observed position, velocity, and ground state are a simulated reachable state")); double d=reachable.candidates().stream().mapToDouble(s->Math.sqrt(s.position().horizontalDistanceSquared(observed.position()))).min().orElse(Double.POSITIVE_INFINITY);return new Evidence(Verdict.IMPOSSIBLE,"MOVEMENT_REACHABILITY",d,List.of("no candidate produced the observed position, velocity, and ground state in the declared input envelope")); }
  private static boolean sameState(Player a, Player b) { return a.position().equals(b.position()) && a.velocity().equals(b.velocity()) && a.onGround()==b.onGround(); }
}
