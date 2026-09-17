package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.WorldSnapshot;

/** Regression coverage for client-visible world replay containing unsupported states. */
class WorldVisibilityHistoryTest {
  @Test
  void statesAtPreservesUnsupportedStatesInsteadOfThrowing() {
    World.VisibilityHistory history = new World.VisibilityHistory();
    BlockState unsupported = BlockState.unsupported("minecraft:something_new");
    history.chunkStates(0, new Chunk(0, 0), Map.of(new Pos(1, 64, 1), unsupported));

    WorldSnapshot world = history.statesAt(0);

    assertTrue(world.hasChunk(0, 0));
    assertEquals(Coverage.UNSUPPORTED, world.coverageAt(1, 64, 1));
    assertNull(world.blockAtOrNull(1, 64, 1));
    assertThrows(WorldSnapshot.UnsupportedStateException.class, () -> world.requireBlockAt(1, 64, 1));
  }
}
