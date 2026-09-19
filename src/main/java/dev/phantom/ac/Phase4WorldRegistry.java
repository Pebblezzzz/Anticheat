package dev.phantom.ac;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns one Phase 4 replica per tracked player; replicas never share mutable world state. */
public final class Phase4WorldRegistry {
  private final Map<UUID,Phase4WorldReplica> replicas=new ConcurrentHashMap<>();
  public Phase4WorldReplica create(UUID player,String worldId){
    Objects.requireNonNull(player);Objects.requireNonNull(worldId);
    Phase4WorldReplica r=new Phase4WorldReplica(Contracts.TARGET_VERSION,worldId,
        dev.phantom.ac.world.WorldSnapshot.OVERWORLD_MIN_Y,dev.phantom.ac.world.WorldSnapshot.OVERWORLD_MAX_Y);
    Phase4WorldReplica prior=replicas.putIfAbsent(player,r);return prior==null?r:prior;
  }
  public Phase4WorldReplica get(UUID player){return replicas.get(player);}
  public Phase4WorldReplica require(UUID player){Phase4WorldReplica r=get(player);if(r==null)throw new IllegalStateException("no Phase 4 replica for "+player);return r;}
  public void remove(UUID player){replicas.remove(player);}
  public int size(){return replicas.size();}
  public Map<UUID,Phase4WorldReplica> snapshot(){return Map.copyOf(replicas);}
}