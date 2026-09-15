package dev.phantom.ac;

import java.io.Serializable; import java.util.*; import static dev.phantom.ac.Maths.*; import static dev.phantom.ac.State.*;
public final class Simulation {
  private Simulation() {}
  /** Version boundary. Values are provisional until independent 1.21.11 traces validate them. */
  public static final class Vanilla12111Physics implements Contracts.PhysicsEngine {
    public static final double GRAVITY=.08, AIR_DRAG=.98, GROUND_FRICTION=.91, WALK_ACCEL=.1, JUMP=.42;
    /** Pending independent 1.21.11 trace verification. Kept here, not in collision. */
    public static final double PROVISIONAL_STEP_HEIGHT=.6;
    public Player tick(Player s, Input input, World.Snapshot world) {
      Aabb start=Aabb.playerAt(s.position());
      if(world.hasUnsupported(start)) return uncertain(s);
      if(!s.gamemode().equals("survival")) return new Player(s.position(),Vec3.ZERO,s.yaw(),s.pitch(),false,s.gamemode(),s.effects(),s.awaitingTeleport(),true);
      double radians=Math.toRadians(s.yaw()); double forward=input.forward()*WALK_ACCEL, strafe=input.strafe()*WALK_ACCEL;
      Vec3 v=s.velocity().add(new Vec3(strafe*Math.cos(radians)-forward*Math.sin(radians),0,forward*Math.cos(radians)+strafe*Math.sin(radians)));
      if(input.jump()&&s.onGround())v=new Vec3(v.x(),JUMP,v.z());
      if(!s.onGround())v=new Vec3(v.x(),v.y()-GRAVITY,v.z());
      Aabb swept=new Aabb(Math.min(start.minX(),start.minX()+v.x()),Math.min(start.minY(),start.minY()+v.y()),Math.min(start.minZ(),start.minZ()+v.z()),Math.max(start.maxX(),start.maxX()+v.x()),Math.max(start.maxY(),start.maxY()+v.y()),Math.max(start.maxZ(),start.maxZ()+v.z()));
      if(world.hasUnsupported(swept)) return uncertain(s);
      World.CollisionResult collision=World.resolveWithStep(world,start,v,s.onGround()?PROVISIONAL_STEP_HEIGHT:0);
      Vec3 actual=collision.resolved(); boolean grounded=v.y()<0&&actual.y()!=v.y();
      double friction=grounded?GROUND_FRICTION:AIR_DRAG;
      Vec3 nextVelocity=new Vec3(actual.x()*friction,actual.y()*AIR_DRAG,actual.z()*friction);
      return new Player(s.position().add(actual),nextVelocity,s.yaw(),s.pitch(),grounded,s.gamemode(),s.effects(),s.awaitingTeleport(),s.uncertain());
    }
    private static Player uncertain(Player state) { return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),state.awaitingTeleport(),true); }
  }
  public record Input(int forward,int strafe,boolean jump) implements Serializable { public Input {if(Math.abs(forward)>1||Math.abs(strafe)>1)throw new IllegalArgumentException("input must be -1..1");} }
  public record Frame(long tick, Player state, Input input, boolean collision) implements Serializable {}
  public record Trace(List<Frame> frames) implements Serializable { public Trace {frames=List.copyOf(frames);} }
  public record Difference(long firstDivergentTick, Vec3 positionDelta, Vec3 velocityDelta, String message) {}
  public static Optional<Difference> firstDivergence(Trace expected,Trace actual,double epsilon) { if(expected.frames().size()!=actual.frames().size())return Optional.of(new Difference(Math.min(expected.frames().size(),actual.frames().size()),Vec3.ZERO,Vec3.ZERO,"trace length differs")); for(int i=0;i<expected.frames().size();i++){Frame a=expected.frames().get(i),b=actual.frames().get(i);Vec3 p=new Vec3(a.state().position().x()-b.state().position().x(),a.state().position().y()-b.state().position().y(),a.state().position().z()-b.state().position().z());Vec3 v=new Vec3(a.state().velocity().x()-b.state().velocity().x(),a.state().velocity().y()-b.state().velocity().y(),a.state().velocity().z()-b.state().velocity().z());if(Math.max(Math.max(Math.abs(p.x()),Math.abs(p.y())),Math.max(Math.abs(p.z()),Math.max(Math.abs(v.x()),Math.max(Math.abs(v.y()),Math.abs(v.z())))))>epsilon||a.state().onGround()!=b.state().onGround())return Optional.of(new Difference(a.tick(),p,v,"position, velocity, or ground state differs"));}return Optional.empty(); }
}
