package dev.phantom.ac;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.Packets.*;

class Phase1AdversarialTest {
  @Test void sameCapturePacketsInDifferentCollectionOrderProduceIdenticalTimeline(){
    List<RawPacket> packets=List.of(
        new RawPacket(4,150_000_000L,new Move(Vec3.ZERO,0f,0f,true,null)),
        new RawPacket(1,0L,new ClientInput(true,false,false,false,false,false,false)),
        new RawPacket(3,50_000_000L,new Velocity(Vec3.ZERO)),
        new RawPacket(2,50_000_000L,new Move(new Vec3(.1,0,0),0f,0f,true,null)));
    List<RawPacket> shuffled=new ArrayList<>(packets);Collections.reverse(shuffled);
    assertEquals(Timeline.assign(new Normalizer().normalize(packets),0,50_000_000L),Timeline.assign(new Normalizer().normalize(shuffled),0,50_000_000L));
  }

  @Test void adversarialOrderingGapDuplicateAndBurstAreRetained(){
    List<RawPacket> raw=List.of(new RawPacket(1,0,new Move(Vec3.ZERO,0f,0f,true,null)),new RawPacket(3,10,new Move(Vec3.ZERO,0f,0f,true,null)),new RawPacket(2,20,new Move(Vec3.ZERO,0f,0f,true,null)),new RawPacket(2,21,new Move(Vec3.ZERO,0f,0f,true,null)));
    List<NormalizedPacket> normalized=new Normalizer().normalize(raw);
    assertEquals(4,normalized.size());
    assertTrue(normalized.get(1).flags().contains(PacketFlag.SEQUENCE_GAP));
    assertTrue(normalized.get(2).flags().contains(PacketFlag.OUT_OF_ORDER));
    assertTrue(normalized.get(3).flags().contains(PacketFlag.DUPLICATE));
    assertEquals(4,Timeline.assign(normalized,0,50_000_000L).events().size());
  }

  @Test void provenanceSurvivesNormalizationAndReplayCodec(){
    CaptureProvenance provenance=CaptureProvenance.fromAdapter("packet-adapter","Move",7L);
    Timeline.Snapshot timeline=Timeline.assign(new Normalizer().normalize(List.of(new RawPacket(1,100,new Move(Vec3.ZERO,0f,0f,true,null),provenance,7L))),0,50);
    assertEquals(provenance,timeline.events().getFirst().packet().provenance());
    Timeline.Snapshot roundTrip=new Timeline.Codec().decode(new Timeline.Codec().encode(timeline));
    assertEquals(provenance,roundTrip.events().getFirst().packet().provenance());
  }

  @Test void preEpochAndSequenceAnomaliesBecomeStructuredPlayerUncertainty(){
    Timeline.Snapshot timeline=Timeline.assign(new Normalizer().normalize(List.of(new RawPacket(1,0,new Move(Vec3.ZERO,0f,0f,null,null)))),100,50);
    State.Player state=State.reconstruct(State.Seed.serverAnchor(State.Player.initial(Vec3.ZERO)),timeline).last().orElseThrow().after();
    assertTrue(state.uncertain());
    assertTrue(state.uncertaintyReasons().contains(State.UncertaintyReason.BEFORE_CAPTURE_EPOCH));
    assertTrue(state.uncertaintyReasons().contains(State.UncertaintyReason.MISSING_GROUND_BIT));
  }

  @Test void benchmarkMeasuresPacketAndTimelineProcessing(){
    Phase1PerformanceBenchmark.Result result=Phase1PerformanceBenchmark.benchmark(10_000);
    assertEquals(10_000,result.events());
    assertTrue(result.nanos()>0);
  }
}
