package dev.phantom.ac;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.VoxelShape;
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


  @Test void pendingWorldSnapshotIncludesSentMutationsBeforeAck(){
    var r=new Phase4WorldReplica(V);
    var stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    var air=BlockState.air();
    var chunk=new Chunk(0,0);
    var position=new Pos(0,64,0);

    r.accept(new Phase4WorldReplica.ChunkData(
        o(1,1),p(1,1),chunk,Map.of(position,stone)));

    r.queue(new Phase4WorldReplica.BlockChange(
        o(2,2),p(2,2),position,air));
    r.openBarrier((short)-2);

    assertEquals(stone,r.snapshotAtOrBefore(3).blockAtOrNull(0,64,0));
    assertNull(r.snapshotAtOrBeforeIncludingPending(3).blockAtOrNull(0,64,0));
    var pending=r.snapshotAtOrBeforeIncludingPending(3);
    assertEquals(Coverage.KNOWN,pending.coverageAt(0,64,0));
    assertEquals(3L,pending.causalSequence());
    assertNull(pending.blockAtOrNull(0,64,0));
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

  @Test void snapshotMergePreservesExactCollisionResolver() {
    var left=new Phase4WorldReplica(V,"world",-64,319);
    var right=new Phase4WorldReplica(V,"world",-64,319);
    var stone=BlockCatalogue12111.decode("minecraft:stone",Map.of());
    left.accept(new Phase4WorldReplica.ChunkData(o(1,1),p(1,1),new Chunk(0,0),
        Map.of(new Pos(0,64,0),stone)));
    right.accept(new Phase4WorldReplica.ChunkData(o(1,2),p(1,2),new Chunk(1,0),
        Map.of(new Pos(16,64,0),stone)));
    var exact=VoxelShape.local(BlockBox.of(0.25,0,0.25,0.75,1,0.75));
    left.setCollisionResolver((snapshot,state,x,y,z)->Optional.of(exact.toWorld(x,y,z)));
    right.setCollisionResolver((snapshot,state,x,y,z)->Optional.of(exact.toWorld(x,y,z)));
    var merged=WorldSnapshot.merge(left.getWorldState(),right.getWorldState());
    assertEquals(exact.toWorld(0,64,0),merged.collisionShapeAt(0,64,0));
    assertEquals(exact.toWorld(16,64,0),merged.collisionShapeAt(16,64,0));
  }

  @Test void legacyBlockStateVariantOrdinalsRemainStable() {
    assertEquals(0, BlockState.Variant.AIR.ordinal());
    assertEquals(25, BlockState.Variant.FLUID.ordinal());
    assertEquals(26, BlockState.Variant.NO_COLLISION_SPECIAL.ordinal());
    assertEquals(27, BlockState.Variant.UNSUPPORTED.ordinal());
    assertEquals(28, BlockState.Variant.CATALOGUE.ordinal());
  }

  @Test void generated12111CatalogueReconstructsStateSpecificCollision() {
    BlockState stairs=BlockCatalogue12111.decode("minecraft:oak_stairs",Map.of(
        "facing","north","half","top","shape","straight","waterlogged","false"));
    VoxelShape stairShape=dev.phantom.ac.world.v12111.BlockCollisionCatalogue12111.shapeFor(stairs).orElseThrow();
    assertEquals(List.of(
        BlockBox.of(0,0,0,1,1,0.5),
        BlockBox.of(0,0.5,0.5,1,1,1)), stairShape.boxes());

    BlockState slab=BlockCatalogue12111.decode("minecraft:oak_slab",Map.of(
        "type","bottom","waterlogged","false"));
    assertEquals(BlockBox.of(0,0,0,1,0.5,1),
        dev.phantom.ac.world.v12111.BlockCollisionCatalogue12111.shapeFor(slab).orElseThrow().boxes().getFirst());
  }

  @Test void generatedCatalogueAcceptsOutOfUnitVanillaBoxes() {
    BlockState conduit=BlockCatalogue12111.decode("minecraft:end_rod",Map.of("facing","north"));
    VoxelShape shape=dev.phantom.ac.world.v12111.BlockCollisionCatalogue12111.shapeFor(conduit).orElseThrow();
    assertNotNull(shape);
    assertTrue(shape.boxes().stream().allMatch(b ->
        b.minX() >= -1 && b.minY() >= -1 && b.minZ() >= -1
            && b.maxX() <= 2 && b.maxY() <= 2 && b.maxZ() <= 2));
  }

  @Test void entityTrackingCompletenessChangesDoNotPublishWorldGenerations() {
    var r=new Phase4WorldReplica(V);
    int initialGenerations=r.generations().size();
    r.markEntityTrackingComplete();
    assertEquals(initialGenerations,r.generations().size());
    assertTrue(r.getWorldGeneration().entities().complete());

    r.markEntityTrackingIncomplete();
    assertEquals(initialGenerations,r.generations().size());
    assertFalse(r.getWorldGeneration().entities().complete());

    r.markEntityTrackingComplete();
    assertTrue(r.getWorldGeneration().entities().complete());
    assertEquals(initialGenerations,r.generations().size());
  }

  @Test void liveBarrierAcknowledgementDoesNotBuildReplayJournal() {
    var r=new Phase4WorldReplica(V);
    for(int i=1;i<=2048;i++) {
      r.queue(new Phase4WorldReplica.ChunkLoad(
          o(i,i),p(i,i),new Chunk(i&31,i>>5)));
    }
    r.openBarrier((short)-7);
    assertTrue(r.acknowledge((short)-7,2048L));
    assertEquals(64,r.generations().size());
    assertEquals(2048L,r.causalSequence());
  }

  @Test void entityTrackingCompletenessIsExplicitAndReplayable() {
    var box=BlockBox.of(0,64,0,1,66,1);
    var replica=new Phase4WorldReplica(V);
    replica.markEntityTrackingComplete();
    replica.accept(new Timeline.Event(1,new Packets.NormalizedPacket(1,1,
        new Packets.EntitySpawn(7,box),EnumSet.of(Packets.PacketFlag.NORMAL),
        Packets.CaptureProvenance.forPacket(new Packets.EntitySpawn(7,box)))));
    assertTrue(replica.getWorldGeneration().entities().complete());
    assertEquals(box,replica.getWorldGeneration().entities()
        .boxesIn(BlockBox.of(-1,63,-1,2,67,2)).boxes().getFirst().box());

    replica.markEntityTrackingIncomplete();
    assertFalse(replica.getWorldGeneration().entities().complete());
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