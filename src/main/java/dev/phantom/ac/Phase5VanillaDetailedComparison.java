package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

/** Rich first-divergence diagnostics for Phase 5 empirical replay. */
public final class Phase5VanillaDetailedComparison {
    private Phase5VanillaDetailedComparison() {}

    public enum Cause {
        TICK_ALIGNMENT, POSITION, VELOCITY, ROTATION, GROUND_STATE, INPUT_TIMING,
        COLLISION, POSE, FLUID, EFFECT, ATTRIBUTE, KNOCKBACK_OR_VELOCITY_PACKET,
        CORRECTION, UNKNOWN
    }

    public record StateContext(long tick, String phase, String position, String velocity, String input,
                               String pose, String fluid, String effects, String modifiers,
                               String collision, String correction, String worldIdentity) implements Serializable {}

    public record Diagnostic(long tick, Cause cause, String field, String expected, String actual, double delta,
                             StateContext previousVanilla, StateContext currentVanilla,
                             String simulatedBefore, String simulatedAfter, String explanation) implements Serializable {}

    public static Optional<Diagnostic> firstDivergence(Phase5VanillaTrace.Trace vanilla, Simulation.Trace simulated, double epsilon) {
        Objects.requireNonNull(vanilla, "vanilla");
        Objects.requireNonNull(simulated, "simulated");
        if (!Double.isFinite(epsilon) || epsilon < 0) throw new IllegalArgumentException("epsilon must be finite and non-negative");

        int count = Math.min(vanilla.rows().size(), simulated.frames().size());
        for (int i = 0; i < count; i++) {
            Phase5VanillaTrace.Row expected = vanilla.rows().get(i);
            Simulation.Frame actual = simulated.frames().get(i);
            Phase5VanillaTrace.Row previousExpected = i == 0 ? null : vanilla.rows().get(i - 1);
            Simulation.Frame previousActual = i == 0 ? null : simulated.frames().get(i - 1);
            long tick = parseLong(expected.tick());
            StateContext previousVanilla = previousExpected == null ? null : context(previousExpected);
            StateContext currentVanilla = context(expected);

            if (tick != actual.tick()) return Optional.of(diag(tick, Cause.TICK_ALIGNMENT, "tick", expected.tick(), Long.toString(actual.tick()), actual.tick() - tick, previousVanilla, currentVanilla, previousActual, actual, "server/client tick alignment differs"));
            if (!matches(expected, "x", expected.positionX(), actual.state().position().x(), epsilon)) return Optional.of(diag(tick, Cause.POSITION, "x", expected.positionX(), Double.toString(actual.state().position().x()), actual.state().position().x() - Double.parseDouble(expected.positionX()), previousVanilla, currentVanilla, previousActual, actual, "first X position divergence"));
            if (!matches(expected, "y", expected.positionY(), actual.state().position().y(), epsilon)) return Optional.of(diag(tick, Cause.POSITION, "y", expected.positionY(), Double.toString(actual.state().position().y()), actual.state().position().y() - Double.parseDouble(expected.positionY()), previousVanilla, currentVanilla, previousActual, actual, "first Y position divergence"));
            if (!matches(expected, "z", expected.positionZ(), actual.state().position().z(), epsilon)) return Optional.of(diag(tick, Cause.POSITION, "z", expected.positionZ(), Double.toString(actual.state().position().z()), actual.state().position().z() - Double.parseDouble(expected.positionZ()), previousVanilla, currentVanilla, previousActual, actual, "first Z position divergence"));
            if (!matches(expected, "vx", expected.velocityX(), actual.state().velocity().x(), epsilon)) return Optional.of(diag(tick, Cause.VELOCITY, "vx", expected.velocityX(), Double.toString(actual.state().velocity().x()), actual.state().velocity().x() - Double.parseDouble(expected.velocityX()), previousVanilla, currentVanilla, previousActual, actual, "first X velocity divergence"));
            if (!matches(expected, "vy", expected.velocityY(), actual.state().velocity().y(), epsilon)) return Optional.of(diag(tick, Cause.VELOCITY, "vy", expected.velocityY(), Double.toString(actual.state().velocity().y()), actual.state().velocity().y() - Double.parseDouble(expected.velocityY()), previousVanilla, currentVanilla, previousActual, actual, "first Y velocity divergence"));
            if (!matches(expected, "vz", expected.velocityZ(), actual.state().velocity().z(), epsilon)) return Optional.of(diag(tick, Cause.VELOCITY, "vz", expected.velocityZ(), Double.toString(actual.state().velocity().z()), actual.state().velocity().z() - Double.parseDouble(expected.velocityZ()), previousVanilla, currentVanilla, previousActual, actual, "first Z velocity divergence"));
            if (!expected.missingFields().contains("yaw") && Math.abs(Double.parseDouble(expected.yaw()) - actual.state().yaw()) > epsilon) return Optional.of(diag(tick, Cause.ROTATION, "yaw", expected.yaw(), Float.toString(actual.state().yaw()), actual.state().yaw() - Double.parseDouble(expected.yaw()), previousVanilla, currentVanilla, previousActual, actual, "yaw differs"));
            if (!expected.missingFields().contains("pitch") && Math.abs(Double.parseDouble(expected.pitch()) - actual.state().pitch()) > epsilon) return Optional.of(diag(tick, Cause.ROTATION, "pitch", expected.pitch(), Float.toString(actual.state().pitch()), actual.state().pitch() - Double.parseDouble(expected.pitch()), previousVanilla, currentVanilla, previousActual, actual, "pitch differs"));
            if (!expected.missingFields().contains("on_ground") && Boolean.parseBoolean(expected.onGround()) != actual.state().onGround()) return Optional.of(diag(tick, Cause.GROUND_STATE, "on_ground", expected.onGround(), Boolean.toString(actual.state().onGround()), 1, previousVanilla, currentVanilla, previousActual, actual, "ground transition differs"));
            if (!expected.missingFields().contains("forward") && !expected.missingFields().contains("strafe") && !expected.missingFields().contains("jump")) {
                String expectedInput = expected.forward() + "," + expected.strafe() + "," + expected.jump();
                String actualInput = actual.input().forward() + "," + actual.input().strafe() + "," + actual.input().jump();
                if (!expectedInput.equals(actualInput)) return Optional.of(diag(tick, Cause.INPUT_TIMING, "input", expectedInput, actualInput, 0, previousVanilla, currentVanilla, previousActual, actual, "pre-tick control input differs from replayed input"));
            }
            if (!expected.missingFields().contains("collision") && Boolean.parseBoolean(expected.collision()) != actual.collision()) return Optional.of(diag(tick, Cause.COLLISION, "collision", expected.collision(), Boolean.toString(actual.collision()), 1, previousVanilla, currentVanilla, previousActual, actual, "collision result differs"));
        }

        if (vanilla.rows().size() != simulated.frames().size()) {
            int index = count;
            long tick = index < vanilla.rows().size() ? parseLong(vanilla.rows().get(index).tick()) : simulated.frames().get(index).tick();
            return Optional.of(new Diagnostic(tick, Cause.TICK_ALIGNMENT, "trace_length", Integer.toString(vanilla.rows().size()), Integer.toString(simulated.frames().size()), simulated.frames().size() - vanilla.rows().size(), null, index < vanilla.rows().size() ? context(vanilla.rows().get(index)) : null, null, index < simulated.frames().size() ? describe(simulated.frames().get(index)) : "<none>", "trace length differs"));
        }
        return Optional.empty();
    }

