package dev.phantom.ac.paper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;

import dev.phantom.ac.Diagnostics;
import dev.phantom.ac.LiveValidation;
import dev.phantom.ac.OperatorValidation;
import dev.phantom.ac.Contracts;
import dev.phantom.ac.State;
import dev.phantom.ac.Validation;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Timeline;
import dev.phantom.ac.World;

/**
 * Paper boundary only. No Bukkit type enters the deterministic core.
 * PlayerMoveEvent is server observation, not a client packet; a later protocol adapter
 * can replace this source while retaining the same normalized packet contract.
 */
public final class PhantomPaperPlugin extends JavaPlugin implements Listener {
  private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();
  private org.bukkit.scheduler.BukkitTask validationTask;
  private volatile boolean alertsEnabled;
  private volatile boolean broadcastAlerts;
  private volatile boolean kicksEnabled;
  private volatile boolean permissionExempt;
  private volatile String exemptPermission;
  private volatile double minimumConfidence;
  private volatile int validationBudget;
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
        recordBlockState(player,new dev.phantom.ac.world.Pos(position.getX(),position.getY(),position.getZ()),toCoreState(packet.getBlockState()));
      } else if(event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        WrapperPlayServerMultiBlockChange packet=new WrapperPlayServerMultiBlockChange(event);
        for(var change:packet.getBlocks()) recordBlockState(player,new dev.phantom.ac.world.Pos(change.getX(),change.getY(),change.getZ()),toCoreState(change.getBlockState(event.getUser().getClientVersion())));
      } else if(event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
        WrapperPlayServerUnloadChunk packet=new WrapperPlayServerUnloadChunk(event);
        record(player,new Packets.ChunkUnload(new World.Chunk(packet.getChunkX(),packet.getChunkZ())));
      } else if(event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
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
    saveDefaultConfig();
    alertsEnabled=getConfig().getBoolean("alerts.enabled",true);
    broadcastAlerts=getConfig().getBoolean("alerts.broadcast",false);
    kicksEnabled=getConfig().getBoolean("enforcement.kick-enabled",false);
    permissionExempt=getConfig().getBoolean("enforcement.permission-exempt",true);
    exemptPermission=getConfig().getString("enforcement.permission","phantom.exempt");
    minimumConfidence=getConfig().getDouble("enforcement.minimum-confidence",0.90);
    validationBudget=Math.max(1,getConfig().getInt("validation.candidate-budget",4096));
    chunkTask=getServer().getScheduler().runTaskTimer(this,this::drainChunkQueue,1L,1L);
    int validationInterval=Math.max(1,getConfig().getInt("validation.interval-ticks",10));
    validationTask=getServer().getScheduler().runTaskTimerAsynchronously(this,this::evaluateCapturesAsync,validationInterval,validationInterval);
  }

  private org.bukkit.scheduler.BukkitTask chunkTask;

  @Override public void onDisable() {
    if(chunkTask!=null) chunkTask.cancel();
    if(validationTask!=null) validationTask.cancel();
    PacketEvents.getAPI().getEventManager().unregisterListener(networkListener);
    captures.clear();
  }

  /**
   * Decodes queued chunk payloads on the server thread and records the resulting
   * block states.
   * FIXED: Added a 10ms budget to prevent the main thread from stalling on massive map copies.
   */
  /**
   * Incremental client-world reconstruction. A complete chunk is never scanned
   * in one server tick: Bukkit block reads are deliberately budgeted and the
   * chunk remains absent from the deterministic world until the scan completes.
   * This preserves UNKNOWN semantics while preventing watchdog stalls.
   */
  private void drainChunkQueue() {
    final long deadline=System.nanoTime()+1_000_000L; // one millisecond total per tick
    final int blocksPerSlice=512;
    while(System.nanoTime()<deadline) {
      Capture selected=null; PendingChunk pending=null;
      for(Capture capture:captures.values()) { pending=capture.chunkQueue.peek(); if(pending!=null){selected=capture;break;} }
      if(selected==null) return;
      Player player=getServer().getPlayer(pending.playerId());
      if(player==null){selected.chunkQueue.poll();continue;}
      try {
        org.bukkit.Chunk chunk=player.getWorld().getChunkAt(pending.chunkX(),pending.chunkZ());
        if(!chunk.isLoaded()){selected.chunkQueue.poll();continue;}
        int minY=player.getWorld().getMinHeight(), maxY=player.getWorld().getMaxHeight();
        int processed=0;
        while(processed++<blocksPerSlice && pending.nextIndex<pending.totalBlocks(minY,maxY)) {
          int index=pending.nextIndex++;
          int x=index/((maxY-minY)*16), remainder=index%((maxY-minY)*16);
          int z=remainder/(maxY-minY), y=minY+(remainder%(maxY-minY));
          dev.phantom.ac.world.BlockState state=toCoreBlockData(chunk.getBlock(x,y,z).getBlockData());
          if(!state.isAir()) pending.states.put(new dev.phantom.ac.world.Pos(chunk.getX()*16+x,y,chunk.getZ()*16+z),state);
        }
        if(pending.nextIndex>=pending.totalBlocks(minY,maxY)) {
          selected.chunkQueue.poll();
          Map<World.Pos,World.Block> legacy=new LinkedHashMap<>();
          for(var entry:pending.states.entrySet()) legacy.put(new World.Pos(entry.getKey().x(),entry.getKey().y(),entry.getKey().z()),toLegacyBlock(entry.getValue()));
          record(selected,new Packets.ChunkData(new World.Chunk(pending.chunkX(),pending.chunkZ()),legacy));
          record(selected,new Packets.ChunkStates(new dev.phantom.ac.world.Chunk(pending.chunkX(),pending.chunkZ()),pending.states));
        }
      } catch(RuntimeException malformed) {
        selected.chunkQueue.poll();
        getLogger().warning("chunk decode failed for "+pending.chunkX()+","+pending.chunkZ()+": "+malformed.getClass().getSimpleName());
      }
    }
  }

  private void evaluateCapturesAsync() {
    if(!alertsEnabled && !kicksEnabled) return;
    for(Map.Entry<UUID,Capture> entry:captures.entrySet()) {
      Capture capture=entry.getValue();
      if(!capture.validationRunning.compareAndSet(false,true)) continue;
      List<Packets.RawPacket> raw=capture.copy();
      if(raw.isEmpty()) { capture.validationRunning.set(false); continue; }
      try {
        Timeline.Snapshot timeline=Timeline.assign(new Packets.Normalizer().normalize(raw),capture.epochNanos,50_000_000L);
        LiveValidation.Report report=LiveValidation.analyze(timeline,validationBudget);
        getServer().getScheduler().runTask(this,()->applyValidationResult(entry.getKey(),capture,report));
      } catch(RuntimeException failure) {
        capture.validationRunning.set(false);
        getLogger().warning("validation skipped for "+entry.getKey()+": "+failure.getClass().getSimpleName());
      }
    }
  }

  private void applyValidationResult(UUID playerId,Capture capture,LiveValidation.Report report) {
    try {
      Player player=getServer().getPlayer(playerId);
      if(player==null) return;
      synchronized(capture) {
        int start=Math.min(capture.processedFindings,report.findings().size());
        for(int i=start;i<report.findings().size();i++) {
          LiveValidation.Finding finding=report.findings().get(i);
          Validation.Verdict verdict=finding.verdict();
          Validation.Reachability reachability=new Validation.Reachability(verdict,java.util.Set.of(),finding.reasons());
          Validation.Evidence evidence=new Validation.Evidence(verdict,"MOVEMENT_REACHABILITY",Double.NaN,finding.reasons());
          boolean timingUncertain=verdict==Validation.Verdict.UNCERTAIN || finding.reasons().stream().anyMatch(reason -> reason.contains("unknown") || reason.contains("unsupported") || reason.contains("budget"));
          Validation.SyncWindow sync=new Validation.SyncWindow(finding.tick(),finding.tick(),timingUncertain,finding.reasons());
          State.Player anchor=State.Player.initial(Vec3.ZERO);
          OperatorValidation.Observation observation=new OperatorValidation.Observation(player.getName(),finding.tick(),anchor,anchor,sync,Contracts.TARGET_VERSION,List.of(),reachability,evidence);
          OperatorValidation.Result result=OperatorValidation.aggregate(capture.aggregator,observation);
          capture.aggregator=result.state();
          if(result.alert().isPresent()) {
            OperatorValidation.Alert alert=result.alert().orElseThrow();
            if(alertsEnabled) sendOperatorAlert(alert.message(),broadcastAlerts);
            if(kicksEnabled && alert.confidence()>=minimumConfidence && !(permissionExempt && player.hasPermission(exemptPermission))) player.kickPlayer("Movement validation failed: "+alert.reason());
          }
        }
        capture.processedFindings=report.findings().size();
      }
    } finally { capture.validationRunning.set(false); }
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
      sender.sendMessage("Pipeline: packets="+report.timelineEvents()+" anchored="+report.anchoredObservations()+" possible="+report.possibleFindings()+" uncertain="+report.uncertainFindings()+" impossible="+report.impossibleFindings()+" evidence="+capture.aggregator.impossibleByRule().values().stream().mapToInt(Integer::intValue).sum());
      sender.sendMessage("Diagnostic only: no enforcement or punishment; independent 1.21.11 vanilla traces are still required before any cheat flag.");
      report.findings().stream().filter(f -> f.verdict()!=dev.phantom.ac.Validation.Verdict.POSSIBLE).limit(3).forEach(f -> sender.sendMessage("["+f.verdict()+"] tick "+f.tick()+": "+f.reasons().getFirst()));
      OperatorValidation.Aggregator aggregate=OperatorValidation.Aggregator.empty();
      State.Player anchor=State.Player.initial(Vec3.ZERO);
      for (LiveValidation.Finding finding : report.findings()) {
        Validation.Reachability reachability=new Validation.Reachability(finding.verdict(),java.util.Set.of(),finding.reasons());
        Validation.Evidence evidence=new Validation.Evidence(finding.verdict(),"MOVEMENT_REACHABILITY",Double.NaN,finding.reasons());
        OperatorValidation.Observation observation=new OperatorValidation.Observation(player.getName(),finding.tick(),anchor,anchor,
            new Validation.SyncWindow(finding.tick(),finding.tick(),false,java.util.List.of("command-time replay has no measured RTT")),
            Contracts.TARGET_VERSION,java.util.List.of(),reachability,evidence);
        OperatorValidation.Result result=OperatorValidation.aggregate(aggregate,observation);
        aggregate=result.state();
        result.alert().ifPresent(alert -> sender.sendMessage(alert.message()));
      }
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
  private void recordBlockState(Player player, dev.phantom.ac.world.Pos position, dev.phantom.ac.world.BlockState state) {
    Packets.Packet packet=state.isUnsupported()?new Packets.UnsupportedBlockStateChange(position,state):new Packets.BlockStateChange(position,state);
    record(player,packet);
  }
  private void sendOperatorAlert(String message, boolean broadcast) {
    if (broadcast) { getServer().broadcastMessage(message); return; }
    for (Player recipient : getServer().getOnlinePlayers())
      if (recipient.hasPermission("phantom.admin")) recipient.sendMessage(message);
    getLogger().info(message);
  }

  private static Vec3 vector(double x, double y, double z) { return new Vec3(x,y,z); }

  private static World.Block toLegacyBlock(dev.phantom.ac.world.BlockState state) {
    if (state.isAir()) return World.Block.AIR;
    return switch (state.variant()) {
      case FULL_CUBE -> state.blockId().contains("ice") ? World.Block.ICE : World.Block.FULL;
      case SLAB -> state.half()==dev.phantom.ac.world.BlockState.Half.TOP ? World.Block.SLAB_TOP : World.Block.SLAB_BOTTOM;
      case STAIRS -> switch (state.facing()) {
        case NORTH -> World.Block.STAIRS_NORTH; case SOUTH -> World.Block.STAIRS_SOUTH;
        case EAST -> World.Block.STAIRS_EAST; case WEST -> World.Block.STAIRS_WEST; default -> World.Block.UNSUPPORTED;
      };
      case CARPET -> World.Block.CARPET;
      case SNOW_LAYER -> World.Block.values()[World.Block.SNOW_LAYER_1.ordinal()+Math.max(0,state.layers()-1)];
      case FENCE, WALL, PANE -> World.Block.FENCE;
      case FLUID -> World.Block.WATER;
      case LADDER -> World.Block.LADDER;
      default -> World.Block.UNSUPPORTED;
    };
  }

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

  private static final class PendingChunk {
    final UUID playerId; final int chunkX,chunkZ; final com.github.retrooper.packetevents.protocol.player.ClientVersion clientVersion;
    final Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> states=new LinkedHashMap<>();
    int nextIndex;
    PendingChunk(UUID playerId,int chunkX,int chunkZ,com.github.retrooper.packetevents.protocol.player.ClientVersion clientVersion){this.playerId=playerId;this.chunkX=chunkX;this.chunkZ=chunkZ;this.clientVersion=clientVersion;}
    int totalBlocks(int minY,int maxY){return 16*16*(maxY-minY);}
    UUID playerId(){return playerId;} int chunkX(){return chunkX;} int chunkZ(){return chunkZ;}
  }

  private static final class Capture {
    final long epochNanos; final AtomicLong sequence = new AtomicLong();
    final AtomicLong chunkPackets = new AtomicLong();
    final Queue<PendingChunk> chunkQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final List<RawPacket> packets = Collections.synchronizedList(new ArrayList<>());
    OperatorValidation.Aggregator aggregator=OperatorValidation.Aggregator.empty();
    final AtomicBoolean validationRunning=new AtomicBoolean();
    int processedFindings;
    Capture(long epochNanos) { this.epochNanos=epochNanos; }
    List<RawPacket> copy() { synchronized (packets) { return List.copyOf(packets); } }
  }
}