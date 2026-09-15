package dev.phantom.ac;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;

/**
 * Phase-0 contracts between anti-cheat layers. Implementations must not add
 * hidden I/O, clocks, mutable live-world access, or platform-specific types.
 * All time is supplied by the caller and all results are replayable values.
 */
public final class Contracts {
  private Contracts() {}

  /** No cross-version fallback is permitted inside the 1.21.11 model. */
  public static final String TARGET_VERSION = "Minecraft Java 1.21.11";

  public interface PacketNormalizer {
    List<Packets.NormalizedPacket> normalize(Collection<Packets.RawPacket> packets);
  }

  public interface TimelineReconstructor {
    Timeline.Snapshot reconstruct(Collection<Packets.NormalizedPacket> packets, long epochNanos, long serverTickNanos);
  }

  public interface PlayerStateReducer {
    State.Player apply(State.Player prior, Packets.NormalizedPacket event);
  }

  public interface PlayerStateReconstructor {
    State.Reconstruction reconstruct(State.Seed seed, Timeline.Snapshot timeline);
  }

  public interface CollisionResolver {
    World.CollisionResult resolve(World.Snapshot world, Maths.Aabb playerBox, Maths.Vec3 desiredDisplacement, boolean stepped);
  }

  public interface PhysicsEngine {
    State.Player tick(State.Player state, Simulation.Input input, World.Snapshot clientWorld);
  }

  public interface ReachabilityEngine {
    Validation.Reachability next(State.Player state, World.Snapshot clientWorld, boolean timingUncertain);
    Validation.SearchResult advance(State.Player start, long firstTick, List<Optional<Simulation.Input>> inputs, LongFunction<World.Snapshot> worlds, int maximumCandidates);
  }

  /** Synchronization widens possible time; it must never itself be a violation. */
  public interface Synchronizer {
    Validation.SyncWindow reconstruct(long serverTick, long roundTripNanos, long jitterNanos, boolean awaitingTeleport);
  }

  /** Evidence is only derived from a declared reachable envelope. */
  public interface MovementValidator {
    Validation.Evidence compare(State.Player observed, Validation.Reachability reachable);
  }

  public static void requireTargetVersion(String version) {
    if (!TARGET_VERSION.equals(version)) throw new IllegalArgumentException("unsupported model version: " + version);
  }

  public static void requireCandidateBudget(int maximumCandidates) {
    if (maximumCandidates < 1) throw new IllegalArgumentException("maximumCandidates must be positive");
  }
}
