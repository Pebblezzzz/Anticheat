package dev.phantom.ac;

import dev.phantom.ac.Phase8MovementValidation.Result;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;

/**
 * Stateful live adapter for the pure {@link CausalMovementPipeline}.
 *
 * <p>The runner owns capture history and deduplication only. It deliberately
 * does not own a second physics state machine, a poison bit, a heuristic
 * contradiction counter, or a "latest server state" shortcut. Every validation
 * cycle reconstructs the causal timeline from the retained packet journal.</p>
 */
public final class Phase8IncrementalRunner {
  public enum Continuation {
    UNANCHORED,
    ACTIVE,
    UNCERTAIN_EMPTY,
    IMPOSSIBLE
  }

  public record Report(
      List<Result> results,
      int packetsProcessed,
      int movementObservations,
      int possible,
      int uncertain,
      int impossible,
      long lastProcessedSequence,
      long relativeClientTick,
      Continuation continuation,
      boolean candidateFrontierRetained) {
    public Report {
      results = List.copyOf(results);
    }
  }

  private record CaptureKey(long sequence, long receivedNanos, Packets.Packet packet) {}

  private final int maximumCandidates;
  private final long epochNanos;
  private final List<Packets.RawPacket> history = new ArrayList<>();
  private final Set<CaptureKey> seen = new HashSet<>();
  private final Map<Long, Verdict> emitted = new HashMap<>();

  private Player anchor;
  private long anchorReceivedNanos = -1L;
  private long lastProcessedSequence = -1L;

  public Phase8IncrementalRunner(int maximumCandidates, long epochNanos) {
    Contracts.requireCandidateBudget(maximumCandidates);
    if (epochNanos < 0) throw new IllegalArgumentException("epochNanos must be non-negative");
    this.maximumCandidates = maximumCandidates;
    this.epochNanos = epochNanos;
  }

  public synchronized long lastProcessedSequence() {
    return lastProcessedSequence;
  }

  public synchronized void reset(Player authoritativeAnchor, long ignoredEpochNanos) {
    Objects.requireNonNull(authoritativeAnchor, "authoritativeAnchor");
    history.clear();
    seen.clear();
    emitted.clear();
    anchor = authoritativeAnchor;
    anchorReceivedNanos = -1L;
    lastProcessedSequence = -1L;
  }

  public synchronized Report process(
      String playerId,
      List<Packets.RawPacket> raw,
      WorldSnapshot liveWorld,
      Player currentAnchor) {
    return process(playerId, raw, liveWorld, currentAnchor, null);
  }

  /**
   * The final parameter is retained for source compatibility with the previous
   * live adapter. Authoritative position already exists in timestamped
   * {@link Packets.PlayerContext} records and is therefore not consumed as an
   * out-of-band "latest position".
   */
  public synchronized Report process(
      String playerId,
      List<Packets.RawPacket> raw,
      WorldSnapshot liveWorld,
      Player currentAnchor,
      Maths.Vec3 ignoredLatestServerPosition) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(raw);

    if (currentAnchor != null && !currentAnchor.equals(anchor)) {
      anchor = currentAnchor;
      // Joining/world-changing is an explicit authoritative boundary. The
      // packet journal remains useful for diagnostics, but prediction must start
      // from the new anchor rather than carrying candidates across worlds.
      emitted.clear();
      anchorReceivedNanos = -1L;
    }

    int newlyCaptured = 0;
    long maxSeen = lastProcessedSequence;
    for (Packets.RawPacket packet : raw) {
      CaptureKey key = new CaptureKey(
          packet.sequence(), packet.receivedNanos(), packet.packet());
      if (seen.add(key)) {
        history.add(packet);
        newlyCaptured++;
      }
      maxSeen = Math.max(maxSeen, packet.sequence());
    }
    lastProcessedSequence = maxSeen;

    if (newlyCaptured == 0) {
      return new Report(
          List.of(), 0, movementCount(raw), 0, 0, 0,
          lastProcessedSequence, -1L, continuationForLatest(), false);
    }

    history.sort(Comparator
        .comparingLong(Packets.RawPacket::receivedNanos)
        .thenComparingLong(Packets.RawPacket::sequence));

    Timeline.Snapshot timeline = Timeline.assign(
        new Packets.Normalizer().normalize(history),
        epochNanos,
        50_000_000L);

    CausalMovementPipeline.Report pipeline = CausalMovementPipeline.analyze(
        playerId,
        timeline,
        maximumCandidates,
        Phase7Timing.Config.defaultConfig(),
        liveWorld,
        anchor,
        anchorReceivedNanos);

    List<Result> fresh = new ArrayList<>();
    long relativeTick = -1L;
    for (int i = 0; i < pipeline.results().size(); i++) {
      Result result = pipeline.results().get(i);
      CausalMovementPipeline.Frame frame = pipeline.frames().get(i);
      relativeTick = Math.max(
          relativeTick,
          frame.timing().simulationClientTicks().max());
      long sequence = frame.sequence();
      Verdict prior = emitted.get(sequence);

      /*
       * A verdict is replayed only when its causal state changed. In particular,
       * UNCERTAIN can later resolve to POSSIBLE/IMPOSSIBLE after an authoritative
       * snapshot or world acknowledgement arrives. This is recovery, not poison.
       */
      if (prior == result.verdict()) continue;
      emitted.put(sequence, result.verdict());
      fresh.add(result);
    }

    Continuation continuation = continuationFor(pipeline, fresh);
    int possible = count(fresh, Verdict.POSSIBLE);
    int uncertain = count(fresh, Verdict.UNCERTAIN);
    int impossible = count(fresh, Verdict.IMPOSSIBLE);

    boolean frontier = pipeline.results().stream()
        .anyMatch(result -> result.evidence().matchingCandidateCount() > 0
            && result.verdict() == Verdict.POSSIBLE);

    return new Report(
        fresh,
        newlyCaptured,
        movementCount(raw),
        possible,
        uncertain,
        impossible,
        lastProcessedSequence,
        relativeTick,
        continuation,
        frontier);
  }

  private Continuation continuationForLatest() {
    if (emitted.isEmpty()) return anchor == null
        ? Continuation.UNANCHORED
        : Continuation.UNCERTAIN_EMPTY;
    Verdict latest = emitted.values().stream().reduce((a, b) -> b).orElse(Verdict.UNCERTAIN);
    return switch (latest) {
      case POSSIBLE -> Continuation.ACTIVE;
      case UNCERTAIN -> Continuation.UNCERTAIN_EMPTY;
      case IMPOSSIBLE -> Continuation.IMPOSSIBLE;
    };
  }

  private static Continuation continuationFor(
      CausalMovementPipeline.Report pipeline,
      List<Result> fresh) {
    if (pipeline.results().isEmpty()) return Continuation.UNANCHORED;
    Result latest = pipeline.results().getLast();
    return switch (latest.verdict()) {
      case POSSIBLE -> Continuation.ACTIVE;
      case UNCERTAIN -> Continuation.UNCERTAIN_EMPTY;
      case IMPOSSIBLE -> Continuation.IMPOSSIBLE;
    };
  }

  private static int movementCount(List<Packets.RawPacket> raw) {
    return (int) raw.stream()
        .filter(packet -> packet.packet() instanceof Packets.Move)
        .count();
  }

  private static int count(List<Result> results, Verdict verdict) {
    return (int) results.stream()
        .filter(result -> result.verdict() == verdict)
        .count();
  }
}
