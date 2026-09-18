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

  private static final int MAX_HISTORY_PACKETS = 12_000;

  private final int maximumCandidates;
  private final long epochNanos;
  private final List<Packets.RawPacket> history = new ArrayList<>();
  private final Set<CaptureKey> seen = new HashSet<>();
  private final Map<Long, Verdict> emitted = new HashMap<>();

  private Player anchor;
  private long anchorReceivedNanos = -1L;
  private long lastProcessedSequence = -1L;
  private long latestRelativeClientTick = -1L;
  private int latestCandidateCount;
  private Continuation latestContinuation = Continuation.UNANCHORED;

  public Phase8IncrementalRunner(int maximumCandidates, long epochNanos) {
    Contracts.requireCandidateBudget(maximumCandidates);
    if (epochNanos < 0) throw new IllegalArgumentException("epochNanos must be non-negative");
    this.maximumCandidates = maximumCandidates;
    this.epochNanos = epochNanos;
  }

  public synchronized long lastProcessedSequence() {
    return lastProcessedSequence;
  }

  public synchronized int candidateCount() {
    return latestCandidateCount;
  }

  public synchronized Continuation continuation() {
    return latestContinuation;
  }

  public synchronized void reset(Player authoritativeAnchor, long ignoredEpochNanos) {
    Objects.requireNonNull(authoritativeAnchor, "authoritativeAnchor");
    history.clear();
    seen.clear();
    emitted.clear();
    anchor = authoritativeAnchor;
    anchorReceivedNanos = -1L;
    lastProcessedSequence = -1L;
    latestRelativeClientTick = -1L;
    latestCandidateCount = 0;
    latestContinuation = anchor == null
        ? Continuation.UNANCHORED
        : Continuation.ACTIVE;
  }

  public synchronized Report process(
      String playerId,
      List<Packets.RawPacket> raw,
      WorldSnapshot liveWorld,
      Player currentAnchor) {
    return process(playerId, raw, liveWorld, currentAnchor, -1L, null);
  }

  public synchronized Report process(
      String playerId,
      List<Packets.RawPacket> raw,
      WorldSnapshot liveWorld,
      Player currentAnchor,
      long currentAnchorReceivedNanos) {
    return process(playerId, raw, liveWorld, currentAnchor, currentAnchorReceivedNanos, null);
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
    return process(playerId, raw, liveWorld, currentAnchor, -1L, ignoredLatestServerPosition);
  }

  private Report process(
      String playerId,
      List<Packets.RawPacket> raw,
      WorldSnapshot liveWorld,
      Player currentAnchor,
      long currentAnchorReceivedNanos,
      Maths.Vec3 ignoredLatestServerPosition) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(raw);

    if (currentAnchor != null && !currentAnchor.equals(anchor)) {
      anchor = currentAnchor;
      emitted.clear();
    }
    if (currentAnchor != null && currentAnchorReceivedNanos >= 0) {
      anchorReceivedNanos = currentAnchorReceivedNanos;
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

    if (history.size() > MAX_HISTORY_PACKETS) {
      int remove = history.size() - MAX_HISTORY_PACKETS;
      history.subList(0, remove).clear();
      // Once the causal prefix is gone, never pretend prior verdicts remain
      // exactly reproducible. The next retained replay will establish fresh
      // evidence from the explicit server anchor or become UNCERTAIN.
      emitted.clear();
    }

    if (newlyCaptured == 0) {
      return new Report(
          List.of(), 0, movementCount(raw), 0, 0, 0,
          lastProcessedSequence, latestRelativeClientTick, latestContinuation,
          latestCandidateCount > 0);
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
      if (prior != result.verdict()) {
        emitted.put(sequence, result.verdict());
        fresh.add(result);
      }
    }

    World.VisibilityHistory historyView = World.fromTimeline(timeline);
    for (Timeline.Event event : timeline.events()) {
      if (!(event.packet().packet() instanceof Packets.FlightToggle)) continue;
      Optional<Result> authoritative = CausalMovementPipeline.evaluateFlightToggle(
          playerId, event, timeline,
          Phase7Timing.reconstruct(timeline, Phase7Timing.Config.defaultConfig()),
          liveWorld != null ? liveWorld : historyView.statesAt(event.serverTick()),
          anchor);
      if (authoritative.isEmpty()) continue;
      Result result = authoritative.get();
      long sequence = event.packet().sequence();
      Verdict prior = emitted.get(sequence);
      if (prior != result.verdict()) {
        emitted.put(sequence, result.verdict());
        fresh.add(result);
      }
    }

    fresh.sort(Comparator.comparingLong(result -> result.evidence().serverTick()));

    Continuation continuation = continuationFor(pipeline, fresh);
    int possible = count(fresh, Verdict.POSSIBLE);
    int uncertain = count(fresh, Verdict.UNCERTAIN);
    int impossible = count(fresh, Verdict.IMPOSSIBLE);

    boolean frontier = pipeline.results().stream()
        .anyMatch(result -> result.evidence().matchingCandidateCount() > 0
            && result.verdict() == Verdict.POSSIBLE);

    latestRelativeClientTick = relativeTick;
    latestCandidateCount = (int) pipeline.results().stream()
        .mapToInt(result -> result.evidence().reachableCandidateCount())
        .max().orElse(0);
    latestContinuation = continuation;

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

  private static Continuation continuationFor(
      CausalMovementPipeline.Report pipeline,
      List<Result> fresh) {
    if (fresh.isEmpty()) {
      return pipeline.results().isEmpty() ? Continuation.UNANCHORED : Continuation.ACTIVE;
    }
    Result latest = fresh.getLast();
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
