package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;
import static dev.phantom.ac.Maths.Vec3;
import static dev.phantom.ac.Packets.*;

/** Pure, immutable PlayerState reducer. The richer fields are part of the state value, not side metadata. */
public final class State {
  private State() {}

  public record Player(Vec3 position,Vec3 velocity,float yaw,float pitch,boolean onGround,String gamemode,Map<String,Integer> effects,OptionalInt awaitingTeleport,boolean uncertain,
      Optional<Simulation.AdvancedInput> input,Simulation.Attributes attributes,Phase5Mechanics.Pose pose,Environment environment,TickRange clientTickRange,Provenance provenance,Set<UncertaintyReason> uncertaintyReasons) implements Serializable {
    public Player {
      Objects.requireNonNull(position);Objects.requireNonNull(velocity);if(gamemode==null||gamemode.isBlank())throw new IllegalArgumentException("gamemode is required");effects=Map.copyOf(effects);Objects.requireNonNull(awaitingTeleport);input=Objects.requireNonNull(input);Objects.requireNonNull(attributes);Objects.requireNonNull(pose);Objects.requireNonNull(environment);Objects.requireNonNull(clientTickRange);Objects.requireNonNull(provenance);uncertaintyReasons=Set.copyOf(uncertaintyReasons);
      if(uncertain&&uncertaintyReasons.isEmpty())uncertaintyReasons=Set.of(UncertaintyReason.EXPLICIT_UNCERTAINTY);
    }
    /** Source-compatible constructor used by legacy tests and APIs; rich fields receive explicit unknown/default values. */
    public Player(Vec3 position,Vec3 velocity,float yaw,float pitch,boolean onGround,String gamemode,Map<String,Integer> effects,OptionalInt awaitingTeleport,boolean uncertain){this(position,velocity,yaw,pitch,onGround,gamemode,effects,awaitingTeleport,uncertain,Optional.empty(),new Simulation.Attributes(0.1),Phase5Mechanics.Pose.STANDING,Environment.UNKNOWN,TickRange.unknown(),Provenance.UNKNOWN,uncertain?Set.of(UncertaintyReason.EXPLICIT_UNCERTAINTY):Set.of());}
    public boolean isSurvival(){return gamemode.equals("survival");}
    public static Player initial(Vec3 position){return new Player(position,Vec3.ZERO,0,0,true,"survival",Map.of(),OptionalInt.empty(),false);}
    public Player withServerProvenance(long serverTick,NormalizedPacket event){return new Player(position,velocity,yaw,pitch,onGround,gamemode,effects,awaitingTeleport,uncertain,input,attributes,pose,environment,clientTickRange,new Provenance(event.sequence(),serverTick,event.packet().getClass().getSimpleName()),uncertaintyReasons);}
    public Player withUncertainty(UncertaintyReason reason){EnumSet<UncertaintyReason> u=EnumSet.noneOf(UncertaintyReason.class);u.addAll(uncertaintyReasons);u.add(reason);return new Player(position,velocity,yaw,pitch,onGround,gamemode,effects,awaitingTeleport,true,input,attributes,pose,environment,clientTickRange,provenance,u);}
  }

  public record TickRange(long min,long max,boolean exact) implements Serializable {
    public TickRange{if(min<0||max<min)throw new IllegalArgumentException("invalid tick range");}
    public static TickRange exact(long tick){return new TickRange(tick,tick,true);}
    public static TickRange unknown(){return new TickRange(0,Long.MAX_VALUE,false);}
  }
  public record Provenance(long sequence,long serverTick,String packetType) implements Serializable {
    public static final Provenance UNKNOWN=new Provenance(0,0,"UNKNOWN");
    public Provenance{if(sequence<0||serverTick<0||packetType==null||packetType.isBlank())throw new IllegalArgumentException("invalid provenance");}
  }
  public enum UncertaintyReason { DUPLICATE_PACKET,OUT_OF_ORDER,SEQUENCE_GAP,BEFORE_CAPTURE_EPOCH,MISSING_GROUND_BIT,UNKNOWN_CLIENT_TICK,UNKNOWN_INPUT,UNKNOWN_ENVIRONMENT,TELEPORT_CORRECTION,MISMATCHED_TELEPORT_ACK,UNSUPPORTED_GAMEMODE,EXPLICIT_UNCERTAINTY }
  public enum Fact { POSITION, ROTATION, VELOCITY, GROUND, INPUT, GAMEMODE, ENVIRONMENT, EFFECTS, TELEPORT, ATTRIBUTES, POSE, SYNCHRONIZATION }
  public enum Environment { UNKNOWN, DRY, WATER, LAVA, CLIMBABLE }

