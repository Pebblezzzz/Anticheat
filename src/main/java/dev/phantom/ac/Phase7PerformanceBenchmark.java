package dev.phantom.ac;

import java.util.ArrayList;
import java.util.List;
import static dev.phantom.ac.Maths.Vec3;
import static dev.phantom.ac.Packets.*;

/** Deterministic Phase 7 workload benchmark. It reports processing time; it makes no performance claim about a real server. */
public final class Phase7PerformanceBenchmark {
  private Phase7PerformanceBenchmark() {}
  public record Result(long nanos, int events, int timingCandidates, int synchronizationWindows, Phase7Timing.Consistency consistency) {}

  public static Result benchmark(int movementEvents) {
    if (movementEvents < 1) throw new IllegalArgumentException("movementEvents must be positive");
    List<RawPacket> raw = new ArrayList<>(movementEvents + 2);
    for (int i = 0; i < movementEvents; i++) {
      long nanos = i * 50_000_000L;
      raw.add(new RawPacket(i + 1L, nanos, new Move(new Vec3(i * 0.05, 0, 0), 0f, 0f, true, (long) i)));
    }
    Timeline.Snapshot timeline = Timeline.assign(new Normalizer().normalize(raw), 0, 50_000_000L);
    Phase7Timing.Config config = new Phase7Timing.Config(
        50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0, 25_000_000L),
        new Phase7Timing.LatencyBounds(0, 25_000_000L),
        new Phase7Timing.TickDelayBounds(0, 1),
        new Phase7Timing.TickDelayBounds(0, 1),
        250_000_000L, 3, 128);
    long begin = System.nanoTime();
    Phase7Timing.Reconstruction reconstruction = Phase7Timing.reconstruct(timeline, config);
    long elapsed = System.nanoTime() - begin;
    int candidates = 0, windows = 0;
    for (Phase7Timing.Frame frame : reconstruction.frames()) {
      candidates += Math.max(1, (int) Math.min(Integer.MAX_VALUE, frame.timing().simulationClientTicks().width() + 1));
      windows += frame.timing().windows().size();
    }
    return new Result(elapsed, reconstruction.frames().size(), candidates, windows, reconstruction.consistency());
  }

  public static Result benchmark() { return benchmark(10_000); }
}
