package dev.phantom.ac;

import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import static dev.phantom.ac.Maths.*;
import static dev.phantom.ac.State.Player;

/** Sole canonical 1.21.11 movement implementation used by Phase 5 and Phase 6. */
public final class Vanilla12111RichPhysics {
    public static final double GRAVITY=0.08,
            AIR_DRAG=0.98f,
            AIR_HORIZONTAL_FRICTION=0.91f,
            AIR_VERTICAL_DRAG=0.98f,
            AIR_ACCEL=0.02f,
            SPRINT_AIR_ACCEL=0.025999999f,
            GROUND_FRICTION=0.546f,
            WALK_ACCEL=0.98f,
            JUMP=0.42f,
            STEP_HEIGHT=0.6,
            INPUT_FRICTION=0.98f,
            FRICTION_SPEED_FACTOR=0.21600002f,
            SPRINT_JUMP_HORIZONTAL_BOOST=0.2,
            SPRINTING_SPEED_MULTIPLIER=1.3,
            AIR_VERTICAL_FRICTION=0.98f;
    private static final double
            SNEAKING_SPEED_MULTIPLIER=0.3,
            WATER_DRAG=0.9,
            LAVA_DRAG=0.5,
            CLIMB_MAX_DOWN=0.15,
            CLIMB_MAX_UP=0.15,
            GLIDE_GRAVITY=0.035,
            GROUND_PROBE=1.0E-4;

