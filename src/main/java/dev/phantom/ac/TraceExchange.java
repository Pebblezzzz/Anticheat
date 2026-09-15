package dev.phantom.ac;

import java.util.*; import static dev.phantom.ac.Maths.*; import static dev.phantom.ac.Simulation.*; import static dev.phantom.ac.State.*;
/**
 * Deliberately small interchange format for independently captured traces.
 * Expected values are observations from a vanilla client, never simulator output.
 */
public final class TraceExchange {
  private TraceExchange() {}
  public static final String HEADER="tick,x,y,z,vx,vy,vz,onGround,forward,strafe,jump,collision";
  public static List<String> write(Trace trace) { List<String> out=new ArrayList<>();out.add(HEADER);for(Frame f:trace.frames()){Player p=f.state();Input i=f.input();out.add(String.join(",",Long.toString(f.tick()),d(p.position().x()),d(p.position().y()),d(p.position().z()),d(p.velocity().x()),d(p.velocity().y()),d(p.velocity().z()),Boolean.toString(p.onGround()),Integer.toString(i.forward()),Integer.toString(i.strafe()),Boolean.toString(i.jump()),Boolean.toString(f.collision())));}return List.copyOf(out); }
  public static Trace read(List<String> lines) { if(lines.isEmpty()||!HEADER.equals(lines.getFirst()))throw new IllegalArgumentException("trace header is missing or incompatible");List<Frame> out=new ArrayList<>();long last=-1;for(int row=1;row<lines.size();row++){String line=lines.get(row).trim();if(line.isEmpty()||line.startsWith("#"))continue;String[] c=line.split(",",-1);if(c.length!=12)throw invalid(row,"expected 12 columns");try{long tick=Long.parseLong(c[0]);if(tick<=last)throw invalid(row,"ticks must strictly increase");last=tick;Player p=new Player(new Vec3(Double.parseDouble(c[1]),Double.parseDouble(c[2]),Double.parseDouble(c[3])),new Vec3(Double.parseDouble(c[4]),Double.parseDouble(c[5]),Double.parseDouble(c[6])),0,0,Boolean.parseBoolean(c[7]),"survival",Map.of(),OptionalInt.empty(),false);out.add(new Frame(tick,p,new Input(Integer.parseInt(c[8]),Integer.parseInt(c[9]),Boolean.parseBoolean(c[10])),Boolean.parseBoolean(c[11])));}catch(NumberFormatException e){throw invalid(row,"invalid number");}}return new Trace(out); }
  public static Trace simulate(Player start, Trace observed, World.History history, Vanilla12111Physics physics) { List<Frame> frames=new ArrayList<>();Player current=start;for(Frame observation:observed.frames()){current=physics.tick(current,observation.input(),history.at(observation.tick()));frames.add(new Frame(observation.tick(),current,observation.input(),observation.collision()));}return new Trace(frames); }
  private static String d(double value){return Double.toString(value);}
  private static IllegalArgumentException invalid(int row,String why){return new IllegalArgumentException("invalid trace row "+(row+1)+": "+why);}
}
