package dev.phantom.ac;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets.ChunkData;
import dev.phantom.ac.Packets.ChunkStates;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.Normalizer;
import dev.phantom.ac.Packets.RawPacket;

class LiveValidationTest {
  private static Timeline.Snapshot capture(List<RawPacket> packets){return Timeline.assign(new Normalizer().normalize(packets),0,50_000_000L);}

  @Test void incompleteLiveWorldCoverageCannotProduceImpossibleFindings(){
    List<RawPacket> packets=new ArrayList<>();
    packets.add(new RawPacket(1,0,new ChunkData(new World.Chunk(-1,0),Map.of())));
    packets.add(new RawPacket(2,0,new ChunkData(new World.Chunk(0,0),Map.of())));
    packets.add(new RawPacket(3,0,new ChunkData(new World.Chunk(1,0),Map.of())));
    packets.add(new RawPacket(4,0,new Move(new Vec3(.5,0,.5),0f,0f,true,null)));
    for(int i=1;i<=4;i++)packets.add(new RawPacket(i+4,i*50_000_000L,new Move(new Vec3(.5+i*5,10,.5),0f,0f,false,null)));
    var report=LiveValidation.analyze(capture(packets),4096);
    assertTrue(report.findings().size()>=2,report.findings().toString());
    assertEquals(0,report.impossibleFindings(),report.findings().toString());
    assertTrue(report.uncertainFindings()>=2,report.findings().toString());
  }

  @Test void firstObservationRemainsUncertain(){
    var report=LiveValidation.analyze(capture(List.of(new RawPacket(1,0,new Move(Vec3.ZERO,0f,0f,true,null)))),64);
    assertEquals(Validation.Verdict.UNCERTAIN,report.findings().getFirst().verdict());
  }

  @Test void knownWorldMakesBlatantVerticalFlightImpossible(){
    var stone=dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of());
    var chunk=new dev.phantom.ac.world.Chunk(0,0);
    List<RawPacket> packets=List.of(
        new RawPacket(1,0,new ChunkStates(chunk,Map.of(new dev.phantom.ac.world.Pos(0,63,0),stone))),
        new RawPacket(2,0,new Move(new Vec3(.5,64,.5),0f,0f,true,null)),
        new RawPacket(3,50_000_000L,new Move(new Vec3(.5,70,.5),0f,0f,false,null)),
        new RawPacket(4,100_000_000L,new Move(new Vec3(.5,76,.5),0f,0f,false,null)),
        new RawPacket(5,150_000_000L,new Move(new Vec3(.5,82,.5),0f,0f,false,null)));
    var report=LiveValidation.analyze(capture(packets),4096);
    assertTrue(report.impossibleFindings()>=1,report.findings().toString());
  }
}
