package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;
import static dev.phantom.ac.Maths.Vec3;

public final class Packets {
  private Packets() {}

  /**
   * The clientbound packet variants the deterministic pipeline understands.
   *
   * <p>World packets carry real 1.21.11 block states rather than legacy shape
   * keys, so the replay and world layers never have to translate through a lossy
   * enumeration.</p>
   */
  public sealed interface Packet extends Serializable permits Move, ClientInput, Teleport, TeleportConfirm,
      Velocity, Effect, Gamemode, ChunkData, ChunkUnload, BlockChange, ChunkStates, BlockStateChange {

    /** True when this packet changes the client-visible world. */
    default boolean mutatesWorld() {
      return this instanceof ChunkData || this instanceof ChunkUnload
          || this instanceof BlockChange || this instanceof ChunkStates || this instanceof BlockStateChange;
    }
  }

  /** Nullable fields mean that a position/rotation-only packet omitted that field. */
  public record Move(Vec3 position, Float yaw, Float pitch, Boolean onGround, Long clientTick) implements Packet { public Move { if(clientTick!=null&&clientTick<0) throw new IllegalArgumentException("clientTick must be non-negative"); } }
  /** Exact client input packet, available on 1.21.2+; it is retained even when no movement packet follows. */
  public record ClientInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) implements Packet {}
  /** Clientbound teleport. Relative fields are applied against the reconstructed client state. */
  public record Teleport(int id, Vec3 position, float yaw, float pitch, boolean relativeX, boolean relativeY, boolean relativeZ, boolean relativeYaw, boolean relativePitch) implements Packet { public Teleport { Objects.requireNonNull(position,"position"); }
    public Teleport(int id, Vec3 position, float yaw, float pitch) { this(id,position,yaw,pitch,false,false,false,false,false); }
  }
  /** A client acknowledgement. It is timeline information, never evidence of cheating by itself. */
  public record TeleportConfirm(int id) implements Packet {}
  public record Velocity(Vec3 velocity) implements Packet { public Velocity { Objects.requireNonNull(velocity,"velocity"); } }
  public record Effect(String id, int amplifier, boolean removed) implements Packet { public Effect { Objects.requireNonNull(id,"id"); } }
  public record Gamemode(String value) implements Packet { public Gamemode { if(value==null||value.isBlank()) throw new IllegalArgumentException("gamemode is required"); } }
  /**
   * The clientbound packet variants the deterministic pipeline understands.
   *
   * <p>World packets carry real 1.21.11 block states rather than legacy shape
   * keys, so the replay and world layers never have to translate through a lossy
   * enumeration.</p>
   */
  /**
   * Legacy shape-key block update, retained for already-recorded captures.
   * New captures must use {@link BlockStateChange}.
   */
  public record BlockChange(World.Pos position, World.Block block) implements Packet { public BlockChange { Objects.requireNonNull(position,"position"); Objects.requireNonNull(block,"block"); } }
  /** A single block changing state, addressed by block position, carrying a real 1.21.11 state. */
  public record BlockStateChange(dev.phantom.ac.world.Pos position, dev.phantom.ac.world.BlockState state) implements Packet { public BlockStateChange { Objects.requireNonNull(position,"position"); Objects.requireNonNull(state,"state"); if(state.isUnsupported()) throw new IllegalArgumentException("an unsupported state carries no verified shape and must not be recorded as a known world change"); } }
  /** Legacy shape-key chunk payload, retained for already-recorded captures. */
  public record ChunkData(World.Chunk chunk, Map<World.Pos,World.Block> blocks) implements Packet { public ChunkData { Objects.requireNonNull(chunk,"chunk"); blocks=Map.copyOf(blocks); } }
  /** A full client-visible chunk payload carrying real 1.21.11 block states. */
  public record ChunkStates(dev.phantom.ac.world.Chunk chunk, Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> states) implements Packet { public ChunkStates { Objects.requireNonNull(chunk,"chunk"); states=Map.copyOf(states); } }
  public record ChunkUnload(World.Chunk chunk) implements Packet { public ChunkUnload { Objects.requireNonNull(chunk,"chunk"); } }
  public enum PacketFlag { NORMAL, DUPLICATE, OUT_OF_ORDER, SEQUENCE_GAP, BEFORE_CAPTURE_EPOCH }

  /** True when the packet carries client-world information rather than player movement. */
  public static boolean isWorldPacket(Packet packet) {
    return packet.mutatesWorld();
  }
  public record RawPacket(long sequence, long receivedNanos, Packet packet) implements Serializable { public RawPacket { if(sequence<0||receivedNanos<0) throw new IllegalArgumentException("sequence and receivedNanos must be non-negative"); Objects.requireNonNull(packet,"packet"); } }
  public record NormalizedPacket(long sequence, long receivedNanos, Packet packet, EnumSet<PacketFlag> flags) implements Serializable { public NormalizedPacket { if(sequence<0||receivedNanos<0) throw new IllegalArgumentException("sequence and receivedNanos must be non-negative"); Objects.requireNonNull(packet,"packet"); flags=flags==null?EnumSet.noneOf(PacketFlag.class):EnumSet.copyOf(flags); if(flags.isEmpty()) flags=EnumSet.of(PacketFlag.NORMAL); } @Override public EnumSet<PacketFlag> flags(){return EnumSet.copyOf(flags);} }
  public static final class Normalizer implements Contracts.PacketNormalizer {
    public List<NormalizedPacket> normalize(Collection<RawPacket> raw) {
      Objects.requireNonNull(raw,"raw packets");
      List<RawPacket> ordered = raw.stream().sorted(Comparator.comparingLong(RawPacket::receivedNanos).thenComparingLong(RawPacket::sequence)).toList();
      Set<Long> seen = new HashSet<>(); long highestSequence = -1; List<NormalizedPacket> result = new ArrayList<>();
      for (RawPacket p : ordered) { EnumSet<PacketFlag> f=EnumSet.of(PacketFlag.NORMAL); if (!seen.add(p.sequence())) f.add(PacketFlag.DUPLICATE); if (highestSequence>=0&&p.sequence()<highestSequence) f.add(PacketFlag.OUT_OF_ORDER); if(highestSequence>=0&&p.sequence()>highestSequence+1) f.add(PacketFlag.SEQUENCE_GAP); highestSequence=Math.max(highestSequence,p.sequence()); result.add(new NormalizedPacket(p.sequence(),p.receivedNanos(),p.packet(),f)); }
      return List.copyOf(result);
    }
  }
}