  public record Seed(Player player,Set<Fact> known,Environment environment){
    public Seed{Objects.requireNonNull(player);known=Set.copyOf(known);Objects.requireNonNull(environment);if(environment!=Environment.UNKNOWN&&!known.contains(Fact.ENVIRONMENT))throw new IllegalArgumentException("known environment requires ENVIRONMENT fact");}
    public static Seed serverAnchor(Player player){return new Seed(player,EnumSet.of(Fact.POSITION,Fact.ROTATION,Fact.VELOCITY,Fact.GROUND,Fact.GAMEMODE,Fact.EFFECTS,Fact.TELEPORT,Fact.ATTRIBUTES,Fact.POSE),Environment.UNKNOWN);}
  }
  public record StateFrame(int index,Timeline.Event event,Player before,Player after,Set<Fact> knownAfter,Set<Fact> refreshedByEvent,Optional<ClientInput> currentInput,Environment environment){
    public StateFrame{if(index<0)throw new IllegalArgumentException("frame index must be non-negative");Objects.requireNonNull(event);Objects.requireNonNull(before);Objects.requireNonNull(after);knownAfter=Set.copyOf(knownAfter);refreshedByEvent=Set.copyOf(refreshedByEvent);currentInput=Objects.requireNonNull(currentInput);Objects.requireNonNull(environment);}
  }
  public record Reconstruction(List<StateFrame> frames){public Reconstruction{frames=List.copyOf(frames);}public Optional<StateFrame> last(){return frames.isEmpty()?Optional.empty():Optional.of(frames.getLast());}}

  public static final class Reducer implements Contracts.PlayerStateReducer{@Override public Player apply(Player prior,NormalizedPacket event){return State.apply(prior,event);}}
  public static final class Reconstructor implements Contracts.PlayerStateReconstructor{@Override public Reconstruction reconstruct(Seed seed,Timeline.Snapshot timeline){return State.reconstruct(seed,timeline);}}

