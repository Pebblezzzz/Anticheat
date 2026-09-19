package dev.phantom.ac;

import dev.phantom.ac.world.WorldSnapshot;
import java.io.Serializable;
import java.util.*;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.State.*;

public final class Simulation {
  private Simulation() {}

  /** Compatibility adapter. All movement mechanics live in Vanilla12111RichPhysics. */
  public static final class Vanilla12111Physics implements Contracts.PhysicsEngine {
    public static final double GRAVITY=Vanilla12111RichPhysics.GRAVITY,AIR_DRAG=Vanilla12111RichPhysics.AIR_DRAG,AIR_HORIZONTAL_FRICTION=Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION,AIR_VERTICAL_DRAG=Vanilla12111RichPhysics.AIR_VERTICAL_DRAG,AIR_ACCEL=Vanilla12111RichPhysics.AIR_ACCEL,GROUND_FRICTION=Vanilla12111RichPhysics.GROUND_FRICTION,WALK_ACCEL=Vanilla12111RichPhysics.WALK_ACCEL,JUMP=Vanilla12111RichPhysics.JUMP,STEP_HEIGHT=Vanilla12111RichPhysics.STEP_HEIGHT;
    private final Vanilla12111RichPhysics rich=new Vanilla12111RichPhysics();

    public Player tick(Player state,Input input,World.Snapshot world){
      Objects.requireNonNull(state);Objects.requireNonNull(input);Objects.requireNonNull(world);
      if(legacyRequiresUncertainty(world,state))return uncertain(state);
      Vanilla12111RichPhysics.StepResult result=rich.step(new Vanilla12111RichPhysics.Context(0,state,new AdvancedInput(input.forward(),input.strafe(),input.jump(),false,false),legacyComplete(world,state,Phase5Mechanics.Pose.STANDING),Environment.DRY,Attributes.DEFAULT,Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(state.onGround(),false,false),false,dev.phantom.ac.world.EntityCollisions.of(List.of())));
      return result.state();
    }

    public StepResult step(PhysicsContext context){
      Objects.requireNonNull(context);
      if(legacyRequiresUncertainty(context.world(),context.state()))return new StepResult(context.simulationTick(),uncertain(context.state()),false,context.pose(),"world coverage is not known for the simulation volume");
      WorldSnapshot compatibleWorld=legacyComplete(context.world(),context.state(),context.pose());
      Vanilla12111RichPhysics.StepResult result=rich.step(new Vanilla12111RichPhysics.Context(context.simulationTick(),context.state(),context.input(),compatibleWorld,context.environment(),context.attributes(),context.effects(),context.pose(),context.movementEnvironment(),false,dev.phantom.ac.world.EntityCollisions.of(List.of())));
      return new StepResult(result.simulationTick(),result.state(),result.collided(),result.state().pose(),result.diagnostic());
    }

    public StepResult step(TickContext context,long tick){return step(new PhysicsContext(tick,context.state(),AdvancedInput.basic(context.input()),context.world(),Environment.DRY,Attributes.DEFAULT));}

    private static WorldSnapshot legacyComplete(World.Snapshot world,Player state,Phase5Mechanics.Pose pose){
      var builder=WorldSnapshot.builder(Contracts.TARGET_VERSION);Aabb box=Aabb.playerAt(state.position(),pose);
      int minChunkX=Math.floorDiv((int)Math.floor(box.minX())-1,16),maxChunkX=Math.floorDiv((int)Math.floor(box.maxX())+1,16),minChunkZ=Math.floorDiv((int)Math.floor(box.minZ())-1,16),maxChunkZ=Math.floorDiv((int)Math.floor(box.maxZ())+1,16);
      for(int cx=minChunkX;cx<=maxChunkX;cx++)for(int cz=minChunkZ;cz<=maxChunkZ;cz++)builder.loadChunk(cx,cz);
      for(var e:world.blocks().entrySet()){if(e.getValue()==World.Block.UNKNOWN||e.getValue()==World.Block.UNSUPPORTED)continue;builder.setBlock(e.getKey().x(),e.getKey().y(),e.getKey().z(),World.legacyBlockState(e.getValue()));}
      // Never synthesize support geometry from the client's onGround bit.
      // Missing support information must remain unknown so downstream physics
      // cannot accidentally turn incomplete legacy world data into certainty.
      return builder.build();
    }

    private static Player uncertain(Player s){return new Player(s.position(),s.velocity(),s.yaw(),s.pitch(),s.onGround(),s.gamemode(),s.effects(),s.awaitingTeleport(),true).withUncertainty(State.UncertaintyReason.UNKNOWN_ENVIRONMENT);}

    private static boolean legacyRequiresUncertainty(World.Snapshot world,Player state){
      int x=(int)Math.floor(state.position().x()),z=(int)Math.floor(state.position().z());
      if(!world.visibleChunks().contains(World.Chunk.containing(x,z)))return true;
      return world.blocks().entrySet().stream().anyMatch(e->{World.Pos p=e.getKey();World.Block b=e.getValue();return p.x()==x&&p.z()==z&&(b==World.Block.UNKNOWN||b==World.Block.UNSUPPORTED||b==World.Block.WATER||b==World.Block.LADDER);});
    }
  }