    public StepResult step(Context context){
        Objects.requireNonNull(context);Player s=context.state();
        if(s.awaitingTeleport().isPresent())return uncertain(context,"awaiting teleport confirmation; pre-correction motion is not integrated");
        if(context.environment()==Simulation.Environment.UNKNOWN)return uncertain(context,"movement environment is unknown");
        Phase5Mechanics.Pose requestedPose=Phase5Mechanics.nextPose(context.pose(),context.movementEnvironment(),context.sleeping());
        Phase5Mechanics.Pose pose=requestedPose;
        if(requestedPose!=context.pose()){
            Aabb requestedBox=Aabb.playerAt(s.position(),requestedPose);
            var worldCollision=dev.phantom.ac.world.WorldQueries.collisions(context.world(),new dev.phantom.ac.geometry.BlockBox(requestedBox.minX(),requestedBox.minY(),requestedBox.minZ(),requestedBox.maxX(),requestedBox.maxY(),requestedBox.maxZ()));
            var entityCollision=context.entityCollisions().boxesIn(new dev.phantom.ac.geometry.BlockBox(requestedBox.minX(),requestedBox.minY(),requestedBox.minZ(),requestedBox.maxX(),requestedBox.maxY(),requestedBox.maxZ()));
            if(!worldCollision.isDefinite()||!entityCollision.isDefinite())return uncertain(context,"pose transition crosses incomplete collision coverage");
            if(!worldCollision.isEmpty()||!entityCollision.isEmpty())pose=context.pose();
        }
        if(s.gamemode().equals("creative")||s.gamemode().equals("spectator"))return new StepResult(context.simulationTick(),richPlayer(s,s.position(),Vec3.ZERO,false,pose,context,false),false,false,false,false,false,false,false,"non-physical gamemode");
        if(!s.gamemode().equals("survival")&&!s.gamemode().equals("adventure"))return uncertain(context,"unsupported gamemode movement model");
        Aabb start=Aabb.playerAt(s.position(),pose);var startEntities=context.entityCollisions().boxesIn(new dev.phantom.ac.geometry.BlockBox(start.minX(),start.minY(),start.minZ(),start.maxX(),start.maxY(),start.maxZ()));if(!startEntities.isDefinite())return uncertain(context,"entity collision history is incomplete");
        if(context.world().hasUnknownOrUnsupported(new dev.phantom.ac.geometry.BlockBox(start.minX(),start.minY(),start.minZ(),start.maxX(),start.maxY(),start.maxZ())))return uncertain(context,"start collision volume is not fully known");
        double radians=Math.toRadians(s.yaw());
        boolean fluid=context.movementEnvironment().fluid()!=Phase5Mechanics.Fluid.NONE,
                climbing=context.movementEnvironment().climbable(),
                gliding=context.movementEnvironment().gliding();
        double inputMagnitude=Math.hypot(context.input().forward(),context.input().strafe());
        double inputScale=inputMagnitude>1.0?1.0/Math.sqrt(2.0):1.0;
        double inputAcceleration;
        if(fluid){
            inputAcceleration=inputMagnitude>1.0?AIR_ACCEL:AIR_ACCEL*INPUT_FRICTION;
        }else if(gliding||climbing){
            inputAcceleration=inputMagnitude>1.0?AIR_ACCEL:AIR_ACCEL*INPUT_FRICTION;
        }else if(s.onGround()){
            BlockState support=context.world().blockAtOrNull(
                    (int)Math.floor(s.position().x()),
                    (int)Math.floor(s.position().y()-GROUND_PROBE),
                    (int)Math.floor(s.position().z()));
            if(support==null||support.isUnsupported())
                return uncertain(context,"support block is unavailable for friction calculation");
            double slipperiness=BlockCatalogue12111.slipperiness(support);
            double movementSpeed=context.attributes().value()*context.effects().speedMultiplier();
            if(context.input().sprint())movementSpeed*=SPRINTING_SPEED_MULTIPLIER;
            if(context.input().sneak())movementSpeed*=SNEAKING_SPEED_MULTIPLIER;
            double frictionInfluencedSpeed=movementSpeed*FRICTION_SPEED_FACTOR
                    /(slipperiness*slipperiness*slipperiness);
            inputAcceleration=inputMagnitude>1.0
                    ?frictionInfluencedSpeed
                    :frictionInfluencedSpeed*INPUT_FRICTION;
        }else{
            double offGroundSpeed=context.input().sprint()?SPRINT_AIR_ACCEL:AIR_ACCEL;
            inputAcceleration=inputMagnitude>1.0
                    ?offGroundSpeed
                    :offGroundSpeed*INPUT_FRICTION;
        }if(gliding)inputAcceleration=AIR_ACCEL;
        Vec3 acceleration=new Vec3(inputScale*(context.input().strafe()*inputAcceleration*Math.cos(radians)-context.input().forward()*inputAcceleration*Math.sin(radians)),0,inputScale*(context.input().forward()*inputAcceleration*Math.cos(radians)+context.input().strafe()*inputAcceleration*Math.sin(radians)));Vec3 velocity=s.velocity().add(acceleration);
        if(climbing){if(context.input().forward()>0)velocity=new Vec3(velocity.x(),CLIMB_MAX_UP,velocity.z());else if(context.input().forward()<0)velocity=new Vec3(velocity.x(),-CLIMB_MAX_DOWN,velocity.z());else velocity=new Vec3(velocity.x(),Math.max(-CLIMB_MAX_DOWN,velocity.y()),velocity.z());}
        boolean jumped=context.input().jump()&&s.onGround()&&!fluid&&!climbing&&!gliding&&!context.sleeping();
        if(jumped){
            velocity=new Vec3(velocity.x(),JUMP+context.effects().jumpVelocityAdd(),velocity.z());
            if(context.input().sprint()){
                velocity=velocity.add(new Vec3(
                        -Math.sin(radians)*SPRINT_JUMP_HORIZONTAL_BOOST,
                        0.0,
                        Math.cos(radians)*SPRINT_JUMP_HORIZONTAL_BOOST));
            }
        }
        if(context.effects().levitation())
            velocity=new Vec3(velocity.x(),context.effects().levitationVelocity(),velocity.z());
        double gravity=GRAVITY*context.movementEnvironment().gravityMultiplier();
        RichWorldCollision.Result collision=RichWorldCollision.resolve(context.world(),start,velocity,s.onGround()&&!fluid&&!climbing&&!gliding?STEP_HEIGHT:0,context.entityCollisions());if(collision.uncertain())return uncertain(context,collision.diagnostic());Vec3 displacement=collision.displacement();
        boolean supported=false;if(s.onGround()&&velocity.y()<=0&&!fluid&&!climbing&&!gliding){RichWorldCollision.Result probe=RichWorldCollision.resolve(context.world(),start,new Vec3(0,-GROUND_PROBE,0),0,context.entityCollisions());if(probe.uncertain())return uncertain(context,probe.diagnostic());supported=probe.collidedY();}
        boolean grounded=velocity.y()<=0&&(collision.collidedY()||supported);
        double horizontalFactor;if(context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER)horizontalFactor=context.movementEnvironment().fluidSpeedMultiplier()*WATER_DRAG;else if(context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.LAVA)horizontalFactor=context.movementEnvironment().fluidSpeedMultiplier()*LAVA_DRAG;else if(climbing)horizontalFactor=s.onGround()?GROUND_FRICTION:AIR_HORIZONTAL_FRICTION;else if(gliding)horizontalFactor=AIR_DRAG;else if(s.onGround()){
            boolean horizontalMotion=Math.hypot(velocity.x(),velocity.z())>1.0E-12;
            if(!horizontalMotion){
                horizontalFactor=1.0;
            } else {
                BlockState support=context.world().blockAtOrNull((int)Math.floor(s.position().x()),(int)Math.floor(s.position().y()-GROUND_PROBE),(int)Math.floor(s.position().z()));
                if(support==null||support.isUnsupported())return uncertain(context,"support block is unavailable for friction calculation");
                horizontalFactor=BlockCatalogue12111.slipperiness(support)*AIR_HORIZONTAL_FRICTION;
            }
        } else horizontalFactor=AIR_HORIZONTAL_FRICTION;
        double postTickVerticalVelocity;if(context.effects().levitation())postTickVerticalVelocity=context.effects().levitationVelocity();else if(climbing)postTickVerticalVelocity=velocity.y();else if(context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER||context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.LAVA)postTickVerticalVelocity=velocity.y()*context.movementEnvironment().fluidDrag()-gravity;else if(gliding)postTickVerticalVelocity=velocity.y()-GLIDE_GRAVITY;else postTickVerticalVelocity=(jumped?(velocity.y()-gravity*context.effects().fallGravityMultiplier())*AIR_VERTICAL_DRAG:velocity.y()*AIR_VERTICAL_DRAG-gravity*context.effects().fallGravityMultiplier()*AIR_VERTICAL_DRAG);
        double groundedVerticalVelocity=context.effects().levitation()?context.effects().levitationVelocity():climbing?velocity.y():(context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER||context.movementEnvironment().fluid()==Phase5Mechanics.Fluid.LAVA)?velocity.y()*context.movementEnvironment().fluidDrag()-gravity:(supported&&s.onGround()&&Math.abs(velocity.y())<=1.0E-12?0.0:-gravity*AIR_VERTICAL_DRAG*context.effects().fallGravityMultiplier());double nextY=grounded&& !context.effects().levitation()&&!climbing?groundedVerticalVelocity:postTickVerticalVelocity;Vec3 nextVelocity=new Vec3(collision.collidedX()?0:velocity.x()*horizontalFactor,nextY,collision.collidedZ()?0:velocity.z()*horizontalFactor);
        Phase5Mechanics.Pose nextPose=Phase5Mechanics.nextPose(pose,context.movementEnvironment(),context.sleeping());Player next=richPlayer(s,s.position().add(displacement),nextVelocity,grounded,nextPose,context,false);return new StepResult(context.simulationTick(),next,collision.collidedX()||collision.collidedY()||collision.collidedZ(),collision.stepAttempted(),collision.stepSucceeded(),collision.collidedX(),collision.collidedY(),collision.collidedZ(),collision.collidedX()||collision.collidedY()||collision.collidedZ(),collision.diagnostic());
    }

