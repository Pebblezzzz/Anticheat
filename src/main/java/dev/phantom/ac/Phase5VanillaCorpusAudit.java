package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

/** Structural audit for a Phase 5 vanilla corpus before numerical replay. */
public final class Phase5VanillaCorpusAudit {
    private Phase5VanillaCorpusAudit() {}

    public record Finding(String scenario, String field, String message) implements Serializable {}
    public record Result(boolean valid, List<Finding> findings, Map<String, Integer> observedRows) implements Serializable {
        public Result { findings = List.copyOf(findings); observedRows = Map.copyOf(observedRows); }
    }

    public static Result audit(Phase5VanillaTrace.Trace trace) { return audit(trace, Phase5ScenarioCatalog.required()); }

    public static Result audit(Phase5VanillaTrace.Trace trace, Collection<Phase5ScenarioCatalog.Scenario> scenarios) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(scenarios, "scenarios");
        List<Phase5VanillaTrace.Row> rows = trace.rows();
        List<Finding> findings = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (Phase5ScenarioCatalog.Scenario scenario : scenarios) {
            List<Phase5VanillaTrace.Row> matches = rows.stream().filter(row -> phaseMatches(row, scenario.id())).toList();
            counts.put(scenario.id(), matches.size());
            if (matches.size() < scenario.minimumTicks()) {
                findings.add(new Finding(scenario.id(), "rows", "expected at least " + scenario.minimumTicks() + " captured rows, got " + matches.size()));
                continue;
            }

            // A field may be unavailable before the event that makes it observable.
            // Require at least one genuine observation rather than a fabricated value
            // in every unrelated row.
            for (String field : scenario.requiredObservations()) {
                long observed = matches.stream().filter(row -> !row.missingFields().contains(field)).count();
                if (observed == 0) findings.add(new Finding(scenario.id(), field, "required observation never appears in the scenario trace"));
            }

            boolean causalInput = matches.stream().allMatch(row -> row.inputSource().startsWith("capture-pre-tick:"));
            if (!causalInput) findings.add(new Finding(scenario.id(), "input_source", "scenario rows must use a pre-tick input observation"));

            for (Phase5VanillaTrace.Row row : matches) {
                if (!Contracts.TARGET_VERSION.equals(row.clientVersion()) && !row.missingFields().contains("client_version")) {
                    findings.add(new Finding(scenario.id(), "client_version", "client version is not " + Contracts.TARGET_VERSION));
                    break;
                }
            }
        }
        return new Result(findings.isEmpty(), findings, counts);
    }

    private static boolean phaseMatches(Phase5VanillaTrace.Row row, String id) {
        String source = row.inputSource();
        return source.equals(id) || source.endsWith(":" + id) || source.contains(":" + id + ":");
    }
}
