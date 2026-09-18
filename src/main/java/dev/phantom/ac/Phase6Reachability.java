package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.*;
import java.util.function.LongFunction;

/** Sound Phase 6 finite reachable-state search over the complete Phase 5 context. */
public final class Phase6Reachability {
  public static final int MAX_HORIZON_TICKS=512, MAX_TIMING_OFFSETS=128, MAX_PROVENANCE_PARENTS=8;
  public enum Verdict { POSSIBLE, UNCERTAIN, IMPOSSIBLE }
  public enum UncertainDimension { POSITION, ROTATION, VELOCITY, GROUND, INPUT, ENVIRONMENT, ATTRIBUTES, EFFECTS, POSE, TELEPORT, WORLD, TIMING }

  public record Context(long simulationTick,Player player,Simulation.Environment environment,Simulation.Attributes attributes,
                        MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping,
                        EntityCollisions entityCollisions,Set<UncertainDimension> uncertainty) implements Serializable {
    public Context(long tick,Player player,Simulation.Environment env,Simulation.Attributes attributes,MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping){this(tick,player,env,attributes,effects,pose,movementEnvironment,sleeping,EntityCollisions.of(List.of()),Set.of());}
    public Context(long tick,Player player,Simulation.Environment env,Simulation.Attributes attributes,MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping,EntityCollisions entityCollisions){this(tick,player,env,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,Set.of());}
    public Context {if(simulationTick<0)throw new IllegalArgumentException("simulationTick must be non-negative");Objects.requireNonNull(player);Objects.requireNonNull(environment);Objects.requireNonNull(attributes);Objects.requireNonNull(effects);Objects.requireNonNull(pose);Objects.requireNonNull(movementEnvironment);Objects.requireNonNull(entityCollisions);uncertainty=Set.copyOf(uncertainty);}
    public Context withTick(long tick){return new Context(tick,player,environment,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,uncertainty);}
    public Context withUncertainty(UncertainDimension... dimensions){EnumSet<UncertainDimension> u=EnumSet.noneOf(UncertainDimension.class);u.addAll(uncertainty);u.addAll(List.of(dimensions));return new Context(simulationTick,player,environment,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,u);}
  }

  public record InputConstraint(OptionalInt forward,OptionalInt strafe,Optional<Boolean> jump,Optional<Boolean> sprint,Optional<Boolean> sneak) implements Serializable {
    public InputConstraint {Objects.requireNonNull(forward);Objects.requireNonNull(strafe);Objects.requireNonNull(jump);Objects.requireNonNull(sprint);Objects.requireNonNull(sneak);if(forward.isPresent()&&Math.abs(forward.getAsInt())>1)throw new IllegalArgumentException("forward must be -1..1");if(strafe.isPresent()&&Math.abs(strafe.getAsInt())>1)throw new IllegalArgumentException("strafe must be -1..1");}
    public static InputConstraint any(){return new InputConstraint(OptionalInt.empty(),OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty());}
    public static InputConstraint exact(AdvancedInput i){Objects.requireNonNull(i);return new InputConstraint(OptionalInt.of(i.forward()),OptionalInt.of(i.strafe()),Optional.of(i.jump()),Optional.of(i.sprint()),Optional.of(i.sneak()));}
    public static InputConstraint fromClientInput(Packets.ClientInput i){Objects.requireNonNull(i);return new InputConstraint(OptionalInt.of(axis(i.forward(),i.backward())),OptionalInt.of(axis(i.right(),i.left())),Optional.of(i.jump()),Optional.of(i.sprint()),Optional.of(i.sneak()));}
    public List<AdvancedInput> enumerate(){List<AdvancedInput> out=new ArrayList<>();for(AdvancedInput i:Validation.allInputs())if(matches(i))out.add(i);return List.copyOf(out);}
    private boolean matches(AdvancedInput i){return(!forward.isPresent()||forward.getAsInt()==i.forward())&&(!strafe.isPresent()||strafe.getAsInt()==i.strafe())&&(!jump.isPresent()||jump.get()==i.jump())&&(!sprint.isPresent()||sprint.get()==i.sprint())&&(!sneak.isPresent()||sneak.get()==i.sneak());}
    private static int axis(boolean p,boolean n){return p==n?0:p?1:-1;}
  }

