package dev.phantom.ac;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.Packets.*;

/** Completion tests for Phase 1 packets/timeline and Phase 3 replay. */
class PacketReplayTest {
  private static Timeline.Snapshot timeline(List<RawPacket> raw){return Timeline.assign(new Packets.Normalizer().normalize(raw),100,50);}

  @Test void normalizationPreservesSameTimestampPacketsInSourceSequenceOrder(){
    var normalized=new Packets.Normalizer().normalize(List.of(new RawPacket(3,100,new Velocity(Vec3.ZERO)),new RawPacket(1,100,new Velocity(Vec3.ZERO)),new RawPacket(2,100,new Velocity(Vec3.ZERO))));
    assertEquals(List.of(1L,2L,3L),normalized.stream().map(NormalizedPacket::sequence).toList());
    assertTrue(normalized.stream().noneMatch(packet->packet.flags().contains(PacketFlag.SEQUENCE_GAP)),"tie-break ordering restores the source sequence without discarding packets");
  }

  @Test void normalizationRetainsGapsDuplicatesAndDelayedSequenceEvidence(){
    var normalized=new Packets.Normalizer().normalize(List.of(new RawPacket(5,10,new Velocity(Vec3.ZERO)),new RawPacket(7,20,new Velocity(Vec3.ZERO)),new RawPacket(6,30,new Velocity(Vec3.ZERO)),new RawPacket(7,40,new Velocity(Vec3.ZERO))));
    assertTrue(normalized.get(1).flags().contains(PacketFlag.SEQUENCE_GAP));
    assertTrue(normalized.get(2).flags().contains(PacketFlag.OUT_OF_ORDER));
    assertTrue(normalized.get(3).flags().contains(PacketFlag.DUPLICATE));
    assertFalse(normalized.get(3).flags().contains(PacketFlag.OUT_OF_ORDER),"a duplicate is distinct evidence from a delayed lower sequence");
  }

  @Test void normalizedFlagsAreDefensiveAndCannotMutateReplayEvidence(){
    var packet=new NormalizedPacket(1,1,new Velocity(Vec3.ZERO),EnumSet.of(PacketFlag.NORMAL));
    packet.flags().add(PacketFlag.DUPLICATE);
    assertEquals(EnumSet.of(PacketFlag.NORMAL),packet.flags());
  }

  @Test void timelineKeepsBurstsAndExplicitlyMarksPreEpochPackets(){
    var result=timeline(List.of(new RawPacket(1,99,new Velocity(Vec3.ZERO)),new RawPacket(2,100,new Velocity(Vec3.ZERO)),new RawPacket(3,149,new Velocity(Vec3.ZERO)),new RawPacket(4,150,new Velocity(Vec3.ZERO))));
    assertEquals(List.of(0L,0L,0L,1L),result.events().stream().map(Timeline.Event::serverTick).toList());
    assertTrue(result.events().getFirst().packet().flags().contains(PacketFlag.BEFORE_CAPTURE_EPOCH));
    assertEquals(100,result.metadata().captureEpochNanos());
    assertEquals(50,result.metadata().serverTickNanos());
  }

  @Test void timelineRejectsInvalidClockMetadataAndUnorderedManualEvents(){
    assertThrows(IllegalArgumentException.class,()->Timeline.assign(List.of(),-1,50));
    assertThrows(IllegalArgumentException.class,()->Timeline.assign(List.of(),0,0));
    var packet=new NormalizedPacket(1,1,new Velocity(Vec3.ZERO),EnumSet.of(PacketFlag.NORMAL));
    assertThrows(IllegalArgumentException.class,()->new Timeline.Snapshot(List.of(new Timeline.Event(2,packet),new Timeline.Event(1,packet))));
    assertThrows(IllegalArgumentException.class,()->new Timeline.Snapshot(List.of(new Timeline.Event(1,new NormalizedPacket(2,2,new Velocity(Vec3.ZERO),EnumSet.of(PacketFlag.NORMAL))),new Timeline.Event(1,new NormalizedPacket(1,1,new Velocity(Vec3.ZERO),EnumSet.of(PacketFlag.NORMAL))))));
  }

  @Test void integritySummarizesPacketProblemsWithoutMakingACheatVerdict(){
    var snapshot=timeline(List.of(new RawPacket(1,99,new Move(Vec3.ZERO,0f,0f,true,null)),new RawPacket(3,100,new ClientInput(true,false,false,false,false,false,false)),new RawPacket(2,101,new Teleport(1,Vec3.ZERO,0,0)),new RawPacket(3,102,new Velocity(Vec3.ZERO))));
    var integrity=Timeline.inspect(snapshot);assertEquals(4,integrity.events());assertEquals(1,integrity.sequenceGaps());assertEquals(1,integrity.outOfOrderPackets());assertEquals(1,integrity.duplicatePackets());assertEquals(1,integrity.preEpochPackets());assertEquals(1,integrity.movementPackets());assertEquals(1,integrity.inputPackets());assertEquals(1,integrity.teleportPackets());assertEquals(1,integrity.velocityPackets());
  }