  public record TickContext(long simulationTick,Player state,Input input,World.Snapshot world) implements Serializable { public TickContext { Objects.requireNonNull(state);Objects.requireNonNull(input);Objects.requireNonNull(world);if(simulationTick<0)throw new IllegalArgumentException("simulationTick must be non-negative"); } }
  public enum Environment { DRY, WATER, LAVA, CLIMBABLE, UNKNOWN }
  public record Attributes(double movementSpeed,List<Phase5Mechanics.AttributeModifier> modifiers) implements Serializable { public static final Attributes DEFAULT=new Attributes(1.0,List.of()); public Attributes(double movementSpeed){this(movementSpeed,List.of());} public Attributes { if(!Double.isFinite(movementSpeed)||movementSpeed<0)throw new IllegalArgumentException("invalid movement speed");modifiers=List.copyOf(modifiers); } public double value(){return Phase5Mechanics.resolveAttribute(movementSpeed,modifiers);} }
  public record AdvancedInput(int forward,int strafe,boolean jump,boolean sprint,boolean sneak) implements Serializable { public AdvancedInput(int forward,int strafe,boolean jump){this(forward,strafe,jump,false,false);} public AdvancedInput {if(Math.abs(forward)>1||Math.abs(strafe)>1)throw new IllegalArgumentException("input must be -1..1");} public static AdvancedInput basic(Input input){return new AdvancedInput(input.forward(),input.strafe(),input.jump(),false,false);} public Input asBasic(){return new Input(forward,strafe,jump);} }
  public record PhysicsContext(long simulationTick,Player state,AdvancedInput input,World.Snapshot world,Environment environment,Attributes attributes,Phase5Mechanics.MovementEffects effects,Phase5Mechanics.Pose pose,Phase5Mechanics.MovementEnvironment movementEnvironment) implements Serializable { public PhysicsContext(long tick,Player state,AdvancedInput input,World.Snapshot world,Environment environment,Attributes attributes){this(tick,state,input,world,environment,attributes,Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(state.onGround(),input.sprint(),input.sneak()));} public PhysicsContext { Objects.requireNonNull(state);Objects.requireNonNull(input);Objects.requireNonNull(world);Objects.requireNonNull(environment);Objects.requireNonNull(attributes);Objects.requireNonNull(effects);Objects.requireNonNull(pose);Objects.requireNonNull(movementEnvironment);if(simulationTick<0)throw new IllegalArgumentException("simulationTick must be non-negative"); } }
  public record StepResult(long simulationTick,Player state,boolean collided,Phase5Mechanics.Pose pose,String diagnostic) implements Serializable { public StepResult(long tick,Player state,boolean collided,String diagnostic){this(tick,state,collided,Phase5Mechanics.Pose.STANDING,diagnostic);} public StepResult {Objects.requireNonNull(state);Objects.requireNonNull(pose);Objects.requireNonNull(diagnostic);} }
  public enum MovementMode { SURVIVAL_GROUND,SURVIVAL_AIR,NON_SURVIVAL,UNKNOWN }
  public record SimulationFrame(long simulationTick,Player before,Input input,Player after,MovementMode mode,boolean collision,String diagnostic) implements Serializable {}
  public record Input(int forward,int strafe,boolean jump) implements Serializable { public Input {if(Math.abs(forward)>1||Math.abs(strafe)>1)throw new IllegalArgumentException("input must be -1..1");} }
  public record Frame(long tick,Player state,Input input,boolean collision) implements Serializable {}
  public record Trace(List<Frame> frames) implements Serializable {public Trace {frames=List.copyOf(frames);}}
  public record Difference(long firstDivergentTick,Vec3 positionDelta,Vec3 velocityDelta,String message) {}
  public static Optional<Difference> firstDivergence(Trace expected,Trace actual,double epsilon){if(expected.frames().size()!=actual.frames().size())return Optional.of(new Difference(Math.min(expected.frames().size(),actual.frames().size()),Vec3.ZERO,Vec3.ZERO,"trace length differs"));for(int i=0;i<expected.frames().size();i++){Frame a=expected.frames().get(i),b=actual.frames().get(i);Vec3 p=new Vec3(a.state().position().x()-b.state().position().x(),a.state().position().y()-b.state().position().y(),a.state().position().z()-b.state().position().z());Vec3 v=new Vec3(a.state().velocity().x()-b.state().velocity().x(),a.state().velocity().y()-b.state().velocity().y(),a.state().velocity().z()-b.state().velocity().z());double max=Math.max(Math.max(Math.abs(p.x()),Math.abs(p.y())),Math.max(Math.abs(p.z()),Math.max(Math.abs(v.x()),Math.max(Math.abs(v.y()),Math.abs(v.z())))));if(max>epsilon||a.state().onGround()!=b.state().onGround()||!a.input().equals(b.input()))return Optional.of(new Difference(a.tick(),p,v,"position, velocity, ground state, or input differs"));}return Optional.empty();}
}