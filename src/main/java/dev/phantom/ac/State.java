package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;
import static dev.phantom.ac.Maths.Vec3;
import static dev.phantom.ac.Packets.*;

/** Pure player-state reducer and provenance-aware timeline reconstruction. */
public final class State {
  private State() {}

  public record Player(Vec3 position,Vec3 velocity,float yaw,float pitch,boolean onGround,String gamemode,Map<String,Integer> effects,OptionalInt awaitingTeleport,boolean uncertain) implements Serializable {
    public Player { Objects.requireNonNull(position,"position");Objects.requireNonNull(velocity,"velocity");if(gamemode==null||gamemode.isBlank())throw new IllegalArgumentException("gamemode is required");effects=Map.copyOf(effects);Objects.requireNonNull(awaitingTeleport,"awaitingTeleport"); }
    public boolean isSurvival() { return gamemode.equals("survival"); }
    /** A test/replay fixture anchor. Production reconstruction should declare its known facts with Seed. */
    public static Player initial(Vec3 position){return new Player(position,Vec3.ZERO,0,0,true,"survival",Map.of(),OptionalInt.empty(),false);}
  }

  /** Facts with independent provenance. Retaining an old value never implies it was refreshed this event. */
  public enum Fact { POSITION, ROTATION, VELOCITY, GROUND, INPUT, GAMEMODE, ENVIRONMENT, EFFECTS, TELEPORT }
  /** Environment is deliberately explicit even though detailed evaluation belongs to the world/physics phases. */
  public enum Environment { UNKNOWN, DRY, WATER, CLIMBABLE }
  public record Seed(Player player,Set<Fact> known,Environment environment) {
    public Seed { Objects.requireNonNull(player,"player");known=Set.copyOf(known);Objects.requireNonNull(environment,"environment");if(environment!=Environment.UNKNOWN&&!known.contains(Fact.ENVIRONMENT))throw new IllegalArgumentException("known environment requires ENVIRONMENT fact"); }
    public static Seed serverAnchor(Player player){return new Seed(player,EnumSet.of(Fact.POSITION,Fact.ROTATION,Fact.VELOCITY,Fact.GROUND,Fact.GAMEMODE,Fact.EFFECTS,Fact.TELEPORT),Environment.UNKNOWN);}
  }
  public record StateFrame(int index,Timeline.Event event,Player before,Player after,Set<Fact> knownAfter,Set<Fact> refreshedByEvent,Optional<ClientInput> currentInput,Environment environment) {
    public StateFrame { if(index<0)throw new IllegalArgumentException("frame index must be non-negative");Objects.requireNonNull(event,"event");Objects.requireNonNull(before,"before");Objects.requireNonNull(after,"after");knownAfter=Set.copyOf(knownAfter);refreshedByEvent=Set.copyOf(refreshedByEvent);currentInput=Objects.requireNonNull(currentInput,"currentInput");Objects.requireNonNull(environment,"environment"); }
  }
  public record Reconstruction(List<StateFrame> frames) {
    public Reconstruction { frames=List.copyOf(frames); }
    public Optional<StateFrame> last(){return frames.isEmpty()?Optional.empty():Optional.of(frames.getLast());}
  }

  public static final class Reducer implements Contracts.PlayerStateReducer { @Override public Player apply(Player prior,NormalizedPacket event){return State.apply(prior,event);} }
  public static final class Reconstructor implements Contracts.PlayerStateReconstructor { @Override public Reconstruction reconstruct(Seed seed,Timeline.Snapshot timeline){return State.reconstruct(seed,timeline);} }

