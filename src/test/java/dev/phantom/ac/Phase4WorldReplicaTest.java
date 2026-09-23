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