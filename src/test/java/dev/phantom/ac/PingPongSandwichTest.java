package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.Pos;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PingPongSandwichTest {
  private static BlockState stone() {
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
  }

  private static Packets.PlayerContext context(double x) {
    return new Packets.PlayerContext(
        "survival",
        new Simulation.Attributes(0.1),
        Map.of(),
        Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(x, 64, 0),
        Maths.Vec3.ZERO,
        false,
        false,
        false,
        List.of());
  }

  @Test
  void oneBoundaryClosesPreviousTickAndOpensTheNext() {
    var world = new CompensatedClientWorld(Contracts.TARGET_VERSION, -64, 319);
    var playerState = new PhantomPlayerState(UUID.randomUUID());
    var position = new Pos(1, 64, 1);
    var chunk = new Chunk(0, 0);

    short firstBoundary = -1;
    short secondBoundary = -2;

    // Boundary 1 is the opening marker. Nothing from the next tick is
    // acknowledged yet.
    world.openBarrier(firstBoundary);
    playerState.observeAuthoritativeContext(context(1.0));

    // State changes produced during tick 1 are held until boundary 2.
    world.queue(new CompensatedClientWorld.Mutation.ChunkSnapshot(
        chunk, Map.of(position, stone())));
    world.openBarrier(secondBoundary);
    Packets.PlayerContext pendingContext = playerState.pendingAuthoritativeContext();
    assertNotNull(pendingContext);
    playerState.markBarrierSent(
        secondBoundary,
        pendingContext.withTransactionBarrier(secondBoundary));
    playerState.markContextPublished();

    assertEquals(Coverage.UNLOADED, world.snapshot().coverageAt(1, 64, 1));
    assertTrue(playerState.latestClientVisibleContext().isEmpty());

    world.acknowledge(secondBoundary);
    playerState.acknowledgeBarrier(secondBoundary, 100L);

    assertEquals(Coverage.KNOWN, world.snapshot().coverageAt(1, 64, 1));
    assertEquals(1.0, playerState.latestClientVisibleContext().orElseThrow()
        .serverPosition().x());

    // Tick 2 changes are not committed by the opening boundary 2; they wait
    // for boundary 3.
    world.queue(new CompensatedClientWorld.Mutation.Block(position, stone()));
    assertEquals(Coverage.KNOWN, world.snapshot().coverageAt(1, 64, 1));
    assertTrue(!world.hasPendingTransaction(secondBoundary));
  }
}
