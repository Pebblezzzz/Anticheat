package dev.phantom.ac;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

class WorldStateHistoryTest {
  @Test void stateDeltaBeforeChunkIsNotRetainedAsClientKnowledge() {
    var history=new World.VisibilityHistory();
    var pos=new dev.phantom.ac.world.Pos(1,64,1);
    var stone=dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of());
    history.blockStateChanged(2,pos,stone);
    assertFalse(history.statesAt(2).hasChunk(0,0));
    history.stateChunkVisible(3,new World.Chunk(0,0));
    assertEquals(dev.phantom.ac.world.Coverage.KNOWN,history.statesAt(3).coverageAt(1,64,1));
    assertEquals(dev.phantom.ac.world.BlockState.air(),history.statesAt(3).requireBlockAt(1,64,1));
  }
  @Test void unloadRemovesPriorStateOnlyAfterTheRecordedTick() {
    var history=new World.VisibilityHistory();
    var pos=new dev.phantom.ac.world.Pos(1,64,1);
    var stone=dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of());
    history.chunkStates(3,new dev.phantom.ac.world.Chunk(0,0),Map.of(pos,stone));
    history.stateChunkUnloaded(5,new World.Chunk(0,0));
    assertEquals(dev.phantom.ac.world.Coverage.KNOWN,history.statesAt(4).coverageAt(1,64,1));
    assertEquals(dev.phantom.ac.world.Coverage.UNLOADED,history.statesAt(5).coverageAt(1,64,1));
  }
  @Test void sameTickChunkAndStateDeltaAreReplayStable() {
    var history=new World.VisibilityHistory();
    var pos=new dev.phantom.ac.world.Pos(1,64,1);
    var stone=dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of());
    history.chunkStates(3,new dev.phantom.ac.world.Chunk(0,0),Map.of(pos,stone));
    var a=history.statesAt(3); var b=history.statesAt(3);
    assertEquals(a,b);
    assertEquals(stone,a.requireBlockAt(1,64,1));
  }
}
