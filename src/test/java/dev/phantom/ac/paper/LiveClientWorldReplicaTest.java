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
  void snapshotAtOrBeforeUsesLatestAcknowledgedCheckpoint() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(10L, emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.openBarrier((short) -11);
    assertTrue(replica.acknowledge((short) -11, 20L));

    replica.queueBlock(30L, new Pos(1, 64, 1), stone());
    replica.openBarrier((short) -12);
    assertTrue(replica.acknowledge((short) -12, 40L));

    assertNull(replica.snapshotAtOrBefore(19L));
    var first = replica.snapshotAtOrBefore(20L);
    assertNotNull(first);
    assertEquals(20L, first.causalSequence());
    assertNull(first.blockAtOrNull(1, 64, 1));

    var second = replica.snapshotAtOrBefore(40L);
    assertNotNull(second);
    assertEquals(40L, second.causalSequence());
    assertEquals(stone(), second.blockAtOrNull(1, 64, 1));
  }

  @Test
  void fullChunkIsNotVisibleBeforeItsBarrierIsAcknowledged() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(10L, emptyFullChunk(0, 0), true, ClientVersion.V_1_21_9);

    assertEquals(0, replica.visibleChunkCount());
    replica.openBarrier((short) -1);
    assertEquals(1, replica.pendingBarrierCount());
    assertEquals(0, replica.visibleChunkCount());

    assertTrue(replica.acknowledge((short) -1, 20L));
    assertEquals(1, replica.visibleChunkCount());

    var snapshot = replica.snapshotAround(0.5, 0.5, 0);
    assertEquals(20L, snapshot.causalSequence());
    assertEquals(Coverage.KNOWN, snapshot.coverageAt(0, 64, 0));
  }

  @Test
  void partialChunkWithoutPriorFullChunkRemainsInvisible() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(10L, new Column(0, 0, false, new BaseChunk[24], new TileEntity[0]), false, ClientVersion.V_1_21_11);
    replica.openBarrier((short) -2);
    assertTrue(replica.acknowledge((short) -2, 20L));

    assertEquals(0, replica.visibleChunkCount());
    assertEquals(Coverage.UNLOADED, replica.snapshotAround(0.5, 0.5, 0).coverageAt(0, 64, 0));
  }

  @Test
  void blockMutationIsHeldUntilAcknowledgement() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(10L, emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.queueBlock(11L, new Pos(1, 64, 1), stone());
    replica.openBarrier((short) -3);

    assertEquals(0, replica.visibleChunkCount());
    assertEquals(Coverage.UNLOADED, replica.snapshotAround(0.5, 0.5, 0).coverageAt(1, 64, 1));

    assertTrue(replica.acknowledge((short) -3, 20L));
    assertEquals(Coverage.KNOWN, replica.snapshotAround(1.5, 1.5, 0).coverageAt(1, 64, 1));
    assertEquals(stone(), replica.snapshotAround(1.5, 1.5, 0).blockAtOrNull(1, 64, 1));
  }

  @Test
  void failedBarrierReturnsItsMutationsToTheUnassignedQueue() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);
    replica.queueChunk(10L, emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.openBarrier((short) -4);
    replica.abortBarrier((short) -4);

    assertEquals(0, replica.pendingBarrierCount());

    replica.openBarrier((short) -5);
    assertTrue(replica.acknowledge((short) -5, 20L));
    assertEquals(1, replica.visibleChunkCount());
  }

  @Test
  void fullChunkReplacementClearsOlderOverlayButLaterOverlayWins() {
    var replica = new LiveClientWorldReplica(Contracts.TARGET_VERSION, -64, 319);

    replica.queueChunk(10L, emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.queueBlock(11L, new Pos(2, 64, 2), stone());
    replica.openBarrier((short) -6);
    assertTrue(replica.acknowledge((short) -6, 20L));
    assertEquals(stone(), replica.snapshotAround(2.5, 2.5, 0).blockAtOrNull(2, 64, 2));

    replica.queueChunk(30L, emptyFullChunk(0, 0), true, ClientVersion.V_1_21_11);
    replica.openBarrier((short) -7);
    assertTrue(replica.acknowledge((short) -7, 40L));
    assertNull(replica.snapshotAround(2.5, 2.5, 0).blockAtOrNull(2, 64, 2));

    replica.queueBlock(50L, new Pos(2, 64, 2), stone());
    replica.openBarrier((short) -8);
    assertTrue(replica.acknowledge((short) -8, 60L));
    assertEquals(stone(), replica.snapshotAround(2.5, 2.5, 0).blockAtOrNull(2, 64, 2));
  }
}
