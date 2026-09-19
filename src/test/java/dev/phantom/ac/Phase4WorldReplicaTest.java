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
import java.util.concurrent.atomic.AtomicInteger;
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
    assertEquals(dirt,r.getWorldState().blockAtOrNull(0,64,0));
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
    var e=new dev.phantom.ac.world.EntityCollisions.EntityBox(4,BlockBox.of(0,64,0,1,66,1));
    r.accept(new Phase4WorldReplica.EntitySpawn(o(1,1),p(1,1),e));
    assertEquals(1,r.getWorldGeneration().entities().boxesIn(BlockBox.of(-1,63,-1,2,67,2)).boxes().size());
    r.accept(new Phase4WorldReplica.EntityDespawn(o(2,2),p(2,2),4));
    assertTrue(r.getWorldGeneration().entities().boxesIn(BlockBox.of(-1,63,-1,2,67,2)).boxes().isEmpty());
  }

  @Test void pendingClientWorldIsNotVisibleUntilBarrierAck(){
    var r=new Phase4WorldReplica(V);
    var stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    var event=new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),Map.of(new Pos(0,64,0),stone));
    r.queue(event);
    assertEquals(Coverage.UNLOADED,r.getWorldState().coverageAt(0,64,0));
    r.openBarrier((short)-1);
    assertEquals(Coverage.UNLOADED,r.getWorldState().coverageAt(0,64,0));
    assertTrue(r.acknowledge((short)-1,3L));
    assertEquals(stone,r.getWorldState().blockAtOrNull(0,64,0));
    assertEquals(3L,r.causalSequence());
    assertEquals(stone,r.snapshotAtSequence(3L).blockAtOrNull(0,64,0));
  }


  @Test void packedSectionRoundTripsAcrossLongBoundary(){
    BlockState[] states=new BlockState[4096];
    for(int i=0;i<states.length;i++)
      states[i]=BlockState.builder("test:state_"+(i%17)).variant(BlockState.Variant.FULL_CUBE).build();
    var packed=Phase4WorldReplica.PackedSection.fromStates(4,states);
    assertEquals(states[12],packed.stateAt(12));
    assertEquals(states[13],packed.stateAt(13));
    assertEquals(states[4095],packed.stateAt(4095));
    assertEquals(5,packed.bitsPerEntry());
  }

  @Test void packedFullChunkKeepsEmptySectionsKnown(){
    var sections=new TreeMap<Integer,Phase4WorldReplica.PackedSection>();
    for(int i=0;i<24;i++)sections.put(i,Phase4WorldReplica.PackedSection.empty(-4+i));
    BlockState stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    BlockState[] cells=new BlockState[4096];
    Arrays.fill(cells,BlockState.air());
    cells[(0<<8)|(0<<4)|0]=stone;
    sections.put(8,Phase4WorldReplica.PackedSection.fromStates(4,cells));

    var r=new Phase4WorldReplica(V,"world",-64,319);
    r.accept(new Phase4WorldReplica.PackedChunkData(
        o(1,1),p(1,1),new Chunk(0,0),sections,true));

    assertEquals(Coverage.KNOWN,r.getWorldState().coverageAt(0,64,0));
    assertEquals(Coverage.KNOWN,r.getWorldState().coverageAt(0,80,0));
    assertEquals(stone,r.getWorldState().blockAtOrNull(0,64,0));
  }

  @Test void partialPackedChunkPreservesUnknownSections(){
    BlockState stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    BlockState[] cells=new BlockState[4096];
    Arrays.fill(cells,BlockState.air());
    cells[0]=stone;

    var r=new Phase4WorldReplica(V,"world",-64,319);
    r.accept(new Phase4WorldReplica.PackedChunkData(
        o(1,1),p(1,1),new Chunk(0,0),
        Map.of(8,Phase4WorldReplica.PackedSection.fromStates(4,cells)),false));

    assertEquals(stone,r.getWorldState().blockAtOrNull(0,64,0));
    assertEquals(Coverage.KNOWN,r.getWorldState().coverageAt(0,64,0));
    assertEquals(Coverage.UNKNOWN,r.getWorldState().coverageAt(0,80,0));
  }

  @Test void packedBlockChangeUsesSmallOverlay(){
    BlockState stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    var r=new Phase4WorldReplica(V);
    Map<Integer,Phase4WorldReplica.PackedSection> sections=new TreeMap<>();
    for(int i=0;i<24;i++)sections.put(i,Phase4WorldReplica.PackedSection.empty(-4+i));
    r.accept(new Phase4WorldReplica.PackedChunkData(
        o(1,1),p(1,1),new Chunk(0,0),sections,true));
    r.accept(new Phase4WorldReplica.BlockChange(
        o(2,2),p(2,2),new Pos(0,64,0),stone));

    assertEquals(stone,r.getWorldState().blockAtOrNull(0,64,0));
    assertTrue(r.compactStateEntryCount() <= 50);
  }

  @Test void wirePropertiesSurviveStateDecodeAndReplay() {
    Map<String,String> properties=Map.of(
        "facing","north","half","bottom","shape","outer_right","waterlogged","false","custom","retained");
    BlockState state=BlockCatalogue12111.decode("minecraft:oak_stairs",properties);
    assertEquals(properties,state.properties());
    assertTrue(state.bukkitDataString().contains("custom=retained"));

    var timelineEvent=new Timeline.Event(1,new Packets.NormalizedPacket(1,1,
        new Packets.ChunkStates(new Chunk(0,0),Map.of(new Pos(0,64,0),state)),
        EnumSet.of(Packets.PacketFlag.NORMAL),Packets.CaptureProvenance.forPacket(
            new Packets.ChunkStates(new Chunk(0,0),Map.of(new Pos(0,64,0),state)))));
    var timeline=new Timeline.Snapshot(List.of(timelineEvent));
    var codec=new Timeline.Codec();
    Timeline.Snapshot decoded=codec.decode(codec.encode(timeline));
    assertEquals(properties,
        ((Packets.ChunkStates)decoded.events().getFirst().packet().packet()).states()
            .get(new Pos(0,64,0)).properties());
  }

  @Test void exactCollisionResolverOverridesReplayCatalogueWithoutChangingCoverage() {
    var r=new Phase4WorldReplica(V,"world",-64,319);
    BlockState stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    r.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),
        Map.of(new Pos(0,64,0),stone)));

    var exact=VoxelShape.local(List.of(BlockBox.of(0.2,0.0,0.2,0.8,0.5,0.8)));
    r.setCollisionResolver((snapshot,state,x,y,z)->Optional.of(exact.toWorld(x,y,z)));

    assertEquals(Coverage.KNOWN,r.getWorldState().coverageAt(0,64,0));
    assertEquals(exact.toWorld(0,64,0),r.getCollisionShape(0,64,0));
  }

  @Test void nativeResolverIsNeverAskedForUnknownOrUnloadedCoverage() {
    var r=new Phase4WorldReplica(V,"world",-64,319);
    AtomicInteger calls=new AtomicInteger();
    r.setCollisionResolver((snapshot,state,x,y,z)->{
      calls.incrementAndGet();
      return Optional.of(VoxelShape.fullCube().toWorld(x,y,z));
    });

    assertEquals(Coverage.UNLOADED,r.getWorldState().coverageAt(0,64,0));
    assertTrue(r.getCollisionShape(0,64,0).isEmpty());
    assertEquals(0,calls.get());

    r.accept(new Phase4WorldReplica.ChunkLoad(o(1,1),p(1,1),new Chunk(0,0)));
    assertEquals(Coverage.UNKNOWN,r.getWorldState().coverageAt(0,64,0));
    assertEquals(0,calls.get());
  }

  @Test void replayIsDeterministic(){
    var events=List.of(
      new Timeline.Event(1,new Packets.NormalizedPacket(1,1,new Packets.ChunkStates(new Chunk(0,0),Map.of(new Pos(0,64,0),BlockCatalogue12111.decode("minecraft:stone",Map.of()))),EnumSet.of(Packets.PacketFlag.NORMAL),Packets.CaptureProvenance.forPacket(new Packets.ChunkStates(new Chunk(0,0),Map.of(new Pos(0,64,0),BlockCatalogue12111.decode("minecraft:stone",Map.of()))))))
    );
    var timeline=new Timeline.Snapshot(events);
    assertEquals(Phase4WorldReplica.replay(timeline).getWorldState(),Phase4WorldReplica.replay(timeline).getWorldState());
  }
}