  public record WorldBranch(String id,WorldSnapshot world,boolean exhaustive,String description) implements Serializable {public WorldBranch{if(id==null||id.isBlank())throw new IllegalArgumentException("world branch id is required");Objects.requireNonNull(world);if(description==null||description.isBlank())throw new IllegalArgumentException("description is required");}}
  public sealed interface ExternalTransition extends Serializable permits None,VelocityImpulse,TeleportCorrection,TeleportConfirmation{}
  public record None() implements ExternalTransition{}
  public record VelocityImpulse(Maths.Vec3 impulse,String source) implements ExternalTransition{public VelocityImpulse{Objects.requireNonNull(impulse);if(source==null||source.isBlank())throw new IllegalArgumentException("source is required");}}
  public record TeleportCorrection(int id,Maths.Vec3 position,Maths.Vec3 velocity,Pose pose,boolean awaitingConfirmation,Float yaw,Float pitch) implements ExternalTransition{
    public TeleportCorrection(int id,Maths.Vec3 position,Maths.Vec3 velocity,Pose pose,boolean awaitingConfirmation){
      this(id,position,velocity,pose,awaitingConfirmation,null,null);
    }
    public TeleportCorrection{
      if(id<0)throw new IllegalArgumentException("teleport id must be non-negative");
      Objects.requireNonNull(position);Objects.requireNonNull(velocity);Objects.requireNonNull(pose);
      if(yaw!=null&&!Float.isFinite(yaw))throw new IllegalArgumentException("teleport yaw must be finite");
      if(pitch!=null&&!Float.isFinite(pitch))throw new IllegalArgumentException("teleport pitch must be finite");
    }
  }
  public record TeleportConfirmation(int id) implements ExternalTransition{public TeleportConfirmation{if(id<0)throw new IllegalArgumentException("teleport id must be non-negative");}}
  public record Provenance(long candidateId,long parentId,long tick,String input,String worldBranch,String externalTransition,List<String> causes,int mergedPathCount,List<Long> mergedParentIds) implements Serializable{public Provenance{if(candidateId<0||parentId<-1||tick<0||mergedPathCount<1)throw new IllegalArgumentException("invalid provenance");Objects.requireNonNull(input);Objects.requireNonNull(worldBranch);Objects.requireNonNull(externalTransition);causes=List.copyOf(causes);mergedParentIds=List.copyOf(mergedParentIds);}}
  public record Candidate(long id,Context context,Provenance provenance) implements Serializable{public Candidate{if(id<0)throw new IllegalArgumentException("candidate id must be non-negative");Objects.requireNonNull(context);Objects.requireNonNull(provenance);}}
  public record SearchResult(Verdict verdict,Set<Candidate> candidates,int simulatedTicks,int peakCandidates,int mergedStates,int nonExhaustiveWorldBranches,int uncertainTransitions,int provenanceMerges,List<String> reasons) implements Serializable{public SearchResult{candidates=Set.copyOf(candidates);reasons=List.copyOf(reasons);}}
  public record TimingSearchResult(Verdict verdict,Set<Candidate> candidates,Map<Long,SearchResult> byFirstTick,int evaluatedOffsets,int skippedOffsets,List<String> reasons) implements Serializable{public TimingSearchResult{candidates=Set.copyOf(candidates);byFirstTick=Map.copyOf(byFirstTick);reasons=List.copyOf(reasons);}}
  public enum ObservedField{POSITION,VELOCITY,ROTATION,GROUND,GAMEMODE,EFFECTS,TELEPORT_PENDING}
  public record Observation(Player observed,Set<ObservedField> known) implements Serializable{public Observation{Objects.requireNonNull(observed);known=Set.copyOf(known);if(known.isEmpty())throw new IllegalArgumentException("known observations required");}}
  public record Evidence(Verdict verdict,int matchingCandidates,List<Provenance> witnesses,List<String> reasons) implements Serializable{public Evidence{witnesses=List.copyOf(witnesses);reasons=List.copyOf(reasons);}}

  private final Vanilla12111RichPhysics physics;
  public Phase6Reachability(Vanilla12111RichPhysics physics){this.physics=Objects.requireNonNull(physics);}

