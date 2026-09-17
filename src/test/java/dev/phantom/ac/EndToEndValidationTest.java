package dev.phantom.ac;

import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets.ChunkData;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.Normalizer;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.Validation.Evidence;
import dev.phantom.ac.Validation.Reachability;
import dev.phantom.ac.Validation.SyncWindow;
import dev.phantom.ac.Validation.Verdict;

/** Proves the conservative packet-to-validation path without a live server. */
class EndToEndValidationTest {
  @Test void incompleteLiveWorldCannotBecomeAnOperatorAlert(){
    World.Chunk chunk=new World.Chunk(0,0);
    List<RawPacket> raw=List.of(
        new RawPacket(1,0,new ChunkData(chunk,Map.of())),
        new RawPacket(2,0,new Move(new Vec3(.5,0,.5),0f,0f,true,null)),
        new RawPacket(3,50_000_000L,new Move(new Vec3(.5,5,.5),0f,0f,false,null)),
        new RawPacket(4,100_000_000L,new Move(new Vec3(.5,10,.5),0f,0f,false,null)));
    Timeline.Snapshot timeline=Timeline.assign(new Normalizer().normalize(raw),0,50_000_000L);
    assertEquals(4,timeline.events().size());
    Replay.Result replay=Replay.replay(Player.initial(Vec3.ZERO),timeline);
    assertEquals(timeline.events().size(),replay.frames().size());
    assertEquals(new Vec3(.5,0,.5),replay.frames().get(1).after().position());
    World.VisibilityHistory history=World.fromTimeline(timeline);
    assertEquals(World.Block.AIR,history.at(0).blockAt(0,0,0));
    assertTrue(history.at(0).visibleChunks().contains(chunk));
    LiveValidation.Report report=LiveValidation.analyze(timeline,4096);
    assertEquals(3,report.movementObservations());
    assertTrue(report.timelineEvents()>=4);
    assertTrue(report.anchoredObservations()>=1);
    assertEquals(0,report.impossibleFindings(),report.findings().toString());
    assertTrue(report.uncertainFindings()>=2,report.findings().toString());
  }

  @Test void operatorAlertStillWorksForExplicitExhaustiveEvidence(){
    Player observed=Player.initial(new Vec3(.5,5,.5));
    Reachability reachability=new Reachability(Verdict.IMPOSSIBLE,Set.of(),List.of("exhaustive Phase 6 search produced no exact candidate"));
    Evidence evidence=new Evidence(Verdict.IMPOSSIBLE,"MOVEMENT_REACHABILITY",Double.POSITIVE_INFINITY,reachability.reasons());
    SyncWindow sync=Validation.synchronize(10,0,0,false);
    assertFalse(sync.uncertain());
    OperatorValidation.Observation observation=new OperatorValidation.Observation("Synthetic",10,Player.initial(new Vec3(.5,0,.5)),observed,sync,Contracts.TARGET_VERSION,List.of(),reachability,evidence);
    OperatorValidation.Aggregator state=OperatorValidation.Aggregator.empty();
    OperatorValidation.Result first=OperatorValidation.aggregate(state,observation);assertTrue(first.alert().isEmpty());
    OperatorValidation.Result second=OperatorValidation.aggregate(first.state(),observation);assertTrue(second.alert().isPresent());
    assertEquals(Verdict.IMPOSSIBLE,second.alert().orElseThrow().verdict());
  }
}
