package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.State.*;

public final class Simulation {
  private Simulation() {}

  public static final class Vanilla12111Physics implements Contracts.PhysicsEngine {
    public static final double GRAVITY=.08, AIR_DRAG=.98, AIR_HORIZONTAL_FRICTION=.91, AIR_VERTICAL_DRAG=.98,
        AIR_ACCEL=.0196, GROUND_FRICTION=.546, WALK_ACCEL=.98, JUMP=.42, STEP_HEIGHT=.6;
    private static final double DIAGONAL_ACCEL=.1;
    private static final double WATER_DRAG=.9, LAVA_DRAG=.5, CLIMB_MAX_DOWN=.15, CLIMB_MAX_UP=.15, GLIDE_GRAVITY=.035;

    public Player tick(Player s, Input input, World.Snapshot world) {
      return step(new TickContext(0,s,input,world),0).state();
    }

    public StepResult step(PhysicsContext context) {
      Objects.requireNonNull(context,"context");
      return step(new TickContext(context.simulationTick(),context.state(),context.input().asBasic(),context.world()),
          context.simulationTick(),context.input(),context.environment(),context.attributes(),context.effects(),context.pose(),context.movementEnvironment());
    }

    public StepResult step(TickContext context,long tick) {
      return step(context,tick,AdvancedInput.basic(context.input()),Environment.DRY,Attributes.DEFAULT,
          Phase5Mechanics.MovementEffects.NONE,Phase5Mechanics.Pose.STANDING,
          Phase5Mechanics.MovementEnvironment.dry(context.state().onGround(),false,false));
    }

