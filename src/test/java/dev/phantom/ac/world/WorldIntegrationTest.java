package dev.phantom.ac.world;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.Contracts;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Timeline;
import dev.phantom.ac.World;

/** Phase 4 integration regressions across replay, visibility history and snapshots. */
class WorldIntegrationTest {
  private static BlockState stone() {
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
  }

  @Test void realStateWorldPacketsRoundTripThroughTheReplayCodec() {
    var chunk = new Chunk(0, 0);
    var position = new Pos(1, 64, 1);
    var packet = new Packets.ChunkStates(chunk, Map.of(position, stone()));
    var timeline = new Timeline.Snapshot(List.of(new Timeline.Event(0,
        new Packets.NormalizedPacket(1, 0, packet, EnumSet.of(Packets.PacketFlag.NORMAL)))));
    var decoded = new Timeline.Codec().decode(new Timeline.Codec().encode(timeline));
    assertEquals(timeline, decoded);
    var history = World.fromTimeline(decoded);
    var snapshot = history.statesAt(0);
    assertEquals(Coverage.KNOWN, snapshot.coverageAt(1, 64, 1));
    assertEquals(stone(), snapshot.blockAtOrNull(1, 64, 1));
    assertEquals(List.of(dev.phantom.ac.geometry.BlockBox.of(1, 64, 1, 2, 65, 2)),
        snapshot.collisionShapeAt(1, 64, 1).boxes());
  }

  @Test void stateBlockChangesBeforeChunkVisibilityAreNotInvented() {
    var history = new World.VisibilityHistory();
    var position = new Pos(1, 64, 1);
    history.blockStateChanged(1, position, stone());
    assertEquals(Coverage.UNLOADED, history.statesAt(1).coverageAt(1, 64, 1));
    history.stateChunkVisible(2, new World.Chunk(0, 0));
    assertEquals(Coverage.KNOWN, history.statesAt(2).coverageAt(1, 64, 1));
    assertNull(history.statesAt(2).blockAtOrNull(1, 64, 1));
    assertTrue(history.statesAt(2).requireBlockAt(1, 64, 1).isAir());
  }

  @Test void stateChunkUnloadTurnsPreviouslyKnownGeometryIntoUnloadedCoverage() {
    var history = new World.VisibilityHistory();
    var chunk = new World.Chunk(0, 0);
    var position = new Pos(1, 64, 1);
    history.chunkStates(0, chunk.toWorldChunk(), Map.of(position, stone()));
    assertEquals(Coverage.KNOWN, history.statesAt(0).coverageAt(1, 64, 1));
    history.stateChunkUnloaded(1, chunk);
    assertEquals(Coverage.UNLOADED, history.statesAt(1).coverageAt(1, 64, 1));
  }

  @Test void worldViewKeepsEntityCompletenessSeparateFromWorldCompleteness() {
    var snapshot = WorldSnapshot.builder(Contracts.TARGET_VERSION)
        .loadChunk(0, 0).setBlock(0, 64, 0, stone()).build();
    var view = WorldView.of(snapshot);
    assertTrue(view.fullyKnown(dev.phantom.ac.geometry.BlockBox.of(0, 64, 0, 1, 65, 1)));
    assertFalse(view.entityCollisions().boxesIn(dev.phantom.ac.geometry.BlockBox.of(0, 64, 0, 1, 65, 1)).isDefinite());
  }

  @Test void legacyUnknownCoverageCannotBeConvertedIntoAnAirBlock() {
    var legacy = new World.Snapshot(Map.of(), Set.of(new World.Chunk(0, 0)));
    assertEquals(World.Block.AIR, legacy.blockAt(1, 64, 1));
    var modern = World.toWorldModel(legacy);
    assertEquals(Coverage.KNOWN, modern.coverageAt(1, 64, 1));
    assertTrue(modern.requireBlockAt(1, 64, 1).isAir());
    assertEquals(Coverage.UNLOADED, modern.coverageAt(20, 64, 20));
  }
}
