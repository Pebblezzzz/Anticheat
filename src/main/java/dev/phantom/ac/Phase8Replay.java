package dev.phantom.ac;

import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/** Deterministic Phase 8 replay envelope over an existing Phase 1-7 capture result. */
public record Phase8Replay(String schemaVersion, String playerId, long serverTick,
                           Player prior, Player observed, WorldSnapshot world,
                           String worldReference, Validation.SyncWindow timing,
                           List<String> inputAssumptions, SearchResult reachable,
                           String replayReference) implements Serializable {
  public static final String SCHEMA_VERSION = "phase8-replay-v1";

  public Phase8Replay {
    if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("unsupported Phase 8 replay schema");
    Objects.requireNonNull(playerId); Objects.requireNonNull(prior); Objects.requireNonNull(observed);
    Objects.requireNonNull(world); Objects.requireNonNull(worldReference); Objects.requireNonNull(timing);
    inputAssumptions = List.copyOf(inputAssumptions); Objects.requireNonNull(reachable); Objects.requireNonNull(replayReference);
  }

  public static Phase8Replay of(String playerId, long serverTick, Player prior, Player observed,
                                 WorldSnapshot world, String worldReference,
                                 Validation.SyncWindow timing, List<String> inputAssumptions,
                                 SearchResult reachable, String replayReference) {
    return new Phase8Replay(SCHEMA_VERSION, playerId, serverTick, prior, observed, world,
        worldReference, timing, inputAssumptions, reachable, replayReference);
  }

  public Phase8MovementValidation.Result replay() {
    return Phase8MovementValidation.validate(playerId, serverTick, prior, observed, world,
        worldReference, timing, inputAssumptions, reachable, replayReference);
  }
}
