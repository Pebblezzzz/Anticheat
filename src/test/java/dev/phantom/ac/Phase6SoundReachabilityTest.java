package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.Observation;
import dev.phantom.ac.Phase6Reachability.ObservedField;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.Verdict;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Direct soundness, ambiguity and regression coverage for the Phase 6 engine. */
class Phase6SoundReachabilityTest {
  private static WorldSnapshot floorWorld(){
    var builder=WorldSnapshot.builder(Contracts.TARGET_VERSION).loadChunk(0,0);
    for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)builder.setBlock(x,64,z,dev.phantom.ac.world.v12111.BlockCatalogue12111.decode("minecraft:stone",Map.of()));
    return builder.build();
  }
  private static final WorldSnapshot FLOOR=floorWorld();
  private static final Phase6Reachability ENGINE=new Phase6Reachability(new Vanilla12111RichPhysics());
  private static Phase6Reachability.Context start(){Player p=Player.initial(new Maths.Vec3(0.5,65.0,0.5));return new Phase6Reachability.Context(0,p,Simulation.Environment.DRY,Attributes.DEFAULT,MovementEffects.NONE,Pose.STANDING,MovementEnvironment.dry(true,false,false),false);}
  private static SearchResult search(InputConstraint input){return ENGINE.search(start(),List.of(input),t->List.of(new WorldBranch("floor",FLOOR,true,"known loaded floor")),t->List.of(new Phase6Reachability.None()),1000);}
  @Test void fullAdvancedInputEnvelopeIsExactly72(){assertEquals(72,Validation.allInputs().size());assertEquals(72,InputConstraint.any().enumerate().size());}
  @Test void partialInputBranchesOnlyTheUnspecifiedDimensions(){assertEquals(36,new InputConstraint(OptionalInt.empty(),OptionalInt.empty(),Optional.empty(),Optional.of(true),Optional.empty()).enumerate().size());}
  @Test void fullCandidateContextKeepsSprintAndSneakAsDistinctFutureState(){SearchResult r=search(InputConstraint.any());assertEquals(Verdict.POSSIBLE,r.verdict());assertEquals(72,r.candidates().size());}
  @Test void exactStateMergeIsSafeAndTracksProvenance(){SearchResult r=ENGINE.search(start(),List.of(InputConstraint.exact(new AdvancedInput(0,0,false,false,false))),t->List.of(new WorldBranch("a",FLOOR,true,"same world"),new WorldBranch("b",FLOOR,true,"same world duplicate")),t->List.of(new Phase6Reachability.None()),1000);assertEquals(Verdict.POSSIBLE,r.verdict());assertTrue(r.mergedStates()>0);assertTrue(r.provenanceMerges()>0);Candidate c=r.candidates().iterator().next();assertTrue(c.provenance().mergedPathCount()>1);assertFalse(c.provenance().mergedParentIds().isEmpty());}
  @Test void candidateBudgetOverflowNeverExposesASampledSubset(){SearchResult limited=ENGINE.search(start(),List.of(InputConstraint.any()),t->List.of(new WorldBranch("floor",FLOOR,true,"known loaded floor")),t->List.of(new Phase6Reachability.None()),10);assertEquals(Verdict.UNCERTAIN,limited.verdict());assertTrue(limited.candidates().isEmpty());}
  @Test void unknownWorldCoverageIsUncertain(){WorldSnapshot unknown=WorldSnapshot.emptyOverworld12111();SearchResult r=ENGINE.search(start(),List.of(InputConstraint.exact(new AdvancedInput(0,0,false,false,false))),t->List.of(new WorldBranch("unknown",unknown,true,"unloaded world")),t->List.of(new Phase6Reachability.None()),100);assertEquals(Verdict.UNCERTAIN,r.verdict());assertTrue(r.candidates().isEmpty());}
  @Test void nonExhaustiveWorldHypothesisIsUncertain(){SearchResult r=ENGINE.search(start(),List.of(InputConstraint.exact(new AdvancedInput(0,0,false,false,false))),t->List.of(new WorldBranch("partial",FLOOR,false,"only one of several possible world states")),t->List.of(new Phase6Reachability.None()),100);assertEquals(Verdict.UNCERTAIN,r.verdict());assertTrue(r.candidates().isEmpty());}
  @Test void velocityKnockbackTransitionIsIntegratedAsAReachableBranch(){SearchResult r=ENGINE.search(start(),List.of(InputConstraint.exact(new AdvancedInput(0,0,false,false,false))),t->List.of(new WorldBranch("floor",FLOOR,true,"known floor")),t->List.of(new Phase6Reachability.VelocityImpulse(new Maths.Vec3(0.4,0.2,0.0),"server knockback packet")),100);assertEquals(Verdict.POSSIBLE,r.verdict());assertTrue(r.candidates().iterator().next().provenance().externalTransition().contains("VelocityImpulse"));}
  @Test void impossibleObservationRequiresACompleteExhaustiveSearch(){SearchResult r=search(InputConstraint.exact(new AdvancedInput(0,0,false,false,false)));Observation o=new Observation(Player.initial(new Maths.Vec3(100,65,100)),EnumSet.of(ObservedField.POSITION));var e=ENGINE.compare(r,o);assertEquals(Verdict.IMPOSSIBLE,e.verdict());assertEquals(0,e.matchingCandidates());}
  @Test void reachableObservationProducesAProvenanceWitness(){SearchResult r=search(InputConstraint.exact(new AdvancedInput(0,0,false,false,false)));Candidate c=r.candidates().iterator().next();Observation o=new Observation(c.context().player(),EnumSet.of(ObservedField.POSITION,ObservedField.GROUND));var e=ENGINE.compare(r,o);assertEquals(Verdict.POSSIBLE,e.verdict());assertFalse(e.witnesses().isEmpty());}
  @Test void uncertainTimingIsNeverPromotedToImpossible(){var r=ENGINE.searchWithinTimingWindow(start(),0,1,true,List.of(InputConstraint.exact(new AdvancedInput(0,0,false,false,false))),t->List.of(new WorldBranch("floor",FLOOR,true,"known floor")),t->List.of(new Phase6Reachability.None()),100);assertEquals(Verdict.UNCERTAIN,r.verdict());}
  @Test void overwideTimingWindowDoesNotSampleOffsets(){var r=ENGINE.searchWithinTimingWindow(start(),0,128,false,List.of(InputConstraint.exact(new AdvancedInput(0,0,false,false,false))),t->List.of(new WorldBranch("floor",FLOOR,true,"known floor")),t->List.of(new Phase6Reachability.None()),100);assertEquals(Verdict.UNCERTAIN,r.verdict());assertEquals(0,r.evaluatedOffsets());assertEquals(129,r.skippedOffsets());}
  @Test void teleportConfirmationMustMatchThePendingCorrection(){SearchResult r=ENGINE.search(start(),List.of(InputConstraint.exact(new AdvancedInput(0,0,false,false,false))),t->List.of(new WorldBranch("floor",FLOOR,true,"known floor")),t->List.of(new Phase6Reachability.TeleportConfirmation(7)),100);assertEquals(Verdict.UNCERTAIN,r.verdict());}
  @Test void replayArtifactIsSelfContainedAndDeterministic(){SearchResult r=search(InputConstraint.exact(new AdvancedInput(0,0,false,false,false)));Phase6Replay replay=Phase6Replay.of(start(),List.of(InputConstraint.exact(new AdvancedInput(0,0,false,false,false))),0,0,false,Map.of(0L,List.of(new WorldBranch("floor",FLOOR,true,"known floor"))),Map.of(0L,List.of(new Phase6Reachability.None())),r);assertEquals(Phase6Replay.SCHEMA_VERSION,replay.schemaVersion());assertTrue(replay.canonicalText().startsWith(Phase6Replay.SCHEMA_VERSION));}
  @Test void performanceBenchmarkHandlesTheFullOneTickEnvelope(){Phase6PerformanceBenchmark.Result r=Phase6PerformanceBenchmark.benchmarkOneTickFullInputEnvelope();assertTrue(r.candidates()>0);assertTrue(r.nanos()<5_000_000_000L);}
}
