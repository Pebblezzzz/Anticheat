package dev.phantom.ac;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.State.Player;

/** Canonical deterministic Minecraft Java 1.21.11 movement transition. */
public final class Vanilla12111RichPhysics {
    public static final String VERSION = "1.21.11";
    public static final double
        GRAVITY=(double)(float)0.08, AIR_DRAG=(double)(float)0.91,
        AIR_VERTICAL_DRAG=(double)(float)0.98, AIR_ACCEL=(double)(float)0.02,
        SPRINT_AIR_ACCEL=(double)(float)0.025999999, GROUND_FRICTION=(double)(float)0.546,
        JUMP=(double)(float)0.42, STEP_HEIGHT=(double)(float)0.6,
        INPUT_FRICTION=(double)(float)0.98, FRICTION_SPEED_FACTOR=(double)(float)0.21600002,
        SPRINT_JUMP_HORIZONTAL_BOOST=(double)(float)0.2,
        SPRINTING_SPEED_MULTIPLIER=(double)(float)1.3,
        WATER_DRAG=(double)(float)0.8, WATER_SPRINT_DRAG=(double)(float)0.9,
        WATER_ACCEL=(double)(float)0.02, LAVA_DRAG=(double)(float)0.5,
        LAVA_ACCEL=(double)(float)0.02, FLUID_VERTICAL_DRAG=(double)(float)0.8,
        FLUID_GRAVITY=(double)(float)0.02, SNEAKING_SPEED_MULTIPLIER=(double)(float)0.3,
        CLIMB_MAX_DOWN=(double)(float)-0.15, CLIMB_MAX_UP=(double)(float)0.15,
        FLY_SPEED=(double)(float)0.05, FLY_SPRINT_MULTIPLIER=(double)(float)2.0,
        GLIDE_GRAVITY=(double)(float)0.035, GROUND_PROBE=1.0E-4;

    public StepResult step(Context context) {
        Objects.requireNonNull(context);
        if (!VERSION.equals(context.world().version())) throw new IllegalArgumentException("Phase 5 requires world version "+VERSION);
        Player source=context.state();
        if (source.awaitingTeleport().isPresent()) return uncertain(context,"teleport correction is still awaiting confirmation");
        if (context.environment()==Simulation.Environment.UNKNOWN) return uncertain(context,"movement environment is unknown");

        Phase5Mechanics.Pose pose=resolvePose(context);
        if (pose==null) return uncertain(context,"requested pose transition is not collision-safe");

        if ("spectator".equals(source.gamemode())) return spectatorStep(context,pose);
        if (!"survival".equals(source.gamemode()) && !"adventure".equals(source.gamemode()) && !"creative".equals(source.gamemode()))
            return uncertain(context,"unsupported gamemode movement model");
        if ("creative".equals(source.gamemode()) && context.flying()) return flyingStep(context,pose);

        Aabb start=Aabb.playerAt(source.position(),pose);
        BlockBox startBox=box(start);
        if (!context.entityCollisions().boxesIn(startBox).isDefinite()) return uncertain(context,"entity collision coverage is incomplete");
        if (context.world().hasUnknownOrUnsupported(startBox)) return uncertain(context,"player collision volume contains unknown or unsupported world data");

        boolean fluid=context.movementEnvironment().fluid()!=Phase5Mechanics.Fluid.NONE;
        boolean climbing=context.movementEnvironment().climbable();
        boolean gliding=context.movementEnvironment().gliding();
        Vec3 velocity=source.velocity();
        Vec3 input=movementInput(context);
        double movementSpeed=context.attributes().value()*context.effects().speedMultiplier();
        if (context.input().sprint()) movementSpeed*=SPRINTING_SPEED_MULTIPLIER;
        if (context.input().sneak()) movementSpeed*=SNEAKING_SPEED_MULTIPLIER;

        boolean jumped=context.input().jump() && source.onGround() && !fluid && !climbing && !gliding && !context.sleeping();
        if (context.effects().levitation()) velocity=new Vec3(velocity.x(),context.effects().levitationVelocity(),velocity.z());
        else if (climbing) velocity=applyClimb(velocity,context.input());
        else if (gliding) velocity=addHorizontalInput(velocity,input,AIR_ACCEL);
        else if (fluid) velocity=addHorizontalInput(velocity,input,
            (context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER?WATER_ACCEL:LAVA_ACCEL)
                * context.movementEnvironment().fluidSpeedMultiplier());
        else {
            double acceleration=source.onGround()?groundAcceleration(context,movementSpeed):
                (context.input().sprint()?SPRINT_AIR_ACCEL:AIR_ACCEL);
            velocity=addHorizontalInput(velocity,input,acceleration);
        }

        if (jumped) {
            velocity=new Vec3(velocity.x(),JUMP+context.effects().jumpVelocityAdd(),velocity.z());
            if (context.input().sprint()) {
                double yaw=Math.toRadians(source.yaw());
                velocity=velocity.add(new Vec3(-Math.sin(yaw)*SPRINT_JUMP_HORIZONTAL_BOOST,0,
                    Math.cos(yaw)*SPRINT_JUMP_HORIZONTAL_BOOST));
            }
        }

        velocity=applyPreMoveVertical(velocity,context,fluid,climbing,gliding);
        double stepHeight=(!fluid&&!climbing&&!gliding&&source.onGround())?STEP_HEIGHT:0.0;
        RichWorldCollision.Result collision=RichWorldCollision.resolve(context.world(),start,velocity,stepHeight,context.entityCollisions());
        if (collision.uncertain()) return uncertain(context,collision.diagnostic());
        Vec3 displacement=collision.displacement();
        boolean grounded=collision.collidedY() && velocity.y()<0.0;
        if (!grounded && source.onGround() && velocity.y()<=0.0 && !fluid && !climbing && !gliding) {
            RichWorldCollision.Result probe=RichWorldCollision.resolve(context.world(),start,new Vec3(0,-GROUND_PROBE,0),0,context.entityCollisions());
            if (probe.uncertain()) return uncertain(context,probe.diagnostic());
            grounded=probe.collidedY();
        }
        Vec3 nextVelocity=postMoveVelocity(velocity,context,grounded,collision);
        Phase5Mechanics.Pose nextPose=Phase5Mechanics.nextPose(pose,context.movementEnvironment(),context.sleeping());
        Player next=richPlayer(source,source.position().add(displacement),nextVelocity,grounded,nextPose,context,false);
        String diagnostic=collision.diagnostic()+"; mode="+movementMode(context,source)+"; jumped="+jumped+"; version="+VERSION;
        return new StepResult(context.simulationTick(),next,
            collision.collidedX()||collision.collidedY()||collision.collidedZ(),
            collision.stepAttempted(),collision.stepSucceeded(),collision.collidedX(),collision.collidedY(),
            collision.collidedZ(),collision.entityCollision(),diagnostic);
    }