  public SearchResult search(Context start,List<InputConstraint> inputs,LongFunction<List<WorldBranch>> worlds,LongFunction<List<ExternalTransition>> externalTransitions,int maximumCandidates){
    Contracts.requireCandidateBudget(maximumCandidates);Objects.requireNonNull(start);Objects.requireNonNull(inputs);Objects.requireNonNull(worlds);Objects.requireNonNull(externalTransitions);
    if(inputs.size()>MAX_HORIZON_TICKS)return uncertain(0,1,"simulation horizon exceeds the finite Phase 6 envelope");
    if(start.player().uncertain()||!start.uncertainty().isEmpty())return uncertain(0,1,"initial state carries explicit uncertainty dimensions: "+start.uncertainty());
    Map<Context,Candidate> current=new LinkedHashMap<>();current.put(start,new Candidate(0,start,new Provenance(0,-1,start.simulationTick(),"ROOT","ROOT","None",List.of("initial replay anchor"),1,List.of())));
    long nextId=1;int peak=1,merged=0,nonExhaustive=0,uncertainTransitions=0,provenanceMerges=0;String firstCoverageIssue=null;
    for(int offset=0;offset<inputs.size();offset++){
      long tick=start.simulationTick()+offset;List<AdvancedInput> allowed=inputs.get(offset).enumerate();if(allowed.isEmpty())return uncertain(offset,peak,"input constraint has no realizable advanced input");
      List<WorldBranch> branches=Objects.requireNonNull(worlds.apply(tick),"world branches");if(branches.isEmpty())return uncertain(offset,peak,"world hypothesis envelope is empty at tick "+tick);if(branches.stream().anyMatch(b->!b.exhaustive()))nonExhaustive++;
      List<ExternalTransition> external=Objects.requireNonNull(externalTransitions.apply(tick),"external transitions");if(external.isEmpty())external=List.of(new None());
      Map<Context,Candidate> next=new LinkedHashMap<>();
      for(Candidate parent:current.values())for(WorldBranch branch:branches){Maths.Aabb pb=Maths.Aabb.playerAt(parent.context().player().position(),parent.context().pose());Set<dev.phantom.ac.world.Coverage> coverage=branch.world().coverageIn(new dev.phantom.ac.geometry.BlockBox(pb.minX(),pb.minY(),pb.minZ(),pb.maxX(),pb.maxY(),pb.maxZ()));if(!coverage.equals(Set.of(dev.phantom.ac.world.Coverage.KNOWN))){uncertainTransitions++;if(firstCoverageIssue==null)firstCoverageIssue="coverage="+coverage+" candidateAabb="+pb;continue;}
        Context pre=parent.context().withTick(tick);
        boolean externalUncertain=false;
        for(ExternalTransition event:external){pre=applyExternal(pre,event,tick);if(pre.player().uncertain()){externalUncertain=true;break;}}
        if(externalUncertain){uncertainTransitions++;continue;}
        for(AdvancedInput input:allowed){
          MovementEnvironment env=inferEnvironment(branch.world(),pre.player(),input);
          Simulation.Environment simulationEnvironment=environmentFor(env);
          Vanilla12111RichPhysics.Context rc=new Vanilla12111RichPhysics.Context(tick,pre.player(),input,branch.world(),
              simulationEnvironment,pre.attributes(),pre.effects(),pre.pose(),env,pre.sleeping(),pre.entityCollisions());
          Vanilla12111RichPhysics.StepResult stepped=physics.step(rc);
          if(stepped.state().uncertain()){uncertainTransitions++;continue;}
          MovementEnvironment nextEnvironment=inferEnvironment(branch.world(),stepped.state(),input);
          Pose nextPose=Phase5Mechanics.nextPose(pre.pose(),nextEnvironment,pre.sleeping());
          Context after=new Context(tick+1,stepped.state(),environmentFor(nextEnvironment),pre.attributes(),pre.effects(),nextPose,
              nextEnvironment,pre.sleeping(),pre.entityCollisions(),pre.uncertainty());Candidate existing=next.get(after);
            if(existing==null){long id=nextId++;next.put(after,new Candidate(id,after,new Provenance(id,parent.id(),tick,input.toString(),branch.id(),external.toString(),List.of(stepped.diagnostic()),1,List.of(parent.id()))));}
            else{merged++;provenanceMerges++;Provenance old=existing.provenance();List<Long> ps=new ArrayList<>(old.mergedParentIds());if(!ps.contains(parent.id())&&ps.size()<MAX_PROVENANCE_PARENTS)ps.add(parent.id());int paths=old.mergedPathCount()==Integer.MAX_VALUE?Integer.MAX_VALUE:old.mergedPathCount()+1;next.put(after,new Candidate(existing.id(),existing.context(),new Provenance(existing.id(),old.parentId(),old.tick(),old.input(),old.worldBranch(),old.externalTransition(),old.causes(),paths,ps)));}
            if(next.size()>maximumCandidates)return uncertain(offset+1,Math.max(peak,next.size()),"candidate budget exceeded; no provisional subset is exposed");
          }
      }
      if(next.isEmpty()){String reason="all transitions became uncertain or were eliminated by incomplete world coverage";if(firstCoverageIssue!=null)reason+="; first observed coverage issue: "+firstCoverageIssue;return new SearchResult(Verdict.UNCERTAIN,Set.of(),offset+1,Math.max(peak,1),merged,nonExhaustive,uncertainTransitions,provenanceMerges,List.of(reason));}
      peak=Math.max(peak,next.size());current=next;if(nonExhaustive>0)return new SearchResult(Verdict.UNCERTAIN,Set.of(),offset+1,peak,merged,nonExhaustive,uncertainTransitions,provenanceMerges,List.of("world hypothesis envelope is not exhaustive at tick "+tick));
    }
    return new SearchResult(Verdict.POSSIBLE,Set.copyOf(current.values()),inputs.size(),peak,merged,nonExhaustive,uncertainTransitions,provenanceMerges,List.of("exhaustive finite search completed with exact full-context merging"));
  }

