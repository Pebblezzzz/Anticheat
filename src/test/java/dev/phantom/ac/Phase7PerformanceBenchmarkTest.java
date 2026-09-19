package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class Phase7PerformanceBenchmarkTest {
  @Test
  void phase7TenThousandEventBenchmarkIsMeasuredAndBounded() {
    Phase7PerformanceBenchmark.Result result =
        Phase7PerformanceBenchmark.benchmark(10_000);
    assertEquals(1, result.players());
    assertEquals(10_000, result.eventsPerPlayer());
    assertEquals(10_000, result.totalEvents());
    assertTrue(result.nanos() > 0);
    assertTrue(result.replayNanos() > 0);
    assertTrue(result.materializedTimingCandidates() > 0);
    assertTrue(result.peakMaterializedTimingCandidates() > 0);
    assertTrue(result.timingHistoryUpperBound() > 0);
    assertTrue(result.estimatedPeakMemoryBytes() > 0);
    assertTrue(result.nanos() < 5_000_000_000L,
        "synthetic Phase 7 reconstruction exceeded 5s");
  }

  @Test
  void multiPlayerTimingWorkloadScalesByDeterministicEventCount() {
    Phase7PerformanceBenchmark.Result result =
        Phase7PerformanceBenchmark.benchmarkMultiPlayer(4, 250);
    assertEquals(4, result.players());
    assertEquals(250, result.eventsPerPlayer());
    assertEquals(1_000, result.totalEvents());
    assertTrue(result.materializedTimingCandidates() > 0);
    assertTrue(result.estimatedPeakMemoryBytes() > 0);
  }

  @Test
  void timingBudgetCanBeObservedWithoutFabricatingPrecision() {
    Phase7Timing.Config config = new Phase7Timing.Config(
        50_000_000L, 1_000_000L, 1_000_000L,
        new Phase7Timing.LatencyBounds(0, 1_000_000_000L),
        new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 1),
        new Phase7Timing.TickDelayBounds(0, 1),
        250_000_000L, 3, 2, 4);
    var capture = Phase7TimingTestFixture.timeline(
        new Packets.RawPacket(1, 0,
            new Packets.Move(Maths.Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 1_000_000_000L,
            new Packets.Move(new Maths.Vec3(1, 0, 0), 0f, 0f, true, null)));
    var result = Phase7Timing.reconstruct(capture, config);
    var timing = result.frames().getLast().timing();
    assertTrue(timing.simulationClientTicks().width() > 2);
    assertTrue(timing.possibleSimulationClientTicks().isEmpty());
    assertFalse(timing.simulationCandidatesExhaustive());
    assertTrue(result.metrics().budgetReached());
  }

  /** Small shared capture helper avoids adding network or clock dependence to benchmarks. */
  static final class Phase7TimingTestFixture {
    static Timeline.Snapshot timeline(Packets.RawPacket... packets) {
      return Timeline.assign(
          new Normalizer().normalize(java.util.List.of(packets)),
          0, 50_000_000L);
    }
  }
}
