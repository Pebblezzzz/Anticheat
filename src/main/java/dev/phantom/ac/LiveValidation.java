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

/** Live integration of canonical timeline, Phase 7 timing, and the authoritative Phase 6 engine. */
public final class LiveValidation {
  private LiveValidation() {}
  public record Finding(long tick,Validation.Verdict verdict,int candidateCount,List<String> reasons){public Finding{reasons=List.copyOf(reasons);}}
  public record Report(List<Finding> findings,int movementObservations,int timelineEvents,int anchoredObservations,int possibleFindings,int uncertainFindings,int impossibleFindings){public Report{findings=List.copyOf(findings);}public Report(List<Finding> findings,int movementObservations){this(findings,movementObservations,0,0,0,0,0);}}

  public static Report analyze(Timeline.Snapshot timeline,int maximumCandidates){return analyze(timeline,maximumCandidates,Phase7Timing.Config.defaultConfig());}
  public static Report analyze(Timeline.Snapshot timeline,int maximumCandidates,Phase7Timing.Config timingConfig){
    Contracts.requireCandidateBudget(maximumCandidates);Objects.requireNonNull(timeline);Objects.requireNonNull(timingConfig);
    World.VisibilityHistory history=World.fromTimeline(timeline);
    Phase7Timing.Reconstruction timing=Phase7Timing.reconstruct(timeline,timingConfig);
    Phase6Reachability engine=new Phase6Reachability(new Vanilla12111RichPhysics());
    Map<Long,List<ExternalTransition>> externalByClientTick=externalTransitions(timeline,timing);
    Set<Candidate> candidates=Set.of();InputConstraint currentInput=InputConstraint.any();List<Finding> findings=new ArrayList<>();int movements=0,anchored=0;
    for(Timeline.Event event:timeline.events()){
      Packets.Packet packet=event.packet().packet();boolean duplicate=event.packet().flags().contains(Packets.PacketFlag.DUPLICATE);
      if(packet instanceof Packets.ClientInput input){if(!duplicate)currentInput=InputConstraint.fromClientInput(input);continue;}
      if(duplicate){findings.add(new Finding(event.serverTick(),Validation.Verdict.UNCERTAIN,candidates.size(),List.of("duplicate capture record retained as evidence; no semantic state advance performed")));continue;}
      if(!(packet instanceof Packets.Move move)||move.position()==null)continue;
      movements++;long serverTick=event.serverTick();WorldSnapshot world=history.statesAt(serverTick);Phase7Timing.EventTiming eventTiming=timing.timingFor(event.packet().sequence()).orElseThrow();
      if(candidates.isEmpty()){
        Player observed=Player.initial(move.position());Phase6Reachability.Context root=anchor(observed,world,currentInput,Math.max(0,eventTiming.simulationClientTicks().min()));
        Candidate c=new Candidate(0,root,new Phase6Reachability.Provenance(0,-1,serverTick,"ROOT","ROOT","None",List.of(anchored==0?"first movement observation establishes a replay anchor":"re-anchor after synchronization uncertainty"),1,List.of()));
        candidates=Set.of(c);anchored++;List<String> reasons=new ArrayList<>();reasons.add("first movement observation anchors analysis without inventing prior client state");reasons.addAll(eventTiming.reasons());findings.add(new Finding(serverTick,Validation.Verdict.UNCERTAIN,1,reasons));continue;
      }
      Player observed=State.apply(candidates.iterator().next().context().player(),event.packet());Set<Candidate> nextAll=new LinkedHashSet<>();Set<Candidate> matches=new LinkedHashSet<>();boolean uncertain=false;LinkedHashSet<String> reasons=new LinkedHashSet<>(eventTiming.reasons());
      Phase7Timing.Range timeRange=eventTiming.simulationClientTicks();long span=timeRange.max()-timeRange.min()+1;
      if(span>timingConfig.maxTimingCandidates()){findings.add(new Finding(serverTick,Validation.Verdict.UNCERTAIN,0,List.of("Phase 7 timing candidate count exceeds the configured bounded envelope")));continue;}
      boolean worldExhaustive=worldTimingExhaustiveBefore(timing,event.packet().sequence());
      for(Candidate parent:candidates){
        Phase6Reachability.Context prepared=withObservedEnvironment(parent.context(),world,currentInput,Math.max(0,timeRange.min()));
        Phase6Reachability.TimingSearchResult search=engine.searchWithinTimingWindow(prepared,timeRange.min(),timeRange.max(),eventTiming.uncertain()||timing.consistency()==Phase7Timing.Consistency.INCONSISTENT,List.of(currentInput),t->List.of(new WorldBranch("client-visible-"+serverTick,world,worldExhaustive,worldExhaustive?"historical client-visible world": "world timing uncertain; branch deliberately non-exhaustive")),t->externalByClientTick.getOrDefault(t,List.of(new Phase6Reachability.None())),maximumCandidates);
        if(search.verdict()!=Phase6Reachability.Verdict.POSSIBLE){uncertain=true;reasons.addAll(search.reasons());continue;}
        nextAll.addAll(search.candidates());if(nextAll.size()>maximumCandidates){uncertain=true;nextAll.clear();reasons.add("combined Phase 6 timing candidate budget exceeded; no provisional subset retained");break;}
      }
      if(!uncertain){Observation projection=new Observation(observed,EnumSet.of(ObservedField.POSITION,ObservedField.GROUND));for(Candidate candidate:nextAll){SearchResult singleton=new SearchResult(Phase6Reachability.Verdict.POSSIBLE,Set.of(candidate),1,1,0,0,0,0,List.of());if(engine.compare(singleton,projection).verdict()==Phase6Reachability.Verdict.POSSIBLE)matches.add(candidate);}}
      if(uncertain){findings.add(new Finding(serverTick,Validation.Verdict.UNCERTAIN,0,reasons.isEmpty()?List.of("Phase 7/6 timing synchronization is uncertain"):List.copyOf(reasons)));candidates=nextAll.isEmpty()?Set.of():Set.copyOf(nextAll);}
      else if(matches.isEmpty()){candidates=Set.copyOf(nextAll);findings.add(new Finding(serverTick,Validation.Verdict.IMPOSSIBLE,0,List.of("no exact reachable candidate matches the observed position and ground state","Phase 7 timing was exhaustively represented for this observation","complete finite candidate envelope is retained after divergence")));}
      else{candidates=Set.copyOf(matches);findings.add(new Finding(serverTick,Validation.Verdict.POSSIBLE,matches.size(),List.of("observed position and ground state are reachable under the Phase 7 timing envelope","candidate provenance was retained through exact state merging")));}
    }
    int possible=(int)findings.stream().filter(f->f.verdict()==Validation.Verdict.POSSIBLE).count();int uncertain=(int)findings.stream().filter(f->f.verdict()==Validation.Verdict.UNCERTAIN).count();int impossible=(int)findings.stream().filter(f->f.verdict()==Validation.Verdict.IMPOSSIBLE).count();return new Report(findings,movements,timeline.events().size(),anchored,possible,uncertain,impossible);
  }

