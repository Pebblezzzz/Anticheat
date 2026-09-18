package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Packets.ClientTickEnd;
import dev.phantom.ac.Packets.ChunkStates;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.PlayerContext;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;

class Phase8IncrementalRunnerTest {
  private static WorldSnapshot floorWorld() {
    var stone = dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of());
    var builder=WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0,0);
    for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) builder.setBlock(x,63,z,stone);
    return builder.build();
  }

  private static Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> floorStates() {
    var stone=dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of());
    Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> out=new LinkedHashMap<>();
    for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) out.put(new dev.phantom.ac.world.Pos(x,63,z),stone);
    return Map.copyOf(out);
  }

  private static Player anchor() {
    return new Player(new Maths.Vec3(.5,64,.5),Maths.Vec3.ZERO,0f,0f,true,"survival",Map.of(),
        OptionalInt.empty(),false,Optional.empty(),Simulation.Attributes.DEFAULT,Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY,State.TickRange.unknown(),State.Provenance.UNKNOWN,Set.of());
  }

  @Test
  void firstImpossibleMovementPersistsWithoutReplayingOldPackets() {
    Phase8IncrementalRunner runner=new Phase8IncrementalRunner(4096,0);
    Player anchor=anchor();
    List<RawPacket> first=List.of(
        new RawPacket(1,20,new PlayerContext("survival",Simulation.Attributes.DEFAULT,Map.of(),
            Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),false,List.of())),
        new RawPacket(2,30,new ClientTickEnd()),
        new RawPacket(3,60,new Move(new Maths.Vec3(.5,65,.5),0f,0f,false,null))
    );
    var firstReport=runner.process("flight",first,floorWorld(),anchor);
    assertEquals(1,firstReport.movementObservations(),firstReport.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE,firstReport.results().getFirst().verdict(),firstReport.results().toString());
    assertEquals(Phase8IncrementalRunner.Continuation.IMPOSSIBLE,firstReport.continuation());
    assertEquals(3,firstReport.lastProcessedSequence());

    List<RawPacket> second=List.of(
        new RawPacket(4,110,new ClientTickEnd()),
        new RawPacket(5,160,new Move(new Maths.Vec3(.5,65,.5),0f,0f,false,null))
    );
    var secondReport=runner.process("flight",second,floorWorld(),anchor);
    assertEquals(1,secondReport.movementObservations(),secondReport.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE,secondReport.results().getFirst().verdict(),secondReport.results().toString());
    assertEquals(5,secondReport.lastProcessedSequence());
  }

  @Test
  void remoteWorldMutationDoesNotPoisonMovementValidation() {
    Phase8IncrementalRunner runner=new Phase8IncrementalRunner(4096,0);
    Player anchor=anchor();
    List<RawPacket> raw=List.of(
        new RawPacket(1,10,new PlayerContext("survival",Simulation.Attributes.DEFAULT,Map.of(),
            Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),false,List.of())),
        new RawPacket(2,20,new ClientTickEnd()),
        new RawPacket(3,60,new Move(new Maths.Vec3(.5,64,.5),0f,0f,true,null)),
        new RawPacket(4,70,new Packets.BlockChange(new World.Pos(1000,63,1000),World.Block.FULL))
    );
    var report=runner.process("remote-world",raw,floorWorld(),anchor);
    assertEquals(1,report.movementObservations(),report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,report.results().getFirst().verdict(),report.results().toString());
    assertEquals(Phase8IncrementalRunner.Continuation.ACTIVE,report.continuation());
  }

  @Test
  void authoritativeGroundContradictionProducesHardEvidenceDuringReplayUncertainty() {
    Phase8IncrementalRunner runner=new Phase8IncrementalRunner(4096,0);
    Player anchor=new Player(new Maths.Vec3(.5,65,.5),Maths.Vec3.ZERO,0f,0f,false,"survival",Map.of(),
        OptionalInt.empty(),false,Optional.empty(),Simulation.Attributes.DEFAULT,Phase5Mechanics.Pose.STANDING,
        State.Environment.DRY,State.TickRange.unknown(),State.Provenance.UNKNOWN,Set.of());
    List<RawPacket> raw=List.of(
        new RawPacket(1,10,new PlayerContext("survival",Simulation.Attributes.DEFAULT,Map.of(),
            Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(false,false,false),false,List.of())),
        new RawPacket(2,20,new ClientTickEnd()),
        new RawPacket(3,60,new Move(new Maths.Vec3(.5,65,.5),0f,0f,true,null)),
        new RawPacket(4,110,new ClientTickEnd()),
        new RawPacket(5,160,new Move(new Maths.Vec3(.5,65,.5),0f,0f,true,null)),
        new RawPacket(6,210,new ClientTickEnd()),
        new RawPacket(7,260,new Move(new Maths.Vec3(.5,65,.5),0f,0f,true,null))
    );
    var report=runner.process("ground-contradiction",raw,WorldSnapshot.builder(Contracts.TARGET_VERSION).build(),anchor);
    assertTrue(report.results().stream().anyMatch(result ->
        result.verdict()==Phase8MovementValidation.Verdict.IMPOSSIBLE
            && result.evidence().rule().equals("AUTHORITATIVE_GROUND_CONTRADICTION")),
        report.results().toString());
  }

  @Test
  void sameTickMovementIsUncertainButDoesNotPoisonLaterTicks() {
    Phase8IncrementalRunner runner=new Phase8IncrementalRunner(4096,0);
    Player anchor=anchor();
    List<RawPacket> raw=List.of(
        new RawPacket(1,10,new PlayerContext("survival",Simulation.Attributes.DEFAULT,Map.of(),
            Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(true,false,false),false,List.of())),
        new RawPacket(2,20,new ClientTickEnd()),
        new RawPacket(3,60,new Move(new Maths.Vec3(.5,64,.5),0f,0f,true,null)),
        new RawPacket(4,65,new Move(new Maths.Vec3(.6,64,.5),15f,0f,true,null))
    );
    var report=runner.process("subtick",raw,floorWorld(),anchor);
    assertEquals(2,report.movementObservations(),report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.POSSIBLE,report.results().getFirst().verdict(),report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.UNCERTAIN,report.results().get(1).verdict(),report.results().toString());
    assertEquals(Phase8IncrementalRunner.Continuation.ACTIVE,report.continuation());

    List<RawPacket> later=List.of(
        new RawPacket(5,120,new ClientTickEnd()),
        new RawPacket(6,160,new Move(new Maths.Vec3(5.0,64,.5),0f,0f,true,null))
    );
    var laterReport=runner.process("subtick",later,floorWorld(),anchor);
    assertEquals(1,laterReport.movementObservations(),laterReport.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.IMPOSSIBLE,laterReport.results().getFirst().verdict(),laterReport.results().toString());
    assertEquals(Phase8IncrementalRunner.Continuation.IMPOSSIBLE,laterReport.continuation());
  }

  @Test
  void futureWorldMutationCannotValidateEarlierMovementAgainstFutureState(){
    Phase8IncrementalRunner runner=new Phase8IncrementalRunner(4096,0);
    Player anchor=anchor();
    List<RawPacket> raw=List.of(
        new RawPacket(1,10,new ClientTickEnd()),
        new RawPacket(2,60,new Move(new Maths.Vec3(.5,64,.5),0f,0f,true,null)),
        new RawPacket(3,70,new Packets.BlockChange(new World.Pos(0,63,0),World.Block.FULL))
    );
    var report=runner.process("future-world",raw,floorWorld(),anchor);
    assertEquals(1,report.movementObservations(),report.results().toString());
    assertEquals(Phase8MovementValidation.Verdict.UNCERTAIN,report.results().getFirst().verdict(),report.results().toString());
    assertEquals(Phase8IncrementalRunner.Continuation.UNCERTAIN,report.continuation());
  }

  @Test
  void replayingTheSameInputDoesNotReprocessAlreadySeenPackets() {
    Phase8IncrementalRunner runner=new Phase8IncrementalRunner(4096,0);
    Player anchor=anchor();
    List<RawPacket> raw=List.of(
        new RawPacket(1,10,new ClientInput(true,false,false,false,false,false,false)),
        new RawPacket(2,20,new ClientTickEnd()));
    var first=runner.process("duplicate-call",raw,floorWorld(),anchor);
    var second=runner.process("duplicate-call",raw,floorWorld(),anchor);
    assertEquals(2,first.packetsProcessed());
    assertEquals(0,second.packetsProcessed());
    assertEquals(first.lastProcessedSequence(),second.lastProcessedSequence());
  }
}
