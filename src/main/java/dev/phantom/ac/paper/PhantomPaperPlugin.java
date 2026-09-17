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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
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

import dev.phantom.ac.Contracts;
import dev.phantom.ac.Diagnostics;
import dev.phantom.ac.LiveValidation;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.OperatorValidation;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.State;
import dev.phantom.ac.Timeline;
import dev.phantom.ac.Validation;
import dev.phantom.ac.World;

/**
 * Paper boundary only. No Bukkit type enters the deterministic core.
 * PlayerMoveEvent is server observation, not a client packet; protocol packets are
 * normalized into the deterministic core through PacketEvents.
 */
public final class PhantomPaperPlugin extends JavaPlugin implements Listener {
  private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();
  private org.bukkit.scheduler.BukkitTask validationTask;
  private org.bukkit.scheduler.BukkitTask chunkTask;
  private ExecutorService chunkExecutor;
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
        RelativeFlag flags = packet.getRelativeFlags();
        record(player, new Packets.Teleport(packet.getTeleportId(), vector(packet.getX(), packet.getY(), packet.getZ()), packet.getYaw(), packet.getPitch(),
            flags.has(RelativeFlag.X), flags.has(RelativeFlag.Y), flags.has(RelativeFlag.Z), flags.has(RelativeFlag.YAW), flags.has(RelativeFlag.PITCH)));
      } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
        WrapperPlayServerEntityVelocity packet = new WrapperPlayServerEntityVelocity(event);
        if (packet.getEntityId() == player.getEntityId()) {
          var velocity = packet.getVelocity();
          record(player, new Packets.Velocity(vector(velocity.getX(), velocity.getY(), velocity.getZ())));
        }
      } else if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
        WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
        var position = packet.getBlockPosition();
        recordBlockState(player, new dev.phantom.ac.world.Pos(position.getX(), position.getY(), position.getZ()), toCoreState(packet.getBlockState()));
      } else if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
        WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);
        for (var change : packet.getBlocks()) {
          recordBlockState(player, new dev.phantom.ac.world.Pos(change.getX(), change.getY(), change.getZ()), toCoreState(change.getBlockState(event.getUser().getClientVersion())));
        }
      } else if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
        WrapperPlayServerUnloadChunk packet = new WrapperPlayServerUnloadChunk(event);
        record(player, new Packets.ChunkUnload(new World.Chunk(packet.getChunkX(), packet.getChunkZ())));
      } else if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
        WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(event);
        Column column = packet.getColumn();
        Capture capture = captures.computeIfAbsent(player.getUniqueId(), ignored -> new Capture(System.nanoTime()));
        long sequence = capture.sequence.incrementAndGet();
        long receivedNanos = System.nanoTime();
        int minY = player.getWorld().getMinHeight();
        int maxY = player.getWorld().getMaxHeight();
        ClientVersion clientVersion = event.getUser().getClientVersion();
        capture.chunkPackets.incrementAndGet();
        capture.chunkQueue.add(new PendingChunk(sequence, receivedNanos, column, minY, maxY, clientVersion));
      }
    }
  };

  @Override public void onEnable() {
    getServer().getPluginManager().registerEvents(this, this);
    PacketEvents.getAPI().getEventManager().registerListener(networkListener);
    saveDefaultConfig();
    alertsEnabled = getConfig().getBoolean("alerts.enabled", true);
    broadcastAlerts = getConfig().getBoolean("alerts.broadcast", false);
    kicksEnabled = getConfig().getBoolean("enforcement.kick-enabled", false);
    permissionExempt = getConfig().getBoolean("enforcement.permission-exempt", true);
    exemptPermission = getConfig().getString("enforcement.permission", "phantom.exempt");
    minimumConfidence = getConfig().getDouble("enforcement.minimum-confidence", 0.90);
    validationBudget = Math.max(1, getConfig().getInt("validation.candidate-budget", 4096));
    chunkExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "Phantom-ClientChunkDecoder");
      thread.setDaemon(true);
      return thread;
    });
    chunkTask = getServer().getScheduler().runTaskTimer(this, this::drainChunkQueue, 1L, 1L);
    int validationInterval = Math.max(1, getConfig().getInt("validation.interval-ticks", 10));
    validationTask = getServer().getScheduler().runTaskTimerAsynchronously(this, this::evaluateCapturesAsync, validationInterval, validationInterval);
  }

  @Override public void onDisable() {
    if (chunkTask != null) chunkTask.cancel();
    if (validationTask != null) validationTask.cancel();
    PacketEvents.getAPI().getEventManager().unregisterListener(networkListener);
    if (chunkExecutor != null) {
      chunkExecutor.shutdownNow();
      try { chunkExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    captures.clear();
  }

  /** Submits a bounded number of client chunk decodes per tick; all expensive work runs off the Paper thread. */
  private void drainChunkQueue() {
    if (chunkExecutor == null) return;
    int submitted = 0;
    while (submitted < 2) {
      Capture selected = null;
      PendingChunk pending = null;
      for (Capture capture : captures.values()) {
        pending = capture.chunkQueue.poll();
        if (pending != null) { selected = capture; break; }
      }
      if (selected == null) return;
      Capture target = selected;
      PendingChunk work = pending;
      chunkExecutor.execute(() -> decodeChunk(target, work));
      submitted++;
    }
  }

  private void decodeChunk(Capture capture, PendingChunk pending) {
    try {
      Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> states = new LinkedHashMap<>();
      BaseChunk[] sections = pending.column.getChunks();
      int minSection = Math.floorDiv(pending.minY, 16);
      int maxSectionExclusive = Math.floorDiv(pending.maxY - 1, 16) + 1;
      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
        BaseChunk section = sections[sectionIndex];
        if (section == null || section.isEmpty()) continue;
        int sectionY = minSection + sectionIndex;
        if (sectionY < minSection || sectionY >= maxSectionExclusive) continue;
        int baseY = sectionY * 16;
        for (int localX = 0; localX < 16; localX++) {
          for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
              WrappedBlockState state = section.get(pending.clientVersion, localX, localY, localZ, false);
              dev.phantom.ac.world.BlockState core = toCoreState(state);
              if (!core.isAir()) {
                states.put(new dev.phantom.ac.world.Pos(pending.column.getX() * 16 + localX, baseY + localY, pending.column.getZ() * 16 + localZ), core);
              }
            }
          }
        }
      }
      Packets.ChunkStates packet = new Packets.ChunkStates(new dev.phantom.ac.world.Chunk(pending.column.getX(), pending.column.getZ()), states);
      capture.packets.add(new RawPacket(pending.sequence(), pending.receivedNanos(), packet));
    } catch (RuntimeException failure) {
      getLogger().warning("client chunk decode failed for " + pending.column.getX() + "," + pending.column.getZ() + ": " + failure.getClass().getSimpleName());
    }
  }

  private void evaluateCapturesAsync() {
    if (!alertsEnabled && !kicksEnabled) return;
    for (Map.Entry<UUID, Capture> entry : captures.entrySet()) {
      Capture capture = entry.getValue();
      if (!capture.validationRunning.compareAndSet(false, true)) continue;
      List<RawPacket> raw = capture.copy();
      if (raw.isEmpty()) { capture.validationRunning.set(false); continue; }
      try {
        Timeline.Snapshot timeline = Timeline.assign(new Packets.Normalizer().normalize(raw), capture.epochNanos, 50_000_000L);
        LiveValidation.Report report = LiveValidation.analyze(timeline, validationBudget);
        getServer().getScheduler().runTask(this, () -> applyValidationResult(entry.getKey(), capture, report));
      } catch (RuntimeException failure) {
        capture.validationRunning.set(false);
        getLogger().warning("validation skipped for " + entry.getKey() + ": " + failure.getClass().getSimpleName());
      }
    }
  }

  private void applyValidationResult(UUID playerId, Capture capture, LiveValidation.Report report) {
    try {
      Player player = getServer().getPlayer(playerId);
      if (player == null) return;
      synchronized (capture) {
        int start = Math.min(capture.processedFindings, report.findings().size());
        for (int i = start; i < report.findings().size(); i++) {
          LiveValidation.Finding finding = report.findings().get(i);
          Validation.Verdict verdict = finding.verdict();
          Validation.Reachability reachability = new Validation.Reachability(verdict, java.util.Set.of(), finding.reasons());
          Validation.Evidence evidence = new Validation.Evidence(verdict, "MOVEMENT_REACHABILITY", Double.NaN, finding.reasons());
          boolean timingUncertain = verdict == Validation.Verdict.UNCERTAIN || finding.reasons().stream().anyMatch(reason -> reason.contains("unknown") || reason.contains("unsupported") || reason.contains("budget"));
          Validation.SyncWindow sync = new Validation.SyncWindow(finding.tick(), finding.tick(), timingUncertain, finding.reasons());
          State.Player anchor = State.Player.initial(Vec3.ZERO);
          OperatorValidation.Observation observation = new OperatorValidation.Observation(player.getName(), finding.tick(), anchor, anchor, sync, Contracts.TARGET_VERSION, List.of(), reachability, evidence);
          OperatorValidation.Result result = OperatorValidation.aggregate(capture.aggregator, observation);
          capture.aggregator = result.state();
          if (result.alert().isPresent()) {
            OperatorValidation.Alert alert = result.alert().orElseThrow();
            if (alertsEnabled) sendOperatorAlert(alert.message(), broadcastAlerts);
            if (kicksEnabled && alert.confidence() >= minimumConfidence && !(permissionExempt && player.hasPermission(exemptPermission))) {
              player.kickPlayer("Movement validation failed: " + alert.reason());
            }
          }
        }
        capture.processedFindings = report.findings().size();
      }
    } finally {
      capture.validationRunning.set(false);
    }
  }

  @EventHandler public void joined(PlayerJoinEvent event) { captures.put(event.getPlayer().getUniqueId(), new Capture(System.nanoTime())); }
  @EventHandler public void changedWorld(PlayerChangedWorldEvent event) { captures.put(event.getPlayer().getUniqueId(), new Capture(System.nanoTime())); }
  @EventHandler public void quit(PlayerQuitEvent event) { captures.remove(event.getPlayer().getUniqueId()); }

  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!command.getName().equalsIgnoreCase("phantom")) return false;
    boolean showAlerts = args.length == 2 && args[0].equalsIgnoreCase("alerts");
    boolean validate = args.length == 2 && args[0].equalsIgnoreCase("validate");
    String target = (showAlerts || validate) ? args[1] : args.length == 1 ? args[0] : null;
    if (target == null) { sender.sendMessage("Usage: /phantom <player> | /phantom alerts <player> | /phantom validate <player>"); return true; }
    Player player = getServer().getPlayerExact(target);
    if (player == null) { sender.sendMessage("Player not found."); return true; }
    Capture capture = captures.get(player.getUniqueId());
    if (capture == null) { sender.sendMessage("No capture for player."); return true; }

    if (validate) {
      List<RawPacket> raw = capture.copy();
      long capturedEvents = raw.size();
      String playerName = player.getName();
      long captureEpoch = capture.epochNanos;
      sender.sendMessage("Phantom validation started asynchronously for " + playerName + " (" + capturedEvents + " captured packets).");
      getServer().getScheduler().runTaskAsynchronously(this, () -> {
        try {
          Timeline.Snapshot timeline = Timeline.assign(new Packets.Normalizer().normalize(raw), captureEpoch, 50_000_000L);
          LiveValidation.Report report = LiveValidation.analyze(timeline, validationBudget);
          getServer().getScheduler().runTask(this, () -> sendValidationDiagnostic(sender, playerName, capture, report));
        } catch (RuntimeException failure) {
          String detail = failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
          getServer().getScheduler().runTask(this, () -> sender.sendMessage("Phantom validation failed safely off-thread: " + detail));
        }
      });
      return true;
    }

    List<Packets.NormalizedPacket> normalized = new Packets.Normalizer().normalize(capture.copy());
    Timeline.Snapshot timeline = Timeline.assign(normalized, capture.epochNanos, 50_000_000L);
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

  private void sendValidationDiagnostic(CommandSender sender, String playerName, Capture capture, LiveValidation.Report report) {
    long possible = report.findings().stream().filter(f -> f.verdict() == Validation.Verdict.POSSIBLE).count();
    long uncertain = report.findings().stream().filter(f -> f.verdict() == Validation.Verdict.UNCERTAIN).count();
    long impossible = report.findings().stream().filter(f -> f.verdict() == Validation.Verdict.IMPOSSIBLE).count();
    sender.sendMessage("Phantom movement diagnostic: " + report.movementObservations() + " observations; " + possible + " reachable, " + uncertain + " uncertain, " + impossible + " impossible.");
    sender.sendMessage("Pipeline: packets=" + report.timelineEvents() + " anchored=" + report.anchoredObservations() + " possible=" + report.possibleFindings() + " uncertain=" + report.uncertainFindings() + " impossible=" + report.impossibleFindings() + " evidence=" + capture.aggregator.impossibleByRule().values().stream().mapToInt(Integer::intValue).sum());
    sender.sendMessage("Diagnostic only: no enforcement or punishment; independent 1.21.11 vanilla traces are still required before any cheat flag.");
    report.findings().stream().filter(f -> f.verdict() != Validation.Verdict.POSSIBLE).limit(3).forEach(f -> sender.sendMessage("[" + f.verdict() + "] tick " + f.tick() + ": " + f.reasons().getFirst()));

    OperatorValidation.Aggregator aggregate = OperatorValidation.Aggregator.empty();
    State.Player anchor = State.Player.initial(Vec3.ZERO);
    for (LiveValidation.Finding finding : report.findings()) {
      Validation.Reachability reachability = new Validation.Reachability(finding.verdict(), java.util.Set.of(), finding.reasons());
      Validation.Evidence evidence = new Validation.Evidence(finding.verdict(), "MOVEMENT_REACHABILITY", Double.NaN, finding.reasons());
      OperatorValidation.Observation observation = new OperatorValidation.Observation(playerName, finding.tick(), anchor, anchor,
          new Validation.SyncWindow(finding.tick(), finding.tick(), false, java.util.List.of("command-time replay has no measured RTT")),
          Contracts.TARGET_VERSION, java.util.List.of(), reachability, evidence);
      OperatorValidation.Result result = OperatorValidation.aggregate(aggregate, observation);
      aggregate = result.state();
      result.alert().ifPresent(alert -> sender.sendMessage(alert.message()));
    }
  }

  private void record(Player player, Packets.Packet packet) { record(captures.computeIfAbsent(player.getUniqueId(), ignored -> new Capture(System.nanoTime())), packet); }
  private void record(Capture capture, Packets.Packet packet) { capture.packets.add(new RawPacket(capture.sequence.incrementAndGet(), System.nanoTime(), packet)); }
  private void recordBlockState(Player player, dev.phantom.ac.world.Pos position, dev.phantom.ac.world.BlockState state) {
    Packets.Packet packet = state.isUnsupported() ? new Packets.UnsupportedBlockStateChange(position, state) : new Packets.BlockStateChange(position, state);
    record(player, packet);
  }
  private void sendOperatorAlert(String message, boolean broadcast) {
    if (broadcast) { getServer().broadcastMessage(message); return; }
    for (Player recipient : getServer().getOnlinePlayers()) if (recipient.hasPermission("phantom.admin")) recipient.sendMessage(message);
    getLogger().info(message);
  }

  private static Vec3 vector(double x, double y, double z) { return new Vec3(x, y, z); }

  private static dev.phantom.ac.world.BlockState toCoreState(WrappedBlockState state) {
    if (state == null || state.getType().isAir()) return dev.phantom.ac.world.BlockState.air();
    String name = state.getType().getName();
    Map<String, String> properties = new LinkedHashMap<>();
    putEnum(properties, "type", state.getData(StateValue.TYPE));
    putEnum(properties, "facing", state.getData(StateValue.FACING));
    putEnum(properties, "half", state.getData(StateValue.HALF));
    putEnum(properties, "shape", state.getData(StateValue.SHAPE));
    putEnum(properties, "part", state.getData(StateValue.PART));
    putEnum(properties, "hinge", state.getData(StateValue.HINGE));
    putNumber(properties, "layers", state.getData(StateValue.LAYERS));
    putNumber(properties, "level", state.getData(StateValue.LEVEL));
    putNumber(properties, "candles", state.getData(StateValue.CANDLES));
    putNumber(properties, "pickles", state.getData(StateValue.PICKLES));
    putBoolean(properties, "waterlogged", state.getData(StateValue.WATERLOGGED));
    putBoolean(properties, "open", state.getData(StateValue.OPEN));
    putBoolean(properties, "powered", state.getData(StateValue.POWERED));
    putBoolean(properties, "up", state.getData(StateValue.UP));
    putBoolean(properties, "north", state.getData(StateValue.NORTH));
    putBoolean(properties, "south", state.getData(StateValue.SOUTH));
    putBoolean(properties, "west", state.getData(StateValue.WEST));
    putBoolean(properties, "east", state.getData(StateValue.EAST));
    putBoolean(properties, "lit", state.getData(StateValue.LIT));
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(name, properties);
  }

  private static void putEnum(Map<String, String> properties, String key, Object value) {
    if (value != null) properties.put(key, value.toString().toLowerCase(Locale.ROOT));
  }
  private static void putNumber(Map<String, String> properties, String key, Object value) {
    if (value instanceof Number number) properties.put(key, Integer.toString(number.intValue()));
  }
  private static void putBoolean(Map<String, String> properties, String key, Object value) {
    if (value instanceof Boolean flag) properties.put(key, Boolean.toString(flag));
  }

  private record PendingChunk(long sequence, long receivedNanos, Column column, int minY, int maxY, ClientVersion clientVersion) {}

  private static final class Capture {
    final long epochNanos;
    final AtomicLong sequence = new AtomicLong();
    final AtomicLong chunkPackets = new AtomicLong();
    final Queue<PendingChunk> chunkQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final List<RawPacket> packets = Collections.synchronizedList(new ArrayList<>());
    OperatorValidation.Aggregator aggregator = OperatorValidation.Aggregator.empty();
    final AtomicBoolean validationRunning = new AtomicBoolean();
    int processedFindings;
    Capture(long epochNanos) { this.epochNanos = epochNanos; }
    List<RawPacket> copy() { synchronized (packets) { return List.copyOf(packets); } }
  }
}
