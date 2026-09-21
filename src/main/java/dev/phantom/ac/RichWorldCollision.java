package dev.phantom.ac;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import static dev.phantom.ac.Maths.*;

/** Deterministic collision resolver over the immutable client-visible world plus tracked entity AABBs. */
public final class RichWorldCollision {
  /** Client collision probing epsilon used by the reference movement implementation. */
  private static final double COLLISION_EPSILON = 1.0E-7;
  private RichWorldCollision() {}

  public record Result(Vec3 displacement,boolean collidedX,boolean collidedY,boolean collidedZ,boolean stepAttempted,boolean stepSucceeded,boolean uncertain,String diagnostic) implements Serializable {}

  public static Result resolve(
      WorldSnapshot world,Aabb start,Vec3 requested,double stepHeight){
    return resolve(world,start,requested,stepHeight,EntityCollisions.of(List.of()));
  }

  public static Result resolve(
      WorldSnapshot world,Aabb start,Vec3 requested,double stepHeight,EntityCollisions entities){
    Objects.requireNonNull(world);
    Objects.requireNonNull(start);
    Objects.requireNonNull(requested);
    Objects.requireNonNull(entities);
    if(!Double.isFinite(stepHeight)||stepHeight<0)
      throw new IllegalArgumentException("stepHeight must be finite and non-negative");
    if(requested.x()==0.0&&requested.y()==0.0&&requested.z()==0.0)
      return new Result(Vec3.ZERO,false,false,false,false,false,false,"zero movement");

    BlockBox startBox=box(start);
    boolean stepEligible=stepHeight>0.0&&requested.y()<=0.0;
    BlockBox query=expandTowards(startBox,
        requested.x(),requested.y(),requested.z());
    if(stepEligible){
      query=new BlockBox(
          query.minX(),query.minY(),query.minZ(),
          query.maxX(),query.maxY()+stepHeight,query.maxZ());
    }
    if(world.hasUnknownOrUnsupported(query))
      return new Result(
          Vec3.ZERO,false,false,false,false,false,true,
          "rich world snapshot does not fully cover swept movement volume problems="
              +coverageProblems(world,query));

    EntityCollisions.EntityCollisionResult entityResult=entities.boxesIn(query);
    if(!entityResult.isDefinite())
      return new Result(Vec3.ZERO,false,false,false,false,false,true,
          "entity collision history is incomplete");

    dev.phantom.ac.world.WorldQueries.CollisionResult blockResult=
        dev.phantom.ac.world.WorldQueries.collisions(world,query);
    if(!blockResult.isDefinite())
      return new Result(Vec3.ZERO,false,false,false,false,false,true,
          "rich world snapshot collision query is incomplete coverage="+blockResult.coverage());

    List<BlockBox> blockBoxes=blockResult.collisions().stream()
        .map(dev.phantom.ac.world.WorldQueries.Collision::box)
        .toList();
    List<EntityCollisions.EntityBox> entityBoxes=entityResult.boxes();

    List<List<Axis>> axisOrders=List.of(
        List.of(Axis.Y,Axis.X,Axis.Z),
        List.of(Axis.Y,Axis.Z,Axis.X));

    Vec3 bestResult=null;
    double bestScore=Double.POSITIVE_INFINITY;
    boolean bestStepAttempted=false;
    boolean bestStepSucceeded=false;
    boolean bestX=false,bestY=false,bestZ=false;
    String bestDiagnostic="rich voxel/entity movement";

    for(List<Axis> order:axisOrders){
      AxisResult direct=collideWithMovementEpsilon(
          requested,startBox,blockBoxes,entityBoxes,order);
      Vec3 directVector=new Vec3(direct.x(),direct.y(),direct.z());
      double directScore=movementScore(directVector,requested,stepHeight);
      if(directScore<bestScore){
        bestScore=directScore;
        bestResult=directVector;
        bestStepAttempted=false;
        bestStepSucceeded=false;
        bestX=direct.collidedX();
        bestY=direct.collidedY();
        bestZ=direct.collidedZ();
        bestDiagnostic=(bestX||bestY||bestZ)
            ?"rich voxel/entity collision":"rich voxel/entity movement";
      }

      if(!stepEligible||( !direct.collidedX()&&!direct.collidedZ()))
        continue;

      boolean movingIntoGroundReal=requested.y()<0.0
          || Math.abs(direct.y()-requested.y())>1.0E-12;
      BlockBox startingOffsetBox=startBox.move(
          0.0,movingIntoGroundReal?direct.y():0.0,0.0);
      BlockBox stepQuery=expandForStep(
          startingOffsetBox,requested.x(),stepHeight,requested.z(),
          movingIntoGroundReal);
      if(world.hasUnknownOrUnsupported(stepQuery))
        return new Result(
            directVector,direct.collidedX(),direct.collidedY(),direct.collidedZ(),
            true,false,true,
            "step candidate crosses unknown or unsupported world coverage problems="
                +coverageProblems(world,stepQuery));

      dev.phantom.ac.world.WorldQueries.CollisionResult stepBlockResult=
          dev.phantom.ac.world.WorldQueries.collisions(world,stepQuery);
      EntityCollisions.EntityCollisionResult stepEntityResult=entities.boxesIn(stepQuery);
      if(!stepBlockResult.isDefinite()||!stepEntityResult.isDefinite())
        return new Result(
            directVector,direct.collidedX(),direct.collidedY(),direct.collidedZ(),
            true,false,true,
            "step candidate collision history is incomplete");

      List<BlockBox> stepBoxes=stepBlockResult.collisions().stream()
          .map(dev.phantom.ac.world.WorldQueries.Collision::box)
          .toList();
      List<BlockBox> stepEntityBoxes=stepEntityResult.boxes().stream()
          .map(EntityCollisions.EntityBox::box)
          .toList();
      List<BlockBox> allStepBoxes=new java.util.ArrayList<>(
          stepBoxes.size()+stepEntityBoxes.size());
      allStepBoxes.addAll(stepBoxes);
      allStepBoxes.addAll(stepEntityBoxes);

      List<Double> stepHeights=collectStepHeights(
          startingOffsetBox,allStepBoxes,stepHeight,direct.y());

      double verticalOffset=startingOffsetBox.minY()-startBox.minY();
      for(double candidateHeight:stepHeights){
        AxisResult steppedResult=collideBoundingBox(
            new Vec3(requested.x(),candidateHeight,requested.z()),
            startingOffsetBox,stepBoxes,stepEntityResult.boxes(),order);
        double directHorizontal=horizontalDistanceSquared(directVector);
        double steppedHorizontal=horizontalDistanceSquared(
            new Vec3(steppedResult.x(),steppedResult.y(),steppedResult.z()));
        if(steppedHorizontal<=directHorizontal+1.0E-12)
          continue;

        Vec3 steppedVector=new Vec3(
            steppedResult.x(),
            steppedResult.y()+verticalOffset,
            steppedResult.z());
        double steppedScore=movementScore(steppedVector,requested,stepHeight);
        if(steppedScore<bestScore){
          bestScore=steppedScore;
          bestResult=steppedVector;
          bestStepAttempted=true;
          bestStepSucceeded=true;
          bestX=steppedResult.collidedX();
          bestY=steppedResult.collidedY();
          bestZ=steppedResult.collidedZ();
          bestDiagnostic="rich voxel/entity step candidate accepted"
              +" candidateHeight="+candidateHeight
              +" directHorizontal="+directHorizontal
              +" steppedHorizontal="+steppedHorizontal
              +" movingIntoGroundReal="+movingIntoGroundReal;
        }
        break;
      }
      if(stepHeights.isEmpty()&&bestResult!=null
          && bestResult.x()==directVector.x()
          && bestResult.y()==directVector.y()
          && bestResult.z()==directVector.z()){
        bestStepAttempted=true;
        bestDiagnostic="rich voxel/entity step candidate rejected; no valid voxel step height";
      }
    }

    if(bestResult==null)
      return new Result(Vec3.ZERO,false,false,false,false,false,false,"no collision result");

    return new Result(
        bestResult,bestX,bestY,bestZ,bestStepAttempted,bestStepSucceeded,false,bestDiagnostic);
  }

