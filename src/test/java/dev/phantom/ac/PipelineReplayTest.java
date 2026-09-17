package dev.phantom.ac;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.Packets.*;

class PipelineReplayTest {
  @Test void fullPipelineArtifactRoundTripsAndReplaysDeterministically(){
    List<RawPacket> raw=List.of(
        new RawPacket(1,0,new ClientInput(true,false,false,false,false,false,false)),
        new RawPacket(2,50_000_000L,new Move(new Vec3(1,64,1),0f,0f,true,1L)),
        new RawPacket(3,100_000_000L,new Velocity(new Vec3(.1,.2,0))));
    Timeline.Snapshot timeline=Timeline.assign(new Normalizer().normalize(raw),0,50_000_000L);
    PipelineReplay replay=PipelineReplay.of(timeline,State.Seed.serverAnchor(State.Player.initial(new Vec3(1,64,1))),Phase7Timing.Config.defaultConfig(),128);
    PipelineReplay decoded=PipelineReplay.decode(replay.encode());
    PipelineReplay.Result a=replay.replay();PipelineReplay.Result b=decoded.replay();
    assertEquals(replay.timeline(),decoded.timeline());
    assertEquals(a.playerHistory(),b.playerHistory());
    assertEquals(a.timing(),b.timing());
    assertEquals(a.validation().findings(),b.validation().findings());
    assertEquals(a.simulationInputs(),b.simulationInputs());
  }

  @Test void pipelineCodecRejectsTrailingBytes(){
    Timeline.Snapshot timeline=Timeline.assign(new Normalizer().normalize(List.of(new RawPacket(1,0,new Move(Vec3.ZERO,0f,0f,true,0L)))),0,50);
    PipelineReplay replay=PipelineReplay.of(timeline,State.Seed.serverAnchor(State.Player.initial(Vec3.ZERO)),Phase7Timing.Config.defaultConfig(),32);
    byte[] bytes=replay.encode();byte[] corrupt=Arrays.copyOf(bytes,bytes.length+1);corrupt[corrupt.length-1]=99;
    assertThrows(IllegalArgumentException.class,()->PipelineReplay.decode(corrupt));
  }

  @Test void richPlayerStateCarriesMovementMetadata(){
    State.Player state=State.apply(State.Player.initial(Vec3.ZERO),new NormalizedPacket(7,100,new ClientInput(true,false,false,true,false,true,true),EnumSet.of(PacketFlag.NORMAL)));
    assertTrue(state.input().isPresent());
    assertTrue(state.input().get().sprint());
    assertTrue(state.input().get().sneak());
    assertNotNull(state.attributes());
    assertNotNull(state.pose());
    assertNotNull(state.environment());
    assertNotNull(state.clientTickRange());
    assertEquals(7,state.provenance().sequence());
  }
}