    private static boolean matches(Phase5VanillaTrace.Row row, String field, String expected, double actual, double epsilon) {
        return row.missingFields().contains(field) || Math.abs(Double.parseDouble(expected) - actual) <= epsilon;
    }

    private static Diagnostic diag(long tick, Cause cause, String field, String expected, String actual, double delta,
                                   StateContext previousVanilla, StateContext currentVanilla, Simulation.Frame previousActual,
                                   Simulation.Frame actualFrame, String explanation) {
        return new Diagnostic(tick, cause, field, expected, actual, delta, previousVanilla, currentVanilla,
                describe(previousActual), describe(actualFrame), explanation);
    }

    private static String describe(Simulation.Frame frame) {
        if (frame == null) return "<none>";
        return "tick=" + frame.tick() + " pos=" + frame.state().position() + " vel=" + frame.state().velocity()
                + " ground=" + frame.state().onGround() + " input=" + frame.input();
    }

    private static StateContext context(Phase5VanillaTrace.Row row) {
        return new StateContext(parseLong(row.tick()), row.inputSource(),
                row.positionX() + "," + row.positionY() + "," + row.positionZ(),
                row.velocityX() + "," + row.velocityY() + "," + row.velocityZ(),
                row.forward() + "," + row.strafe() + "," + row.jump(), row.pose(), row.fluid(),
                "speed=" + row.speedAmp() + ",slow=" + row.slownessAmp() + ",jump=" + row.jumpBoostAmp()
                        + ",levitation=" + row.levitation() + ",slow_falling=" + row.slowFalling(),
                row.modifiers(),
                "collision=" + row.collision() + ",x=" + row.collisionX() + ",y=" + row.collisionY() + ",z=" + row.collisionZ(),
                "id=" + row.correctionId() + ",pending=" + row.correctionPending(), row.worldIdentity());
    }

    private static long parseLong(String value) { return Long.parseLong(value); }
}
