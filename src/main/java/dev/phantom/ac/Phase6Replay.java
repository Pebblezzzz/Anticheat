package dev.phantom.ac;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Self-contained, deterministic Phase 6 replay description for debugging and regression fixtures. */
public record Phase6Replay(
    String schemaVersion,
    Phase6Reachability.Context start,
    List<Phase6Reachability.InputConstraint> inputs,
    long earliestTick,
    long latestTick,
    boolean timingUncertain,
    Map<Long,List<Phase6Reachability.WorldBranch>> worldBranches,
    Map<Long,List<Phase6Reachability.ExternalTransition>> externalTransitions,
    Phase6Reachability.SearchResult result) implements Serializable {
  public static final String SCHEMA_VERSION="phase6-replay-v1";
  public Phase6Replay {
    if(!SCHEMA_VERSION.equals(schemaVersion))throw new IllegalArgumentException("unsupported Phase 6 replay schema: "+schemaVersion);
    Objects.requireNonNull(start);inputs=List.copyOf(inputs);worldBranches=Map.copyOf(worldBranches);externalTransitions=Map.copyOf(externalTransitions);Objects.requireNonNull(result);
    if(earliestTick<0||latestTick<earliestTick)throw new IllegalArgumentException("invalid replay timing window");
  }
  public static Phase6Replay of(Phase6Reachability.Context start,List<Phase6Reachability.InputConstraint> inputs,long earliestTick,long latestTick,boolean timingUncertain,
                                Map<Long,List<Phase6Reachability.WorldBranch>> worldBranches,
                                Map<Long,List<Phase6Reachability.ExternalTransition>> externalTransitions,
                                Phase6Reachability.SearchResult result){
    return new Phase6Replay(SCHEMA_VERSION,start,inputs,earliestTick,latestTick,timingUncertain,worldBranches,externalTransitions,result);
  }
  /** Canonical text is intentionally dependency-free so it can be stored beside an existing packet replay. */
  public String canonicalText(){StringBuilder b=new StringBuilder();b.append(SCHEMA_VERSION).append('\n');b.append("start=").append(start).append('\n');b.append("inputs=").append(inputs).append('\n');b.append("timing=").append(earliestTick).append("..").append(latestTick).append(" uncertain=").append(timingUncertain).append('\n');b.append("worldBranches=").append(worldBranches).append('\n');b.append("externalTransitions=").append(externalTransitions).append('\n');b.append("result=").append(result).append('\n');return b.toString();}
}
