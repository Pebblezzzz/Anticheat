package dev.phantom.ac;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class Phase4WorldReplicaTest {
  private static final String V=Contracts.TARGET_VERSION;
  private static Phase4WorldReplica.Provenance p(long s,long t){return new Phase4WorldReplica.Provenance("test","world",s,t,null,true,"fixture");}
  private static Phase4WorldReplica.Order o(long t,long s){return new Phase4WorldReplica.Order(t,t*100,s,s);}

  @Test void perPlayerReplicasDivergeWithoutServerLookup(){
    var a=new Phase4WorldReplica(V,"world", -64,319); var b=new Phase4WorldReplica(V,"world",-64,319);
    a.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),Map.of(new Pos(0,64,0),BlockCatalogue12111.decode("minecraft:stone",Map.of()))));
    b.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),Map.of(new Pos(0,64,0),BlockCatalogue12111.decode("minecraft:dirt",Map.of()))));
    assertNotEquals(a.getWorldState().blockAtOrNull(0,64,0),b.getWorldState().blockAtOrNull(0,64,0));
  }

  @Test void loadWithoutPayloadIsUnknownNotAir(){
    var r=new Phase4WorldReplica(V);
    r.accept(new Phase4WorldReplica.ChunkLoad(o(1,1),p(1,1),new Chunk(0,0)));
    assertEquals(Coverage.UNKNOWN,r.getWorldState().coverageAt(0,64,0));
    assertNull(r.getWorldState().blockAtOrNull(0,64,0));
    assertNotEquals(Coverage.UNKNOWN,Coverage.KNOWN);
    assertNotEquals(Coverage.UNKNOWN,Coverage.UNLOADED);
  }

  @Test void unloadIsNotAir(){
    var r=new Phase4WorldReplica(V);
    r.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),Map.of()));
    r.accept(new Phase4WorldReplica.ChunkUnload(o(2,2),p(2,2),new Chunk(0,0)));
    assertEquals(Coverage.UNLOADED,r.getWorldState().coverageAt(0,64,0));
  }

  @Test void repeatedUpdatesUseCanonicalOrder(){
    var r=new Phase4WorldReplica(V);
    var stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    var dirt=BlockCatalogue12111.decode("minecraft:dirt",Map.of());
    r.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),Map.of()));
    r.accept(new Phase4WorldReplica.BlockChange(o(3,3),p(3,3),new Pos(0,64,0),dirt));
    r.accept(new Phase4WorldReplica.BlockChange(o(2,2),p(2,2),new Pos(0,64,0),stone));
    assertEquals(stone,r.getWorldState().blockAtOrNull(0,64,0));
  }

  @Test void historicalGenerationsReturnEarlierState(){
    var r=new Phase4WorldReplica(V);
    var stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    var dirt=BlockCatalogue12111.decode("minecraft:dirt",Map.of());
    r.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),Map.of(new Pos(0,64,0),stone)));
    long first=r.getWorldGeneration().sequence();
    r.accept(new Phase4WorldReplica.BlockChange(o(2,2),p(2,2),new Pos(0,64,0),dirt));
    assertEquals(stone,r.snapshotAtSequence(first).blockAtOrNull(0,64,0));
    assertEquals(dirt,r.getWorldState().blockAtOrNull(0,64,0));
  }

  @Test void dimensionChangeClearsOldWorld(){
    var r=new Phase4WorldReplica(V,"overworld",-64,319);
    r.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),Map.of(new Pos(0,64,0),BlockCatalogue12111.decode("minecraft:stone",Map.of()))));
    r.accept(new Phase4WorldReplica.DimensionChange(o(2,2),p(2,2),"minecraft:the_nether",-64,319));
    assertEquals("minecraft:the_nether",r.getWorldGeneration().worldId());
    assertEquals(Coverage.UNLOADED,r.getWorldState().coverageAt(0,64,0));
  }

  @Test void fakeClientBlockIsPreserved(){
    var r=new Phase4WorldReplica(V);
    r.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),Map.of(new Pos(0,64,0),BlockCatalogue12111.decode("minecraft:stone",Map.of()))));
    var fake=BlockCatalogue12111.decode("minecraft:glass",Map.of());
    r.accept(new Phase4WorldReplica.BlockChange(o(2,2),p(2,2),new Pos(0,64,0),fake));
    assertEquals("minecraft:glass",r.getWorldState().blockAtOrNull(0,64,0).blockId());
  }

  @Test void collisionQueryCarriesUnknownCoverage(){
    var r=new Phase4WorldReplica(V);
    r.accept(new Phase4WorldReplica.ChunkLoad(o(1,1),p(1,1),new Chunk(0,0)));
    var result=r.getCollisionShapes(BlockBox.of(0,64,0,1,65,1));
    assertFalse(result.isDefinite());
    assertTrue(result.coverage().contains(Coverage.UNKNOWN));
  }

  @Test void entityHistoryIsDeterministic(){
    var r=new Phase4WorldReplica(V);
    var e=new dev.phantom.ac.world.EntityCollisions.EntityBox(4,BlockBox.of(0,64,0,1,2,1));
    r.accept(new Phase4WorldReplica.EntitySpawn(o(1,1),p(1,1),e));
    assertEquals(1,r.getWorldGeneration().entities().boxesIn(BlockBox.of(-1,63,-1,2,3,2)).boxes().size());
    r.accept(new Phase4WorldReplica.EntityDespawn(o(2,2),p(2,2),4));
    assertTrue(r.getWorldGeneration().entities().boxesIn(BlockBox.of(-1,63,-1,2,3,2)).boxes().isEmpty());
  }

  @Test void replayIsDeterministic(){
    var events=List.of(
      new Timeline.Event(1,new Packets.NormalizedPacket(1,1,new Packets.ChunkStates(new Chunk(0,0),Map.of(new Pos(0,64,0),BlockCatalogue12111.decode("minecraft:stone",Map.of()))),EnumSet.of(Packets.PacketFlag.NORMAL),Packets.CaptureProvenance.forPacket(new Packets.ChunkStates(new Chunk(0,0),Map.of(new Pos(0,64,0),BlockCatalogue12111.decode("minecraft:stone",Map.of()))))))
    );
    var timeline=new Timeline.Snapshot(events);
    assertEquals(Phase4WorldReplica.replay(timeline).getWorldState(),Phase4WorldReplica.replay(timeline).getWorldState());
  }
}
