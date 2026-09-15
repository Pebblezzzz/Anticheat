package dev.phantom.ac;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.Packets.*;

/** Phase-2 state reconstruction tests: transitions plus provenance. */
class PlayerStateTest {
  private static Timeline.Snapshot timeline(List<RawPacket> raw){return Timeline.assign(new Packets.Normalizer().normalize(raw),0,50);}

  @Test void reconstructionTracksEverySupportedStateDomainAndEventProvenance(){
    var start=new State.Player(new Vec3(10,64,10),Vec3.ZERO,30,4,true,"survival",Map.of(),OptionalInt.empty(),false);
    var events=timeline(List.of(
      new RawPacket(1,0,new ClientInput(true,false,false,true,false,false,true)),
      new RawPacket(2,1,new Move(new Vec3(11,64,10),null,null,null,null)),
      new RawPacket(3,2,new Velocity(new Vec3(.2,.3,.4))),
      new RawPacket(4,3,new Effect("minecraft:speed",1,false)),
      new RawPacket(5,4,new Gamemode("creative")),
      new RawPacket(6,5,new Teleport(9,new Vec3(1,2,3),5,6,true,false,true,true,false)),
      new RawPacket(7,6,new TeleportConfirm(9))));
    var reconstruction=State.reconstruct(State.Seed.serverAnchor(start),events);assertEquals(7,reconstruction.frames().size());
    var inputFrame=reconstruction.frames().get(0);assertEquals(Optional.of(new ClientInput(true,false,false,true,false,false,true)),inputFrame.currentInput());assertEquals(Set.of(State.Fact.INPUT),inputFrame.refreshedByEvent());
    var partialMove=reconstruction.frames().get(1);assertEquals(new Vec3(11,64,10),partialMove.after().position());assertEquals(30,partialMove.after().yaw());assertTrue(partialMove.after().uncertain());assertEquals(Set.of(State.Fact.POSITION),partialMove.refreshedByEvent());assertTrue(partialMove.knownAfter().contains(State.Fact.ROTATION));
    var velocity=reconstruction.frames().get(2);assertEquals(new Vec3(.2,.3,.4),velocity.after().velocity());assertEquals(Set.of(State.Fact.VELOCITY),velocity.refreshedByEvent());
    assertEquals(1,reconstruction.frames().get(3).after().effects().get("minecraft:speed"));assertEquals("creative",reconstruction.frames().get(4).after().gamemode());
    var teleport=reconstruction.frames().get(5);assertEquals(new Vec3(12,2,13),teleport.after().position());assertEquals(35,teleport.after().yaw());assertEquals(6,teleport.after().pitch());assertEquals(9,teleport.after().awaitingTeleport().getAsInt());assertTrue(teleport.refreshedByEvent().containsAll(Set.of(State.Fact.POSITION,State.Fact.ROTATION,State.Fact.TELEPORT)));
    var confirmed=reconstruction.frames().getLast();assertTrue(confirmed.after().awaitingTeleport().isEmpty());assertEquals(State.Environment.UNKNOWN,confirmed.environment());assertFalse(confirmed.knownAfter().contains(State.Fact.ENVIRONMENT));
  }

  @Test void effectRemovalAndTeleportMismatchAreNeverSilentlyAccepted(){
    var start=State.Player.initial(Vec3.ZERO);var effect=State.apply(start,new NormalizedPacket(1,0,new Effect("minecraft:jump_boost",2,false),EnumSet.of(PacketFlag.NORMAL)));var removed=State.apply(effect,new NormalizedPacket(2,1,new Effect("minecraft:jump_boost",0,true),EnumSet.of(PacketFlag.NORMAL)));assertFalse(removed.effects().containsKey("minecraft:jump_boost"));
    var teleported=State.apply(removed,new NormalizedPacket(3,2,new Teleport(4,Vec3.ZERO,0,0),EnumSet.of(PacketFlag.NORMAL)));var mismatch=State.apply(teleported,new NormalizedPacket(4,3,new TeleportConfirm(5),EnumSet.of(PacketFlag.NORMAL)));assertTrue(mismatch.uncertain());assertEquals(4,mismatch.awaitingTeleport().getAsInt());
  }

  @Test void packetIntegrityProblemsWidenEveryFollowingStateInsteadOfBeingForgotten(){
    var start=State.Player.initial(Vec3.ZERO);var event=new NormalizedPacket(2,0,new Move(Vec3.ZERO,0f,0f,true,null),EnumSet.of(PacketFlag.SEQUENCE_GAP));var result=State.apply(start,event);assertTrue(result.uncertain());
    var later=State.apply(result,new NormalizedPacket(3,1,new Velocity(Vec3.ZERO),EnumSet.of(PacketFlag.NORMAL)));assertTrue(later.uncertain());
  }

  @Test void reconstructionIsDeterministicAcrossRecordSerializeDeserializeReplay(){
    var raw=List.of(new RawPacket(1,0,new Move(Vec3.ZERO,0f,0f,true,null)),new RawPacket(2,1,new ClientInput(true,false,false,false,false,false,false)),new RawPacket(3,2,new Velocity(new Vec3(.1,.2,.3))),new RawPacket(4,3,new Effect("minecraft:slow_falling",0,false)));
    var original=timeline(raw);var decoded=new Timeline.Codec().decode(new Timeline.Codec().encode(original));var seed=State.Seed.serverAnchor(State.Player.initial(Vec3.ZERO));assertEquals(State.reconstruct(seed,original),State.reconstruct(seed,decoded));
  }

  @Test void seedRequiresExplicitEnvironmentKnowledgeAndFramesAreImmutable(){
    var player=State.Player.initial(Vec3.ZERO);assertThrows(IllegalArgumentException.class,()->new State.Seed(player,Set.of(State.Fact.POSITION),State.Environment.DRY));
    var reconstruction=State.reconstruct(State.Seed.serverAnchor(player),timeline(List.of(new RawPacket(1,0,new Move(Vec3.ZERO,0f,0f,true,null)))));assertThrows(UnsupportedOperationException.class,()->reconstruction.frames().add(null));assertThrows(UnsupportedOperationException.class,()->reconstruction.frames().getFirst().knownAfter().add(State.Fact.INPUT));
  }
}