  private static Map<Long,List<ExternalTransition>> externalTransitions(Timeline.Snapshot timeline,Phase7Timing.Reconstruction timing){
    Map<Long,List<ExternalTransition>> out=new HashMap<>();Set<Long> applied=new HashSet<>();Player state=Player.initial(Maths.Vec3.ZERO);
    for(Timeline.Event event:timeline.events()){
      if(event.packet().flags().contains(Packets.PacketFlag.DUPLICATE))continue;
      if(!applied.add(event.packet().sequence()))continue;
      Packets.Packet packet=event.packet().packet();Phase7Timing.EventTiming eventTiming=timing.timingFor(event.packet().sequence()).orElse(null);if(eventTiming==null)continue;Phase7Timing.Range range=eventTiming.simulationClientTicks();long span=range.max()-range.min()+1;if(span>timing.config().maxTimingCandidates())continue;
      ExternalTransition transition=null;
      if(packet instanceof Packets.Velocity v)transition=new Phase6Reachability.VelocityImpulse(v.velocity(),"server velocity packet");
      else if(packet instanceof Packets.Teleport t){Maths.Vec3 target=new Maths.Vec3(t.relativeX()?state.position().x()+t.position().x():t.position().x(),t.relativeY()?state.position().y()+t.position().y():t.position().y(),t.relativeZ()?state.position().z()+t.position().z():t.position().z());transition=new Phase6Reachability.TeleportCorrection(t.id(),target,Maths.Vec3.ZERO,Pose.STANDING,true);}
      else if(packet instanceof Packets.TeleportConfirm t)transition=new Phase6Reachability.TeleportConfirmation(t.id());
      if(transition!=null)for(long tick=range.min();tick<=range.max();tick++)out.computeIfAbsent(tick,k->new ArrayList<>()).add(transition);
      try{state=State.apply(state,event.packet());}catch(RuntimeException ignored){}
    }
    Map<Long,List<ExternalTransition>> frozen=new HashMap<>();for(Map.Entry<Long,List<ExternalTransition>> e:out.entrySet())frozen.put(e.getKey(),List.copyOf(e.getValue()));return Map.copyOf(frozen);
  }
  private static boolean worldTimingExhaustiveBefore(Phase7Timing.Reconstruction timing,long movementSequence){for(Phase7Timing.Frame frame:timing.frames()){if(frame.timing().sequence()>=movementSequence)break;if(frame.timing().kind()==Phase7Timing.EventKind.WORLD&&frame.timing().uncertain())return false;}return true;}
  private static Phase6Reachability.Context anchor(Player player,WorldSnapshot world,InputConstraint input,long tick){MovementEnvironment env=inferEnvironment(world,player,input);return new Phase6Reachability.Context(tick,player,environmentFor(env),Attributes.DEFAULT,MovementEffects.NONE,Pose.STANDING,env,false);}
  private static Phase6Reachability.Context withObservedEnvironment(Phase6Reachability.Context c,WorldSnapshot world,InputConstraint input,long tick){MovementEnvironment env=inferEnvironment(world,c.player(),input);return new Phase6Reachability.Context(tick,c.player(),environmentFor(env),c.attributes(),c.effects(),c.pose(),env,c.sleeping(),c.uncertainty());}
  private static Simulation.Environment environmentFor(MovementEnvironment env){if(env.fluid()==Phase5Mechanics.Fluid.WATER)return Simulation.Environment.WATER;if(env.fluid()==Phase5Mechanics.Fluid.LAVA)return Simulation.Environment.LAVA;if(env.climbable())return Simulation.Environment.CLIMBABLE;return Simulation.Environment.DRY;}
  private static MovementEnvironment inferEnvironment(WorldSnapshot world,Player player,InputConstraint input){int x=(int)Math.floor(player.position().x()),y=(int)Math.floor(player.position().y()),z=(int)Math.floor(player.position().z());boolean water=false,lava=false,climb=false;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){BlockState s=world.blockAtOrNull(x+dx,y+dy,z+dz);if(s==null)continue;if(s.variant()==BlockState.Variant.FLUID&&s.blockId().equals("minecraft:water"))water=true;if(s.variant()==BlockState.Variant.FLUID&&s.blockId().equals("minecraft:lava"))lava=true;if(s.variant()==BlockState.Variant.LADDER)climb=true;}boolean sprint=input.sprint().orElse(false),sneak=input.sneak().orElse(false),swim=input.jump().orElse(false);if(water)return MovementEnvironment.vanillaWater(player.onGround(),sprint,sneak,swim);if(lava)return MovementEnvironment.vanillaLava(player.onGround(),sprint,sneak);if(climb)return MovementEnvironment.vanillaClimbable(player.onGround(),sprint,sneak);return MovementEnvironment.dry(player.onGround(),sprint,sneak);}
}