  public TimingSearchResult searchWithinTimingWindow(Context start,long earliestTick,long latestTick,boolean timingUncertain,List<InputConstraint> inputs,LongFunction<List<WorldBranch>> worlds,LongFunction<List<ExternalTransition>> externalTransitions,int maximumCandidates){
    if(earliestTick<0||latestTick<earliestTick)throw new IllegalArgumentException("invalid timing window");
    long span=latestTick-earliestTick+1;
    if(span>MAX_TIMING_OFFSETS)return new TimingSearchResult(Verdict.UNCERTAIN,Set.of(),Map.of(),0,(int)Math.min(Integer.MAX_VALUE,span),List.of("timing window exceeds exhaustive offset envelope"));
    Map<Long,SearchResult> results=new LinkedHashMap<>();
    Map<Context,Candidate> union=new LinkedHashMap<>();
    LinkedHashSet<String> reasons=new LinkedHashSet<>();
    boolean incompleteOffset=false;
    int evaluated=0;
    for(long tick=earliestTick;tick<=latestTick;tick++){
      SearchResult r=search(start.withTick(tick),inputs,worlds,externalTransitions,maximumCandidates);
      results.put(tick,r);
      evaluated++;
      if(r.verdict()!=Verdict.POSSIBLE){incompleteOffset=true;reasons.addAll(r.reasons());}
      else for(Candidate c:r.candidates())union.put(c.context(),c);
      if(union.size()>maximumCandidates)return new TimingSearchResult(Verdict.UNCERTAIN,Set.of(),results,evaluated,(int)span-evaluated,List.of("combined candidate budget exceeded across timing offsets"));
    }
    if(incompleteOffset){reasons.add("at least one client-tick offset was not exhaustively representable; candidates from fully represented offsets were retained");return new TimingSearchResult(Verdict.UNCERTAIN,Set.copyOf(union.values()),results,evaluated,0,List.copyOf(reasons));}
    if(timingUncertain)return new TimingSearchResult(Verdict.UNCERTAIN,Set.copyOf(union.values()),results,evaluated,0,List.of("all timing offsets were simulated but synchronization remains ambiguous"));
    return new TimingSearchResult(Verdict.POSSIBLE,Set.copyOf(union.values()),results,evaluated,0,List.of("all client-tick offsets were exhaustively simulated"));
  }

