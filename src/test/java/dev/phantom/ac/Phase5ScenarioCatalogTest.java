package dev.phantom.ac;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Phase5ScenarioCatalogTest {
    @Test
    void requiredManifestHasNoDuplicatesAndCoversPreviouslyMissingScenarioFamilies() {
        List<Phase5ScenarioCatalog.Scenario> scenarios = Phase5ScenarioCatalog.required();
        assertFalse(scenarios.isEmpty());
        assertEquals(scenarios.size(), Phase5ScenarioCatalog.requiredIds().size());
        Set<String> ids = new HashSet<>(Phase5ScenarioCatalog.ids());
        for (String required : List.of("idle", "walk-backward", "strafe-left", "strafe-right", "sprint-strafe", "sprint-jump", "repeated-jumps", "ascent-apex-fall", "landing", "slab-up", "step-up", "partial-collision", "edge", "corner", "corner-sprint", "deep-swimming", "water-sprint", "ladder-sprint", "vines", "slow-falling", "levitation", "attribute-modifier", "knockback-ground", "knockback-air", "teleport-water", "sleeping", "step-sprint-jump")) assertTrue(ids.contains(required), "missing scenario " + required);
    }

    @Test
    void scenarioEntriesDeclareObservationRequirements() {
        for (Phase5ScenarioCatalog.Scenario scenario : Phase5ScenarioCatalog.required()) {
            assertFalse(scenario.requiredObservations().isEmpty(), scenario.id());
            assertTrue(scenario.minimumTicks() >= 10, scenario.id());
        }
    }

    @Test
    void corpusAuditRejectsTheOldSyntheticPostTickAndPlaceholderContract() {
        String[] columns = new String[Phase5VanillaTrace.COLUMN_COUNT];
        Arrays.fill(columns, "0");
        columns[0] = columns[1] = columns[2] = "1";
        for (int i = 3; i <= 10; i++) columns[i] = "0.0";
        columns[11] = "true"; columns[12] = columns[13] = "0"; columns[14] = columns[15] = columns[16] = "false";
        columns[17] = "STANDING"; columns[18] = "survival"; columns[19] = "NONE";
        columns[20] = columns[21] = columns[22] = columns[28] = columns[29] = columns[33] = columns[35] = columns[38] = columns[39] = columns[40] = columns[41] = columns[42] = columns[43] = "false";
        columns[23] = "0.1"; columns[24] = "-"; columns[25] = columns[26] = columns[27] = "-1"; columns[30] = columns[31] = columns[32] = "0.0"; columns[34] = "-1"; columns[36] = "test-world"; columns[37] = "1";
        columns[44] = "capture-post-tick:corpus:walk-forward";
        columns[45] = Contracts.TARGET_VERSION;
        columns[46] = "step_attempted,step_succeeded,collision_x,collision_y,collision_z,knockback_x,knockback_y,knockback_z,correction_pending";
        Phase5VanillaTrace.Row row = new Phase5VanillaTrace.Row(columns, Set.of("step_attempted", "step_succeeded", "collision_x", "collision_y", "collision_z", "knockback_x", "knockback_y", "knockback_z", "correction_pending"));
        Phase5VanillaTrace.Trace trace = new Phase5VanillaTrace.Trace("synthetic", "2026-09-17T00:00:00Z", List.of(row));
        Phase5VanillaCorpusAudit.Result result = Phase5VanillaCorpusAudit.audit(trace, List.of(Phase5ScenarioCatalog.find("walk-forward").orElseThrow()));
        assertFalse(result.valid());
        assertTrue(result.findings().stream().anyMatch(f -> f.field().equals("rows")));
        assertTrue(result.findings().stream().anyMatch(f -> f.field().equals("input_source")));
    }
}