  private record AxisResult(double x,double y,double z,
                            boolean collidedX,boolean collidedY,boolean collidedZ){}

  /*
   * Probe a tiny signed epsilon past the requested movement, like the client-side
   * collision path, then remove the epsilon again when that probe did not collide.
   * This prevents exact face-contact rounding from changing the collision result.
   */
  private static AxisResult collideWithMovementEpsilon(
      Vec3 requested,BlockBox start,List<BlockBox> blocks,
      List<EntityCollisions.EntityBox> entities,List<Axis> order){
    Vec3 probe=new Vec3(
        requested.x()+Math.copySign(COLLISION_EPSILON,requested.x()),
        requested.y()+Math.copySign(COLLISION_EPSILON,requested.y()),
        requested.z()+Math.copySign(COLLISION_EPSILON,requested.z()));
    AxisResult result=collideBoundingBox(probe,start,blocks,entities,order);
    double x=result.collidedX()?result.x():result.x()-Math.copySign(COLLISION_EPSILON,requested.x());
    double y=result.collidedY()?result.y():result.y()-Math.copySign(COLLISION_EPSILON,requested.y());
    double z=result.collidedZ()?result.z():result.z()-Math.copySign(COLLISION_EPSILON,requested.z());
    return new AxisResult(x,y,z,result.collidedX(),result.collidedY(),result.collidedZ());
  }