    private static Phase5Mechanics.Pose resolvePose(Context context) {
        Phase5Mechanics.Pose requested=Phase5Mechanics.nextPose(context.pose(),context.movementEnvironment(),context.sleeping());
        if (requested==context.pose()) return requested;
        BlockBox requestedBox=box(Aabb.playerAt(context.state().position(),requested));
        var world=dev.phantom.ac.world.WorldQueries.collisions(context.world(),requestedBox);
        var entities=context.entityCollisions().boxesIn(requestedBox);
        if (!world.isDefinite() || !entities.isDefinite()) return null;
        return world.isEmpty() && entities.isEmpty()?requested:context.pose();
    }

    private StepResult spectatorStep(Context context,Phase5Mechanics.Pose pose) {
        Vec3 movement=movementInput(context);
        double speed=FLY_SPEED*(context.input().sprint()?FLY_SPRINT_MULTIPLIER:1.0);
        double y=(context.input().jump()?speed:0)-(context.input().sneak()?speed:0);
        Player next=richPlayer(context.state(),context.state().position().add(movement.multiply(speed).add(new Vec3(0,y,0))),
            Vec3.ZERO,false,pose,context,false);
        return new StepResult(context.simulationTick(),next,false,false,false,false,false,false,false,
            "spectator no-physics movement; version="+VERSION);
    }

    private StepResult flyingStep(Context context,Phase5Mechanics.Pose pose) {
        Vec3 movement=movementInput(context);
        double speed=FLY_SPEED*(context.input().sprint()?FLY_SPRINT_MULTIPLIER:1.0);
        double y=(context.input().jump()?speed:0)-(context.input().sneak()?speed:0);
        Vec3 requested=movement.multiply(speed).add(new Vec3(0,y,0));
        RichWorldCollision.Result collision=RichWorldCollision.resolve(context.world(),
            Aabb.playerAt(context.state().position(),pose),requested,0,context.entityCollisions());
        if (collision.uncertain()) return uncertain(context,collision.diagnostic());
        Player next=richPlayer(context.state(),context.state().position().add(collision.displacement()),Vec3.ZERO,
            collision.collidedY()&&requested.y()<0,pose,context,false);
        return new StepResult(context.simulationTick(),next,
            collision.collidedX()||collision.collidedY()||collision.collidedZ(),false,false,
            collision.collidedX(),collision.collidedY(),collision.collidedZ(),collision.entityCollision(),
            "creative flight; version="+VERSION);
    }

