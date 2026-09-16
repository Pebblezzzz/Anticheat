package dev.phantom.ac;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets.ChunkData;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.Normalizer;
import dev.phantom.ac.Packets.RawPacket;

class LiveValidationTest {
  private static Timeline.Snapshot capture(List<RawPacket> packets) {
    return Timeline.assign(new Normalizer().normalize(packets),0,50_000_000L);
  }
  @Test void sustainedDivergenceProducesMoreThanOneImpossibleFinding() {
    List<RawPacket> packets=new ArrayList<>();
    packets.add(new RawPacket(1,0,new ChunkData(new World.Chunk(-1,0),Map.of())));
    packets.add(new RawPacket(2,0,new ChunkData(new World.Chunk(0,0),Map.of())));
    packets.add(new RawPacket(3,0,new ChunkData(new World.Chunk(1,0),Map.of())));
    packets.add(new RawPacket(4,0,new Move(new Vec3(.5,0,.5),0f,0f,true,null)));
    for(int i=1;i<=4;i++) packets.add(new RawPacket(i+4,i*50_000_000L,new Move(new Vec3(.5+i*5,10,.5),0f,0f,false,null)));
    var timeline=capture(packets);
    var report=LiveValidation.analyze(timeline,4096);
    assertTrue(report.findings().stream().filter(f->f.verdict()==Validation.Verdict.IMPOSSIBLE).count()>=2, report.findings().toString());
  }
  @Test void firstObservationRemainsUncertain() {
    var report=LiveValidation.analyze(capture(List.of(new RawPacket(1,0,new Move(Vec3.ZERO,0f,0f,true,null)))),64);
    assertEquals(Validation.Verdict.UNCERTAIN,report.findings().getFirst().verdict());
  }
}
