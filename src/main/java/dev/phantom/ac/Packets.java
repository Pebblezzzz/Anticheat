package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;
import static dev.phantom.ac.Maths.Vec3;

public final class Packets {
  private Packets() {}

  public sealed interface Packet extends Serializable permits Move, ClientInput, ClientTickEnd, Teleport, TeleportConfirm,
      Velocity, Effect, Gamemode, PlayerContext, FlightToggle, ChunkData, ChunkUnload, BlockChange, ChunkStates, BlockStateChange, UnsupportedBlockStateChange,
      WorldTransactionSend, WorldTransactionAck {
    default boolean mutatesWorld() {
      return this instanceof ChunkData || this instanceof ChunkUnload
          || this instanceof BlockChange || this instanceof ChunkStates || this instanceof BlockStateChange || this instanceof UnsupportedBlockStateChange;
    }
  }

  public record Move(Vec3 position, Float yaw, Float pitch, Boolean onGround, Long clientTick) implements Packet { public Move { if(clientTick!=null&&clientTick<0) throw new IllegalArgumentException("clientTick must be non-negative"); } }
  /** Protocol-observed end of a client tick. This is a boundary signal, not a movement timestamp. */
  public record ClientTickEnd() implements Packet {}
  public record ClientInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) implements Packet {}
  /** Server-side event evidence that the client attempted to toggle flying. */
  public record FlightToggle(boolean flying, boolean cancelled) implements Packet {}
  public record Teleport(int id, Vec3 position, float yaw, float pitch, boolean relativeX, boolean relativeY, boolean relativeZ, boolean relativeYaw, boolean relativePitch) implements Packet { public Teleport { Objects.requireNonNull(position,"position"); }
    public Teleport(int id, Vec3 position, float yaw, float pitch) { this(id,position,yaw,pitch,false,false,false,false,false); }
  }
  public record TeleportConfirm(int id) implements Packet {}
  /** Server-side synthetic transaction barrier used to prove client receipt of world updates. */
  public record WorldTransactionSend(short id) implements Packet {}
  /** Client acknowledgement of a synthetic world transaction barrier. */
  public record WorldTransactionAck(short id) implements Packet {}
  public record Velocity(Vec3 velocity) implements Packet { public Velocity { Objects.requireNonNull(velocity,"velocity"); } }
  public record Effect(String id, int amplifier, boolean removed) implements Packet { public Effect { Objects.requireNonNull(id,"id"); } }
  public record Gamemode(String value) implements Packet { public Gamemode { if(value==null||value.isBlank()) throw new IllegalArgumentException("gamemode is required"); } }
  /** Main-thread authoritative server snapshot, deliberately separate from client movement claims. */
  public record PlayerContext(String gamemode, Simulation.Attributes attributes, Map<String,Integer> effects,
                              Phase5Mechanics.Pose pose, Phase5Mechanics.MovementEnvironment movementEnvironment,
                              Vec3 serverPosition, Vec3 serverVelocity, boolean canFly, boolean flying,
                              boolean sleeping, List<dev.phantom.ac.world.EntityCollisions.EntityBox> entityBoxes) implements Packet {
    public PlayerContext {
      if(gamemode==null||gamemode.isBlank()) throw new IllegalArgumentException("gamemode is required");
      Objects.requireNonNull(attributes); effects=Map.copyOf(effects); Objects.requireNonNull(pose);
      Objects.requireNonNull(movementEnvironment); Objects.requireNonNull(serverPosition); Objects.requireNonNull(serverVelocity);
      if(!Double.isFinite(serverPosition.x())||!Double.isFinite(serverPosition.y())||!Double.isFinite(serverPosition.z()))
        throw new IllegalArgumentException("serverPosition must be finite");
      if(!Double.isFinite(serverVelocity.x())||!Double.isFinite(serverVelocity.y())||!Double.isFinite(serverVelocity.z()))
        throw new IllegalArgumentException("serverVelocity must be finite");
      entityBoxes=List.copyOf(entityBoxes);
    }
    public PlayerContext(String gamemode, Simulation.Attributes attributes, Map<String,Integer> effects,
                         Phase5Mechanics.Pose pose, Phase5Mechanics.MovementEnvironment movementEnvironment,
                         boolean sleeping, List<dev.phantom.ac.world.EntityCollisions.EntityBox> entityBoxes) {
      this(gamemode,attributes,effects,pose,movementEnvironment,Vec3.ZERO,Vec3.ZERO,false,false,sleeping,entityBoxes);
    }
  }
  public record BlockChange(World.Pos position, World.Block block) implements Packet { public BlockChange { Objects.requireNonNull(position,"position"); Objects.requireNonNull(block,"block"); } }
  public record BlockStateChange(dev.phantom.ac.world.Pos position, dev.phantom.ac.world.BlockState state) implements Packet { public BlockStateChange { Objects.requireNonNull(position,"position"); Objects.requireNonNull(state,"state"); if(state.isUnsupported()) throw new IllegalArgumentException("an unsupported state carries no verified shape and must not be recorded as a known world change"); } }
  public record UnsupportedBlockStateChange(dev.phantom.ac.world.Pos position, dev.phantom.ac.world.BlockState state) implements Packet { public UnsupportedBlockStateChange { Objects.requireNonNull(position,"position"); Objects.requireNonNull(state,"state"); if(!state.isUnsupported()) throw new IllegalArgumentException("unsupported state packet requires an unsupported state"); } }
  public record ChunkData(World.Chunk chunk, Map<World.Pos,World.Block> blocks) implements Packet { public ChunkData { Objects.requireNonNull(chunk,"chunk"); blocks=Map.copyOf(blocks); } }
  public record ChunkStates(dev.phantom.ac.world.Chunk chunk, Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> states) implements Packet { public ChunkStates { Objects.requireNonNull(chunk,"chunk"); states=Map.copyOf(states); } }
  public record ChunkUnload(World.Chunk chunk) implements Packet { public ChunkUnload { Objects.requireNonNull(chunk,"chunk"); } }

  /** Capture-side provenance. A missing protocol sequence is explicit rather than silently invented. */
  public record CaptureProvenance(String sourceId,String direction,String packetType,Long authoritativeServerTick) implements Serializable {
    public CaptureProvenance {
      if(sourceId==null||sourceId.isBlank()) throw new IllegalArgumentException("sourceId is required");
      if(direction==null||direction.isBlank()) throw new IllegalArgumentException("direction is required");
      if(packetType==null||packetType.isBlank()) throw new IllegalArgumentException("packetType is required");
      if(authoritativeServerTick!=null&&authoritativeServerTick<0) throw new IllegalArgumentException("authoritativeServerTick must be non-negative");
    }
    public static CaptureProvenance forPacket(Packet packet){Objects.requireNonNull(packet);return new CaptureProvenance("unknown-capture-source",directionFor(packet),packet.getClass().getSimpleName(),null);}
    public static CaptureProvenance fromAdapter(String sourceId,Packet packet,Long authoritativeServerTick){Objects.requireNonNull(packet);return new CaptureProvenance(sourceId,directionFor(packet),packet.getClass().getSimpleName(),authoritativeServerTick);}
    public static String directionFor(Packet packet){
      if(packet instanceof Move||packet instanceof ClientInput||packet instanceof ClientTickEnd||packet instanceof TeleportConfirm||packet instanceof WorldTransactionAck||packet instanceof FlightToggle)return "CLIENT_TO_SERVER";
      if(packet instanceof Teleport||packet instanceof Velocity||packet instanceof Effect||packet instanceof Gamemode||packet instanceof PlayerContext||packet instanceof WorldTransactionSend||packet.mutatesWorld())return "SERVER_TO_CLIENT";
      return "UNKNOWN";
    }
  }

  public enum PacketFlag { NORMAL, DUPLICATE, OUT_OF_ORDER, SEQUENCE_GAP, BEFORE_CAPTURE_EPOCH }
  public static boolean isWorldPacket(Packet packet){return packet.mutatesWorld();}

  /** Local capture sequence/time plus optional adapter-provided protocol provenance. */
  public record RawPacket(long sequence,long receivedNanos,Packet packet,CaptureProvenance provenance) implements Serializable {
    public RawPacket { if(sequence<0||receivedNanos<0) throw new IllegalArgumentException("sequence and receivedNanos must be non-negative"); Objects.requireNonNull(packet,"packet"); provenance=provenance==null?CaptureProvenance.forPacket(packet):provenance; }
    public RawPacket(long sequence,long receivedNanos,Packet packet){this(sequence,receivedNanos,packet,CaptureProvenance.forPacket(packet));}
    public RawPacket(long sequence,long receivedNanos,Packet packet,CaptureProvenance provenance,Long authoritativeServerTick){this(sequence,receivedNanos,packet,new CaptureProvenance(provenance.sourceId(),provenance.direction(),provenance.packetType(),authoritativeServerTick));}
  }

  /** Canonical normalized event metadata; never drops provenance or uncertainty markers. */
  public record NormalizedPacket(long sequence,long receivedNanos,Packet packet,EnumSet<PacketFlag> flags,CaptureProvenance provenance) implements Serializable {
    public NormalizedPacket { if(sequence<0||receivedNanos<0) throw new IllegalArgumentException("sequence and receivedNanos must be non-negative"); Objects.requireNonNull(packet,"packet"); flags=flags==null?EnumSet.noneOf(PacketFlag.class):EnumSet.copyOf(flags); if(flags.isEmpty()) flags=EnumSet.of(PacketFlag.NORMAL); provenance=provenance==null?CaptureProvenance.forPacket(packet):provenance; }
    public NormalizedPacket(long sequence,long receivedNanos,Packet packet,EnumSet<PacketFlag> flags){this(sequence,receivedNanos,packet,flags,CaptureProvenance.forPacket(packet));}
    @Override public EnumSet<PacketFlag> flags(){return EnumSet.copyOf(flags);}
  }

  public static final class Normalizer implements Contracts.PacketNormalizer {
    public List<NormalizedPacket> normalize(Collection<RawPacket> raw){
      Objects.requireNonNull(raw,"raw packets");
      List<RawPacket> ordered=raw.stream().sorted(Comparator.comparingLong(RawPacket::receivedNanos).thenComparingLong(RawPacket::sequence)).toList();
      Set<Long> seen=new HashSet<>();long highestSequence=-1;List<NormalizedPacket> result=new ArrayList<>(ordered.size());
      for(RawPacket p:ordered){EnumSet<PacketFlag> flags=EnumSet.of(PacketFlag.NORMAL);if(!seen.add(p.sequence()))flags.add(PacketFlag.DUPLICATE);if(highestSequence>=0&&p.sequence()<highestSequence)flags.add(PacketFlag.OUT_OF_ORDER);if(highestSequence>=0&&p.sequence()>highestSequence+1)flags.add(PacketFlag.SEQUENCE_GAP);highestSequence=Math.max(highestSequence,p.sequence());result.add(new NormalizedPacket(p.sequence(),p.receivedNanos(),p.packet(),flags,p.provenance()));}
      return List.copyOf(result);
    }
  }
}
