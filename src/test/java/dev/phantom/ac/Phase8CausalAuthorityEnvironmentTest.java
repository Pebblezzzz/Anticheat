package dev.phantom.ac;

import dev.phantom.ac.Packets.*;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Phase8CausalAuthorityEnvironmentTest {
  private static WorldSnapshot floorWorld() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
      builder.setBlock(x, 63, z, stone);
    }
    return builder.build();
  }

  @Test
  void authorityRebaseUsesCausalWorldEnvironmentWhenServerSampleDisagrees() {
    Phase8PredictionRunner runner = new Phase8PredictionRunner(4096);
    PlayerContext authority = new PlayerContext(
        "survival", Simulation.Attributes.DEFAULT, Map.of(),
        Pose.STANDING, MovementEnvironment.vanillaWater(true, false, false, false),
        new Maths.Vec3(.5, 64.0, .5), Maths.Vec3.ZERO,
        false, false, false, List.of());

    var observed = new Move(
        new Maths.Vec3(.5, 64.0, .5), 10f, 5f, true, 0L);
    var report = runner.processWithWorldProvider(
        "causal-env",
        List.of(
            new RawPacket(1, 10, authority,
                Packets.CaptureProvenance.fromAdapter("paper-live", authority, 0L, 0L)),
            new RawPacket(2, 20, observed,
                Packets.CaptureProvenance.fromAdapter("paper-client-tick-boundary", observed, 1L, 0L))),
        ignored -> floorWorld(),
        State.Player.initial(new Maths.Vec3(.5, 64.0, .5)),
        0L);

    assertTrue(report.candidateFrontierRetained(), report.toString());
    assertTrue(report.frames().getLast().trace().stream().anyMatch(
        line -> line.startsWith("ROOT_ENVIRONMENT source=causal-packet-world")),
        report.frames().getLast().trace().toString());
    assertTrue(report.results().stream().allMatch(
        result -> result.verdict() != Phase8MovementValidation.Verdict.IMPOSSIBLE),
        report.results().toString());
  }
}
