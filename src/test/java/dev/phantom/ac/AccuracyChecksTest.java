package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class AccuracyChecksTest {

  @Test
  void unknownTransactionAcknowledgementProducesHardIntegrityFinding() {
    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1L, 1L, new Packets.WorldTransactionAck((short) -123))
    );

    var findings = AccuracyChecks.analyze("p", packets, List.of());

    assertTrue(findings.stream().anyMatch(f ->
        f.rule().equals("TransactionOrder")
            && f.verdict() == ProductionCheckEngine.Verdict.IMPOSSIBLE));
  }

  @Test
  void highlyPeriodicAttackIntervalsProduceAutoclickerFinding() {
    List<Packets.RawPacket> packets = new ArrayList<>();
    long sequence = 1L;
    long nanos = 0L;
    for (int i = 0; i < 60; i++) {
      packets.add(new Packets.RawPacket(
          sequence++, nanos,
          new Packets.InteractEntity(7, Packets.InteractAction.ATTACK)));
      nanos += 50_000_000L;
    }

    var findings = AccuracyChecks.analyze("p", packets, List.of());

    assertTrue(findings.stream().anyMatch(f ->
        f.rule().equals("Autoclicker")
            && f.verdict() == ProductionCheckEngine.Verdict.IMPOSSIBLE),
        () -> findings.toString());
  }

  @Test
  void transactionStateSurvivesValidationBatchBoundary() {
    AccuracyChecks.State state = new AccuracyChecks.State();

    var first = AccuracyChecks.analyze(
        "p",
        List.of(new Packets.RawPacket(1L, 1L,
            new Packets.WorldTransactionSend((short) -7))),
        List.of(),
        state);
    assertTrue(first.stream().noneMatch(f -> f.rule().equals("TransactionOrder")));

    var second = AccuracyChecks.analyze(
        "p",
        List.of(new Packets.RawPacket(2L, 2L,
            new Packets.WorldTransactionAck((short) -7))),
        List.of(),
        state);
    assertTrue(second.stream().noneMatch(f -> f.rule().equals("TransactionOrder")),
        () -> second.toString());
  }

  @Test
  void jitteredAttackIntervalsDoNotProduceAutoclickerFinding() {
    List<Packets.RawPacket> packets = new ArrayList<>();
    long sequence = 1L;
    long nanos = 0L;
    for (int i = 0; i < 28; i++) {
      packets.add(new Packets.RawPacket(
          sequence++, nanos,
          new Packets.InteractEntity(7, Packets.InteractAction.ATTACK)));
      nanos += 30_000_000L + (i % 5) * 17_000_000L;
    }

    var findings = AccuracyChecks.analyze("p", packets, List.of());

    assertFalse(findings.stream().anyMatch(f -> f.rule().equals("Autoclicker")),
        () -> findings.toString());
  }

  @Test
  void legitimateEntityActionsAreNotRejected() {
    List<String> actions = List.of(
        "LEAVE_BED",
        "OPEN_HORSE_INVENTORY",
        "START_JUMPING_WITH_HORSE",
        "STOP_JUMPING_WITH_HORSE",
        "START_FLYING_WITH_ELYTRA");
    for (int i = 0; i < actions.size(); i++) {
      String action = actions.get(i);
      var findings = AccuracyChecks.analyze(
          "p",
          List.of(new Packets.RawPacket(i + 1L, i + 1L,
              new Packets.EntityAction(action, 0))),
          List.of());
      assertTrue(findings.stream().noneMatch(f -> f.rule().equals("EntityAction")),
          () -> action + ": " + findings);
    }
  }

  @Test
  void extremeVehicleDisplacementProducesNonPunitiveVehicleEvidence() {
    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1L, 1L,
            new Packets.VehicleMove(new Maths.Vec3(0.0, 64.0, 0.0), 0f, 0f, true)),
        new Packets.RawPacket(2L, 2L,
            new Packets.VehicleMove(new Maths.Vec3(4.0, 64.0, 0.0), 0f, 0f, true))
    );

    var findings = AccuracyChecks.analyze("p", packets, List.of());

    assertTrue(findings.stream().anyMatch(f ->
        f.rule().equals("Vehicle")
            && f.verdict() == ProductionCheckEngine.Verdict.UNCERTAIN));
  }

  @Test
  void timerBalanceDoesNotDoubleCountRollingWindowDrift() {
    List<Packets.RawPacket> packets = new ArrayList<>();
    long sequence = 1L;
    long nanos = 0L;

    for (int i = 0; i < 12; i++) {
      packets.add(new Packets.RawPacket(sequence++, nanos, new Packets.ClientTickEnd()));
      nanos += 40_000_000L;
    }
    for (int i = 0; i < 19; i++) {
      packets.add(new Packets.RawPacket(sequence++, nanos, new Packets.ClientTickEnd()));
      nanos += 50_000_000L;
    }

    var findings = AccuracyChecks.analyze("p", packets, List.of());

    assertTrue(findings.stream().noneMatch(f -> f.rule().equals("TimerBurst")),
        () -> "rolling-window drift was double-counted: " + findings);
  }

  @Test
  void timerBalanceDetectsSustainedFastClientClock() {
    List<Packets.RawPacket> packets = new ArrayList<>();
    long sequence = 1L;
    long nanos = 0L;
    for (int i = 0; i < 28; i++) {
      packets.add(new Packets.RawPacket(sequence++, nanos, new Packets.ClientTickEnd()));
      nanos += 40_000_000L;
    }

    var findings = AccuracyChecks.analyze("p", packets, List.of());

    assertTrue(findings.stream().anyMatch(f ->
        (f.rule().equals("TimerBurst") || f.rule().equals("TimerLimit"))),
        () -> findings.toString());
  }
}