  public static Player apply(Player state,NormalizedPacket event){
    Objects.requireNonNull(state,"state");Objects.requireNonNull(event,"event");
    Packet packet=event.packet();EnumSet<PacketFlag> flags=event.flags();
    boolean uncertain=state.uncertain()||flags.contains(PacketFlag.DUPLICATE)||flags.contains(PacketFlag.OUT_OF_ORDER)||flags.contains(PacketFlag.SEQUENCE_GAP)||flags.contains(PacketFlag.BEFORE_CAPTURE_EPOCH);
    if(packet instanceof Move move)return new Player(move.position()==null?state.position():move.position(),state.velocity(),move.yaw()==null?state.yaw():move.yaw(),move.pitch()==null?state.pitch():move.pitch(),move.onGround()==null?state.onGround():move.onGround(),state.gamemode(),state.effects(),state.awaitingTeleport(),uncertain||move.onGround()==null);
    if(packet instanceof ClientInput||packet.mutatesWorld())return copy(state,uncertain);
    if(packet instanceof Teleport teleport){Vec3 target=new Vec3(teleport.relativeX()?state.position().x()+teleport.position().x():teleport.position().x(),teleport.relativeY()?state.position().y()+teleport.position().y():teleport.position().y(),teleport.relativeZ()?state.position().z()+teleport.position().z():teleport.position().z());float yaw=teleport.relativeYaw()?state.yaw()+teleport.yaw():teleport.yaw();float pitch=teleport.relativePitch()?state.pitch()+teleport.pitch():teleport.pitch();return new Player(target,Vec3.ZERO,yaw,pitch,false,state.gamemode(),state.effects(),OptionalInt.of(teleport.id()),uncertain);}
    if(packet instanceof TeleportConfirm confirm){boolean matches=state.awaitingTeleport().isPresent()&&state.awaitingTeleport().getAsInt()==confirm.id();return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),matches?OptionalInt.empty():state.awaitingTeleport(),uncertain||!matches);}
    if(packet instanceof Velocity velocity)return new Player(state.position(),velocity.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),state.awaitingTeleport(),uncertain);
    if(packet instanceof Effect effect){Map<String,Integer> effects=new HashMap<>(state.effects());if(effect.removed())effects.remove(effect.id());else effects.put(effect.id(),effect.amplifier());return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),effects,state.awaitingTeleport(),uncertain);}
    if(packet instanceof Gamemode gamemode)return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),gamemode.value(),state.effects(),state.awaitingTeleport(),uncertain);
    throw new IllegalStateException("unhandled packet: "+packet.getClass());
  }

  /** Reconstructs every state transition and exposes both retained and refreshed facts. */
  public static Reconstruction reconstruct(Seed seed,Timeline.Snapshot timeline){
    Objects.requireNonNull(seed,"seed");Objects.requireNonNull(timeline,"timeline");
    Player current=seed.player();Set<Fact> known=new HashSet<>(seed.known());Optional<ClientInput> input=Optional.empty();List<StateFrame> frames=new ArrayList<>();int index=0;
    for(Timeline.Event event:timeline.events()){
      Player before=current;Packet packet=event.packet().packet();Set<Fact> refreshed=refreshed(packet);
      if(packet instanceof ClientInput clientInput)input=Optional.of(clientInput);
      current=apply(current,event.packet());known.addAll(refreshed);
      frames.add(new StateFrame(index++,event,before,current,known,refreshed,input,seed.environment()));
    }
    return new Reconstruction(frames);
  }
  private static Set<Fact> refreshed(Packet packet){
    EnumSet<Fact> facts=EnumSet.noneOf(Fact.class);
    if(packet instanceof Move move){if(move.position()!=null)facts.add(Fact.POSITION);if(move.yaw()!=null||move.pitch()!=null)facts.add(Fact.ROTATION);if(move.onGround()!=null)facts.add(Fact.GROUND);}
    else if(packet instanceof ClientInput)facts.add(Fact.INPUT);
    else if(packet instanceof Teleport)facts.addAll(EnumSet.of(Fact.POSITION,Fact.ROTATION,Fact.VELOCITY,Fact.GROUND,Fact.TELEPORT));
    else if(packet instanceof TeleportConfirm)facts.add(Fact.TELEPORT);
    else if(packet instanceof Velocity)facts.add(Fact.VELOCITY);
    else if(packet instanceof Effect)facts.add(Fact.EFFECTS);
    else if(packet instanceof Gamemode)facts.add(Fact.GAMEMODE);
    return facts;
  }
  private static Player copy(Player state,boolean uncertain){return new Player(state.position(),state.velocity(),state.yaw(),state.pitch(),state.onGround(),state.gamemode(),state.effects(),state.awaitingTeleport(),uncertain);}
}
