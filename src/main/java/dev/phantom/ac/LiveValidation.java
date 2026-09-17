package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.ExternalTransition;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.Observation;
import dev.phantom.ac.Phase6Reachability.ObservedField;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;

/** Live replay integration for the sound Phase 6 engine, retaining the legacy Validation.Verdict API. */
public final class LiveValidation {
  private LiveValidation() {}
  public record Finding(long tick,Validation.Verdict verdict,int candidateCount,List<String> reasons){public Finding{reasons=List.copyOf(reasons);}}
  public record Report(List<Finding> findings,int movementObservations,int timelineEvents,int anchoredObservations,int possibleFindings,int uncertainFindings,int impossibleFindings){public Report{findings=List.copyOf(findings);}public Report(List<Finding> findings,int movementObservations){this(findings,movementObservations,0,0,0,0,0);}}

  public static Report analyze(Timeline.Snapshot timeline,int maximumCandidates){
    Contracts.requireCandidateBudget(maximumCandidates);World.VisibilityHistory history=World.fromTimeline(timeline);Phase6Reachability engine=new Phase6Reachability(new Vanilla12111RichPhysics());Map<Long,List<ExternalTransition>> externalByTick=externalTransitions(timeline);
    Set<Candidate> candidates=Set.of();InputConstraint currentInput=InputConstraint.any();List<Finding> findings=new ArrayList<>();int movements=0,anchored=0;
    for(Timeline.Event event:timeline.events()){
      Packets.Packet packet=event.packet().packet();if(packet instanceof Packets.ClientInput input){currentInput=InputConstraint.fromClientInput(input);continue;}if(!(packet instanceof Packets.Move move)||move.position()==null)continue;
      movements++;long tick=event.serverTick();WorldSnapshot world=history.statesAt(tick);Player observed=anchored==0?Player.initial(move.position()):State.apply(candidates.iterator().next().context().player(),event.packet());
      if(anchored==0){Phase6Reachability.Context root=anchor(observed,world,currentInput,tick);Candidate c=new Candidate(0,root,new Phase6Reachability.Provenance(0,-1,tick,"ROOT","ROOT","None",List.of("first movement observation establishes a replay anchor"),1,List.of()));candidates=Set.of(c);anchored++;findings.add(new Finding(tick,Validation.Verdict.UNCERTAIN,1,List.of("first movement packet establishes the observed-state anchor; no prior client state is available")));continue;}
      if(candidates.isEmpty()){findings.add(new Finding(tick,Validation.Verdict.UNCERTAIN,0,List.of("prediction envelope was empty; observation re-anchors validation instead of producing a violation")));Phase6Reachability.Context root=anchor(observed,world,currentInput,tick);Candidate c=new Candidate(0,root,new Phase6Reachability.Provenance(0,-1,tick,"ROOT","ROOT","None",List.of("re-anchor after uncertain interval"),1,List.of()));candidates=Set.of(c);anchored++;continue;}

      Set<Candidate> nextAll=new LinkedHashSet<>();Set<Candidate> matches=new LinkedHashSet<>();boolean uncertain=false;LinkedHashSet<String> reasons=new LinkedHashSet<>();
      for(Candidate parent:candidates){Phase6Reachability.Context prepared=withObservedEnvironment(parent.context(),world,currentInput,tick);SearchResult result=engine.search(prepared,List.of(currentInput),t->List.of(new WorldBranch("client-visible-"+t,world,true,"World.VisibilityHistory at observed client tick")),t->externalByTick.getOrDefault(t,List.of(new Phase6Reachability.None())),maximumCandidates);if(result.verdict()==Phase6Reachability.Verdict.UNCERTAIN){uncertain=true;reasons.addAll(result.reasons());}else nextAll.addAll(result.candidates());}
      if(!uncertain){Observation projection=new Observation(observed,EnumSet.of(ObservedField.POSITION,ObservedField.GROUND));for(Candidate candidate:nextAll){SearchResult singleton=new SearchResult(Phase6Reachability.Verdict.POSSIBLE,Set.of(candidate),1,1,0,0,0,0,List.of());if(engine.compare(singleton,projection).verdict()==Phase6Reachability.Verdict.POSSIBLE)matches.add(candidate);}}
      if(uncertain){findings.add(new Finding(tick,Validation.Verdict.UNCERTAIN,0,reasons.isEmpty()?List.of("one or more branches became uncertain"):List.copyOf(reasons)));candidates=nextAll.isEmpty()?Set.of():Set.copyOf(nextAll);}
      else if(matches.isEmpty()){candidates=Set.copyOf(nextAll);findings.add(new Finding(tick,Validation.Verdict.IMPOSSIBLE,0,List.of("no exact reachable candidate matches the observed position and ground state","the complete candidate envelope is retained after divergence")));}
      else{candidates=Set.copyOf(matches);findings.add(new Finding(tick,Validation.Verdict.POSSIBLE,matches.size(),List.of("observed position and ground state are reachable","candidate provenance was retained through exact state merging")));}
    }
    int possible=(int)findings.stream().filter(f->f.verdict()==Validation.Verdict.POSSIBLE).count(),uncertain=(int)findings.stream().filter(f->f.verdict()==Validation.Verdict.UNCERTAIN).count(),impossible=(int)findings.stream().filter(f->f.verdict()==Validation.Verdict.IMPOSSIBLE).count();return new Report(findings,movements,timeline.events().size(),anchored,possible,uncertain,impossible);
  }