  private static AxisResult collideBoundingBox(
      Vec3 requested,BlockBox start,List<BlockBox> blockBoxes,
      List<EntityCollisions.EntityBox> entities,List<Axis> order){
    double x=requested.x(),y=requested.y(),z=requested.z();
    BlockBox current=start;
    for(Axis axis:order){
      double amount=switch(axis){
        case X -> x;
        case Y -> y;
        case Z -> z;
      };
      if(amount==0.0)continue;
      AxisResult axisResult=clipAxis(current,axis,amount,blockBoxes,entities);
      switch(axis){
        case X -> { x=axisResult.x(); current=current.move(x,0,0); }
        case Y -> { y=axisResult.y(); current=current.move(0,y,0); }
        case Z -> { z=axisResult.z(); current=current.move(0,0,z); }
      }
    }
    return new AxisResult(
        x,y,z,
        axisCollided(requested.x(),x),
        axisCollided(requested.y(),y),
        axisCollided(requested.z(),z));
  }

  private static boolean axisCollided(double requested,double actual){
    return Math.abs(requested-actual)>1.0E-12;
  }

  private static BlockBox expandTowards(
      BlockBox box,double x,double y,double z){
    double minX=x<0?box.minX()+x:box.minX();
    double maxX=x>0?box.maxX()+x:box.maxX();
    double minY=y<0?box.minY()+y:box.minY();
    double maxY=y>0?box.maxY()+y:box.maxY();
    double minZ=z<0?box.minZ()+z:box.minZ();
    double maxZ=z>0?box.maxZ()+z:box.maxZ();
    return new BlockBox(minX,minY,minZ,maxX,maxY,maxZ);
  }

  private static BlockBox expandForStep(
      BlockBox box,double desiredX,double stepHeight,double desiredZ,
      boolean movingIntoGroundReal){
    BlockBox expanded=expandTowards(box,desiredX,0.0,desiredZ);
    double maxY=box.maxY()+stepHeight;
    if(!movingIntoGroundReal)maxY=Math.max(box.maxY()+stepHeight,box.maxY()-1.0E-5);
    return new BlockBox(
        expanded.minX(),expanded.minY(),expanded.minZ(),
        expanded.maxX(),maxY,expanded.maxZ());
  }

  private static List<Double> collectStepHeights(
      BlockBox collisionBox,List<BlockBox> collisions,
      double stepHeight,double collideY){
    java.util.TreeSet<Double> heights=new java.util.TreeSet<>();
    for(BlockBox collision:collisions){
      addStepHeight(heights,collision.minY(),collisionBox.minY(),stepHeight,collideY);
      addStepHeight(heights,collision.maxY(),collisionBox.minY(),stepHeight,collideY);
    }
    return List.copyOf(heights);
  }

  private static void addStepHeight(
      java.util.Set<Double> heights,double pointY,double baseY,
      double maxStep,double collideY){
    double yDiff=pointY-baseY;
    if(yDiff < -1.0E-7 || yDiff > maxStep+1.0E-7) return;
    if(Math.abs(yDiff-collideY)<=1.0E-7) return;
    heights.add(yDiff);
  }

  private static double horizontalDistanceSquared(Vec3 v){
    return v.x()*v.x()+v.z()*v.z();
  }

  private static double movementScore(Vec3 actual,Vec3 requested,double stepHeight){
    double desiredY=Math.max(-1.0,Math.min(stepHeight,requested.y()));
    double dx=actual.x()-requested.x();
    double dy=actual.y()-desiredY;
    double dz=actual.z()-requested.z();
    return dx*dx+dy*dy+dz*dz;
  }

