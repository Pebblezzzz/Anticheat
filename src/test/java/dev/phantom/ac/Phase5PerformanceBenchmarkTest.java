package dev.phantom.ac;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Phase5PerformanceBenchmarkTest {
    @Test
    void richCollisionBenchmarkProducesFiniteThroughputMeasurement() {
        Phase5PerformanceBenchmark.Result result = Phase5PerformanceBenchmark.benchmarkRichCollision(2_000);
        assertEquals(2_000, result.iterations());
        assertTrue(result.elapsedNanos() > 0);
        assertTrue(result.operationsPerSecond() > 0.0);
    }
}
