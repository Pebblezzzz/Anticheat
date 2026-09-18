package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets.ChunkStates;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.Normalizer;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase8MovementValidation.CandidateSummary;
import dev.phantom.ac.Phase8MovementValidation.Evidence;
import dev.phantom.ac.Phase8MovementValidation.Verdict;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;

class Phase8HardeningRegressionTest {
  private static Phase7Timing.Config exactTiming() {
    return new Phase7Timing.Config(50_000_000L, 50_000_000L, 50_000_000L,
        new Phase7Timing.LatencyBounds(0, 0), new Phase7Timing.LatencyBounds(0, 0),
        new Phase7Timing.TickDelayBounds(0, 0), new Phase7Timing.TickDelayBounds(0, 0),
        250_000_000L, 3, 128);
  }

  private static Timeline.Snapshot capture(List<RawPacket> packets) {
    return Timeline.assign(new Normalizer().normalize(packets), 0, 50_000_000L);
  }

  private static WorldSnapshot floorWorld() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    var builder = WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0, 0);
    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) builder.setBlock(x, 63, z, stone);
    return builder.build();
  }

  private static Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> floorStates() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone", Map.of());
    Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> states=new LinkedHashMap<>();
    for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) states.put(new dev.phantom.ac.world.Pos(x,63,z),stone);
    return Map.copyOf(states);
  }

  @Test
  void explicitClientTicksDoNotBecomeWallClockInconsistencyUnderBoundedLatency() {
    var packets=List.of(
        new RawPacket(1,0,new Move(new Vec3(.5,64,.5),0f,0f,true,0L)),
        new RawPacket(2,50_000_000L,new Move(new Vec3(.5,64,.5),0f,0f,true,1L)));
    var timeline=Timeline.assign(new Normalizer().normalize(packets),0,50_000_000L);
    var reconstruction=Phase7Timing.reconstruct(timeline,Phase7Timing.Config.defaultConfig());
    assertNotEquals(Phase7Timing.Consistency.INCONSISTENT,reconstruction.consistency(),reconstruction.canonicalText());
    assertEquals(Phase7Timing.TimingSource.EXPLICIT_CLIENT_TICK,reconstruction.frames().get(1).timing().source());
    assertEquals(Phase7Timing.Range.exact(1),reconstruction.frames().get(1).timing().packetGenerationClientTicks());
  }

  @Test
  void liveValidationAdvancesAcrossARealClientTickGapInsteadOfRebasing() {
    var packets=List.of(
        new RawPacket(1,0,new ChunkStates(new dev.phantom.ac.world.Chunk(0,0),floorStates())),
        new RawPacket(2,0,new Packets.PlayerContext("survival",Simulation.Attributes.DEFAULT,Map.of(),
            Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),false,List.of())),
        new RawPacket(3,0,new Packets.ClientInput(false,false,false,false,false,false,false)),
        new RawPacket(4,0,new Move(new Vec3(.5,64,.5),0f,0f,true,0L)),
        new RawPacket(5,100_000_000L,new Move(new Vec3(.5,64,.5),0f,0f,true,2L)));
    var timeline=Timeline.assign(new Normalizer().normalize(packets),0,50_000_000L);
    var report=Phase8LiveValidation.analyze("gap-test",timeline,256,exactTiming());
    assertEquals(2,report.movementObservations(),report.results().toString());
    assertEquals(Verdict.UNCERTAIN,report.results().getFirst().verdict());
    assertEquals(Verdict.POSSIBLE,report.results().get(1).verdict(),report.results().toString());
  }


  @Test
  void blatantTeleportLikeMovementIsExhaustivelyImpossibleOnKnownWorld() {
    var packets=List.of(
        new RawPacket(1,0,new ChunkStates(new dev.phantom.ac.world.Chunk(0,0),floorStates())),
        new RawPacket(2,0,new Packets.PlayerContext("survival",Simulation.Attributes.DEFAULT,Map.of(),
            Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),false,List.of())),
        new RawPacket(3,0,new Packets.ClientInput(false,false,false,false,false,false,false)),
        new RawPacket(4,0,new Move(new Vec3(.5,64,.5),0f,0f,true,0L)),
        new RawPacket(5,50_000_000L,new Move(new Vec3(5.5,64,.5),0f,0f,true,1L))
    );
    var report=Phase8LiveValidation.analyze("blatant",capture(packets),256,exactTiming());
    assertEquals(2,report.movementObservations(),report.results().toString());
    assertEquals(Verdict.UNCERTAIN,report.results().getFirst().verdict());
    assertEquals(Verdict.IMPOSSIBLE,report.results().get(1).verdict(),report.results().toString());
    assertEquals(0,report.results().get(1).evidence().matchingCandidateCount());
    assertTrue(report.results().get(1).evidence().eliminationReason().startsWith("all exhaustively modeled legitimate candidates"));
  }

  @Test
  void firstLiveMovementCanBeProvenImpossibleAgainstAuthoritativeJoinAnchor(){
    var packets=List.of(
        new RawPacket(1,0,new ChunkStates(new dev.phantom.ac.world.Chunk(0,0),floorStates())),
        new RawPacket(2,0,new Packets.PlayerContext("survival",Simulation.Attributes.DEFAULT,Map.of(),
            Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),false,List.of())),
        new RawPacket(3,0,new Packets.ClientInput(false,false,false,false,false,false,false)),
        new RawPacket(4,10_000_000L,new Packets.ClientTickEnd()),
        new RawPacket(5,60_000_000L,new Packets.ClientTickEnd()),
        new RawPacket(6,100_000_000L,new Move(new Vec3(0.5,65.0,0.5),0f,0f,false,null))
    );
    var timeline=capture(packets);
    var anchor=Player.initial(new Vec3(0.5,64.0,0.5));
    var report=Phase8LiveValidation.analyze("flight-anchor",timeline,4096,exactTiming(),null,anchor);
    assertEquals(1,report.movementObservations(),report.results().toString());
    assertEquals(Verdict.IMPOSSIBLE,report.results().getFirst().verdict(),report.results().toString());
    assertEquals(0,report.results().getFirst().evidence().matchingCandidateCount());
  }

  @Test
  void validationResultGateCountsAnImpossibleObservationOnlyOnce() {
    var gate=new ValidationResultGate();
    assertTrue(gate.accept("move:42",Verdict.IMPOSSIBLE));
    assertFalse(gate.accept("move:42",Verdict.IMPOSSIBLE));
    assertFalse(gate.accept("move:42",Verdict.UNCERTAIN));
    assertEquals(Verdict.IMPOSSIBLE,gate.last("move:42"));
  }

  @Test
  void validationResultGateAllowsUncertainToResolveOnce() {
    var gate=new ValidationResultGate();
    assertTrue(gate.accept("move:7",Verdict.UNCERTAIN));
    assertTrue(gate.accept("move:7",Verdict.IMPOSSIBLE));
    assertFalse(gate.accept("move:7",Verdict.IMPOSSIBLE));
  }

  @Test
  void strictSetbackRequiresExhaustiveImpossibleEvidence() {
    Player p=Player.initial(new Vec3(.5,64,.5));
    Evidence evidence=new Evidence(
        Phase8MovementValidation.VERSION,Verdict.IMPOSSIBLE,"test",20,2,2,p,p,
        Contracts.TARGET_VERSION,"world",List.of(),List.of(),1,0,1,
        "all exhaustively modeled legitimate candidates disagree with the observed movement state",
        OptionalLong.of(20),Optional.<CandidateSummary>empty(),List.of(),List.of(),
        Phase8MovementValidation.PHASE5_VERSION,Phase8MovementValidation.PHASE6_VERSION,
        Phase8MovementValidation.PHASE7_VERSION,"replay","MOVEMENT_REACHABILITY");
    assertTrue(SetbackPolicy.evaluate(evidence,true,true).allowed());
    assertFalse(SetbackPolicy.evaluate(evidence,false,true).allowed());
  }

  @Test
  void strictSetbackRejectsUncertainPlayerStateEvenWhenVerdictIsImpossible() {
    Player safe=Player.initial(new Vec3(.5,64,.5));
    Player uncertain=safe.withUncertainty(State.UncertaintyReason.UNKNOWN_ENVIRONMENT);
    Evidence evidence=new Evidence(
        Phase8MovementValidation.VERSION,Verdict.IMPOSSIBLE,"test",20,2,2,safe,uncertain,
        Contracts.TARGET_VERSION,"world",List.of(),List.of(),1,0,1,
        "all exhaustively modeled legitimate candidates disagree with the observed movement state",
        OptionalLong.of(20),Optional.<CandidateSummary>empty(),List.of(),List.of(),
        Phase8MovementValidation.PHASE5_VERSION,Phase8MovementValidation.PHASE6_VERSION,
        Phase8MovementValidation.PHASE7_VERSION,"replay","MOVEMENT_REACHABILITY");
    assertFalse(SetbackPolicy.evaluate(evidence,true,true).allowed());
  }
}