    private static Vec3 applyClimb(Vec3 velocity,Simulation.AdvancedInput input) {
        double y=velocity.y();
        if (input.forward()>0) y=CLIMB_MAX_UP;
        else if (input.forward()<0) y=CLIMB_MAX_DOWN;
        else y=Math.max(CLIMB_MAX_DOWN,y);
        return new Vec3(velocity.x(),y,velocity.z());
    }

    private static Vec3 applyPreMoveVertical(Vec3 velocity,Context context,boolean fluid,boolean climbing,boolean gliding) {
        if (context.effects().levitation()||climbing) return velocity;
        if (gliding) return new Vec3(velocity.x(),velocity.y()-GLIDE_GRAVITY,velocity.z());
        if (fluid) {
            double gravity=FLUID_GRAVITY*context.movementEnvironment().gravityMultiplier();
            return new Vec3(velocity.x(),velocity.y()-gravity,velocity.z());
        }
        double gravity=GRAVITY*context.effects().fallGravityMultiplier()*context.movementEnvironment().gravityMultiplier();
        double y=Math.max(-3.92,velocity.y()-gravity);
        return new Vec3(velocity.x(),y,velocity.z());
    }

    private static Vec3 postMoveVelocity(Vec3 velocity,Context context,boolean grounded,RichWorldCollision.Result collision) {
        boolean fluid=context.movementEnvironment().fluid()!=Phase5Mechanics.Fluid.NONE;
        boolean climbing=context.movementEnvironment().climbable();
        boolean gliding=context.movementEnvironment().gliding();
        double horizontalFactor;
        double verticalFactor;
        if (fluid) horizontalFactor=context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER
            ?(context.input().sprint()?WATER_SPRINT_DRAG:WATER_DRAG):LAVA_DRAG;
        else if (climbing) horizontalFactor=grounded?GROUND_FRICTION:AIR_DRAG;
        else if (gliding) horizontalFactor=AIR_DRAG;
        else horizontalFactor=grounded?supportFriction(context):AIR_DRAG;
        verticalFactor=fluid?FLUID_VERTICAL_DRAG:AIR_VERTICAL_DRAG;
        double vx=collision.collidedX()?0:velocity.x()*horizontalFactor;
        double vz=collision.collidedZ()?0:velocity.z()*horizontalFactor;
        double vy;
        if (collision.collidedY()&&velocity.y()<0) vy=0;
        else if (context.effects().levitation()) vy=context.effects().levitationVelocity();
        else if (climbing) vy=velocity.y();
        else vy=velocity.y()*verticalFactor;
        return new Vec3(vx,vy,vz);
    }

    private static double groundAcceleration(Context context,double movementSpeed) {
        BlockState support=supportBlock(context);
        double s=BlockCatalogue12111.slipperiness(support);
        return movementSpeed*FRICTION_SPEED_FACTOR/(s*s*s);
    }

    private static double supportFriction(Context context) {
        return BlockCatalogue12111.slipperiness(supportBlock(context))*AIR_DRAG;
    }

    private static BlockState supportBlock(Context context) {
        Player s=context.state();
        BlockState support=context.world().blockAtOrNull((int)Math.floor(s.position().x()),
            (int)Math.floor(s.position().y()-GROUND_PROBE),(int)Math.floor(s.position().z()));
        if (support==null||support.isUnsupported())
            throw new IncompleteSupportException("support block is unavailable for ground physics");
        return support;
    }

    private static Vec3 addHorizontalInput(Vec3 velocity,Vec3 input,double acceleration) {
        return new Vec3(velocity.x()+input.x()*acceleration,velocity.y(),velocity.z()+input.z()*acceleration);
    }

    private static Vec3 movementInput(Context context) {
        int f=context.input().forward(),s=context.input().strafe();
        if (f==0&&s==0) return Vec3.ZERO;
        double x=s,z=f,len=Math.hypot(x,z);
        if (len>1){x/=len;z/=len;}
        double yaw=Math.toRadians(context.state().yaw());
        return new Vec3(x*Math.cos(yaw)-z*Math.sin(yaw),0,z*Math.cos(yaw)+x*Math.sin(yaw));
    }