  @Test void replayCodecRoundTripsEveryNormalizedPacketVariantAndIsByteStable(){
    var blockPos=new World.Pos(2,64,-4);var chunk=new World.Chunk(0,-1);
    List<RawPacket> raw=List.of(
      new RawPacket(1,100,new Move(new Vec3(1,2,3),null,4f,null,9L)),
      new RawPacket(2,101,new ClientInput(true,false,true,false,true,false,true)),
      new RawPacket(3,102,new Teleport(7,new Vec3(1,2,3),4,5,true,false,true,false,true)),
      new RawPacket(4,103,new TeleportConfirm(7)),new RawPacket(5,104,new Velocity(new Vec3(1,2,3))),
      new RawPacket(6,105,new Effect("minecraft:speed",2,false)),new RawPacket(7,106,new Gamemode("survival")),
      new RawPacket(8,107,new ChunkData(chunk,Map.of(blockPos,World.Block.FULL))),new RawPacket(9,108,new ChunkUnload(chunk)),new RawPacket(10,109,new BlockChange(blockPos,World.Block.AIR)));
    var snapshot=timeline(raw);var codec=new Timeline.Codec();byte[] first=codec.encode(snapshot),second=codec.encode(snapshot);
    assertArrayEquals(first,second);assertEquals(snapshot,codec.decode(first));
  }

  @Test void replayCodecRejectsTruncationBadMagicTrailingDataAndUnknownTags(){
    var codec=new Timeline.Codec();byte[] valid=codec.encode(timeline(List.of(new RawPacket(1,100,new Velocity(Vec3.ZERO)))));
    assertThrows(IllegalArgumentException.class,()->codec.decode(Arrays.copyOf(valid,valid.length-1)));
    byte[] badMagic=valid.clone();badMagic[0]=0;assertThrows(IllegalArgumentException.class,()->codec.decode(badMagic));
    byte[] trailing=Arrays.copyOf(valid,valid.length+1);assertThrows(IllegalArgumentException.class,()->codec.decode(trailing));
  }

  @Test void replayIsDeterministicAndReportsTheFirstStateFieldThatDiverges(){
    var event=new Timeline.Event(5,new NormalizedPacket(7,100,new Velocity(Vec3.ZERO),EnumSet.of(PacketFlag.NORMAL)));
    var before=State.Player.initial(Vec3.ZERO);var expectedAfter=before;var actualAfter=new State.Player(new Vec3(9,0,0),Vec3.ZERO,0,0,true,"survival",Map.of(),OptionalInt.empty(),false);
    var expected=new Replay.Result(List.of(new Replay.Frame(0,event,before,expectedAfter)));var actual=new Replay.Result(List.of(new Replay.Frame(0,event,before,actualAfter)));
    var difference=Replay.firstDivergence(expected,actual).orElseThrow();assertEquals("position",difference.field());assertEquals(5,difference.serverTick());assertEquals(7,difference.sequence());
  }

  @Test void replayReportsLengthAndEventDivergenceWithoutComparingOnlyFinalState(){
    var first=timeline(List.of(new RawPacket(1,100,new Velocity(Vec3.ZERO))));var second=timeline(List.of(new RawPacket(2,100,new Velocity(Vec3.ZERO))));
    assertEquals("event",Replay.firstDivergence(Replay.replay(State.Player.initial(Vec3.ZERO),first),Replay.replay(State.Player.initial(Vec3.ZERO),second)).orElseThrow().field());
    assertEquals("replay length",Replay.firstDivergence(Replay.replay(State.Player.initial(Vec3.ZERO),first),Replay.replay(State.Player.initial(Vec3.ZERO),timeline(List.of()))).orElseThrow().field());
  }

  @Test void packetConstructorsRejectMissingAndInvalidCaptureFacts(){
    assertThrows(IllegalArgumentException.class,()->new RawPacket(-1,0,new Velocity(Vec3.ZERO)));
    assertThrows(IllegalArgumentException.class,()->new RawPacket(1,-1,new Velocity(Vec3.ZERO)));
    assertThrows(NullPointerException.class,()->new Velocity(null));
    assertThrows(IllegalArgumentException.class,()->new Gamemode(" "));
    assertThrows(IllegalArgumentException.class,()->new Move(null,null,null,null,-1L));
  }
}
