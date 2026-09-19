package dev.phantom.ac.world.v12111;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.BlockState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic Minecraft 1.21.11 block-state collision catalogue.
 *
 * <p>The checked-in data resource is generated from the public PrismarineJS
 * minecraft-data 1.21.11 block/state and blockCollisionShapes datasets. Collision
 * coordinates are stored as exact 1/32-block integers.</p>
 */
public final class BlockCollisionCatalogue12111 {
  private static final String RESOURCE="/phase4/block-collision-1.21.11.tsv";
  private record Spec(String[] names,String[][] values,int[] shapeIds){}
  private static final List<String> LINES=readLines();
  private static final Map<String,Spec> SPECS=loadSpecs();
  private static final Map<Integer,String> RAW_SHAPES=loadShapes();
  private static final ConcurrentHashMap<Integer,VoxelShape> SHAPE_CACHE=new ConcurrentHashMap<>();

  private BlockCollisionCatalogue12111(){}

  public static Optional<VoxelShape> shapeFor(BlockState state){
    if(state==null||state.isUnsupported())return Optional.empty();
    Spec spec=SPECS.get(state.blockId());
    if(spec==null)return Optional.empty();
    int id;
    if(spec.shapeIds().length==1)id=spec.shapeIds()[0];
    else{
      int index=0;
      for(int i=0;i<spec.names().length;i++){
        String value=state.properties().get(spec.names()[i]);
        if(value==null)return Optional.empty();
        String[] allowed=spec.values()[i];
        int vi=-1;
        for(int j=0;j<allowed.length;j++){
          if(allowed[j].equals(value)){vi=j;break;}
        }
        if(vi<0)return Optional.empty();
        index=index*allowed.length+vi;
      }
      if(index<0||index>=spec.shapeIds().length)return Optional.empty();
      id=spec.shapeIds()[index];
    }
    String raw=RAW_SHAPES.get(id);
    return raw==null?Optional.empty():Optional.of(shape(id,raw));
  }

  public static boolean knowsBlock(String blockId){return SPECS.containsKey(blockId);}

  private static VoxelShape shape(int id,String raw){
    return SHAPE_CACHE.computeIfAbsent(id,k->{
      ArrayList<BlockBox> boxes=new ArrayList<>();
      if(!raw.isEmpty()){
        for(String encoded:raw.split(";",-1)){
          if(encoded.isEmpty())continue;
          String[] p=encoded.split(",");
          if(p.length!=6)throw new IllegalStateException("invalid collision shape "+id);
          boxes.add(new BlockBox(
              Integer.parseInt(p[0])/32.0,Integer.parseInt(p[1])/32.0,Integer.parseInt(p[2])/32.0,
              Integer.parseInt(p[3])/32.0,Integer.parseInt(p[4])/32.0,Integer.parseInt(p[5])/32.0));
        }
      }
      return VoxelShape.localUnbounded(boxes);
    });
  }

  private static Map<String,Spec> loadSpecs(){
    Map<String,Spec> out=new HashMap<>();
    for(String line:LINES){
      String[] p=line.split("\\|",-1);
      if(p.length<4||p[0].charAt(0)!='B')continue;
      String[] propParts=p[2].isEmpty()?new String[0]:p[2].split(";");
      String[] names=new String[propParts.length];
      String[][] values=new String[propParts.length][];
      for(int i=0;i<propParts.length;i++){
        int eq=propParts[i].indexOf('=');
        if(eq<=0)throw new IllegalStateException("invalid property definition for "+p[1]);
        names[i]=propParts[i].substring(0,eq);
        values[i]=propParts[i].substring(eq+1).split(",");
      }
      int[] ids=Arrays.stream(p[3].split(",")).mapToInt(Integer::parseInt).toArray();
      out.put(p[1],new Spec(names,values,ids));
    }
    return Map.copyOf(out);
  }

  private static Map<Integer,String> loadShapes(){
    Map<Integer,String> out=new HashMap<>();
    for(String line:LINES){
      String[] p=line.split("\\|",-1);
      if(p.length<2||p[0].charAt(0)!='S')continue;
      out.put(Integer.parseInt(p[1]),p.length>2?p[2]:"");
    }
    return Map.copyOf(out);
  }

  private static List<String> readLines(){
    try(var in=BlockCollisionCatalogue12111.class.getResourceAsStream(RESOURCE)){
      if(in==null)throw new IllegalStateException("missing "+RESOURCE);
      try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
        return reader.lines().toList();
      }
    }catch(IOException e){
      throw new IllegalStateException("cannot load "+RESOURCE,e);
    }
  }
}
