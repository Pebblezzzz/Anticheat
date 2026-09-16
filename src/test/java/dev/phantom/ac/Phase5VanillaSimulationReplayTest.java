package dev.phantom.ac;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays stable, observation-rich vanilla ticks through the real Phantom
 * 1.21.11 integrator. The previous post-tick vanilla row is used as the
 * authoritative pre-tick state, which isolates one integrator step without
 * inventing a synthetic trajectory.
 *
 * Collision/step result rows are skipped because the course geometry is not
 * serialized in the trace. Those mechanics remain covered by dedicated
 * world-shape/regression tests until the exact captured world snapshot is
 * available.
 */
class Phase5VanillaSimulationReplayTest {
    private static final String TRACE_PROPERTY = "phantom.phase5.trace";
    private static final double EPSILON = 2.0e-3;
    private static final Set<String> CALIBRATABLE_PHASES = Set.of(
            "all:walk", "all:sprint", "all:jump", "all:sneak", "all:diagonal",
            "all:water", "all:lava", "all:speed-effect", "all:slowness-effect",
            "all:jump-boost", "all:tail");

    private static final Set<String> OBSERVED_FIELDS = Set.of(
            "x", "y", "z", "vx", "vy", "vz", "on_ground", "forward", "strafe", "jump",
            "fluid", "pose", "base_movement_speed", "modifiers", "speed_amp", "slowness_amp",
            "jump_boost_amp", "levitation", "slow_falling");

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
            if (!sameObserved(current, OBSERVED_FIELDS.toArray(String[]::new))) {
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
            if (Boolean.parseBoolean(current.onGround()) != actual.onGround()) {
                divergences.add(format(current, phase, "on_ground", current.onGround(), Boolean.toString(actual.onGround()), 1.0));
            }
            if (Phase5Mechanics.Pose.valueOf(current.pose()) != result.pose()) {
                divergences.add(format(current, phase, "pose", current.pose(), result.pose().name(), 1.0));
            }
            if (divergences.size() >= 20) break;

            previous = current;
            previousPhase = phase;
        }

