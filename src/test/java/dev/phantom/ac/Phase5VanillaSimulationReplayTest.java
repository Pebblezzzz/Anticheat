package dev.phantom.ac;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays each stable, observation-rich vanilla tick through the real Phantom
 * 1.21.11 integrator. The prior vanilla row is used as the authoritative
 * pre-tick state, so this test measures the integrator itself rather than
 * depending on an invented initial state or a synthetic trajectory.
 *
 * Collision/step ticks are deliberately excluded here: the capture records
 * the result, but the batch course geometry is not serialized into the trace.
 * Those rows remain covered by dedicated collision/step fixtures until their
 * exact world snapshot is available.
 */
class Phase5VanillaSimulationReplayTest {
    private static final String TRACE_PROPERTY = "phantom.phase5.trace";
    private static final double EPSILON = 2.0e-3;
    private static final Set<String> CALIBRATABLE_PHASES = Set.of(
            "all:walk", "all:sprint", "all:jump", "all:sneak", "all:diagonal",
            "all:water", "all:lava", "all:speed-effect", "all:slowness-effect",
            "all:jump-boost", "all:tail");

    @Test
    void capturedVanillaTicksReplayThroughSimulation() throws Exception {
        String rawPath = System.getProperty(TRACE_PROPERTY);
        if (rawPath == null || rawPath.isBlank()) return;

        Path path = Path.of(rawPath);
        assertTrue(Files.isRegularFile(path), "Phase 5 trace does not exist: " + path);
        Phase5VanillaTrace.Trace trace = Phase5VanillaTrace.read(Files.readAllLines(path));
        assertFalse(trace.rows().isEmpty(), "Phase 5 trace contains no rows");

        Simulation.Vanilla12111Physics physics = new Simulation.Vanilla12111Physics();
        List<String> divergences = new ArrayList<>();
        Phase5VanillaTrace.Row previous = null;
        String previousPhase = null;

        for (Phase5VanillaTrace.Row current : trace.rows()) {
            String phase = normalizePhase(current.inputSource());
            if (!CALIBRATABLE_PHASES.contains(phase)) {
                previous = current;
                previousPhase = phase;
                continue;
            }
            if (!phase.equals(previousPhase) || previous == null) {
                previous = current;
                previousPhase = phase;
                continue;
            }
            if (Boolean.parseBoolean(current.collision())
                    || Boolean.parseBoolean(current.stepAttempted())
                    || Boolean.parseBoolean(previous.collision())
                    || Boolean.parseBoolean(previous.stepAttempted())) {
                previous = current;
                previousPhase = phase;
                continue;
            }

            Simulation.PhysicsContext context = contextFrom(previous, current);
            Simulation.StepResult result = physics.step(context);
            State.Player actual = result.state();
            compare(divergences, current, phase, "x", current.positionX(), actual.position().x());
            compare(divergences, current, phase, "y", current.positionY(), actual.position().y());
            compare(divergences, current, phase, "z", current.positionZ(), actual.position().z());
            compare(divergences, current, phase, "vx", current.velocityX(), actual.velocity().x());
            compare(divergences, current, phase, "vy", current.velocityY(), actual.velocity().y());
            compare(divergences, current, phase, "vz", current.velocityZ(), actual.velocity().z());
            if (current.onGround() != actual.onGround()) {
                divergences.add(format(current, phase, "on_ground", Boolean.toString(current.onGround()), Boolean.toString(actual.onGround()), 1.0));
            }
            if (divergences.size() >= 20) break;

            previous = current;
            previousPhase = phase;
        }

        assertTrue(divergences.isEmpty(), () -> "Vanilla→simulation replay divergences:\n" + String.join("\n", divergences));
    }

    private static Simulation.PhysicsContext contextFrom(Phase5VanillaTrace.Row previous, Phase5VanillaTrace.Row current) {
        State.Player player = new State.Player(
                new Maths.Vec3(previous.positionXValue(), previous.positionYValue(), previous.positionZValue()),
                new Maths.Vec3(previous.velocityXValue(), previous.velocityYValue(), previous.velocityZValue()),
                (float) previous.yawValue(),
                (float) previous.pitchValue(),
                previous.onGroundValue(),
                previous.gamemode(),
                effects(current),
                OptionalInt.empty(),
                false);

        Simulation.AdvancedInput input = new Simulation.AdvancedInput(
                parseSigned(current.forward()), parseSigned(current.strafe()),
                Boolean.parseBoolean(current.jump()), Boolean.parseBoolean(current.sprint()),
                Boolean.parseBoolean(current.sneak()));

        Simulation.Environment environment = switch (current.fluid()) {
            case WATER -> Simulation.Environment.WATER;
            case LAVA -> Simulation.Environment.LAVA;
            case NONE -> Simulation.Environment.DRY;
        };

        Simulation.Attributes attributes = new Simulation.Attributes(
                Double.parseDouble(current.baseMovementSpeed()), current.modifiers());

        Phase5Mechanics.MovementEnvironment movement = switch (current.fluid()) {
            case WATER -> Phase5Mechanics.vanillaWaterEnv(current.onGroundValue(), current.sprintingValue(), current.sneakingValue(), "SWIMMING".equals(current.pose()));
            case LAVA -> Phase5Mechanics.vanillaLavaEnv(current.onGroundValue(), current.sprintingValue(), current.sneakingValue());
            case NONE -> Phase5Mechanics.MovementEnvironment.dry(current.onGroundValue(), current.sprintingValue(), current.sneakingValue());
        };

        World.Snapshot world = visibleFloor(previous);
        return new Simulation.PhysicsContext(previous.tick(), player, input, world, environment, attributes,
                effects(current), pose(current), movement);
    }

    private static World.Snapshot visibleFloor(Phase5VanillaTrace.Row row) {
        int bx = (int) Math.floor(row.positionXValue());
        int bz = (int) Math.floor(row.positionZValue());
        int by = (int) Math.floor(row.positionYValue()) - 1;
        Map<World.Pos, World.Block> blocks = new HashMap<>();
        if (row.onGroundValue()) {
            for (int x = bx - 1; x <= bx + 1; x++) {
                for (int z = bz - 1; z <= bz + 1; z++) blocks.put(new World.Pos(x, by, z), World.Block.FULL);
            }
        }
        return new World.Snapshot(blocks, Set.of(World.Chunk.containing(bx, bz)));
    }

    private static Phase5Mechanics.MovementEffects effects(Phase5VanillaTrace.Row row) {
        return row.effects();
    }

    private static Phase5Mechanics.Pose pose(Phase5VanillaTrace.Row row) {
        return Phase5Mechanics.Pose.valueOf(row.pose());
    }

    private static int parseSigned(String value) { return Integer.parseInt(value); }

    private static void compare(List<String> out, Phase5VanillaTrace.Row row, String phase,
                                String field, String expectedRaw, double actual) {
        double expected = Double.parseDouble(expectedRaw);
        double delta = actual - expected;
        if (!Double.isFinite(actual) || Math.abs(delta) > EPSILON) {
            out.add(format(row, phase, field, expectedRaw, Double.toString(actual), delta));
        }
    }

    private static String format(Phase5VanillaTrace.Row row, String phase, String field,
                                 String expected, String actual, double delta) {
        return "phase=" + phase + " tick=" + row.tick() + " field=" + field
                + " vanilla=" + expected + " simulation=" + actual + " delta=" + delta;
    }

    private static String normalizePhase(String source) {
        return source.startsWith("capture-post-tick:")
                ? source.substring("capture-post-tick:".length()) : source;
    }
}
