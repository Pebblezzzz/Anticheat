package dev.phantom.ac;

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
import java.util.Set;

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
    accumulator = accumulator.accept(evidence(Verdict.IMPOSSIBLE, 3), config).state();
    assertEquals(2, accumulator.players().get("player/MOVEMENT_REACHABILITY").supportingImpossible());
    assertTrue(accumulator.accept(evidence(Verdict.IMPOSSIBLE, 4), config).alert().isPresent());
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

  @Test void playerContextReplayRoundTripsThroughTimelineCodec() {
    var context = new Packets.PlayerContext("survival", new Simulation.Attributes(0.1),
        Map.of("minecraft:speed", 1), Phase5Mechanics.Pose.SWIMMING,
        Phase5Mechanics.MovementEnvironment.vanillaWater(false, true, false, true), false,
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
    Phase6Reachability.SearchResult search = new Phase6Reachability.SearchResult(
        verdict == Verdict.UNCERTAIN ? Phase6Reachability.Verdict.UNCERTAIN :
            verdict == Verdict.IMPOSSIBLE ? Phase6Reachability.Verdict.IMPOSSIBLE : Phase6Reachability.Verdict.POSSIBLE,
        Set.of(), 1, 1, 0, 0, verdict == Verdict.UNCERTAIN ? 1 : 0, 0, List.of("test"));
    return Phase8MovementValidation.validate("player", tick, p, p, WorldSnapshot.emptyOverworld12111(), "test-world",
        timing, List.of("test-input"), search, "test-replay", true).evidence();
  }
}