        assertTrue(divergences.isEmpty(), () -> "Vanilla→simulation replay divergences:\n" + String.join("\n", divergences));
    }

    private static boolean sameObserved(Phase5VanillaTrace.Row row, String... fields) {
        for (String field : fields) if (row.missingFields().contains(field)) return false;
        return true;
    }

    private static Simulation.PhysicsContext contextFrom(Phase5VanillaTrace.Row previous, Phase5VanillaTrace.Row current) {
        Phase5Mechanics.MovementEffects effects = new Phase5Mechanics.MovementEffects(
                Integer.parseInt(current.speedAmp()),
                Integer.parseInt(current.slownessAmp()),
                Integer.parseInt(current.jumpBoostAmp()),
                Boolean.parseBoolean(current.levitation()),
                Boolean.parseBoolean(current.slowFalling()));

        State.Player player = new State.Player(
                new Maths.Vec3(Double.parseDouble(previous.positionX()), Double.parseDouble(previous.positionY()), Double.parseDouble(previous.positionZ())),
                new Maths.Vec3(Double.parseDouble(previous.velocityX()), Double.parseDouble(previous.velocityY()), Double.parseDouble(previous.velocityZ())),
                Float.parseFloat(previous.yaw()),
                Float.parseFloat(previous.pitch()),
                Boolean.parseBoolean(previous.onGround()),
                previous.gamemode(),
                Map.of(),
                OptionalInt.empty(),
                false);

        Simulation.AdvancedInput input = new Simulation.AdvancedInput(
                Integer.parseInt(current.forward()),
                Integer.parseInt(current.strafe()),
                Boolean.parseBoolean(current.jump()),
                Boolean.parseBoolean(current.sprint()),
                Boolean.parseBoolean(current.sneak()));

        Simulation.Environment environment = switch (current.fluid()) {
            case "WATER" -> Simulation.Environment.WATER;
            case "LAVA" -> Simulation.Environment.LAVA;
            default -> Simulation.Environment.DRY;
        };

        Simulation.Attributes attributes = new Simulation.Attributes(
                Double.parseDouble(current.baseMovementSpeed()), parseModifiers(current.modifiers()));

        Phase5Mechanics.MovementEnvironment movement = switch (current.fluid()) {
            case "WATER" -> new Phase5Mechanics.MovementEnvironment(
                    Phase5Mechanics.Fluid.WATER,
                    Boolean.parseBoolean(current.submerged()),
                    Boolean.parseBoolean(current.climbable()),
                    Boolean.parseBoolean(current.onGround()),
                    Boolean.parseBoolean(current.sprint()),
                    Boolean.parseBoolean(current.sneak()),
                    "SWIMMING".equals(current.pose()),
                    Boolean.parseBoolean(current.gliding()),
                    1.0, 0.9, 0.0);
            case "LAVA" -> new Phase5Mechanics.MovementEnvironment(
                    Phase5Mechanics.Fluid.LAVA,
                    Boolean.parseBoolean(current.submerged()),
                    Boolean.parseBoolean(current.climbable()),
                    Boolean.parseBoolean(current.onGround()),
                    Boolean.parseBoolean(current.sprint()),
                    Boolean.parseBoolean(current.sneak()),
                    false,
                    Boolean.parseBoolean(current.gliding()),
                    1.0, 0.5, 0.5);
            default -> new Phase5Mechanics.MovementEnvironment(
                    Phase5Mechanics.Fluid.NONE,
                    false,
                    Boolean.parseBoolean(current.climbable()),
                    Boolean.parseBoolean(current.onGround()),
                    Boolean.parseBoolean(current.sprint()),
                    Boolean.parseBoolean(current.sneak()),
                    false,
                    Boolean.parseBoolean(current.gliding()),
                    1.0, 1.0, 1.0);
        };

        return new Simulation.PhysicsContext(
                Long.parseLong(previous.tick()),
                player,
                input,
                visibleFloor(previous),
                environment,
                attributes,
                effects,
                Phase5Mechanics.Pose.valueOf(previous.pose()),
                movement);
    }

    private static World.Snapshot visibleFloor(Phase5VanillaTrace.Row row) {
        int bx = (int) Math.floor(Double.parseDouble(row.positionX()));
        int bz = (int) Math.floor(Double.parseDouble(row.positionZ()));
        Map<World.Pos, World.Block> blocks = new HashMap<>();
        Set<World.Chunk> chunks = new HashSet<>();
        for (int cx = Math.floorDiv(bx, 16) - 1; cx <= Math.floorDiv(bx, 16) + 1; cx++) {
            for (int cz = Math.floorDiv(bz, 16) - 1; cz <= Math.floorDiv(bz, 16) + 1; cz++) {
                chunks.add(new World.Chunk(cx, cz));
            }
        }

        // The controlled dry arenas all start the player at Y=64 on a flat
        // Y=63 floor. Keep that fixed across falling/jump rows; deriving the
        // floor from the current Y creates artificial holes as the player
        // descends between ticks.
        if (Boolean.parseBoolean(row.onGround()) || Double.parseDouble(row.positionY()) < 64.0) {
            int floorY = 63;
            for (int x = bx - 2; x <= bx + 2; x++) {
                for (int z = bz - 2; z <= bz + 2; z++) {
                    blocks.put(new World.Pos(x, floorY, z), World.Block.FULL);
                }
            }
        }
        return new World.Snapshot(blocks, chunks);
    }

    private static List<Phase5Mechanics.AttributeModifier> parseModifiers(String raw) {
        if (raw == null || raw.isBlank() || raw.equals("-")) return List.of();
        List<Phase5Mechanics.AttributeModifier> out = new ArrayList<>();
        for (String encoded : raw.split(";", -1)) {
            if (encoded.isBlank()) continue;
            int last = encoded.lastIndexOf(':');
            if (last <= 0 || last == encoded.length() - 1) {
                throw new IllegalArgumentException("invalid modifier " + encoded);
            }
            int secondLast = encoded.lastIndexOf(':', last - 1);
            if (secondLast <= 0 || secondLast == last - 1) {
                throw new IllegalArgumentException("invalid modifier " + encoded);
            }
            String id = encoded.substring(0, secondLast);
            String amount = encoded.substring(secondLast + 1, last);
            String operation = encoded.substring(last + 1);
            out.add(new Phase5Mechanics.AttributeModifier(
                    unescape(id), Double.parseDouble(amount),
                    Phase5Mechanics.ModifierOperation.valueOf(operation)));
        }
        return List.copyOf(out);
    }

    private static String unescape(String value) {
        return value.replace("%0D", "\r").replace("%0A", "\n").replace("%09", "\t").replace("%25", "%");
    }

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
