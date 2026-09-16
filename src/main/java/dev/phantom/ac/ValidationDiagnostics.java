 package dev.phantom.ac;

import java.util.List;
import java.util.Objects;

/**
 * Bounded diagnostic projection for operators and controlled test captures.
 */
public final class ValidationDiagnostics {

    private ValidationDiagnostics() {
    }

    public record Summary(int packets, int timelineEvents, int movementPackets, int anchoredObservations,
            int possible, int uncertain, int impossible, int evidenceCount, int alertCount,
            List<String> reasons) {

        public Summary {
            reasons = List.copyOf(reasons);
        }
    }

    public static Summary summarize(Timeline.Snapshot timeline, LiveValidation.Report report,
            int evidenceCount, int alertCount) {
        Objects.requireNonNull(timeline);
        Objects.requireNonNull(report);
        if (evidenceCount < 0 || alertCount < 0) {
            throw new IllegalArgumentException("diagnostic counts must be non-negative");
        }
        List<String> reasons = report.findings().stream().flatMap(f -> f.reasons().stream()).distinct().limit(12).toList();
        return new Summary(timeline.events().size(), report.timelineEvents(), report.movementObservations(), report.anchoredObservations(),
                report.possibleFindings(), report.uncertainFindings(), report.impossibleFindings(), evidenceCount, alertCount, reasons);
    }

    public static String format(Summary s) {
        return "packets=" + s.packets() + " timeline=" + s.timelineEvents() + " moves=" + s.movementPackets()
                + " anchored=" + s.anchoredObservations() + " possible=" + s.possible() + " uncertain=" + s.uncertain()
                + " impossible=" + s.impossible() + " evidence=" + s.evidenceCount() + " alerts=" + s.alertCount();
    }
}
