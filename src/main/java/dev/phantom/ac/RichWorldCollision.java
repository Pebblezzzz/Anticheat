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
  private RichWorldCollision() {}

  public record Result(Vec3 displacement,boolean collidedX,boolean collidedY,boolean collidedZ,boolean stepAttempted,boolean stepSucceeded,boolean uncertain,String diagnostic) implements Serializable {}

  public static Result resolve(WorldSnapshot world,Aabb start,Vec3 requested,double stepHeight){return resolve(world,start,requested,stepHeight,EntityCollisions.of(List.of()));}

  public static Result resolve(WorldSnapshot world,Aabb start,Vec3 requested,double stepHeight,EntityCollisions entities){
    Objects.requireNonNull(world);Objects.requireNonNull(start);Objects.requireNonNull(requested);Objects.requireNonNull(entities);
    if(!Double.isFinite(stepHeight)||stepHeight<0)throw new IllegalArgumentException("stepHeight must be finite and non-negative");
    BlockBox startBox=box(start),sweptBox=startBox.enclose(startBox.move(requested.x(),requested.y(),requested.z()));
    EntityCollisions.EntityCollisionResult entityResult=entities.boxesIn(sweptBox);
    if(!entityResult.isDefinite())return new Result(Vec3.ZERO,false,false,false,false,false,true,"entity collision history is incomplete");
    if(world.hasUnknownOrUnsupported(sweptBox))return new Result(
        Vec3.ZERO,false,false,false,false,false,true,
        "rich world snapshot does not fully cover swept movement volume"
            + " problems=" + coverageProblems(world,sweptBox));
    List<EntityCollisions.EntityBox> entityBoxes=entityResult.boxes();

    AxisResult vertical=clipAxis(world,startBox,Axis.Y,requested.y(),entityBoxes);BlockBox afterY=startBox.move(0,vertical.amount(),0);
    boolean xFirst=Math.abs(requested.x())>=Math.abs(requested.z());
    AxisResult first=xFirst?clipAxis(world,afterY,Axis.X,requested.x(),entityBoxes):clipAxis(world,afterY,Axis.Z,requested.z(),entityBoxes);
    BlockBox afterFirst=xFirst?afterY.move(first.amount(),0,0):afterY.move(0,0,first.amount());
    AxisResult second=xFirst?clipAxis(world,afterFirst,Axis.Z,requested.z(),entityBoxes):clipAxis(world,afterFirst,Axis.X,requested.x(),entityBoxes);
    AxisResult horizontalX=xFirst?first:second;
    AxisResult horizontalZ=xFirst?second:first;
    Vec3 direct=new Vec3(horizontalX.amount(),vertical.amount(),horizontalZ.amount());boolean collided=vertical.collided()||horizontalX.collided()||horizontalZ.collided();
    if(stepHeight<=0||requested.y()>0||!(horizontalX.collided()||horizontalZ.collided()))return new Result(direct,horizontalX.collided(),vertical.collided(),horizontalZ.collided(),false,false,false,collided?"rich voxel/entity collision":"rich voxel/entity movement");

    Aabb raised=start.move(new Vec3(0,stepHeight,0));BlockBox raisedBox=box(raised),raisedSweep=raisedBox.enclose(raisedBox.move(requested.x(),requested.y(),requested.z()));
    if(world.hasUnknownOrUnsupported(raisedSweep))return new Result(
        direct,horizontalX.collided(),vertical.collided(),horizontalZ.collided(),true,false,true,
        "step candidate crosses unknown or unsupported world coverage"
            + " problems=" + coverageProblems(world,raisedSweep));
    AxisResult sx=clipAxis(world,raisedBox,Axis.X,requested.x(),entityBoxes);BlockBox bx=raisedBox.move(sx.amount(),0,0);
    AxisResult sz=clipAxis(world,bx,Axis.Z,requested.z(),entityBoxes);BlockBox steppedHorizontal=bx.move(0,0,sz.amount());
    AxisResult sy=clipAxis(world,steppedHorizontal,Axis.Y,-stepHeight+requested.y(),entityBoxes);
    Vec3 stepped=new Vec3(sx.amount(),stepHeight+sy.amount(),sz.amount());double directHorizontal=horizontalX.amount()*horizontalX.amount()+horizontalZ.amount()*horizontalZ.amount();double steppedHorizontalDistance=stepped.x()*stepped.x()+stepped.z()*stepped.z();
    boolean success=steppedHorizontalDistance>directHorizontal+1.0E-12&&stepped.y()>=0;
    if(!success)return new Result(direct,horizontalX.collided(),vertical.collided(),horizontalZ.collided(),true,false,false,"rich voxel/entity step candidate rejected; direct path retained");
    return new Result(stepped,sx.collided(),sy.collided(),sz.collided(),true,true,false,"rich voxel/entity step candidate accepted");
  }

  private record AxisResult(double amount,boolean collided){}

  private static AxisResult clipAxis(WorldSnapshot world,BlockBox moving,Axis axis,double requested,List<EntityCollisions.EntityBox> entities){
    if(requested==0.0)return new AxisResult(0.0,false);double result=requested;
    BlockBox sweep=moving.move(axis==Axis.X?requested:0.0,axis==Axis.Y?requested:0.0,axis==Axis.Z?requested:0.0);
    int minX=(int)Math.floor(Math.min(moving.minX(),sweep.minX())),maxX=(int)Math.floor(Math.max(moving.maxX(),sweep.maxX()));
    int minY=(int)Math.floor(Math.min(moving.minY(),sweep.minY())),maxY=(int)Math.floor(Math.max(moving.maxY(),sweep.maxY()));
    int minZ=(int)Math.floor(Math.min(moving.minZ(),sweep.minZ())),maxZ=(int)Math.floor(Math.max(moving.maxZ(),sweep.maxZ()));
    for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++){VoxelShape shape=world.collisionShapeAt(x,y,z);if(shape.isEmpty())continue;double clipped=shape.clip(axis,moving,result);result=requested>0?Math.min(result,clipped):Math.max(result,clipped);}
    for(EntityCollisions.EntityBox entity:entities){double clipped=clipAabb(axis,moving,entity.box(),result);result=requested>0?Math.min(result,clipped):Math.max(result,clipped);}
    return new AxisResult(result,Math.abs(result-requested)>1.0E-12);
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
      result.append(problem.coverage()).append('@').append(problem.position());
    }
    return result.append(']').toString();
  }

  private static BlockBox box(Aabb box){return new BlockBox(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ());}
}
