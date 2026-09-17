package dev.phantom.ac;

import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.Map;

/** Lightweight deterministic performance probe for Phase 5 collision/replay primitives. */
public final class Phase5PerformanceBenchmark {
    private Phase5PerformanceBenchmark() {}

    public record Result(int iterations, long elapsedNanos, double operationsPerSecond) implements Serializable {
        public Result {
            if (iterations <= 0 || elapsedNanos <= 0 || !Double.isFinite(operationsPerSecond) || operationsPerSecond <= 0) throw new IllegalArgumentException("invalid benchmark result");
        }
    }

    public static Result benchmarkRichCollision(int iterations) {
        if (iterations <= 0) throw new IllegalArgumentException("iterations must be positive");
        WorldSnapshot world = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).loadChunk(-1, 0)
                .setBlock(0, 64, 0, dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of())).build();
        Maths.Aabb player = new Maths.Aabb(-0.3, 65.0, 0.2, 0.3, 66.8, 0.8);
        Maths.Vec3 motion = new Maths.Vec3(0.031, -0.02, 0.027);
        long start = System.nanoTime();
        double sink = 0;
        for (int i = 0; i < iterations; i++) {
            RichWorldCollision.Result result = RichWorldCollision.resolve(world, player, motion, 0.0);
            sink += result.displacement().x() + result.displacement().y() + result.displacement().z();
        }
        long elapsed = System.nanoTime() - start;
        if (!Double.isFinite(sink)) throw new IllegalStateException("benchmark produced non-finite result");
        return new Result(iterations, elapsed, iterations / (elapsed / 1_000_000_000.0));
    }
}