  public static Player apply(Player state,NormalizedPacket event){
    Objects.requireNonNull(state);Objects.requireNonNull(event);Packet packet=event.packet();EnumSet<PacketFlag> flags=event.flags();EnumSet<UncertaintyReason> reasons=EnumSet.noneOf(UncertaintyReason.class);reasons.addAll(state.uncertaintyReasons());if(flags.contains(PacketFlag.DUPLICATE))reasons.add(UncertaintyReason.DUPLICATE_PACKET);if(flags.contains(PacketFlag.OUT_OF_ORDER))reasons.add(UncertaintyReason.OUT_OF_ORDER);if(flags.contains(PacketFlag.SEQUENCE_GAP))reasons.add(UncertaintyReason.SEQUENCE_GAP);if(flags.contains(PacketFlag.BEFORE_CAPTURE_EPOCH))reasons.add(UncertaintyReason.BEFORE_CAPTURE_EPOCH);boolean uncertain=state.uncertain()||!reasons.isEmpty();
    if(packet instanceof Move move){Vec3 position=move.position()==null?state.position():move.position();float yaw=move.yaw()==null?state.yaw():move.yaw();float pitch=move.pitch()==null?state.pitch():move.pitch();boolean ground=move.onGround()==null?state.onGround():move.onGround();if(move.onGround()==null){reasons.add(UncertaintyReason.MISSING_GROUND_BIT);uncertain=true;}if(move.clientTick()==null)reasons.add(UncertaintyReason.UNKNOWN_CLIENT_TICK);TickRange ticks=move.clientTick()==null?state.clientTickRange():TickRange.exact(move.clientTick());return new Player(position,state.velocity(),yaw,pitch,ground,state.gamemode(),state.effects(),state.awaitingTeleport(),uncertain,state.input(),state.attributes(),state.pose(),state.environment(),ticks,new Provenance(event.sequence(),0,"Move"),reasons);}
    if(packet instanceof ClientInput i){Simulation.AdvancedInput advanced=new Simulation.AdvancedInput(i.forward()?1:i.backward()?-1:0,i.right()?1:i.left()?-1:0,i.jump(),i.sprint(),i.sneak());return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),state.awaitingTeleport(),uncertain,Optional.of(advanced),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,"ClientInput"),reasons);}
    if(packet instanceof Teleport t){Vec3 target=new Vec3(t.relativeX()?state.position().x()+t.position().x():t.position().x(),t.relativeY()?state.position().y()+t.position().y():t.position().y(),t.relativeZ()?state.position().z()+t.position().z():t.position().z());float yaw=t.relativeYaw()?state.yaw()+t.yaw():t.yaw();float pitch=t.relativePitch()?state.pitch()+t.pitch():t.pitch();reasons.add(UncertaintyReason.TELEPORT_CORRECTION);return new Player(target,Vec3.ZERO,yaw,pitch,false,state.gamemode(),state.effects(),OptionalInt.of(t.id()),true,state.input(),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,"Teleport"),reasons);}
    if(packet instanceof TeleportConfirm c){boolean matches=state.awaitingTeleport().isPresent()&&state.awaitingTeleport().getAsInt()==c.id();if(!matches){reasons.add(UncertaintyReason.MISMATCHED_TELEPORT_ACK);uncertain=true;}else{reasons.remove(UncertaintyReason.TELEPORT_CORRECTION);uncertain=state.uncertain()&&!state.uncertaintyReasons().equals(Set.of(UncertaintyReason.TELEPORT_CORRECTION));}return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),matches?OptionalInt.empty():state.awaitingTeleport(),uncertain,state.input(),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,"TeleportConfirm"),reasons);}
    if(packet instanceof Velocity v)return new Player(state.position(),v.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),state.awaitingTeleport(),uncertain,state.input(),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,"Velocity"),reasons);
    if(packet instanceof Effect e){Map<String,Integer> effects=new HashMap<>(state.effects());if(e.removed())effects.remove(e.id());else effects.put(e.id(),e.amplifier());return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),effects,state.awaitingTeleport(),uncertain,state.input(),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,"Effect"),reasons);}
    if(packet instanceof Gamemode g){return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),g.value(),state.effects(),state.awaitingTeleport(),uncertain,state.input(),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,"Gamemode"),reasons);}
    if(packet instanceof PlayerContext c){Simulation.Environment env=c.movementEnvironment().fluid()==Phase5Mechanics.Fluid.WATER?Environment.WATER:c.movementEnvironment().fluid()==Phase5Mechanics.Fluid.LAVA?Environment.LAVA:c.movementEnvironment().climbable()?Environment.CLIMBABLE:Environment.DRY;return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),c.gamemode(),c.effects(),state.awaitingTeleport(),uncertain,state.input(),c.attributes(),c.pose(),env,state.clientTickRange(),new Provenance(event.sequence(),0,"PlayerContext"),reasons);}
    if(packet.mutatesWorld())return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),state.awaitingTeleport(),uncertain,state.input(),state.attributes(),state.pose(),state.environment(),state.clientTickRange(),new Provenance(event.sequence(),0,packet.getClass().getSimpleName()),reasons);
    throw new IllegalStateException("unhandled packet: "+packet.getClass());
  }

  public static Reconstruction reconstruct(Seed seed,Timeline.Snapshot timeline){
    Objects.requireNonNull(seed);Objects.requireNonNull(timeline);Player current=seed.player();Set<Fact> known=new HashSet<>(seed.known());Optional<ClientInput> input=Optional.empty();List<StateFrame> frames=new ArrayList<>();int index=0;
    for(Timeline.Event event:timeline.events()){Player before=current;Packet packet=event.packet().packet();Set<Fact> refreshed=refreshed(packet);boolean duplicate=event.packet().flags().contains(PacketFlag.DUPLICATE);if(!duplicate)current=apply(current,event.packet()).withServerProvenance(event.serverTick(),event.packet());else current=current.withUncertainty(UncertaintyReason.DUPLICATE_PACKET);if(packet instanceof ClientInput&&!duplicate)input=Optional.of((ClientInput)packet);if(!duplicate)known.addAll(refreshed);frames.add(new StateFrame(index++,event,before,current,known,refreshed,input,current.environment()));}
    return new Reconstruction(frames);
  }
  private static Set<Fact> refreshed(Packet packet){EnumSet<Fact> facts=EnumSet.noneOf(Fact.class);if(packet instanceof Move m){if(m.position()!=null)facts.add(Fact.POSITION);if(m.yaw()!=null||m.pitch()!=null)facts.add(Fact.ROTATION);if(m.onGround()!=null)facts.add(Fact.GROUND);}else if(packet instanceof ClientInput)facts.add(Fact.INPUT);else if(packet instanceof Teleport)facts.addAll(EnumSet.of(Fact.POSITION,Fact.ROTATION,Fact.VELOCITY,Fact.GROUND,Fact.TELEPORT));else if(packet instanceof TeleportConfirm)facts.add(Fact.SYNCHRONIZATION);else if(packet instanceof Velocity)facts.add(Fact.VELOCITY);else if(packet instanceof Effect)facts.add(Fact.EFFECTS);else if(packet instanceof Gamemode)facts.add(Fact.GAMEMODE);else if(packet instanceof PlayerContext)facts.addAll(EnumSet.of(Fact.GAMEMODE,Fact.ATTRIBUTES,Fact.EFFECTS,Fact.POSE,Fact.ENVIRONMENT));return facts;}
}
