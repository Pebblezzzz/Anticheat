package dev.phantom.ac;

import static dev.phantom.ac.Maths.Vec3;
import static dev.phantom.ac.Packets.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Phase 7 synthetic performance fixtures.
 *
 * <p>Elapsed time is intentionally benchmark-only and is never part of a replay
 * signature. Workloads themselves are deterministic.</p>
 */
public final class Phase7PerformanceBenchmark {
  private Phase7PerformanceBenchmark() {}

  public record Result(
      long nanos,
      long replayNanos,
      int players,
      int eventsPerPlayer,
      long totalEvents,
      long materializedTimingCandidates,
      int peakMaterializedTimingCandidates,
      long timingHistoryUpperBound,
      long synchronizationTransitions,
      boolean budgetReached,
      long estimatedPeakMemoryBytes,
      Phase7Timing.Consistency consistency) {}

  public static Result benchmark(int movementEvents) {
    return benchmarkMultiPlayer(1, movementEvents);
  }

  public static Result benchmarkMultiPlayer(
      int players,
      int eventsPerPlayer) {
    if (players < 1 || eventsPerPlayer < 1) {
      throw new IllegalArgumentException("players and eventsPerPlayer must be positive");
    }

    Phase7Timing.Config config = Phase7Timing.Config.defaultConfig();
    List<Timeline.Snapshot> captures = new ArrayList<>(players);
    for (int player = 0; player < players; player++) {
      captures.add(syntheticTimeline(eventsPerPlayer, player));
    }

    long begin = System.nanoTime();
    List<Phase7Timing.Reconstruction> reconstructions = new ArrayList<>(players);
    for (Timeline.Snapshot capture : captures) {
      reconstructions.add(Phase7Timing.reconstruct(capture, config));
    }
    long nanos = System.nanoTime() - begin;

    long replayBegin = System.nanoTime();
    for (Timeline.Snapshot capture : captures) {
      Phase7Replay replay = Phase7Replay.of(capture, config);
      Phase7Replay decoded = Phase7Replay.decode(replay.encode());
      Phase7Timing.Reconstruction original = replay.reconstruct();
      Phase7Replay.Verification verification = decoded.verifyAgainst(original);
      if (!verification.identical()) {
        throw new IllegalStateException(
            "Phase 7 benchmark replay verification diverged");
      }
    }
    long replayNanos = System.nanoTime() - replayBegin;

    long materialized = 0;
    int peak = 0;
    long histories = 1;
    long transitions = 0;
    boolean budget = false;
    Phase7Timing.Consistency consistency = Phase7Timing.Consistency.CONSISTENT;
    for (Phase7Timing.Reconstruction reconstruction : reconstructions) {
      Phase7Timing.TimingMetrics metrics = reconstruction.metrics();
      materialized += metrics.materializedTimingCandidates();
      peak = Math.max(peak, metrics.peakMaterializedTimingCandidates());
      histories = Math.max(histories, metrics.timingHistoryUpperBound());
      transitions += metrics.synchronizationTransitions();
      budget |= metrics.budgetReached();
      if (reconstruction.consistency() == Phase7Timing.Consistency.INCONSISTENT) {
        consistency = Phase7Timing.Consistency.INCONSISTENT;
      } else if (reconstruction.consistency() == Phase7Timing.Consistency.UNCERTAIN
          && consistency == Phase7Timing.Consistency.CONSISTENT) {
        consistency = Phase7Timing.Consistency.UNCERTAIN;
      }
    }

    long totalEvents = (long) players * eventsPerPlayer;
    long estimatedPeakMemoryBytes = safeEstimate(
        totalEvents,
        peak,
        histories);

    return new Result(
        nanos,
        replayNanos,
        players,
        eventsPerPlayer,
        totalEvents,
        materialized,
        peak,
        histories,
        transitions,
        budget,
        estimatedPeakMemoryBytes,
        consistency);
  }

  private static Timeline.Snapshot syntheticTimeline(
      int movementEvents,
      int playerIndex) {
    List<RawPacket> packets = new ArrayList<>(movementEvents + 3);
    long sequence = 1;
    long nanos = 0;

    packets.add(new RawPacket(
        sequence++,
        nanos,
        new Move(Vec3.ZERO, 0f, 0f, true, 0L)));
    packets.add(new RawPacket(
        sequence++,
        nanos + 1_000_000L,
        new ClientTickEnd()));

    for (int i = 0; i < movementEvents - 1; i++) {
      nanos += 50_000_000L;
      Long tick = i < movementEvents / 2 ? (long) (i + 1) : null;
      Vec3 position = new Vec3(
          playerIndex * 0.01 + i * 0.01,
          0,
          (i % 3) * 0.005);
      packets.add(new RawPacket(
          sequence++,
          nanos,
          new Move(position, (float) (i % 360), 0f, true, tick)));
      if (i % 97 == 0) {
        packets.add(new RawPacket(
            sequence++,
            nanos + 1_000_000L,
            new Velocity(new Vec3(0.02, 0.04, 0.01))));
      }
      if (i % 131 == 0) {
        packets.add(new RawPacket(
            sequence++,
            nanos + 2_000_000L,
            new ChunkData(new World.Chunk(playerIndex, 0), Map.of())));
      }
    }

    return Timeline.assign(
        new Normalizer().normalize(packets),
        0,
        50_000_000L);
  }

  private static long safeEstimate(
      long events,
      int peakCandidates,
      long historyUpperBound) {
    long eventBytes = events > Long.MAX_VALUE / 768L
        ? Long.MAX_VALUE : events * 768L;
    long candidateBytes = (long) peakCandidates * 256L;
    long historyBytes = historyUpperBound > Long.MAX_VALUE / 16L
        ? Long.MAX_VALUE : historyUpperBound * 16L;
    long total = saturatingAdd(eventBytes, candidateBytes);
    return saturatingAdd(total, historyBytes);
  }

  private static long saturatingAdd(long left, long right) {
    if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
    return left + right;
  }
}
