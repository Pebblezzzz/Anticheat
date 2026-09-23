package dev.phantom.ac;

import dev.phantom.ac.Phase8MovementValidation.CandidateSummary;
import dev.phantom.ac.Phase8MovementValidation.Evidence;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class HardeningRegressionTest {
  @Test void oneImpossibleObservationAlertsWithDefaultConfig() {
    Evidence impossible = evidence(Verdict.IMPOSSIBLE, 10);
    var result = Phase8MovementValidation.Accumulator.empty().accept(impossible, Phase8MovementValidation.Config.defaults());
    assertTrue(result.alert().isPresent(), "one impossible observation must not be silently ignored");
  }

  @Test void alternatingImpossibleAndPossibleRetainsImpossibleEvidence() {
    var accumulator = Phase8MovementValidation.Accumulator.empty();
    var config = new Phase8MovementValidation.Config(2, 0, true, true);
    accumulator = accumulator.accept(evidence(Verdict.IMPOSSIBLE, 1), config).state();
    accumulator = accumulator.accept(evidence(Verdict.POSSIBLE, 2), config).state();
    var third = accumulator.accept(evidence(Verdict.IMPOSSIBLE, 3), config);
    accumulator = third.state();
    assertEquals(2, accumulator.players().get("player/MOVEMENT_REACHABILITY").supportingImpossible());
    assertTrue(third.alert().isPresent());
  }

  @Test void velocityPacketReplacesVelocity() {
    Player base = new Player(Maths.Vec3.ZERO, new Maths.Vec3(0.9, 0.1, 0.2), 0, 0, true,
        "survival", Map.of(), OptionalInt.empty(), false);
    Player moved = Phase5Mechanics.applyVelocityImpulse(base, new Phase5Mechanics.Vec3Like(0.1, 0.4, -0.2));
    assertEquals(new Maths.Vec3(0.1, 0.4, -0.2), moved.velocity());
  }

  @Test void richPhysicsDefaultEntityHistoryIsIncomplete() {
    Vanilla12111RichPhysics.Context context = new Vanilla12111RichPhysics.Context(0, Player.initial(Maths.Vec3.ZERO),
        new Simulation.AdvancedInput(0, 0, false), WorldSnapshot.emptyOverworld12111(),
        Simulation.Environment.DRY, Simulation.Attributes.DEFAULT, Phase5Mechanics.MovementEffects.NONE,
        Phase5Mechanics.Pose.STANDING, Phase5Mechanics.MovementEnvironment.dry(true, false, false), false);
    assertFalse(context.entityCollisions().boxesIn(new dev.phantom.ac.geometry.BlockBox(-1, -1, -1, 1, 2, 1)).complete());
  }

  @Test void flightToggleReplayRoundTripsThroughTimelineCodec() {
    var toggle = new Packets.FlightToggle(true, true);
    var timeline = Timeline.assign(List.of(new Packets.NormalizedPacket(1, 1, toggle,
        java.util.EnumSet.of(Packets.PacketFlag.NORMAL),
        Packets.CaptureProvenance.fromAdapter("test", toggle, 4L))), 0, 50_000_000L);
    var codec = new Timeline.Codec();
    var decoded = codec.decode(codec.encode(timeline));
    assertEquals(toggle, decoded.events().getFirst().packet().packet());
  }

  @Test void playerContextReplayRoundTripsThroughTimelineCodec() {
    var context = new Packets.PlayerContext("survival", new Simulation.Attributes(0.1),
        Map.of("minecraft:speed", 1), Phase5Mechanics.Pose.SWIMMING,
        Phase5Mechanics.MovementEnvironment.vanillaWater(false, true, false, true),
        new Maths.Vec3(10.5, 64.0, -2.5), new Maths.Vec3(0.25, -0.08, 0.12),
        false, false, false,
        List.of(new EntityCollisions.EntityBox(7, new dev.phantom.ac.geometry.BlockBox(1, 2, 3, 2, 3, 4))));

    var timeline = Timeline.assign(List.of(new Packets.NormalizedPacket(0, 1, context,
        java.util.EnumSet.of(Packets.PacketFlag.NORMAL), Packets.CaptureProvenance.fromAdapter("test", context, null))), 0, 50_000_000L);
    var codec = new Timeline.Codec();
    var decoded = codec.decode(codec.encode(timeline));
    assertEquals(context, decoded.events().getFirst().packet().packet());
  }

  private static Evidence evidence(Verdict verdict, long tick) {
    Player p = Player.initial(Maths.Vec3.ZERO);
    Validation.SyncWindow timing = new Validation.SyncWindow(tick, tick, false, List.of());
    return new Evidence(Phase8MovementValidation.VERSION, verdict, "player", tick, tick, tick,
        p, p, Contracts.TARGET_VERSION, "test-world", List.of("test-input"), timing.reasons(),
        1, verdict == Verdict.POSSIBLE ? 1 : 0, verdict == Verdict.POSSIBLE ? 0 : 1,
        verdict == Verdict.POSSIBLE ? "match" : "no match", verdict == Verdict.IMPOSSIBLE ? OptionalLong.of(tick) : OptionalLong.empty(),
        Optional.<CandidateSummary>empty(), List.of("test"), List.of(), Phase8MovementValidation.PHASE5_VERSION,
        Phase8MovementValidation.PHASE6_VERSION, Phase8MovementValidation.PHASE7_VERSION, "test-replay", "MOVEMENT_REACHABILITY");
  }
}