    private Player richPlayer(Player source,Vec3 position,Vec3 velocity,boolean onGround,Phase5Mechanics.Pose pose,Context context,boolean uncertain){State.Environment environment=switch(context.environment()){case WATER->State.Environment.WATER;case LAVA->State.Environment.LAVA;case CLIMBABLE->State.Environment.CLIMBABLE;case DRY->State.Environment.DRY;case UNKNOWN->State.Environment.UNKNOWN;};return new Player(position,velocity,source.yaw(),source.pitch(),onGround,source.gamemode(),source.effects(),source.awaitingTeleport(),uncertain,Optional.of(context.input()),context.attributes(),pose,environment,source.clientTickRange(),source.provenance(),source.uncertaintyReasons());}
    private StepResult uncertain(Context context,String message){return new StepResult(context.simulationTick(),richPlayer(context.state(),context.state().position(),context.state().velocity(),context.state().onGround(),context.pose(),context,true),false,false,false,false,false,false,false,message);}
    public record Context(long simulationTick,Player state,Simulation.AdvancedInput input,WorldSnapshot world,Simulation.Environment environment,Simulation.Attributes attributes,Phase5Mechanics.MovementEffects effects,Phase5Mechanics.Pose pose,Phase5Mechanics.MovementEnvironment movementEnvironment,boolean sleeping,EntityCollisions entityCollisions) implements Serializable {
        public Context(long tick,Player state,Simulation.AdvancedInput input,WorldSnapshot world,Simulation.Environment environment,Simulation.Attributes attributes,Phase5Mechanics.MovementEffects effects,Phase5Mechanics.Pose pose,Phase5Mechanics.MovementEnvironment movementEnvironment,boolean sleeping){this(tick,state,input,world,environment,attributes,effects,pose,movementEnvironment,sleeping,EntityCollisions.NONE_TRACKED);}
        public Context{Objects.requireNonNull(state);Objects.requireNonNull(input);Objects.requireNonNull(world);Objects.requireNonNull(environment);Objects.requireNonNull(attributes);Objects.requireNonNull(effects);Objects.requireNonNull(pose);Objects.requireNonNull(movementEnvironment);Objects.requireNonNull(entityCollisions);if(simulationTick<0)throw new IllegalArgumentException("simulationTick must be non-negative");}
    }
    public record StepResult(long simulationTick,Player state,boolean collided,boolean stepAttempted,boolean stepSucceeded,boolean collisionX,boolean collisionY,boolean collisionZ,boolean entityCollision,String diagnostic) implements Serializable {public StepResult{Objects.requireNonNull(state);Objects.requireNonNull(diagnostic);}}
}