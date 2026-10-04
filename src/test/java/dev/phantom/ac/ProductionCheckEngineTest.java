package dev.phantom.ac;

import dev.phantom.ac.Phase8PredictionRunner.PredictionFrame;
import dev.phantom.ac.Phase8PredictionRunner.Report;
import dev.phantom.ac.Phase8PredictionRunner.Continuation;
import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.Set;

import static dev.phantom.ac.Maths.Vec3;
import static org.junit.jupiter.api.Assertions.*;

class ProductionCheckEngineTest {
  private static final ProductionCheckEngine.Config CONFIG =
      new ProductionCheckEngine.Config(true, 3, 40, 20, 4.0, 5.0);

  private static State.Player player(float yaw, float pitch) {
    return new State.Player(
        new Vec3(0, 0, 0), Vec3.ZERO, yaw, pitch, true, "survival",
        Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY, State.TickRange.exact(0), State.Provenance.UNKNOWN, Set.of());
  }

  private static PredictionFrame frame(long sequence, State.Player player) {
    Packets.Move move = new Packets.Move(player.position(), player.yaw(), player.pitch(), true, 0L);
    return new PredictionFrame(
        sequence, sequence, sequence, 0L, move, player, player,
        Set.of(), Set.of(), WorldSnapshot.emptyOverworld12111(), List.of(), List.of());
  }

  private static Report report(PredictionFrame... frames) {
    return new Report(
        List.of(), frames.length, frames.length, frames.length, 0, 0,
        frames.length == 0 ? -1L : frames[frames.length - 1].sequence(),
        0L, Continuation.ACTIVE, true, List.of(frames));
  }

  private static List<Packets.RawPacket> moves(float... pitches) {
    java.util.ArrayList<Packets.RawPacket> packets = new java.util.ArrayList<>();
    long seq = 1;
    for (float pitch : pitches) {
      packets.add(new Packets.RawPacket(seq++, seq * 1_000_000L,
          new Packets.Move(null, null, pitch, true, 0L)));
    }
    return packets;
  }

  @Test
  void repeatedImpossibleFindingsAlertOnlyAfterConfiguredEpisode() {
    ProductionCheckEngine.Finding finding =
        new ProductionCheckEngine.Finding("p", 1, "Reach",
            ProductionCheckEngine.Verdict.IMPOSSIBLE, "too far", 1.0, "r1");
    ProductionCheckEngine.Accumulator accumulator =
        ProductionCheckEngine.Accumulator.empty();

    var first = accumulator.accept(finding, CONFIG);
    var second = first.state().accept(finding, CONFIG);
    var third = second.state().accept(finding, CONFIG);

    assertTrue(first.alert().isEmpty());
    assertTrue(second.alert().isEmpty());
    assertTrue(third.alert().isPresent());
    assertEquals(3, third.state().rules().get("Reach").supportingEvents());
  }

  @Test
  void largeInvalidPositionIsDetected() {
    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1, 1, new Packets.Move(new Vec3(30_000_000, 0, 0), 0f, 0f, true, 0L)),
        new Packets.RawPacket(2, 2, new Packets.Move(new Vec3(30_000_000, 0, 0), 0f, 0f, true, 0L)),
        new Packets.RawPacket(3, 3, new Packets.Move(new Vec3(30_000_000, 0, 0), 0f, 0f, true, 0L)));

    var result = ProductionCheckEngine.analyze(
        "p", packets,
        report(frame(1, player(0, 0)), frame(2, player(0, 0)), frame(3, player(0, 0))),
        CONFIG);

    assertTrue(result.findings().stream().allMatch(f -> f.rule().equals("PacketPosition")));
    assertEquals(3, result.findings().size());
  }

  @Test
  void validAttackRayDoesNotTripReach() {
    BlockBox target = new BlockBox(-0.5, 1.0, 2.5, 0.5, 2.0, 3.5);
    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1, 1, new Packets.EntitySpawn(10, target)),
        new Packets.RawPacket(2, 2, new Packets.InteractEntity(10, Packets.InteractAction.ATTACK)));

    var result = ProductionCheckEngine.analyze("p", packets, report(frame(2, player(0, 0))), CONFIG);

    assertTrue(result.findings().stream().noneMatch(f -> f.rule().equals("Reach")));
  }

  @Test
  void distantAttackTripsReach() {
    BlockBox target = new BlockBox(-0.5, 1.0, 9.5, 0.5, 2.0, 10.5);
    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1, 1, new Packets.EntitySpawn(10, target)),
        new Packets.RawPacket(2, 2, new Packets.InteractEntity(10, Packets.InteractAction.ATTACK)));

    var result = ProductionCheckEngine.analyze("p", packets, report(frame(2, player(0, 0))), CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("Reach")));
  }

  @Test
  void farBreakAndPlaceAreDetected() {
    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1, 1, new Packets.ClientBlockBreak(new dev.phantom.ac.world.Pos(0, 0, 8), 1, 0L)),
        new Packets.RawPacket(2, 2, new Packets.BlockPlace(new dev.phantom.ac.world.Pos(0, 0, 8))));

    var result = ProductionCheckEngine.analyze("p", packets, report(frame(1, player(0, 0)), frame(2, player(0, 0))), CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("FarBreak")));
    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("FarPlace")));
  }

  @Test
  void repeatedModulo360YawPatternIsDetected() {
    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1, 1, new Packets.Move(null, 0f, 0f, true, 0L)),
        new Packets.RawPacket(2, 2, new Packets.Move(null, 360f, 0f, true, 1L)),
        new Packets.RawPacket(3, 3, new Packets.Move(null, 720f, 0f, true, 2L)),
        new Packets.RawPacket(4, 4, new Packets.Move(null, 1080f, 0f, true, 3L)));

    var result = ProductionCheckEngine.analyze("p", packets, report(), CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("AimModulo360")));
  }

  @Test
  void illegalPitchIsDetected() {
    var result = ProductionCheckEngine.analyze("p", moves(91f), report(), CONFIG);
    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("PacketRotation")));
  }
}
