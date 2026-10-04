package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase8PredictionRunner.PredictionFrame;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

class ProductionMovementAnomalyTest {
  private static final ProductionCheckEngine.Config CONFIG =
      new ProductionCheckEngine.Config(true, 100.0, 40, 20, 4.0, 5.0);

  private static Player player(double x, double y, double z, boolean onGround) {
    return new Player(
        new Maths.Vec3(x, y, z),
        Maths.Vec3.ZERO,
        0.0f, 0.0f, onGround,
        "survival",
        Map.of(),
        OptionalInt.empty(),
        false,
        Optional.of(new Simulation.AdvancedInput(0, 0, false, false, false)),
        Simulation.Attributes.DEFAULT,
        Pose.STANDING,
        State.Environment.DRY,
        State.TickRange.exact(1),
        State.Provenance.UNKNOWN,
        Set.of());
  }

  private static PredictionFrame frame(
      long sequence, Player before, Player after) {
    Packets.Move move = new Packets.Move(
        after.position(), 0.0f, 0.0f, after.onGround(), 1L);
    return new PredictionFrame(
        sequence, sequence * 50_000_000L, sequence, sequence,
        move, before, after,
        Set.of(), Set.of(),
        WorldSnapshot.emptyOverworld12111(),
        List.of(), List.of());
  }

  private static ProductionCheckEngine.Report analyze(List<PredictionFrame> frames) {
    List<Packets.RawPacket> packets = frames.stream()
        .map(frame -> new Packets.RawPacket(
            frame.sequence(), frame.receivedNanos(), frame.movement()))
        .toList();

    Phase8PredictionRunner.Report report = new Phase8PredictionRunner.Report(
        List.of(), packets.size(), frames.size(), 0, 0, 0,
        frames.getLast().sequence(), frames.getLast().clientTick(),
        null, true, frames);
    return ProductionCheckEngine.analyze("alice", packets, report, CONFIG);
  }

  @Test void blatantStepHeightProducesHardFinding() {
    ProductionCheckEngine.Report report = analyze(List.of(
        frame(1, player(0.5, 64.0, 0.5, true),
            player(0.7, 65.0, 0.5, true))));

    assertTrue(report.findings().stream()
        .anyMatch(f -> f.rule().equals("Step") && f.verdict() == ProductionCheckEngine.Verdict.IMPOSSIBLE),
        () -> "expected hard Step finding, got " + report.findings());
  }

  @Test void blatantSpeedProducesHardFinding() {
    ProductionCheckEngine.Report report = analyze(List.of(
        frame(1, player(0.5, 64.0, 0.5, true),
            player(1.7, 64.0, 0.5, true))));

    assertTrue(report.findings().stream()
        .anyMatch(f -> f.rule().equals("Speed") && f.verdict() == ProductionCheckEngine.Verdict.IMPOSSIBLE),
        () -> "expected hard Speed finding, got " + report.findings());
  }

  @Test void sustainedAirHoverProducesHardFlightFinding() {
    Player ground = player(0.5, 64.0, 0.5, true);
    Player air = player(0.5, 65.0, 0.5, false);

    ProductionCheckEngine.Report report = analyze(List.of(
        frame(1, ground, air),
        frame(2, air, player(0.7, 65.0, 0.5, false))));

    assertTrue(report.findings().stream()
        .anyMatch(f -> f.rule().equals("Flight") && f.verdict() == ProductionCheckEngine.Verdict.IMPOSSIBLE),
        () -> "expected hard Flight finding, got " + report.findings());
  }
}
