package dev.phantom.ac.paper;

import dev.phantom.ac.Contracts;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.Pos;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LiveClientWorldReplicaTest {
  private static BlockState stone() {
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
  }

  private static Column emptyFullChunk(int x, int z) {
    return new Column(x, z, true, new BaseChunk[24], new TileEntity[0]);
  }

  @Test
  void fullChunkIsNotVisibleBeforeItsBarrierIsAcknowledged() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);

    assertEquals(0, replica.visibleChunkCount());
    replica.openBarrier((short) -1);
    assertEquals(1, replica.pendingBarrierCount());
    assertEquals(0, replica.visibleChunkCount());

    assertTrue(replica.acknowledge((short) -1));
    assertEquals(1, replica.visibleChunkCount());
    assertEquals(Coverage.KNOWN, replica.snapshotAround(0.5, 0.5, 0).coverageAt(0, 64, 0));
  }

  @Test
  void partialChunkWithoutPriorFullChunkRemainsInvisible() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(new Column(0, 0, false, new BaseChunk[24], new TileEntity[0]), false, ClientVersion.V_1_21_11);
    replica.openBarrier((short) -2);
    assertTrue(replica.acknowledge((short) -2));

    assertEquals(0, replica.visibleChunkCount());
    assertEquals(Coverage.UNLOADED, replica.snapshotAround(0.5, 0.5, 0).coverageAt(0, 64, 0));
  }

  @Test
  void blockMutationIsHeldUntilAcknowledgement() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.queueBlock(new Pos(1, 64, 1), stone());
    replica.openBarrier((short) -3);

    assertEquals(0, replica.visibleChunkCount());
    assertEquals(Coverage.UNLOADED, replica.snapshotAround(0.5, 0.5, 0).coverageAt(1, 64, 1));

    assertTrue(replica.acknowledge((short) -3));
    assertEquals(Coverage.KNOWN, replica.snapshotAround(1.5, 1.5, 0).coverageAt(1, 64, 1));
    assertEquals(stone(), replica.snapshotAround(1.5, 1.5, 0).blockAtOrNull(1, 64, 1));
  }

  @Test
  void failedBarrierReturnsItsMutationsToTheUnassignedQueue() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.openBarrier((short) -4);
    replica.abortBarrier((short) -4);

    assertEquals(0, replica.pendingBarrierCount());

    replica.openBarrier((short) -5);
    assertTrue(replica.acknowledge((short) -5));
    assertEquals(1, replica.visibleChunkCount());
  }

  @Test
  void fullChunkReplacementClearsOlderOverlayButLaterOverlayWins() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);

    replica.queueChunk(emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.queueBlock(new Pos(2, 64, 2), stone());
    replica.openBarrier((short) -6);
    assertTrue(replica.acknowledge((short) -6));
    assertEquals(stone(), replica.snapshotAround(2.5, 2.5, 0).blockAtOrNull(2, 64, 2));

    replica.queueChunk(emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.openBarrier((short) -7);
    assertTrue(replica.acknowledge((short) -7));
    assertNull(replica.snapshotAround(2.5, 2.5, 0).blockAtOrNull(2, 64, 2));

    replica.queueBlock(new Pos(2, 64, 2), stone());
    replica.openBarrier((short) -8);
    assertTrue(replica.acknowledge((short) -8));
    assertEquals(stone(), replica.snapshotAround(2.5, 2.5, 0).blockAtOrNull(2, 64, 2));
  }
}