  private static Map<Long,List<ExternalTransition>> externalTransitions(Timeline.Snapshot timeline){Map<Long,List<ExternalTransition>> out=new HashMap<>();Player state=Player.initial(Maths.Vec3.ZERO);for(Timeline.Event event:timeline.events()){Packets.Packet p=event.packet().packet();if(p instanceof Packets.Velocity v)out.computeIfAbsent(event.serverTick(),k->new ArrayList<>()).add(new Phase6Reachability.VelocityImpulse(v.velocity(),"server velocity packet"));else if(p instanceof Packets.Teleport t){Maths.Vec3 target=new Maths.Vec3(t.relativeX()?state.position().x()+t.position().x():t.position().x(),t.relativeY()?state.position().y()+t.position().y():t.position().y(),t.relativeZ()?state.position().z()+t.position().z():t.position().z());out.computeIfAbsent(event.serverTick(),k->new ArrayList<>()).add(new Phase6Reachability.TeleportCorrection(t.id(),target,Maths.Vec3.ZERO,Pose.STANDING,true));}else if(p instanceof Packets.TeleportConfirm t)out.computeIfAbsent(event.serverTick(),k->new ArrayList<>()).add(new Phase6Reachability.TeleportConfirmation(t.id()));try{state=State.apply(state,event.packet());}catch(RuntimeException ignored){}}return out;}
  private static Phase6Reachability.Context anchor(Player player,WorldSnapshot world,InputConstraint input,long tick){MovementEnvironment env=inferEnvironment(world,player,input);return new Phase6Reachability.Context(tick,player,environmentFor(env),Attributes.DEFAULT,MovementEffects.NONE,Pose.STANDING,env,false);}
  private static Phase6Reachability.Context withObservedEnvironment(Phase6Reachability.Context c,WorldSnapshot world,InputConstraint input,long tick){MovementEnvironment env=inferEnvironment(world,c.player(),input);return new Phase6Reachability.Context(tick,c.player(),environmentFor(env),c.attributes(),c.effects(),c.pose(),env,c.sleeping(),c.uncertainty());}
  private static Simulation.Environment environmentFor(MovementEnvironment env){if(env.fluid()==Phase5Mechanics.Fluid.WATER)return Simulation.Environment.WATER;if(env.fluid()==Phase5Mechanics.Fluid.LAVA)return Simulation.Environment.LAVA;if(env.climbable())return Simulation.Environment.CLIMBABLE;return Simulation.Environment.DRY;}
  private static MovementEnvironment inferEnvironment(WorldSnapshot world,Player player,InputConstraint input){int x=(int)Math.floor(player.position().x()),y=(int)Math.floor(player.position().y()),z=(int)Math.floor(player.position().z());boolean water=false,lava=false,climb=false;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){BlockState s=world.blockAtOrNull(x+dx,y+dy,z+dz);if(s==null)continue;if(s.variant()==BlockState.Variant.FLUID&&s.blockId().equals("minecraft:water"))water=true;if(s.variant()==BlockState.Variant.FLUID&&s.blockId().equals("minecraft:lava"))lava=true;if(s.variant()==BlockState.Variant.LADDER)climb=true;}boolean sprint=input.sprint().orElse(false),sneak=input.sneak().orElse(false),swim=input.jump().orElse(false);if(water)return MovementEnvironment.vanillaWater(player.onGround(),sprint,sneak,swim);if(lava)return MovementEnvironment.vanillaLava(player.onGround(),sprint,sneak);if(climb)return MovementEnvironment.vanillaClimbable(player.onGround(),sprint,sneak);return MovementEnvironment.dry(player.onGround(),sprint,sneak);}
}