    private StepResult step(TickContext context,long tick,AdvancedInput input,Environment environment,
                            Attributes attributes,Phase5Mechanics.MovementEffects effects,
                            Phase5Mechanics.Pose priorPose,Phase5Mechanics.MovementEnvironment env) {
      Player s=context.state();
      World.Snapshot world=context.world();
      if(s.awaitingTeleport().isPresent())
        return new StepResult(tick,uncertain(s),false,priorPose,"awaiting teleport confirmation; pre-correction motion is not integrated");
      if(environment==Environment.UNKNOWN)
        return new StepResult(tick,uncertain(s),false,priorPose,"movement environment is unknown");
      if(s.gamemode().equals("creative") || s.gamemode().equals("spectator"))
        return new StepResult(tick,new Player(s.position(),Vec3.ZERO,s.yaw(),s.pitch(),false,s.gamemode(),s.effects(),s.awaitingTeleport(),false),false,priorPose,"non-physical gamemode");
      if(!s.gamemode().equals("survival") && !s.gamemode().equals("adventure"))
        return new StepResult(tick,uncertain(s),false,priorPose,"unsupported gamemode movement model");

      Aabb start=Aabb.playerAt(s.position(),priorPose);
      if(world.hasUnsupported(start) && environment==Environment.DRY)
        return new StepResult(tick,uncertain(s),false,priorPose,"start collision volume is not fully known");

      double radians=Math.toRadians(s.yaw());
      double speed=attributes.value()*effects.speedMultiplier()*(input.sprint()?1.3:1.0)*(input.sneak()?0.3:1.0);
      boolean fluid=env.fluid()!=Phase5Mechanics.Fluid.NONE;
      boolean climbing=env.climbable();
      boolean gliding=env.gliding();
      double inputMagnitude=Math.hypot(input.forward(),input.strafe());
      double inputScale=inputMagnitude>1.0?1.0/Math.sqrt(2.0):1.0;
      double inputAcceleration;
      if(fluid) inputAcceleration=AIR_ACCEL;
      else if(s.onGround() && inputMagnitude>1.0) inputAcceleration=DIAGONAL_ACCEL*effects.speedMultiplier()*(input.sprint()?1.3:1.0)*(input.sneak()?0.3:1.0);
      else inputAcceleration=s.onGround()?WALK_ACCEL*speed:AIR_ACCEL;
      if(gliding) inputAcceleration=AIR_ACCEL;
      Vec3 acceleration=new Vec3(
          inputScale*(input.strafe()*inputAcceleration*Math.cos(radians)-input.forward()*inputAcceleration*Math.sin(radians)),
          0,
          inputScale*(input.forward()*inputAcceleration*Math.cos(radians)+input.strafe()*inputAcceleration*Math.sin(radians)));

      Vec3 velocity=s.velocity().add(acceleration);
      if(climbing) {
        double verticalInput=input.forward();
        double climbY=velocity.y();
        if(verticalInput>0) climbY=Math.min(CLIMB_MAX_UP, climbY+0.15*verticalInput);
        else if(verticalInput<0) climbY=Math.max(CLIMB_MAX_DOWN, climbY+0.15*verticalInput);
        climbY=Math.max(CLIMB_MAX_DOWN,Math.min(CLIMB_MAX_UP,climbY));
        velocity=new Vec3(velocity.x(),climbY,velocity.z());
      }
      boolean jumped=input.jump()&&s.onGround()&&!fluid&&!climbing&&!gliding;
      if(jumped) velocity=new Vec3(velocity.x(),JUMP+effects.jumpVelocityAdd(),velocity.z());
      if(effects.levitation()) velocity=new Vec3(velocity.x(),effects.levitationVelocity(),velocity.z());

      Aabb swept=expanded(start,velocity);
      if(world.hasUnsupported(swept) && environment==Environment.DRY)
        return new StepResult(tick,uncertain(s),false,priorPose,"swept collision volume is not fully known");

      World.CollisionResult collision=World.resolveWithStep(world,start,velocity,s.onGround()&&!fluid&&!climbing&&!gliding?STEP_HEIGHT:0);
      Vec3 displacement=collision.resolved();
      boolean grounded=collision.collidedY()&&velocity.y()<=0;

      double horizontalFactor;
      if(env.fluid()==Phase5Mechanics.Fluid.WATER) horizontalFactor=env.fluidSpeedMultiplier()*WATER_DRAG;
      else if(env.fluid()==Phase5Mechanics.Fluid.LAVA) horizontalFactor=env.fluidSpeedMultiplier()*LAVA_DRAG;
      else if(climbing) horizontalFactor=s.onGround()?GROUND_FRICTION:AIR_HORIZONTAL_FRICTION;
      else if(gliding) horizontalFactor=AIR_DRAG;
      else horizontalFactor=s.onGround()?GROUND_FRICTION:AIR_HORIZONTAL_FRICTION;

      double gravity=GRAVITY*env.gravityMultiplier();
      double postTickVerticalVelocity;
      if(effects.levitation()) postTickVerticalVelocity=effects.levitationVelocity();
      else if(climbing) postTickVerticalVelocity=velocity.y();
      else if(env.fluid()==Phase5Mechanics.Fluid.WATER) postTickVerticalVelocity=velocity.y()*env.fluidDrag()-gravity;
      else if(env.fluid()==Phase5Mechanics.Fluid.LAVA) postTickVerticalVelocity=velocity.y()*env.fluidDrag()-gravity;
      else if(gliding) postTickVerticalVelocity=velocity.y()-GLIDE_GRAVITY;
      else {
        double effectiveGravity=gravity*effects.fallGravityMultiplier();
        postTickVerticalVelocity=jumped?(velocity.y()-effectiveGravity)*AIR_VERTICAL_DRAG:velocity.y()*AIR_VERTICAL_DRAG-effectiveGravity*AIR_VERTICAL_DRAG;
      }
      double groundedVerticalVelocity;
      if(effects.levitation()) groundedVerticalVelocity=effects.levitationVelocity();
      else if(climbing) groundedVerticalVelocity=velocity.y();
      else if(env.fluid()==Phase5Mechanics.Fluid.WATER) groundedVerticalVelocity=velocity.y()*env.fluidDrag()-gravity;
      else if(env.fluid()==Phase5Mechanics.Fluid.LAVA) groundedVerticalVelocity=velocity.y()*env.fluidDrag()-gravity;
      else groundedVerticalVelocity=-gravity*AIR_VERTICAL_DRAG*effects.fallGravityMultiplier();
      double nextY=grounded&&!effects.levitation()&&!climbing?groundedVerticalVelocity:postTickVerticalVelocity;
      Vec3 nextVelocity=new Vec3(collision.collidedX()?0:velocity.x()*horizontalFactor,nextY,collision.collidedZ()?0:velocity.z()*horizontalFactor);

      Phase5Mechanics.Pose requestedPose=Phase5Mechanics.nextPose(priorPose,env);
      Phase5Mechanics.Pose nextPose=resolvePoseTransition(world,s.position().add(displacement),requestedPose,priorPose);
      boolean poseChanged=nextPose!=priorPose;
      String diagnostic=poseChanged?"deterministic collision-resolved movement step; pose="+nextPose:"deterministic collision-resolved movement step; pose retained="+priorPose;
      Player next=new Player(s.position().add(displacement),nextVelocity,s.yaw(),s.pitch(),grounded,s.gamemode(),s.effects(),s.awaitingTeleport(),false);
      return new StepResult(tick,next,collision.collidedHorizontally()||collision.collidedY(),nextPose,diagnostic);
    }

