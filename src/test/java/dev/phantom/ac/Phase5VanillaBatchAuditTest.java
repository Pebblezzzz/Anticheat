package dev.phantom.ac;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Validates the deterministic 1.21.11 batch capture without inventing telemetry. */
class Phase5VanillaBatchAuditTest {
    private static final String TRACE_PROPERTY = "phantom.phase5.trace";
    private static final String RECORDER_PREFIX = "capture-post-tick:";
    private static final Set<String> REQUIRED_PHASES = Set.of(
            "all:setup", "all:walk", "all:sprint", "all:jump", "all:sneak", "all:diagonal",
            "all:collision", "all:water", "all:lava", "all:speed-effect", "all:slowness-effect",
            "all:jump-boost", "all:step", "all:tail");
    private static final Set<String> DRY_PHASES = Set.of(
            "all:walk", "all:sprint", "all:jump", "all:sneak", "all:diagonal",
            "all:collision", "all:speed-effect", "all:slowness-effect", "all:jump-boost", "all:step", "all:tail");

    @Test
    void batchCaptureContainsControlledPhaseCoverage() throws Exception {
        String rawPath = System.getProperty(TRACE_PROPERTY);
        if (rawPath == null || rawPath.isBlank()) return;

        Path path = Path.of(rawPath);
        assertTrue(Files.isRegularFile(path), "Phase 5 trace does not exist: " + path);
        Phase5VanillaTrace.Trace trace = Phase5VanillaTrace.read(Files.readAllLines(path));
        Phase5VanillaTrace.ValidationResult validation = Phase5VanillaTrace.validate(trace);
        assertTrue(validation.valid(), () -> "Invalid Phase 5 trace: " + validation.diagnostics());
        assertEquals("vanilla-client-1.21.11", trace.sourceId());
        assertFalse(trace.rows().isEmpty(), "Capture contains no data rows");
        assertTrue(trace.rows().stream().allMatch(r -> "1.21.11".equals(r.clientVersion())), "Capture contains a non-1.21.11 client version");
        assertTrue(trace.rows().stream().allMatch(r -> "survival".equals(r.gamemode())), "Capture contains non-Survival rows");

        Map<String, List<Phase5VanillaTrace.Row>> phases = trace.rows().stream()
                .collect(Collectors.groupingBy(r -> normalizePhase(r.inputSource())));
        assertTrue(phases.keySet().containsAll(REQUIRED_PHASES), () -> "Missing phases: " + REQUIRED_PHASES.stream().filter(Predicate.not(phases.keySet()::contains)).toList());
        for (String phase : REQUIRED_PHASES) assertTrue(phases.get(phase).size() >= 10, "Phase has too few rows: " + phase);

        assertFluidPhase(phases.get("all:water"), "WATER", "water");
        assertFluidPhase(phases.get("all:lava"), "LAVA", "lava");
        assertTrue(phases.get("all:water").stream().anyMatch(r -> "SWIMMING".equals(r.pose())), "Water phase never entered vanilla SWIMMING pose");
        assertTrue(phases.get("all:water").stream().anyMatch(r -> "SWIMMING".equals(r.pose()) && r.submerged() && Boolean.parseBoolean(r.sprint())), "Water phase never recorded submerged sprint-swimming state");
        for (String phase : DRY_PHASES) assertNoFluid(phases.get(phase), phase);
        assertEffectPhase(phases.get("all:speed-effect"), 0, "speed-effect", Phase5VanillaTrace.Row::speedAmp);
        assertEffectPhase(phases.get("all:slowness-effect"), 0, "slowness-effect", Phase5VanillaTrace.Row::slownessAmp);
        assertEffectPhase(phases.get("all:jump-boost"), 0, "jump-boost", Phase5VanillaTrace.Row::jumpBoostAmp);
        assertJumpPulseWithTrajectory(phases.get("all:jump"), "jump");
        assertJumpPulseWithTrajectory(phases.get("all:jump-boost"), "jump-boost");
        assertStepTransition(phases.get("all:step"));

        Phase5VanillaTrace.Row first = trace.rows().getFirst();
        Phase5VanillaTrace.Row last = trace.rows().getLast();
        assertEquals("1", first.tick(), "capture should start at tick 1");
        assertTrue(Long.parseLong(last.tick()) >= 1000, "batch capture ended too early: " + last.tick());
    }

    private static String normalizePhase(String source) {
        return source.startsWith(RECORDER_PREFIX) ? source.substring(RECORDER_PREFIX.length()) : source;
    }
    private static void assertFluidPhase(List<Phase5VanillaTrace.Row> rows, String expectedFluid, String label) {
        assertTrue(rows.stream().anyMatch(r -> expectedFluid.equals(r.columns()[19])), "No " + label + " fluid observation was captured");
    }
    private static void assertNoFluid(List<Phase5VanillaTrace.Row> rows, String label) {
        assertTrue(rows.stream().allMatch(r -> "NONE".equals(r.columns()[19])), "Unexpected fluid contamination in phase " + label);
    }
    private static void assertEffectPhase(List<Phase5VanillaTrace.Row> rows, int expectedAmp, String label, Function<Phase5VanillaTrace.Row, String> getter) {
        assertTrue(rows.stream().anyMatch(r -> Integer.toString(expectedAmp).equals(getter.apply(r))), "No " + label + " effect observation with amplifier " + expectedAmp);
    }
    private static void assertJumpPulseWithTrajectory(List<Phase5VanillaTrace.Row> rows, String label) {
        int consecutive = 0, total = 0;
        boolean launched = false;
        for (Phase5VanillaTrace.Row row : rows) {
            boolean jump = Boolean.parseBoolean(row.jump());
            if (jump) { consecutive++; total++; assertTrue(consecutive <= 2, label + " jump key was held for more than two ticks at tick " + row.tick()); }
            else consecutive = 0;
            if (Double.parseDouble(row.velocityY()) > 0.3 && Double.parseDouble(row.velocityY()) < 0.6) launched = true;
        }
        assertTrue(total >= 1, label + " phase contains no jump input");
        assertTrue(launched, label + " phase contains no positive vanilla jump launch velocity");
    }
    private static void assertStepTransition(List<Phase5VanillaTrace.Row> rows) {
        assertTrue(rows.stream().anyMatch(r -> Double.parseDouble(r.positionY()) > 64.25), "Step phase never recorded a raised Y position");
        assertTrue(rows.stream().anyMatch(r -> Math.abs(Double.parseDouble(r.positionY()) - 64.5) < 1e-6), "Step phase never recorded the expected half-block standing height");
    }
}