  private static AxisResult clipAxis(
      BlockBox moving,Axis axis,double requested,
      List<BlockBox> blockBoxes,List<EntityCollisions.EntityBox> entities){
    double result=requested;
    if(requested==0.0)
      return new AxisResult(0.0,0.0,0.0,false,false,false);

    for(BlockBox collision:blockBoxes)
      result=clipBox(axis,moving,collision,result);
    for(EntityCollisions.EntityBox entity:entities)
      result=clipBox(axis,moving,entity.box(),result);

    return switch(axis){
      case X -> new AxisResult(result,0.0,0.0,axisCollided(requested,result),false,false);
      case Y -> new AxisResult(0.0,result,0.0,false,axisCollided(requested,result),false);
      case Z -> new AxisResult(0.0,0.0,result,false,false,axisCollided(requested,result));
    };
  }

  private static double clipBox(
      Axis axis,BlockBox moving,BlockBox obstacle,double amount){
    boolean cross=switch(axis){
      case X -> moving.maxY()>obstacle.minY()&&moving.minY()<obstacle.maxY()
          &&moving.maxZ()>obstacle.minZ()&&moving.minZ()<obstacle.maxZ();
      case Y -> moving.maxX()>obstacle.minX()&&moving.minX()<obstacle.maxX()
          &&moving.maxZ()>obstacle.minZ()&&moving.minZ()<obstacle.maxZ();
      case Z -> moving.maxX()>obstacle.minX()&&moving.minX()<obstacle.maxX()
          &&moving.maxY()>obstacle.minY()&&moving.minY()<obstacle.maxY();
    };
    if(!cross)return amount;
    if(axis==Axis.X){
      if(amount>0.0&&moving.maxX()<=obstacle.minX())
        return Math.min(amount,obstacle.minX()-moving.maxX());
      if(amount<0.0&&moving.minX()>=obstacle.maxX())
        return Math.max(amount,obstacle.maxX()-moving.minX());
    }else if(axis==Axis.Y){
      if(amount>0.0&&moving.maxY()<=obstacle.minY())
        return Math.min(amount,obstacle.minY()-moving.maxY());
      if(amount<0.0&&moving.minY()>=obstacle.maxY())
        return Math.max(amount,obstacle.maxY()-moving.minY());
    }else{
      if(amount>0.0&&moving.maxZ()<=obstacle.minZ())
        return Math.min(amount,obstacle.minZ()-moving.maxZ());
      if(amount<0.0&&moving.minZ()>=obstacle.maxZ())
        return Math.max(amount,obstacle.maxZ()-moving.minZ());
    }
    return amount;
  }

  private static double clipAabb(Axis axis,BlockBox moving,BlockBox obstacle,double amount){
    boolean cross=switch(axis){case X->moving.maxY()>obstacle.minY()&&moving.minY()<obstacle.maxY()&&moving.maxZ()>obstacle.minZ()&&moving.minZ()<obstacle.maxZ();case Y->moving.maxX()>obstacle.minX()&&moving.minX()<obstacle.maxX()&&moving.maxZ()>obstacle.minZ()&&moving.minZ()<obstacle.maxZ();case Z->moving.maxX()>obstacle.minX()&&moving.minX()<obstacle.maxX()&&moving.maxY()>obstacle.minY()&&moving.minY()<obstacle.maxY();};
    if(!cross)return amount;
    if(axis==Axis.X){if(amount>0&&moving.maxX()<=obstacle.minX())return Math.min(amount,obstacle.minX()-moving.maxX());if(amount<0&&moving.minX()>=obstacle.maxX())return Math.max(amount,obstacle.maxX()-moving.minX());}
    else if(axis==Axis.Y){if(amount>0&&moving.maxY()<=obstacle.minY())return Math.min(amount,obstacle.minY()-moving.maxY());if(amount<0&&moving.minY()>=obstacle.maxY())return Math.max(amount,obstacle.maxY()-moving.minY());}
    else {if(amount>0&&moving.maxZ()<=obstacle.minZ())return Math.min(amount,obstacle.minZ()-moving.maxZ());if(amount<0&&moving.minZ()>=obstacle.maxZ())return Math.max(amount,obstacle.maxZ()-moving.minZ());}
    return amount;
  }

  private static String coverageProblems(WorldSnapshot world,BlockBox query){
    List<WorldSnapshot.CoverageProblem> problems=world.coverageProblemsIn(query);
    if(problems.isEmpty())return "[]";
    StringBuilder result=new StringBuilder("[");
    for(int i=0;i<problems.size();i++){
      if(i>0)result.append(", ");
      WorldSnapshot.CoverageProblem problem=problems.get(i);
      result.append(problem.coverage()).append('@').append(problem.position())
          .append(" ").append(problem.detail());
    }
    return result.append(']').toString();
  }

  private static BlockBox box(Aabb box){return new BlockBox(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ());}
}
