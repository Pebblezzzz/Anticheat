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
  void productionAlertUsesVlThresholdInsteadOfThreeImpossibleObservations() {
    ProductionCheckEngine.Config config =
        new ProductionCheckEngine.Config(true, 100.0, 40, 20, 4.0, 5.0);
    ProductionCheckEngine.Finding finding =
        new ProductionCheckEngine.Finding("p", 1, "Reach",
            ProductionCheckEngine.Verdict.IMPOSSIBLE, "too far", 1.0, "r1");

    var state = ProductionCheckEngine.Accumulator.empty();
    for (int i = 0; i < 99; i++) {
      state = state.accept(finding, config).state();
      finding = new ProductionCheckEngine.Finding("p", i + 2L, "Reach",
          ProductionCheckEngine.Verdict.IMPOSSIBLE, "too far", 1.0, "r1");
    }
    assertTrue(state.rules().get("Reach").supportingEvents() >= 99);
    var threshold = state.accept(finding, config);
    assertTrue(threshold.alert().isPresent());
    assertEquals(100.0, threshold.state().rules().get("Reach").violationLevel(), 1.0e-9);
  }

  private static Phase6Reachability.Candidate candidate(State.Player player, long id) {
    Phase6Reachability.Context context = new Phase6Reachability.Context(
        20, player, Simulation.Environment.DRY, Simulation.Attributes.DEFAULT,
        Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(player.onGround(), false, false), false);
    return new Phase6Reachability.Candidate(
        id, context,
        new Phase6Reachability.Provenance(
            id, -1L, 20L, "INPUT", "WORLD", "None", List.of("ground-spoof test"), 1, List.of()));
  }

  @Test
  void groundSpoofIgnoresRotationOnlyPackets() {
    State.Player grounded = player(0, 0);
    Phase6Reachability.Candidate airborne = candidate(
        new State.Player(
            grounded.position(), grounded.velocity(), grounded.yaw(), grounded.pitch(), false,
            grounded.gamemode(), grounded.effects(), grounded.awaitingTeleport(), grounded.uncertain(),
            grounded.input(), grounded.attributes(), grounded.pose(), grounded.environment(),
            grounded.clientTickRange(), grounded.provenance(), grounded.uncertaintyReasons()),
        1L);

    Packets.Move move = new Packets.Move(null, 90f, 0f, true, 1L);
    PredictionFrame prediction = new PredictionFrame(
        1L, 1L, 1L, 1L, move, grounded, grounded,
        Set.of(airborne), Set.of(airborne), WorldSnapshot.emptyOverworld12111(), List.of(), List.of());

    var result = ProductionCheckEngine.analyze("p",
        List.of(new Packets.RawPacket(1L, 1L, move)),
        report(prediction), CONFIG);

    assertTrue(result.findings().stream().noneMatch(f -> f.rule().equals("GroundSpoof")));
  }

  @Test
  void groundSpoofRequiresDeterministicPredictionGroundState() {
    State.Player before = player(0, 0);
    State.Player after = new State.Player(
        new Vec3(0, -1, 0), before.velocity(), before.yaw(), before.pitch(), true,
        before.gamemode(), before.effects(), before.awaitingTeleport(), before.uncertain(),
        before.input(), before.attributes(), before.pose(), before.environment(),
        before.clientTickRange(), before.provenance(), before.uncertaintyReasons());
    State.Player predictedAir = new State.Player(
        new Vec3(0, -1, 0), Vec3.ZERO, 0, 0, false,
        "survival", Map.of(), OptionalInt.empty(), false, Optional.empty(),
        Simulation.Attributes.DEFAULT, Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY, State.TickRange.exact(1), State.Provenance.UNKNOWN, Set.of());

    Packets.Move move = new Packets.Move(after.position(), after.yaw(), after.pitch(), true, 1L);
    Phase6Reachability.Candidate airCandidate = candidate(predictedAir, 2L);
    Phase6Reachability.Candidate groundCandidate = candidate(
        new State.Player(
            predictedAir.position(), predictedAir.velocity(), predictedAir.yaw(), predictedAir.pitch(), true,
            predictedAir.gamemode(), predictedAir.effects(), OptionalInt.empty(), predictedAir.uncertain(),
            predictedAir.input(), predictedAir.attributes(), predictedAir.pose(), predictedAir.environment(),
            predictedAir.clientTickRange(), predictedAir.provenance(), predictedAir.uncertaintyReasons()),
        3L);

    PredictionFrame deterministic = new PredictionFrame(
        1L, 1L, 1L, 1L, move, before, after,
        Set.of(airCandidate), Set.of(airCandidate), WorldSnapshot.emptyOverworld12111(), List.of(), List.of());
    var deterministicResult = ProductionCheckEngine.analyze(
        "p", List.of(new Packets.RawPacket(1L, 1L, move)), report(deterministic), CONFIG);
    assertTrue(deterministicResult.findings().stream().anyMatch(f -> f.rule().equals("GroundSpoof")));

    PredictionFrame mixed = new PredictionFrame(
        1L, 1L, 1L, 1L, move, before, after,
        Set.of(airCandidate, groundCandidate), Set.of(airCandidate, groundCandidate),
        WorldSnapshot.emptyOverworld12111(), List.of(), List.of());
    var mixedResult = ProductionCheckEngine.analyze(
        "p", List.of(new Packets.RawPacket(1L, 1L, move)), report(mixed), CONFIG);
    assertTrue(mixedResult.findings().stream().noneMatch(f -> f.rule().equals("GroundSpoof")));
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
  @Test
  void heldItemOutsideHotbarIsDetected() {
    var result = ProductionCheckEngine.analyze(
        "p",
        List.of(new Packets.RawPacket(1, 1, new Packets.HeldItemChange(9))),
        report(),
        CONFIG);
    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("HeldItemSlot")));
  }

  @Test
  void sustainedClientTickBurstIsDetectedAsTimerEvidence() {
    java.util.ArrayList<Packets.RawPacket> packets = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) {
      packets.add(new Packets.RawPacket(i + 1, i * 10_000_000L, new Packets.ClientTickEnd()));
    }

    var result = ProductionCheckEngine.analyze("p", packets, report(), CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("TimerBurst")));
  }

  @Test
  void veryFastHardBlockBreakIsDetected() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var world = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0)
        .setBlock(0, 0, 0, stone)
        .build();
    var p = player(0, 0);
    var movement = new PredictionFrame(
        2, 20_000_000L, 2, 0L,
        new Packets.Move(p.position(), p.yaw(), p.pitch(), true, 0L),
        p, p, Set.of(), Set.of(), world, List.of(), List.of());

    var packets = List.of(
        new Packets.RawPacket(1, 0L,
            new Packets.DigAction("STARTED_DIGGING", new dev.phantom.ac.world.Pos(0, 0, 0), 1)),
        new Packets.RawPacket(2, 20_000_000L,
            new Packets.DigAction("FINISHED_DIGGING", new dev.phantom.ac.world.Pos(0, 0, 0), 2)));

    var result = ProductionCheckEngine.analyze("p", packets, report(movement), CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("FastBreak")));
  }


  @Test
  void illegalBlockPlaceCursorIsDetected() {
    var place = new Packets.BlockPlace(
        new dev.phantom.ac.world.Pos(0, 0, 0),
        1,
        new Vec3(1.5, 0.5, 0.5),
        true);

    var result = ProductionCheckEngine.analyze(
        "p",
        List.of(new Packets.RawPacket(1, 1, place)),
        report(),
        CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("BlockPlaceCursor")));
  }

  @Test
  void impossibleInventorySlotIsDetected() {
    var result = ProductionCheckEngine.analyze(
        "p",
        List.of(new Packets.RawPacket(
            1, 1, new Packets.InventoryClick(0, 128, 0, "PICKUP"))),
        report(),
        CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("InventorySlot")));
  }

  @Test
  void illegalEntityActionOpcodeIsDetected() {
    var result = ProductionCheckEngine.analyze(
        "p",
        List.of(new Packets.RawPacket(1, 1, new Packets.EntityAction("NOT_A_CLIENT_ACTION", 0))),
        report(),
        CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("EntityAction")));
    assertEquals(ProductionCheckEngine.Verdict.IMPOSSIBLE,
        result.findings().stream().filter(f -> f.rule().equals("EntityAction")).findFirst().orElseThrow().verdict());
  }

  @Test
  void nonHorseEntityActionCannotCarryJumpBoost() {
    var result = ProductionCheckEngine.analyze(
        "p",
        List.of(new Packets.RawPacket(1, 1, new Packets.EntityAction("START_SPRINTING", 1))),
        report(),
        CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("EntityAction")));
  }

  @Test
  void oversizedHorseJumpBoostIsDetected() {
    var result = ProductionCheckEngine.analyze(
        "p",
        List.of(new Packets.RawPacket(1, 1, new Packets.EntityAction("START_JUMPING_WITH_HORSE", 101))),
        report(),
        CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("EntityAction")));
  }

  @Test
  void invalidInventoryButtonIsDetected() {
    var result = ProductionCheckEngine.analyze(
        "p",
        List.of(new Packets.RawPacket(
            1, 1, new Packets.InventoryClick(0, 5, 4, "PICKUP"))),
        report(),
        CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("InventoryButton")));
  }

  @Test
  void heuristicFindingsRemainNonPunitive() {
    BlockBox target = new BlockBox(-0.5, 1.0, 9.5, 0.5, 2.0, 10.5);
    List<Packets.RawPacket> packets = List.of(
        new Packets.RawPacket(1, 1, new Packets.EntitySpawn(10, target)),
        new Packets.RawPacket(2, 2, new Packets.InteractEntity(10, Packets.InteractAction.ATTACK)));

    var result = ProductionCheckEngine.analyze("p", packets, report(frame(2, player(0, 0))), CONFIG);

    assertTrue(result.findings().stream().anyMatch(f -> f.rule().equals("Reach")));
    assertTrue(result.findings().stream().filter(f -> f.rule().equals("Reach"))
        .allMatch(f -> f.verdict() == ProductionCheckEngine.Verdict.UNCERTAIN));

    var accumulator = ProductionCheckEngine.Accumulator.empty();
    for (var finding : result.findings()) {
      var accepted = accumulator.accept(finding, CONFIG);
      assertTrue(accepted.alert().isEmpty());
      accumulator = accepted.state();
    }
  }


}
