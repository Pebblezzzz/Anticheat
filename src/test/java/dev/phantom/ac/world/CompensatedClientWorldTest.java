package dev.phantom.ac.world;

import dev.phantom.ac.CompensatedClientWorld;
import dev.phantom.ac.Contracts;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Timeline;
import dev.phantom.ac.World;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class CompensatedClientWorldTest {
  private static BlockState stone() {
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
  }

  @Test void liveJournalKeepsWorldPendingUntilTransactionAck() {
    var world = new CompensatedClientWorld(Contracts.TARGET_VERSION, -64, 319);
    var chunk = new Chunk(0, 0);
    var position = new Pos(1, 64, 1);
    short tx = -1;

    world.queueForBarrier(tx, new CompensatedClientWorld.Mutation.ChunkSnapshot(chunk,
        Map.of(position, stone())));
    world.openBarrier(tx);

    assertEquals(Coverage.UNLOADED, world.snapshot().coverageAt(1, 64, 1));
    assertTrue(world.acknowledge(tx));
    assertEquals(Coverage.KNOWN, world.snapshot().coverageAt(1, 64, 1));
    assertEquals(stone(), world.snapshot().blockAtOrNull(1, 64, 1));
  }

  @Test void replayMakesWorldVisibleAtAckTickNotServerSendTick() {
    var chunk = new Chunk(0, 0);
    var position = new Pos(1, 64, 1);
    short tx = -7;

    var chunkPacket = new Packets.ChunkStates(chunk, Map.of(position, stone()));
    var send = new Packets.WorldTransactionSend(tx);
    var ack = new Packets.WorldTransactionAck(tx);

    var events = List.of(
        new Timeline.Event(10, new Packets.NormalizedPacket(1, 100, chunkPacket, EnumSet.of(Packets.PacketFlag.NORMAL))),
        new Timeline.Event(10, new Packets.NormalizedPacket(2, 101, send, EnumSet.of(Packets.PacketFlag.NORMAL))),
        new Timeline.Event(11, new Packets.NormalizedPacket(3, 150, ack, EnumSet.of(Packets.PacketFlag.NORMAL)))
    );

    var timeline = new Timeline.Snapshot(events);
    var history = World.fromTimeline(timeline);

    assertEquals(Coverage.UNLOADED, history.statesAt(10).coverageAt(1, 64, 1));
    assertEquals(Coverage.KNOWN, history.statesAt(11).coverageAt(1, 64, 1));
  }
}