  public Evidence compare(SearchResult result,Observation observation){if(result.verdict()==Verdict.UNCERTAIN)return new Evidence(Verdict.UNCERTAIN,0,List.of(),result.reasons());List<Provenance> matches=new ArrayList<>();for(Candidate c:result.candidates())if(matches(c.context().player(),observation))matches.add(c.provenance());if(!matches.isEmpty())return new Evidence(Verdict.POSSIBLE,matches.size(),matches,List.of("observed facts are reachable","candidate provenance is retained"));return new Evidence(Verdict.IMPOSSIBLE,0,List.of(),List.of("no exact candidate matches the declared observed facts","all declared branches were exhausted"));}
  private static boolean matches(Player c,Observation o){Player x=o.observed();for(ObservedField f:o.known())switch(f){case POSITION->{if(!c.position().equals(x.position()))return false;}case VELOCITY->{if(!c.velocity().equals(x.velocity()))return false;}case ROTATION->{if(Float.compare(c.yaw(),x.yaw())!=0||Float.compare(c.pitch(),x.pitch())!=0)return false;}case GROUND->{if(c.onGround()!=x.onGround())return false;}case GAMEMODE->{if(!c.gamemode().equals(x.gamemode()))return false;}case EFFECTS->{if(!c.effects().equals(x.effects()))return false;}case TELEPORT_PENDING->{if(c.awaitingTeleport().isPresent()!=x.awaitingTeleport().isPresent())return false;}}return true;}
  private static Context applyExternal(Context c,ExternalTransition e,long tick){Player s=c.player();if(e instanceof None)return c.withTick(tick);if(e instanceof VelocityImpulse v){Player n=Phase5Mechanics.applyVelocityImpulse(s,new Phase5Mechanics.Vec3Like(v.impulse().x(),v.impulse().y(),v.impulse().z()));return new Context(tick,n,c.environment(),c.attributes(),c.effects(),c.pose(),c.movementEnvironment(),c.sleeping(),c.entityCollisions(),c.uncertainty());}if(e instanceof TeleportCorrection t){OptionalInt p=t.awaitingConfirmation()?OptionalInt.of(t.id()):OptionalInt.empty();float yaw=t.yaw()==null?s.yaw():t.yaw();float pitch=t.pitch()==null?s.pitch():t.pitch();Player n=new Player(t.position(),t.velocity(),yaw,pitch,false,s.gamemode(),s.effects(),p,false);return new Context(tick,n,c.environment(),c.attributes(),c.effects(),t.pose(),c.movementEnvironment(),c.sleeping(),c.entityCollisions(),c.uncertainty());}if(e instanceof TeleportConfirmation t){boolean ok=s.awaitingTeleport().isPresent()&&s.awaitingTeleport().getAsInt()==t.id();Player n=new Player(s.position(),s.velocity(),s.yaw(),s.pitch(),s.onGround(),s.gamemode(),s.effects(),ok?OptionalInt.empty():s.awaitingTeleport(),s.uncertain()||!ok);return new Context(tick,n,c.environment(),c.attributes(),c.effects(),c.pose(),c.movementEnvironment(),c.sleeping(),c.entityCollisions(),c.uncertainty());}throw new IllegalStateException("unhandled external transition "+e.getClass());}
  private static MovementEnvironment inferEnvironment(WorldSnapshot world,Player player,AdvancedInput input){
    Maths.Aabb box=Maths.Aabb.playerAt(player.position(),player.pose());
    int minX=(int)Math.floor(box.minX()),maxX=(int)Math.floor(Math.nextDown(box.maxX()));
    int minY=(int)Math.floor(box.minY()),maxY=(int)Math.floor(Math.nextDown(box.maxY()));
    int minZ=(int)Math.floor(box.minZ()),maxZ=(int)Math.floor(Math.nextDown(box.maxZ()));
    boolean water=false,lava=false,climb=false;
    for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
      dev.phantom.ac.world.BlockState state=world.blockAtOrNull(x,y,z);
      if(state==null)continue;
      if(state.variant()==dev.phantom.ac.world.BlockState.Variant.LADDER)climb=true;
      var fluid=dev.phantom.ac.world.v12111.BlockCatalogue12111.fluid(state);
      if(fluid.type()==dev.phantom.ac.world.FluidState.Type.WATER)water=true;
      if(fluid.type()==dev.phantom.ac.world.FluidState.Type.LAVA)lava=true;
    }
    boolean sprint=input.sprint(),sneak=input.sneak(),swim=player.pose()==Pose.SWIMMING;
    if(water)return MovementEnvironment.vanillaWater(player.onGround(),sprint,sneak,swim);
    if(lava)return MovementEnvironment.vanillaLava(player.onGround(),sprint,sneak);
    if(climb)return MovementEnvironment.vanillaClimbable(player.onGround(),sprint,sneak);
    return MovementEnvironment.dry(player.onGround(),sprint,sneak);
  }
  private static Simulation.Environment environmentFor(MovementEnvironment env){
    if(env.fluid()==Phase5Mechanics.Fluid.WATER)return Simulation.Environment.WATER;
    if(env.fluid()==Phase5Mechanics.Fluid.LAVA)return Simulation.Environment.LAVA;
    if(env.climbable())return Simulation.Environment.CLIMBABLE;
    return Simulation.Environment.DRY;
  }
  private static SearchResult uncertain(int ticks,int peak,String reason){return new SearchResult(Verdict.UNCERTAIN,Set.of(),ticks,peak,0,0,0,0,List.of(reason));}
}
