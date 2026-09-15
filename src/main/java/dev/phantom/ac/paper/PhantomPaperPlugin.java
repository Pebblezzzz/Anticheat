package dev.phantom.ac.paper;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Timeline;
import dev.phantom.ac.Diagnostics;
import dev.phantom.ac.World;
import dev.phantom.ac.LiveValidation;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper boundary only. No Bukkit type enters the deterministic core.
 * PlayerMoveEvent is server observation, not a client packet; a later protocol adapter
 * can replace this source while retaining the same normalized packet contract.
 */
public final class PhantomPaperPlugin extends JavaPlugin implements Listener {
  private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();
  private final PacketListenerAbstract networkListener = new PacketListenerAbstract() {
    @Override public void onPacketReceive(PacketReceiveEvent event) {
      Object rawPlayer = event.getPlayer();
      if (!(rawPlayer instanceof Player player)) return;
      if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
        WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
        var location = packet.getLocation();
        Vec3 position = packet.hasPositionChanged() ? vector(location.getX(), location.getY(), location.getZ()) : null;
        Float yaw = packet.hasRotationChanged() ? location.getYaw() : null;
        Float pitch = packet.hasRotationChanged() ? location.getPitch() : null;
        record(player, new Packets.Move(position, yaw, pitch, packet.isOnGround(), null));
      } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_INPUT) {
        WrapperPlayClientPlayerInput input = new WrapperPlayClientPlayerInput(event);
        record(player, new Packets.ClientInput(input.isForward(), input.isBackward(), input.isLeft(), input.isRight(), input.isJump(), input.isShift(), input.isSprint()));
      } else if (event.getPacketType() == PacketType.Play.Client.TELEPORT_CONFIRM) {
        record(player, new Packets.TeleportConfirm(new WrapperPlayClientTeleportConfirm(event).getTeleportId()));
      }
    }
    @Override public void onPacketSend(PacketSendEvent event) {
      Object rawPlayer = event.getPlayer();
      if (!(rawPlayer instanceof Player player)) return;
      if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
        WrapperPlayServerPlayerPositionAndLook packet = new WrapperPlayServerPlayerPositionAndLook(event);
        RelativeFlag flags=packet.getRelativeFlags();
        record(player,new Packets.Teleport(packet.getTeleportId(),vector(packet.getX(),packet.getY(),packet.getZ()),packet.getYaw(),packet.getPitch(),flags.has(RelativeFlag.X),flags.has(RelativeFlag.Y),flags.has(RelativeFlag.Z),flags.has(RelativeFlag.YAW),flags.has(RelativeFlag.PITCH)));
      } else if(event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
        WrapperPlayServerEntityVelocity packet=new WrapperPlayServerEntityVelocity(event);
        if(packet.getEntityId()==player.getEntityId()) { var velocity=packet.getVelocity(); record(player,new Packets.Velocity(vector(velocity.getX(),velocity.getY(),velocity.getZ()))); }
      } else if(event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
        WrapperPlayServerBlockChange packet=new WrapperPlayServerBlockChange(event);
        var position=packet.getBlockPosition();
        record(player,new Packets.BlockStateChange(new dev.phantom.ac.world.Pos(position.getX(),position.getY(),position.getZ()),toCoreState(packet.getBlockState())));
      } else if(event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        WrapperPlayServerMultiBlockChange packet=new WrapperPlayServerMultiBlockChange(event);
        for(var change:packet.getBlocks()) record(player,new Packets.BlockStateChange(new dev.phantom.ac.world.Pos(change.getX(),change.getY(),change.getZ()),toCoreState(change.getBlockState(event.getUser().getClientVersion()))));
      } else if(event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
        WrapperPlayServerUnloadChunk packet=new WrapperPlayServerUnloadChunk(event);
        record(player,new Packets.ChunkUnload(new World.Chunk(packet.getChunkX(),packet.getChunkZ())));
      } else if(event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
        // A 1.21.11 chunk can require 98,304 state reads, which must not run on
        // PacketEvents' Netty thread. The payload is therefore queued and decoded
        // on the plugin's own scheduler thread by drainChunkQueue(), and only the
        // resulting normalized packet is recorded in the deterministic capture.
        WrapperPlayServerChunkData packet=new WrapperPlayServerChunkData(event);
        var column=packet.getColumn();
        int chunkX=column.getX(),chunkZ=column.getZ();
        Capture capture=captures.computeIfAbsent(player.getUniqueId(), ignored -> new Capture(System.nanoTime()));
        capture.chunkPackets.incrementAndGet();
        capture.chunkQueue.add(new PendingChunk(player.getUniqueId(),chunkX,chunkZ,event.getUser().getClientVersion()));
      }
    }
  };

  @Override public void onEnable() {
    getServer().getPluginManager().registerEvents(this, this);
    PacketEvents.getAPI().getEventManager().registerListener(networkListener);
    // Chunk decoding is deferred off the network thread, so it runs here.
    chunkTask=getServer().getScheduler().runTaskTimer(this,this::drainChunkQueue,1L,1L);
  }

  /** The repeating task that decodes queued chunk payloads off the network thread. */
  private org.bukkit.scheduler.BukkitTask chunkTask;

  @Override public void onDisable() {
    if(chunkTask!=null) chunkTask.cancel();
    PacketEvents.getAPI().getEventManager().unregisterListener(networkListener);
    captures.clear();
  }

  /**
   * Decodes queued chunk payloads on the server thread and records the resulting
   * block states. Only states that {@code BlockCatalogue12111} can verify are
   * recorded; every other position is left out of the capture entirely, which the
   * deterministic core then reports as {@code UNLOADED}-style missing data rather
   * than as air.
   */
  private void drainChunkQueue() {
    for(Capture capture:captures.values()) {
      PendingChunk pending;
      while((pending=capture.chunkQueue.poll())!=null) {
        // A chunk column the deterministic core cannot fully describe is recorded
        // as a chunk with no block states. The core then reports every position in
        // it as missing data rather than as air, which is the honest answer.
        Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> states=decodeColumn(pending);
        record(capture,new Packets.ChunkStates(new dev.phantom.ac.world.Chunk(pending.chunkX(),pending.chunkZ()),states));
      }
    }
  }

  /**
   * Reads the server's authoritative block states for a queued chunk.
   *
   * <p>Reading the server's live chunk rather than re-parsing the packet
   * keeps this adapter independent of PacketEvents' chunk-section internals, and
   * the states it reads are exactly the states the client was sent, because the
   * chunk packet is generated from them.</p>
   */
  private Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> decodeColumn(PendingChunk pending) {
    Player player=getServer().getPlayer(pending.playerId());
    if(player==null) return Map.of();
    Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> states=new LinkedHashMap<>();
    try {
      org.bukkit.Chunk chunk=player.getWorld().getChunkAt(pending.chunkX(),pending.chunkZ());
      if(!chunk.isLoaded()) return Map.of();
      int minY=player.getWorld().getMinHeight();
      int maxY=player.getWorld().getMaxHeight();
      for(int x=0;x<16;x++) {
        for(int z=0;z<16;z++) {
          for(int y=minY;y<maxY;y++) {
            org.bukkit.block.data.BlockData data=chunk.getBlock(x,y,z).getBlockData();
            dev.phantom.ac.world.BlockState state=toCoreBlockData(data);
            if(state.isAir()) continue;
            states.put(new dev.phantom.ac.world.Pos(chunk.getX()*16+x,y,chunk.getZ()*16+z),state);
          }
        }
      }
    } catch(RuntimeException malformed) {
      getLogger().warning("chunk decode failed for "+pending.chunkX()+","+pending.chunkZ()+": "+malformed.getClass().getSimpleName());
      return Map.of();
    }
    return states;
  }

  @EventHandler public void joined(PlayerJoinEvent event) { captures.put(event.getPlayer().getUniqueId(), new Capture(System.nanoTime())); }
  @EventHandler public void changedWorld(PlayerChangedWorldEvent event) { captures.put(event.getPlayer().getUniqueId(),new Capture(System.nanoTime())); }
  @EventHandler public void quit(PlayerQuitEvent event) { captures.remove(event.getPlayer().getUniqueId()); }

  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!command.getName().equalsIgnoreCase("phantom")) return false;
    boolean showAlerts = args.length == 2 && args[0].equalsIgnoreCase("alerts");
    boolean validate = args.length == 2 && args[0].equalsIgnoreCase("validate");
    String target = (showAlerts||validate) ? args[1] : args.length == 1 ? args[0] : null;
    if (target == null) { sender.sendMessage("Usage: /phantom <player> | /phantom alerts <player> | /phantom validate <player>"); return true; }
    Player player = getServer().getPlayerExact(target);
    if (player == null) { sender.sendMessage("Player not found."); return true; }
    Capture capture = captures.get(player.getUniqueId());
    if (capture == null) { sender.sendMessage("No capture for player."); return true; }
    List<Packets.NormalizedPacket> normalized = new Packets.Normalizer().normalize(capture.copy());
    Timeline.Snapshot timeline = Timeline.assign(normalized, capture.epochNanos, 50_000_000L);
    if(validate) {
      LiveValidation.Report report=LiveValidation.analyze(timeline,4096);
      long possible=report.findings().stream().filter(f -> f.verdict()==dev.phantom.ac.Validation.Verdict.POSSIBLE).count();
      long uncertain=report.findings().stream().filter(f -> f.verdict()==dev.phantom.ac.Validation.Verdict.UNCERTAIN).count();
      long impossible=report.findings().stream().filter(f -> f.verdict()==dev.phantom.ac.Validation.Verdict.IMPOSSIBLE).count();
      sender.sendMessage("Phantom movement diagnostic: "+report.movementObservations()+" observations; "+possible+" reachable, "+uncertain+" uncertain, "+impossible+" impossible.");
      sender.sendMessage("Diagnostic only: no enforcement or punishment; independent 1.21.11 vanilla traces are still required before any cheat flag.");
      report.findings().stream().filter(f -> f.verdict()!=dev.phantom.ac.Validation.Verdict.POSSIBLE).limit(3).forEach(f -> sender.sendMessage("["+f.verdict()+"] tick "+f.tick()+": "+f.reasons().getFirst()));
      return true;
    }
    Diagnostics.Report report = Diagnostics.audit(timeline);
    if (!showAlerts) {
      sender.sendMessage("Phantom raw capture: " + report.movementPackets() + " moves, " + report.inputPackets() + " input changes, " + report.teleportConfirms() + " teleport confirms, " + capture.chunkPackets.get() + " chunk packets observed; " + timeline.events().size() + " timeline events; " + report.alerts().size() + " diagnostic alerts.");
      return true;
    }
    if (report.alerts().isEmpty()) {
      sender.sendMessage("Phantom alerts: none. This is a timeline/synchronization health result, not a cheat verdict.");
      return true;
    }
    sender.sendMessage("Phantom diagnostic alerts for " + player.getName() + ":");
    report.alerts().stream().limit(8).forEach(alert -> sender.sendMessage("[" + alert.severity() + "/" + alert.category() + "] tick " + alert.serverTick() + ": " + alert.message()));
    if (report.alerts().size() > 8) sender.sendMessage("… " + (report.alerts().size() - 8) + " more alerts omitted.");
    return true;
  }

  private void record(Player player, Packets.Packet packet) { record(captures.computeIfAbsent(player.getUniqueId(), ignored -> new Capture(System.nanoTime())), packet); }
  private void record(Capture capture, Packets.Packet packet) { capture.packets.add(new RawPacket(capture.sequence.incrementAndGet(), System.nanoTime(), packet)); }
  private static Vec3 vector(double x, double y, double z) { return new Vec3(x,y,z); }

  /**
   * Translates a protocol block state into the deterministic core's Phase 4
   * {@link dev.phantom.ac.world.BlockState}, forwarding every modelled property.
   *
   * <p>The core resolves the block's collision shape itself from this state. The
   * adapter must not decide shapes, because shape data belongs to the version
   * catalogue and not to the platform boundary.</p>
   */
  private static dev.phantom.ac.world.BlockState toCoreState(WrappedBlockState state) {
    if(state==null||state.getType().isAir()) return dev.phantom.ac.world.BlockState.air();
    String name=state.getType().getName();
    Map<String,String> properties=new LinkedHashMap<>();
    putEnum(properties,"type",state.getData(StateValue.TYPE));
    putEnum(properties,"facing",state.getData(StateValue.FACING));
    putEnum(properties,"half",state.getData(StateValue.HALF));
    putEnum(properties,"shape",state.getData(StateValue.SHAPE));
    putEnum(properties,"part",state.getData(StateValue.PART));
    putEnum(properties,"hinge",state.getData(StateValue.HINGE));
    putNumber(properties,"layers",state.getData(StateValue.LAYERS));
    putNumber(properties,"level",state.getData(StateValue.LEVEL));
    putNumber(properties,"candles",state.getData(StateValue.CANDLES));
    putNumber(properties,"pickles",state.getData(StateValue.PICKLES));
    putBoolean(properties,"waterlogged",state.getData(StateValue.WATERLOGGED));
    putBoolean(properties,"open",state.getData(StateValue.OPEN));
    putBoolean(properties,"powered",state.getData(StateValue.POWERED));
    putBoolean(properties,"up",state.getData(StateValue.UP));
    putBoolean(properties,"north",state.getData(StateValue.NORTH));
    putBoolean(properties,"south",state.getData(StateValue.SOUTH));
    putBoolean(properties,"west",state.getData(StateValue.WEST));
    putBoolean(properties,"east",state.getData(StateValue.EAST));
    putBoolean(properties,"lit",state.getData(StateValue.LIT));
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(name,properties);
  }

  /**
   * Translates a Bukkit {@link org.bukkit.block.data.BlockData} into the core's
   * Phase 4 {@link dev.phantom.ac.world.BlockState}. Every modelled property is
   * forwarded; a property this build does not model simply does not reach the
   * catalogue, which is what lets the catalogue report the state as unsupported
   * instead of guessing.
   */
  private static dev.phantom.ac.world.BlockState toCoreBlockData(org.bukkit.block.data.BlockData data) {
    if(data==null||data.getMaterial().isAir()) return dev.phantom.ac.world.BlockState.air();
    String name=data.getMaterial().getKey().toString();
    Map<String,String> properties=new LinkedHashMap<>();
    if(data instanceof org.bukkit.block.data.type.Slab slab) {
      properties.put("type",slab.getType().name().toLowerCase(Locale.ROOT));
      properties.put("waterlogged",Boolean.toString(slab.isWaterlogged()));
    } else if(data instanceof org.bukkit.block.data.type.Stairs stairs) {
      properties.put("facing",stairs.getFacing().name().toLowerCase(Locale.ROOT));
      properties.put("half",stairs.getHalf().name().toLowerCase(Locale.ROOT));
      properties.put("shape",stairs.getShape().name().toLowerCase(Locale.ROOT));
      properties.put("waterlogged",Boolean.toString(stairs.isWaterlogged()));
    } else if(data instanceof org.bukkit.block.data.type.Door door) {
      properties.put("facing",door.getFacing().name().toLowerCase(Locale.ROOT));
      properties.put("half",door.getHalf().name().toLowerCase(Locale.ROOT));
      properties.put("hinge",door.getHinge().name().toLowerCase(Locale.ROOT));
      properties.put("open",Boolean.toString(door.isOpen()));
      properties.put("powered",Boolean.toString(door.isPowered()));
    } else if(data instanceof org.bukkit.block.data.type.TrapDoor trapdoor) {
      properties.put("facing",trapdoor.getFacing().name().toLowerCase(Locale.ROOT));
      properties.put("half",trapdoor.getHalf().name().toLowerCase(Locale.ROOT));
      properties.put("open",Boolean.toString(trapdoor.isOpen()));
      properties.put("powered",Boolean.toString(trapdoor.isPowered()));
      properties.put("waterlogged",Boolean.toString(trapdoor.isWaterlogged()));
    } else if(data instanceof org.bukkit.block.data.type.Snow snow) {
      properties.put("layers",Integer.toString(snow.getLayers()));
    } else if(data instanceof org.bukkit.block.data.type.Bed bed) {
      properties.put("facing",bed.getFacing().name().toLowerCase(Locale.ROOT));
      properties.put("part",bed.getPart().name().toLowerCase(Locale.ROOT));
    } else if(data instanceof org.bukkit.block.data.type.Fence fence) {
      properties.put("north",Boolean.toString(fence.hasFace(org.bukkit.block.BlockFace.NORTH)));
      properties.put("south",Boolean.toString(fence.hasFace(org.bukkit.block.BlockFace.SOUTH)));
      properties.put("west",Boolean.toString(fence.hasFace(org.bukkit.block.BlockFace.WEST)));
      properties.put("east",Boolean.toString(fence.hasFace(org.bukkit.block.BlockFace.EAST)));
      properties.put("waterlogged",Boolean.toString(fence.isWaterlogged()));
    } else if(data instanceof org.bukkit.block.data.type.Wall wall) {
      properties.put("up",Boolean.toString(wall.isUp()));
      properties.put("north",Boolean.toString(wall.getHeight(org.bukkit.block.BlockFace.NORTH)!=org.bukkit.block.data.type.Wall.Height.NONE));
      properties.put("south",Boolean.toString(wall.getHeight(org.bukkit.block.BlockFace.SOUTH)!=org.bukkit.block.data.type.Wall.Height.NONE));
      properties.put("west",Boolean.toString(wall.getHeight(org.bukkit.block.BlockFace.WEST)!=org.bukkit.block.data.type.Wall.Height.NONE));
      properties.put("east",Boolean.toString(wall.getHeight(org.bukkit.block.BlockFace.EAST)!=org.bukkit.block.data.type.Wall.Height.NONE));
    } else if(data instanceof org.bukkit.block.data.Directional directional) {
      properties.put("facing",directional.getFacing().name().toLowerCase(Locale.ROOT));
    }
    if(data instanceof org.bukkit.block.data.Waterlogged waterlogged) {
      properties.put("waterlogged",Boolean.toString(waterlogged.isWaterlogged()));
    }
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(name,properties);
  }

  private static void putEnum(Map<String,String> properties,String key,Object value) {
    if(value!=null) properties.put(key,value.toString().toLowerCase(Locale.ROOT));
  }

  private static void putNumber(Map<String,String> properties,String key,Object value) {
    if(value instanceof Number number) properties.put(key,Integer.toString(number.intValue()));
  }

  private static void putBoolean(Map<String,String> properties,String key,Object value) {
    if(value instanceof Boolean flag) properties.put(key,Boolean.toString(flag));
  }
  /**
   * A chunk payload waiting to be decoded off the network thread. Decoding is
   * deferred because a full 1.21.11 chunk is large; the payload object is only
   * read once, on the plugin's scheduler thread.
   */
  private record PendingChunk(UUID playerId,int chunkX,int chunkZ,com.github.retrooper.packetevents.protocol.player.ClientVersion clientVersion) {}

  private static final class Capture {
    final long epochNanos; final AtomicLong sequence = new AtomicLong();
    final AtomicLong chunkPackets = new AtomicLong();
    final Queue<PendingChunk> chunkQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final List<RawPacket> packets = Collections.synchronizedList(new ArrayList<>());
    Capture(long epochNanos) { this.epochNanos=epochNanos; }
    List<RawPacket> copy() { synchronized (packets) { return List.copyOf(packets); } }
  }
}
