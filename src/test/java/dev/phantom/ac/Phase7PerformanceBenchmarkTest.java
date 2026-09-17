package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class Phase7PerformanceBenchmarkTest {
  @Test void phase7TenThousandEventBenchmarkIsMeasurable() {
    Phase7PerformanceBenchmark.Result result = Phase7PerformanceBenchmark.benchmark(10_000);
    assertEquals(10_000, result.events());
    assertTrue(result.nanos() > 0);
    assertTrue(result.timingCandidates() > 0);
    assertTrue(result.synchronizationWindows() >= 0);
    assertTrue(result.nanos() < 5_000_000_000L, "synthetic Phase 7 reconstruction exceeded 5s");
  }
}
