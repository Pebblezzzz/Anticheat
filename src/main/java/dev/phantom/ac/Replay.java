package dev.phantom.ac;

import java.util.*;
import static dev.phantom.ac.State.*;
import static dev.phantom.ac.Timeline.*;
import static dev.phantom.ac.Packets.PacketFlag;

/** Deterministic state replay and first-divergence diagnostics. */
public final class Replay {
  private Replay() {}
  public record Frame(int index,Event event,Player before,Player after){public Frame{if(index<0)throw new IllegalArgumentException("frame index must be non-negative");Objects.requireNonNull(event);Objects.requireNonNull(before);Objects.requireNonNull(after);}}
  public record Result(List<Frame> frames){public Result{frames=List.copyOf(frames);}public List<Player> states(){return frames.stream().map(Frame::after).toList();}public Player finalState(){if(frames.isEmpty())throw new NoSuchElementException("empty replay has no final state");return frames.getLast().after();}}
  public record Divergence(int frameIndex,long serverTick,long sequence,String field,Object expected,Object actual){}

  /** Replays each canonical event exactly once. Duplicate capture records remain frames but do not advance semantic player state. */
  public static Result replay(Player start,Snapshot timeline){
    Objects.requireNonNull(start);Objects.requireNonNull(timeline);List<Frame> frames=new ArrayList<>();Player current=start;Set<Long> appliedSequences=new HashSet<>();int index=0;
    for(Event event:timeline.events()){
      Player before=current;long sequence=event.packet().sequence();boolean duplicate=event.packet().flags().contains(PacketFlag.DUPLICATE)||!appliedSequences.add(sequence);
      if(!duplicate) current=State.apply(current,event.packet());
      else current=new Player(current.position(),current.velocity(),current.yaw(),current.pitch(),current.onGround(),current.gamemode(),current.effects(),current.awaitingTeleport(),true);
      frames.add(new Frame(index++,event,before,current));
    }
    return new Result(frames);
  }
  public static Result reconstruct(Player start,Snapshot timeline){return replay(start,timeline);}
  public static Optional<Divergence> firstDivergence(Result expected,Result actual){
    int shared=Math.min(expected.frames().size(),actual.frames().size());
    for(int index=0;index<shared;index++){Frame left=expected.frames().get(index),right=actual.frames().get(index);if(!left.event().equals(right.event()))return Optional.of(new Divergence(index,left.event().serverTick(),left.event().packet().sequence(),"event",left.event(),right.event()));Optional<Divergence> state=stateDifference(index,left.event(),left.after(),right.after());if(state.isPresent())return state;}
    if(expected.frames().size()!=actual.frames().size()){Frame frame=expected.frames().size()>shared?expected.frames().get(shared):actual.frames().get(shared);return Optional.of(new Divergence(shared,frame.event().serverTick(),frame.event().packet().sequence(),"replay length",expected.frames().size(),actual.frames().size()));}
    return Optional.empty();
  }
  private static Optional<Divergence> stateDifference(int index,Event event,Player expected,Player actual){
    if(!expected.position().equals(actual.position()))return difference(index,event,"position",expected.position(),actual.position());
    if(!expected.velocity().equals(actual.velocity()))return difference(index,event,"velocity",expected.velocity(),actual.velocity());
    if(expected.yaw()!=actual.yaw())return difference(index,event,"yaw",expected.yaw(),actual.yaw());
    if(expected.pitch()!=actual.pitch())return difference(index,event,"pitch",expected.pitch(),actual.pitch());
    if(expected.onGround()!=actual.onGround())return difference(index,event,"onGround",expected.onGround(),actual.onGround());
    if(!expected.gamemode().equals(actual.gamemode()))return difference(index,event,"gamemode",expected.gamemode(),actual.gamemode());
    if(!expected.effects().equals(actual.effects()))return difference(index,event,"effects",expected.effects(),actual.effects());
    if(!expected.awaitingTeleport().equals(actual.awaitingTeleport()))return difference(index,event,"awaitingTeleport",expected.awaitingTeleport(),actual.awaitingTeleport());
    if(expected.uncertain()!=actual.uncertain())return difference(index,event,"uncertain",expected.uncertain(),actual.uncertain());
    return Optional.empty();
  }
  private static Optional<Divergence> difference(int index,Event event,String field,Object expected,Object actual){return Optional.of(new Divergence(index,event.serverTick(),event.packet().sequence(),field,expected,actual));}
}