    private static String movementMode(Context c,Player s) {
        if ("spectator".equals(s.gamemode())) return "SPECTATOR";
        if ("creative".equals(s.gamemode())&&c.flying()) return "CREATIVE_FLYING";
        if (c.movementEnvironment().gliding()) return "FALL_FLYING";
        if (c.movementEnvironment().climbable()) return "CLIMBING";
        if (c.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER) return "WATER";
        if (c.movementEnvironment().fluid()==Phase5Mechanics.Fluid.LAVA) return "LAVA";
        return s.onGround()?"GROUND":"AIR";
    }

    private Player richPlayer(Player source,Vec3 position,Vec3 velocity,boolean onGround,Phase5Mechanics.Pose pose,
                              Context context,boolean uncertain) {
        State.Environment environment=switch(context.environment()) {
            case WATER->State.Environment.WATER; case LAVA->State.Environment.LAVA;
            case CLIMBABLE->State.Environment.CLIMBABLE; case DRY->State.Environment.DRY;
            case UNKNOWN->State.Environment.UNKNOWN; };
        return new Player(position,velocity,source.yaw(),source.pitch(),onGround,source.gamemode(),
            source.effects(),source.awaitingTeleport(),uncertain,Optional.of(context.input()),context.attributes(),pose,
            environment,source.clientTickRange(),source.provenance(),source.uncertaintyReasons());
    }

    private StepResult uncertain(Context context,String message) {
        Player s=context.state();
        java.util.EnumSet<State.UncertaintyReason> reasons=java.util.EnumSet.noneOf(State.UncertaintyReason.class);
        reasons.addAll(s.uncertaintyReasons()); reasons.add(State.UncertaintyReason.UNKNOWN_ENVIRONMENT);
        Player next=new Player(s.position(),s.velocity(),s.yaw(),s.pitch(),s.onGround(),s.gamemode(),s.effects(),
            s.awaitingTeleport(),true,s.input(),s.attributes(),s.pose(),s.environment(),s.clientTickRange(),s.provenance(),reasons);
        return new StepResult(context.simulationTick(),next,false,false,false,false,false,false,false,"UNCERTAIN: "+message);
    }

    private static BlockBox box(Aabb a) {
        return new BlockBox(a.minX(),a.minY(),a.minZ(),a.maxX(),a.maxY(),a.maxZ());
    }

    private static final class IncompleteSupportException extends RuntimeException {
        IncompleteSupportException(String message){super(message);}
    }

    public record Context(long simulationTick,Player state,Simulation.AdvancedInput input,WorldSnapshot world,
                          Simulation.Environment environment,Simulation.Attributes attributes,
                          Phase5Mechanics.MovementEffects effects,Phase5Mechanics.Pose pose,
                          Phase5Mechanics.MovementEnvironment movementEnvironment,boolean sleeping,
                          boolean flying,EntityCollisions entityCollisions) implements Serializable {
        public Context(long tick,Player state,Simulation.AdvancedInput input,WorldSnapshot world,
                       Simulation.Environment environment,Simulation.Attributes attributes,
                       Phase5Mechanics.MovementEffects effects,Phase5Mechanics.Pose pose,
                       Phase5Mechanics.MovementEnvironment movementEnvironment,boolean sleeping) {
            this(tick,state,input,world,environment,attributes,effects,pose,movementEnvironment,sleeping,false,EntityCollisions.NONE_TRACKED);
        }
        public Context(long tick,Player state,Simulation.AdvancedInput input,WorldSnapshot world,
                       Simulation.Environment environment,Simulation.Attributes attributes,
                       Phase5Mechanics.MovementEffects effects,Phase5Mechanics.Pose pose,
                       Phase5Mechanics.MovementEnvironment movementEnvironment,boolean sleeping,
                       EntityCollisions entityCollisions) {
            this(tick,state,input,world,environment,attributes,effects,pose,movementEnvironment,sleeping,false,entityCollisions);
        }
        public Context {
            Objects.requireNonNull(state);Objects.requireNonNull(input);Objects.requireNonNull(world);
            Objects.requireNonNull(environment);Objects.requireNonNull(attributes);Objects.requireNonNull(effects);
            Objects.requireNonNull(pose);Objects.requireNonNull(movementEnvironment);Objects.requireNonNull(entityCollisions);
            if(simulationTick<0) throw new IllegalArgumentException("simulationTick must be non-negative");
        }
    }

    public record StepResult(long simulationTick,Player state,boolean collided,boolean stepAttempted,boolean stepSucceeded,
                             boolean collisionX,boolean collisionY,boolean collisionZ,boolean entityCollision,
                             String diagnostic) implements Serializable {
        public StepResult {Objects.requireNonNull(state);Objects.requireNonNull(diagnostic);}
    }
}