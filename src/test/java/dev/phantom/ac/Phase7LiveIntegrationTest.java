package dev.phantom.ac;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.phantom.ac.Maths.Vec3;
import static dev.phantom.ac.Packets.*;

class Phase7LiveIntegrationTest {
  private static Timeline.Snapshot capture(List<RawPacket> packets) {
    return Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L);
  }

  @Test void liveValidationUsesTimingEnvelopeAndDoesNotTurnJitterIntoImpossible() {
    var packets = List.of(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 55_000_000L, new Move(new Vec3(.1, 0, 0), 0f, 0f, true, null)),
        new RawPacket(3, 115_000_000L, new Move(new Vec3(.2, 0, 0), 0f, 0f, true, null)),
        new RawPacket(4, 180_000_000L, new Move(new Vec3(.3, 0, 0), 0f, 0f, true, null)));
    var timing = new Phase7Timing.Config(50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0, 35_000_000L), new Phase7Timing.LatencyBounds(0, 35_000_000L),
        new Phase7Timing.TickDelayBounds(0, 1), new Phase7Timing.TickDelayBounds(0, 1),
        250_000_000L, 3, 128);
    var report = LiveValidation.analyze(capture(packets), 256, timing);
    assertEquals(0, report.impossibleFindings(), report.findings().toString());
    assertTrue(report.uncertainFindings() >= 1, report.findings().toString());
  }

  @Test void liveValidationPassesPhase7TimingReasonsDownstream() {
    var packets = List.of(
        new RawPacket(1, 0, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
        new RawPacket(2, 400_000_000L, new Move(new Vec3(.2, 0, 0), 0f, 0f, true, null)));
    var timing = new Phase7Timing.Config(50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0, 25_000_000L), new Phase7Timing.LatencyBounds(0, 25_000_000L),
        new Phase7Timing.TickDelayBounds(0, 0), new Phase7Timing.TickDelayBounds(0, 0),
        100_000_000L, 3, 128);
    var report = LiveValidation.analyze(capture(packets), 256, timing);
    assertTrue(report.findings().stream().anyMatch(f -> f.reasons().stream().anyMatch(s -> s.contains("observation gap") || s.contains("timing"))), report.findings().toString());
  }
}
