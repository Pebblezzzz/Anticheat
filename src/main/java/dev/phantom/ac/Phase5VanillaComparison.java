package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

/**
 * Comparison surface for empirical version-2 vanilla captures.
 * Environment/effect/attribute fields are compared only when present in both
 * the capture and the supplied simulation context; no missing observation is
 * converted into an inferred value.
 */
public final class Phase5VanillaComparison {
    private Phase5VanillaComparison() {}

    public record Diagnostic(long tick, String field, String expected, String actual, double delta, String message) implements Serializable {}

    public static List<Diagnostic> firstDivergence(
            Phase5VanillaTrace.Trace vanilla,
            Simulation.Trace simulated,
            double epsilon
    ) {
        Objects.requireNonNull(vanilla);
        Objects.requireNonNull(simulated);
        if (!Double.isFinite(epsilon) || epsilon < 0) throw new IllegalArgumentException("epsilon must be finite and non-negative");

        int n = Math.min(vanilla.rows().size(), simulated.frames().size());
        for (int i = 0; i < n; i++) {
            Phase5VanillaTrace.Row expected = vanilla.rows().get(i);
            Simulation.Frame actual = simulated.frames().get(i);
            long tick = parseLong(expected.tick());

            if (tick != actual.tick()) return List.of(new Diagnostic(tick, "tick", expected.tick(), Long.toString(actual.tick()), actual.tick() - tick, "first tick identity mismatch"));
            if (!within(expected, "x", expected.positionX(), actual.state().position().x(), epsilon)) return List.of(divergence(tick, "x", expected.positionX(), actual.state().position().x()));
            if (!within(expected, "y", expected.positionY(), actual.state().position().y(), epsilon)) return List.of(divergence(tick, "y", expected.positionY(), actual.state().position().y()));
            if (!within(expected, "z", expected.positionZ(), actual.state().position().z(), epsilon)) return List.of(divergence(tick, "z", expected.positionZ(), actual.state().position().z()));
            if (!within(expected, "vx", expected.velocityX(), actual.state().velocity().x(), epsilon)) return List.of(divergence(tick, "vx", expected.velocityX(), actual.state().velocity().x()));
            if (!within(expected, "vy", expected.velocityY(), actual.state().velocity().y(), epsilon)) return List.of(divergence(tick, "vy", expected.velocityY(), actual.state().velocity().y()));
            if (!within(expected, "vz", expected.velocityZ(), actual.state().velocity().z(), epsilon)) return List.of(divergence(tick, "vz", expected.velocityZ(), actual.state().velocity().z()));

            if (!expected.missingFields().contains("yaw") && Math.abs(Double.parseDouble(expected.yaw()) - actual.state().yaw()) > epsilon) {
                return List.of(divergence(tick, "yaw", expected.yaw(), actual.state().yaw()));
            }
            if (!expected.missingFields().contains("pitch") && Math.abs(Double.parseDouble(expected.pitch()) - actual.state().pitch()) > epsilon) {
                return List.of(divergence(tick, "pitch", expected.pitch(), actual.state().pitch()));
            }
            if (!expected.missingFields().contains("on_ground") && Boolean.parseBoolean(expected.onGround()) != actual.state().onGround()) {
                return List.of(new Diagnostic(tick, "on_ground", expected.onGround(), Boolean.toString(actual.state().onGround()), 1, "ground-state mismatch"));
            }
            if (!expected.missingFields().contains("forward") && !expected.missingFields().contains("strafe") && !expected.missingFields().contains("jump")) {
                int actualForward = actual.input().forward();
                int actualStrafe = actual.input().strafe();
                boolean actualJump = actual.input().jump();
                if (Integer.parseInt(expected.forward()) != actualForward || Integer.parseInt(expected.strafe()) != actualStrafe || Boolean.parseBoolean(expected.jump()) != actualJump) {
                    return List.of(new Diagnostic(tick, "input", expected.forward() + "," + expected.strafe() + "," + expected.jump(), actualForward + "," + actualStrafe + "," + actualJump, 0, "input mismatch"));
                }
            }
            if (!expected.missingFields().contains("collision") && Boolean.parseBoolean(expected.collision()) != actual.collision()) {
                return List.of(new Diagnostic(tick, "collision", expected.collision(), Boolean.toString(actual.collision()), 1, "collision result mismatch"));
            }
        }

        if (vanilla.rows().size() != simulated.frames().size()) {
            return List.of(new Diagnostic(n, "trace_length", Integer.toString(vanilla.rows().size()), Integer.toString(simulated.frames().size()), simulated.frames().size() - vanilla.rows().size(), "trace length mismatch"));
        }
        return List.of();
    }

    private static boolean within(Phase5VanillaTrace.Row row, String field, String rawExpected, double actual, double epsilon) {
        if (row.missingFields().contains(field)) return true;
        return Math.abs(Double.parseDouble(rawExpected) - actual) <= epsilon;
    }

    private static Diagnostic divergence(long tick, String field, String expected, double actual) {
        double e = Double.parseDouble(expected);
        return new Diagnostic(tick, field, expected, Double.toString(actual), actual - e, "first numeric divergence");
    }

    private static long parseLong(String value) { return Long.parseLong(value); }
}