    private static Aabb expanded(Aabb start,Vec3 velocity) {
      return new Aabb(Math.min(start.minX(),start.minX()+velocity.x()),Math.min(start.minY(),start.minY()+velocity.y()),
          Math.min(start.minZ(),start.minZ()+velocity.z()),Math.max(start.maxX(),start.maxX()+velocity.x()),
          Math.max(start.maxY(),start.maxY()+velocity.y()),Math.max(start.maxZ(),start.maxZ()+velocity.z()));
    }

    private static Phase5Mechanics.Pose resolvePoseTransition(World.Snapshot world,Vec3 position,
                                                               Phase5Mechanics.Pose requested,Phase5Mechanics.Pose previous) {
      if(requested==previous) return previous;
      Aabb requestedBox=Aabb.playerAt(position,requested);
      for(Aabb collision:world.collisions(requestedBox)) if(collision.intersects(requestedBox)) return previous;
      return requested;
    }

    private static Player uncertain(Player state) {
      return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),
          state.effects(),state.awaitingTeleport(),true);
    }
  }

  public record TickContext(long simulationTick,Player state,Input input,World.Snapshot world) implements Serializable {
    public TickContext {
      Objects.requireNonNull(state);Objects.requireNonNull(input);Objects.requireNonNull(world);
      if(simulationTick<0)throw new IllegalArgumentException("simulationTick must be non-negative");
    }
  }
  public enum Environment { DRY, WATER, LAVA, CLIMBABLE, UNKNOWN }
  public record Attributes(double movementSpeed,List<Phase5Mechanics.AttributeModifier> modifiers) implements Serializable {
    public static final Attributes DEFAULT=new Attributes(1.0,List.of());
    public Attributes(double movementSpeed){this(movementSpeed,List.of());}
    public Attributes {
      if(!Double.isFinite(movementSpeed)||movementSpeed<0)throw new IllegalArgumentException("invalid movement speed");
      modifiers=List.copyOf(modifiers);
    }
    public double value(){return Phase5Mechanics.resolveAttribute(movementSpeed,modifiers);}
  }
  public record AdvancedInput(int forward,int strafe,boolean jump,boolean sprint,boolean sneak) implements Serializable {
    public AdvancedInput(int forward,int strafe,boolean jump){this(forward,strafe,jump,false,false);}
    public AdvancedInput {if(Math.abs(forward)>1||Math.abs(strafe)>1)throw new IllegalArgumentException("input must be -1..1");}
    public static AdvancedInput basic(Input input){return new AdvancedInput(input.forward(),input.strafe(),input.jump(),false,false);}
    public Input asBasic(){return new Input(forward,strafe,jump);}
  }
  public record PhysicsContext(long simulationTick,Player state,AdvancedInput input,World.Snapshot world,
                               Environment environment,Attributes attributes,Phase5Mechanics.MovementEffects effects,
                               Phase5Mechanics.Pose pose,Phase5Mechanics.MovementEnvironment movementEnvironment) implements Serializable {
    public PhysicsContext(long tick,Player state,AdvancedInput input,World.Snapshot world,Environment environment,Attributes attributes){
      this(tick,state,input,world,environment,attributes,Phase5Mechanics.MovementEffects.NONE,
          Phase5Mechanics.Pose.STANDING,Phase5Mechanics.MovementEnvironment.dry(state.onGround(),input.sprint(),input.sneak()));
    }
    public PhysicsContext {
      Objects.requireNonNull(state);Objects.requireNonNull(input);Objects.requireNonNull(world);Objects.requireNonNull(environment);
      Objects.requireNonNull(attributes);Objects.requireNonNull(effects);Objects.requireNonNull(pose);Objects.requireNonNull(movementEnvironment);
      if(simulationTick<0)throw new IllegalArgumentException("simulationTick must be non-negative");
    }
  }
  public record StepResult(long simulationTick,Player state,boolean collided,Phase5Mechanics.Pose pose,String diagnostic) implements Serializable {
    public StepResult(long tick,Player state,boolean collided,String diagnostic){this(tick,state,collided,Phase5Mechanics.Pose.STANDING,diagnostic);}
    public StepResult {Objects.requireNonNull(state);Objects.requireNonNull(pose);Objects.requireNonNull(diagnostic);}
  }
  public enum MovementMode { SURVIVAL_GROUND,SURVIVAL_AIR,NON_SURVIVAL,UNKNOWN }
  public record SimulationFrame(long simulationTick,Player before,Input input,Player after,MovementMode mode,boolean collision,String diagnostic) implements Serializable {}
  public record Input(int forward,int strafe,boolean jump) implements Serializable {
    public Input {if(Math.abs(forward)>1||Math.abs(strafe)>1)throw new IllegalArgumentException("input must be -1..1");}
  }
  public record Frame(long tick,Player state,Input input,boolean collision) implements Serializable {}
  public record Trace(List<Frame> frames) implements Serializable {public Trace {frames=List.copyOf(frames);}}
  public record Difference(long firstDivergentTick,Vec3 positionDelta,Vec3 velocityDelta,String message) {}

  public static Optional<Difference> firstDivergence(Trace expected,Trace actual,double epsilon){
    if(expected.frames().size()!=actual.frames().size())return Optional.of(new Difference(Math.min(expected.frames().size(),actual.frames().size()),Vec3.ZERO,Vec3.ZERO,"trace length differs"));
    for(int i=0;i<expected.frames().size();i++){
      Frame a=expected.frames().get(i),b=actual.frames().get(i);
      Vec3 p=new Vec3(a.state().position().x()-b.state().position().x(),a.state().position().y()-b.state().position().y(),a.state().position().z()-b.state().position().z());
      Vec3 v=new Vec3(a.state().velocity().x()-b.state().velocity().x(),a.state().velocity().y()-b.state().velocity().y(),a.state().velocity().z()-b.state().velocity().z());
      double max=Math.max(Math.max(Math.abs(p.x()),Math.abs(p.y())),Math.max(Math.abs(p.z()),Math.max(Math.abs(v.x()),Math.max(Math.abs(v.y()),Math.abs(v.z())))));
      if(max>epsilon||a.state().onGround()!=b.state().onGround()||!a.input().equals(b.input()))return Optional.of(new Difference(a.tick(),p,v,"position, velocity, ground state, or input differs"));
    }
    return Optional.empty();
  }
}