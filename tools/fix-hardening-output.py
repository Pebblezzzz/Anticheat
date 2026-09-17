from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    file = ROOT / path
    text = file.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing output-fix anchor in {path}: {old[:100]!r}")
    file.write_text(text.replace(old, new), encoding="utf-8", newline="\n")

# The generated live-state recorder should create one snapshot object and let
# RawPacket create its own provenance. The earlier patch accidentally constructed
# the snapshot twice and used the five-argument RawPacket overload unnecessarily.
replace(
    "src/main/java/dev/phantom/ac/paper/PhantomPaperPlugin.java",
    """      appendPacket(capture, new RawPacket(capture.sequence.incrementAndGet(), System.nanoTime(), new Packets.PlayerSnapshot(\n          player.getGameMode().name().toLowerCase(Locale.ROOT), new dev.phantom.ac.Simulation.Attributes(movementSpeed), movementEffects,\n          effects, pose, environment, player.isSleeping(), entities),\n          Packets.CaptureProvenance.fromAdapter(\"paper-live\", new Packets.PlayerSnapshot(\n              player.getGameMode().name().toLowerCase(Locale.ROOT), new dev.phantom.ac.Simulation.Attributes(movementSpeed), movementEffects,\n              effects, pose, environment, player.isSleeping(), entities), null), null)));""",
    """      Packets.PlayerSnapshot snapshot = new Packets.PlayerSnapshot(\n          player.getGameMode().name().toLowerCase(Locale.ROOT), new dev.phantom.ac.Simulation.Attributes(movementSpeed), movementEffects,\n          effects, pose, environment, player.isSleeping(), entities);\n      appendPacket(capture, new RawPacket(capture.sequence.incrementAndGet(), System.nanoTime(), snapshot,\n          Packets.CaptureProvenance.fromAdapter(\"paper-live\", snapshot, null)));""",
)

# Keep the regression suite small and tied to public, stable APIs.
Path(ROOT / "src/test/java/dev/phantom/ac/HardeningRegressionTest.java").write_text(r'''package dev.phantom.ac;

import dev.phantom.ac.Phase8MovementValidation.Config;
import dev.phantom.ac.Phase8MovementValidation.Result;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class HardeningRegressionTest {
  @Test void oneImpossibleObservationIsImmediatelyAccumulatable() {
    Player prior = Player.initial(Maths.Vec3.ZERO);
    Player observed = new Player(new Maths.Vec3(2, 0, 0), Maths.Vec3.ZERO, 0, 0, true,
        "survival", Map.of(), OptionalInt.empty(), false);
    var world = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build();
    var timing = new Validation.SyncWindow(0, 0, false, List.of());
    var impossibleSearch = new Phase6Reachability.SearchResult(Phase6Reachability.Verdict.IMPOSSIBLE,
        java.util.Set.of(), 1, 1, 0, 0, 0, 0, List.of("test exhaustive elimination"));
    Result result = Phase8MovementValidation.validate("player", 0, prior, observed, world, "test",
        timing, List.of("test-input"), impossibleSearch, "test-replay");
    assertEquals(Verdict.IMPOSSIBLE, result.verdict());
    var accumulated = Phase8MovementValidation.Accumulator.empty().accept(result.evidence(), Config.defaults());
    assertTrue(accumulated.alert().isPresent());
  }

  @Test void velocityPacketReplacesVelocity() {
    Player base = new Player(Maths.Vec3.ZERO, new Maths.Vec3(0.9, 0.1, 0.2), 0, 0, true,
        "survival", Map.of(), OptionalInt.empty(), false);
    Player moved = Phase5Mechanics.applyVelocityImpulse(base, new Phase5Mechanics.Vec3Like(0.1, 0.4, -0.2));
    assertEquals(new Maths.Vec3(0.1, 0.4, -0.2), moved.velocity());
  }

  @Test void incompleteFenceStateIsUnsupported() {
    assertTrue(BlockCatalogue12111.decode("minecraft:oak_fence", Map.of("north", "true")).isUnsupported());
  }

  @Test void defaultRichPhysicsEntityProviderIsIncomplete() {
    Vanilla12111RichPhysics.Context context = new Vanilla12111RichPhysics.Context(0, Player.initial(Maths.Vec3.ZERO),
        new Simulation.AdvancedInput(0, 0, false),
        emptyWorld(), Simulation.Environment.DRY, Simulation.Attributes.DEFAULT,
        Phase5Mechanics.MovementEffects.NONE, Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false), false);
    assertFalse(context.entityCollisions().boxesIn(new dev.phantom.ac.geometry.BlockBox(-1, -1, -1, 1, 2, 1)).complete());
  }

  @Test void timelineCodecPreservesRichSnapshot() {
    var snapshot = new Packets.PlayerSnapshot("survival", new Simulation.Attributes(0.1),
        Phase5Mechanics.MovementEffects.NONE, Map.of("minecraft:speed", 0), Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false), false,
        List.of(new EntityCollisions.EntityBox(5, new dev.phantom.ac.geometry.BlockBox(1, 2, 3, 2, 3, 4))));
    var timeline = Timeline.assign(new Packets.Normalizer().normalize(List.of(new Packets.RawPacket(0, 1, snapshot))), 0, 50_000_000L);
    var decoded = new Timeline.Codec().decode(new Timeline.Codec().encode(timeline));
    assertEquals(snapshot, decoded.events().getFirst().packet().packet());
  }

  private static WorldSnapshot emptyWorld() {
    return WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0).build();
  }
}
''', encoding="utf-8", newline="\n")

# Trigger marker for the temporary repair runner.
# hardening-runner-retrigger
