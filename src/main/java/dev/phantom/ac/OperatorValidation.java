package dev.phantom.ac;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.phantom.ac.State.Player;
import dev.phantom.ac.Validation.Evidence;
import dev.phantom.ac.Validation.Reachability;
import dev.phantom.ac.Validation.SyncWindow;
import dev.phantom.ac.Validation.Verdict;

/**
 * Phase 8 orchestration kept in the deterministic core. It turns simulation
 * evidence into operator alerts, but has no punishment side effects.
 */
public final class OperatorValidation {
  private OperatorValidation() {}

  public record Observation(String playerId, long serverTick, Player prior, Player observed,
                            SyncWindow synchronization, String worldVersion,
                            List<Simulation.Input> inputs, Reachability reachability,
                            Evidence evidence) {
    public Observation(String playerId, long serverTick, Player prior, SyncWindow synchronization,
            String worldVersion, List<Simulation.Input> inputs, Reachability reachability, Evidence evidence) {
        this(playerId, serverTick, prior, prior, synchronization, worldVersion, inputs, reachability, evidence);
    }

    public Observation {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(prior);
        Objects.requireNonNull(observed);
        Objects.requireNonNull(synchronization); Objects.requireNonNull(worldVersion);
      inputs=List.copyOf(inputs); Objects.requireNonNull(reachability); Objects.requireNonNull(evidence);
    }
  }

  public record Alert(String playerId, long serverTick, Verdict verdict, double confidence,
                      String reason, int supportingEvents) {
    public Alert { Objects.requireNonNull(playerId); Objects.requireNonNull(verdict); Objects.requireNonNull(reason); }
    public String message() { return "[AntiCheat] " + playerId + " — Movement " + verdict
        + " | reason=" + reason + " | confidence=" + String.format(Locale.ROOT,"%.2f",confidence)
        + " | tick=" + serverTick; }
  }

  /** Immutable aggregation state; callers can safely keep one instance per session. */
  public record Aggregator(Map<String, Integer> impossibleByRule, Map<String, Long> lastAlertTick) {
    public Aggregator { impossibleByRule=Map.copyOf(impossibleByRule); lastAlertTick=Map.copyOf(lastAlertTick); }
    public static Aggregator empty() { return new Aggregator(Map.of(),Map.of()); }
    public Result accept(Observation event, long debounceTicks, int minimumSupportingEvents) {
      if (debounceTicks < 0 || minimumSupportingEvents < 1) throw new IllegalArgumentException("invalid aggregation policy");
      Map<String,Integer> counts=new HashMap<>(impossibleByRule);
      Map<String,Long> last=new HashMap<>(lastAlertTick);
      String key=event.playerId()+"/"+event.evidence().rule();
      int count=event.evidence().verdict()==Verdict.IMPOSSIBLE ? counts.merge(key,1,Integer::sum) : counts.getOrDefault(key,0);
      Alert alert=null;
      if(event.evidence().verdict()==Verdict.IMPOSSIBLE && count>=minimumSupportingEvents
          && event.synchronization().uncertain()==false
          && (!last.containsKey(key) || event.serverTick()-last.get(key)>=debounceTicks)) {
        double confidence=Math.min(1.0,0.5+0.1*count);
        alert=new Alert(event.playerId(),event.serverTick(),Verdict.IMPOSSIBLE,confidence,
            event.evidence().reasons().isEmpty()?event.evidence().rule():event.evidence().reasons().getFirst(),count);
        last.put(key,event.serverTick());
      }
      return new Result(new Aggregator(counts,last),Optional.ofNullable(alert));
    }
  }
  public record Result(Aggregator state, Optional<Alert> alert) { public Result { Objects.requireNonNull(state); Objects.requireNonNull(alert); } }

  /** A single observation can never create an operator alert. */
  public static Result aggregate(Aggregator state, Observation observation) {
    return state.accept(observation,20,2);
  }
}
