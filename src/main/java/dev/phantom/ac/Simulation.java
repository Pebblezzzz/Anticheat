package dev.phantom.ac;

import java.io.Serializable; import java.util.*; import static dev.phantom.ac.Maths.*; import static dev.phantom.ac.State.*;
public final class Simulation {
  private Simulation() {}
  /**
   * Version-isolated movement model. Constants are named model inputs, not a
   * claim of vanilla parity; independent 1.21.11 traces are required before
   * enforcement. The important invariant here is that velocity is integrated
   * independently from displacement and collision only clips displacement.
   */
  public static final class Vanilla12111Physics implements Contracts.PhysicsEngine {
    public static final double GRAVITY=.08, AIR_DRAG=.98, GROUND_FRICTION=.91, WALK_ACCEL=.1, JUMP=.42;
    public static final double STEP_HEIGHT=.6;
    public Player tick(Player s, Input input, World.Snapshot world) {
      return step(new TickContext(0,s,input,world),0).state();
    }
    /** Full Phase 5 entry point. All movement-affecting facts are explicit inputs. */
    public StepResult step(PhysicsContext context) {
      Objects.requireNonNull(context,"context");
      return step(new TickContext(context.simulationTick(),context.state(),context.input().asBasic(),context.world()),context.simulationTick(),context.input(),context.environment(),context.attributes());
    }
    public StepResult step(TickContext context,long simulationTick) {
      return step(context,simulationTick,AdvancedInput.basic(context.input()),Environment.DRY,Attributes.DEFAULT);
    }
    private StepResult step(TickContext context,long simulationTick,AdvancedInput input,Environment environment,Attributes attributes) {
      Objects.requireNonNull(context,"context");
      Player s=context.state(); World.Snapshot world=context.world();
      Aabb start=Aabb.playerAt(s.position());
      if(world.hasUnsupported(start) && environment==Environment.DRY) return new StepResult(simulationTick,uncertain(s),false,"start collision volume is not fully known");
      if(!s.gamemode().equals("survival")) return new StepResult(simulationTick,new Player(s.position(),Vec3.ZERO,s.yaw(),s.pitch(),false,s.gamemode(),s.effects(),s.awaitingTeleport(),false),false,"non-survival movement is not simulated");
      double radians=Math.toRadians(s.yaw());
      double speed=attributes.movementSpeed() * (input.sprint()?1.3:1.0) * (input.sneak()?0.3:1.0);
      Vec3 acceleration=new Vec3(input.strafe()*WALK_ACCEL*speed*Math.cos(radians)-input.forward()*WALK_ACCEL*speed*Math.sin(radians),0,
          input.forward()*WALK_ACCEL*speed*Math.cos(radians)+input.strafe()*WALK_ACCEL*speed*Math.sin(radians));
      Vec3 velocity=s.velocity().add(acceleration);
      if(environment==Environment.WATER) velocity=new Vec3(velocity.x()*0.8,velocity.y()*0.8,velocity.z()*0.8);
      if(environment==Environment.LAVA) velocity=new Vec3(velocity.x()*0.5,velocity.y()*0.5,velocity.z()*0.5);
      if(environment==Environment.CLIMBABLE) velocity=new Vec3(velocity.x(),Math.max(-0.15,velocity.y()),velocity.z());
      if(input.jump()&&s.onGround()) velocity=new Vec3(velocity.x(),JUMP,velocity.z());
      else velocity=new Vec3(velocity.x(),velocity.y()-GRAVITY,velocity.z());
      Aabb swept=new Aabb(Math.min(start.minX(),start.minX()+velocity.x()),Math.min(start.minY(),start.minY()+velocity.y()),Math.min(start.minZ(),start.minZ()+velocity.z()),Math.max(start.maxX(),start.maxX()+velocity.x()),Math.max(start.maxY(),start.maxY()+velocity.y()),Math.max(start.maxZ(),start.maxZ()+velocity.z()));
      if(world.hasUnsupported(swept) && environment==Environment.DRY) return new StepResult(simulationTick,uncertain(s),false,"swept collision volume is not fully known");
      World.CollisionResult collision=World.resolveWithStep(world,start,velocity,s.onGround()?STEP_HEIGHT:0);
      Vec3 displacement=collision.resolved();
      boolean grounded=collision.collidedY()&&velocity.y()<=0;
      double horizontalFactor=grounded?GROUND_FRICTION:AIR_DRAG;
      if(environment==Environment.WATER) horizontalFactor*=0.8;
      if(environment==Environment.LAVA) horizontalFactor*=0.5;
      Vec3 nextVelocity=new Vec3(collision.collidedX()?0:velocity.x()*horizontalFactor,grounded?0:velocity.y()*AIR_DRAG,collision.collidedZ()?0:velocity.z()*horizontalFactor);
      return new StepResult(simulationTick,new Player(s.position().add(displacement),nextVelocity,s.yaw(),s.pitch(),grounded,s.gamemode(),s.effects(),s.awaitingTeleport(),false),collision.collidedHorizontally()||collision.collidedY(),"deterministic collision-resolved movement step");
    }
    private static Player uncertain(Player state) { return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),state.awaitingTeleport(),true); }
  }
  public record TickContext(long simulationTick,Player state,Input input,World.Snapshot world) implements Serializable { public TickContext { Objects.requireNonNull(state); Objects.requireNonNull(input); Objects.requireNonNull(world); if(simulationTick<0) throw new IllegalArgumentException("simulationTick must be non-negative"); } }
  public enum Environment { DRY, WATER, LAVA, CLIMBABLE, UNKNOWN }
  public record Attributes(double movementSpeed) implements Serializable { public static final Attributes DEFAULT=new Attributes(1.0); public Attributes { if(!Double.isFinite(movementSpeed)||movementSpeed<0) throw new IllegalArgumentException("invalid movement speed"); } }
  public record AdvancedInput(int forward,int strafe,boolean jump,boolean sprint,boolean sneak) implements Serializable {
    public AdvancedInput(int forward,int strafe,boolean jump) { this(forward, strafe, jump, false, false); }
    public AdvancedInput { if(Math.abs(forward)>1||Math.abs(strafe)>1) throw new IllegalArgumentException("input must be -1..1"); }
    public static AdvancedInput basic(Input input){return new AdvancedInput(input.forward(),input.strafe(),input.jump(),false,false);}
    public Input asBasic(){return new Input(forward,strafe,jump);}
  }
  public record PhysicsContext(long simulationTick,Player state,AdvancedInput input,World.Snapshot world,Environment environment,Attributes attributes) implements Serializable {
    public PhysicsContext {Objects.requireNonNull(state);Objects.requireNonNull(input);Objects.requireNonNull(world);Objects.requireNonNull(environment);Objects.requireNonNull(attributes);if(simulationTick<0)throw new IllegalArgumentException("simulationTick must be non-negative");}
  }
  public record StepResult(long simulationTick,Player state,boolean collided,String diagnostic) implements Serializable { public StepResult { Objects.requireNonNull(state); Objects.requireNonNull(diagnostic); } }
  public enum MovementMode { SURVIVAL_GROUND, SURVIVAL_AIR, NON_SURVIVAL, UNKNOWN }
  public record SimulationFrame(long simulationTick,Player before,Input input,Player after,MovementMode mode,boolean collision,String diagnostic) implements Serializable {}
  public record Input(int forward,int strafe,boolean jump) implements Serializable { public Input {if(Math.abs(forward)>1||Math.abs(strafe)>1)throw new IllegalArgumentException("input must be -1..1");} }
  public record Frame(long tick, Player state, Input input, boolean collision) implements Serializable {}
  public record Trace(List<Frame> frames) implements Serializable { public Trace {frames=List.copyOf(frames);} }
  public record Difference(long firstDivergentTick, Vec3 positionDelta, Vec3 velocityDelta, String message) {}
  public static Optional<Difference> firstDivergence(Trace expected,Trace actual,double epsilon) { if(expected.frames().size()!=actual.frames().size())return Optional.of(new Difference(Math.min(expected.frames().size(),actual.frames().size()),Vec3.ZERO,Vec3.ZERO,"trace length differs")); for(int i=0;i<expected.frames().size();i++){Frame a=expected.frames().get(i),b=actual.frames().get(i);Vec3 p=new Vec3(a.state().position().x()-b.state().position().x(),a.state().position().y()-b.state().position().y(),a.state().position().z()-b.state().position().z());Vec3 v=new Vec3(a.state().velocity().x()-b.state().velocity().x(),a.state().velocity().y()-b.state().velocity().y(),a.state().velocity().z()-b.state().velocity().z());if(Math.max(Math.max(Math.abs(p.x()),Math.abs(p.y())),Math.max(Math.abs(p.z()),Math.max(Math.abs(v.x()),Math.max(Math.abs(v.y()),Math.abs(v.z())))))>epsilon||a.state().onGround()!=b.state().onGround())return Optional.of(new Difference(a.tick(),p,v,"position, velocity, or ground state differs"));}return Optional.empty(); }
}
