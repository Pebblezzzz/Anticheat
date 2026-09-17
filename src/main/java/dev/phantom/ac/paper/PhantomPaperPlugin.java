package dev.phantom.ac.paper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase8LiveValidation;
import dev.phantom.ac.Phase8MovementValidation;
import dev.phantom.ac.Phase7Timing;
import dev.phantom.ac.Timeline;
import dev.phantom.ac.World;

/**
 * Paper boundary only. No Bukkit type enters the deterministic core.
 * Protocol packets are normalized into the Phase 1-7 pipeline and Phase 8 is
 * then run as the canonical observation-only movement validator.
 */
public final class PhantomPaperPlugin extends JavaPlugin implements Listener {
  private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();
  private org.bukkit.scheduler.BukkitTask validationTask;
  private org.bukkit.scheduler.BukkitTask chunkTask;
  private ExecutorService chunkExecutor;
  private volatile int chunkDecoderThreads;
  private final AtomicInteger chunkInFlight = new AtomicInteger();
  private final Map<ClientVersion, ConcurrentHashMap<Integer, dev.phantom.ac.world.BlockState>> coreStateCache = new ConcurrentHashMap<>();
  private volatile boolean alertsEnabled;
  private volatile boolean broadcastAlerts;
  private volatile boolean phase8Debug;
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
    phase8Debug = getConfig().getBoolean("debug.phase8", false);
    validationBudget = Math.max(1, getConfig().getInt("validation.candidate-budget", 4096));
    int processors = Runtime.getRuntime().availableProcessors();
    chunkDecoderThreads = Math.max(1, Math.min(4, processors / 2));
    chunkExecutor = Executors.newFixedThreadPool(chunkDecoderThreads, r -> {
      Thread thread = new Thread(r, "Phantom-ClientChunkDecoder");
      thread.setDaemon(true);
      return thread;
    });
    chunkTask = getServer().getScheduler().runTaskTimer(this, this::drainChunkQueue, 1L, 1L);
    int validationInterval = Math.max(1, getConfig().getInt("validation.interval-ticks", 10));
    validationTask = getServer().getScheduler().runTaskTimerAsynchronously(this, this::evaluateCapturesAsync, validationInterval, validationInterval);
    getLogger().info("Phase 8 live validation enabled: alerts=" + alertsEnabled + ", debug=" + phase8Debug
        + ", intervalTicks=" + validationInterval + ", candidateBudget=" + validationBudget
        + ", chunkDecoderThreads=" + chunkDecoderThreads);
  }

  @Override public void onDisable() {
    getServer().getScheduler().cancelTasks(this);
    if (chunkTask != null) chunkTask.cancel();
    if (validationTask != null) validationTask.cancel();
    PacketEvents.getAPI().getEventManager().unregisterListener(networkListener);
    if (chunkExecutor != null) {
      chunkExecutor.shutdownNow();
      try { chunkExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    chunkInFlight.set(0);
    coreStateCache.clear();
    captures.clear();
  }

  /** Submits at most one task per decoder thread so expensive decoding cannot build a hidden executor backlog. */
  private void drainChunkQueue() {
    if (chunkExecutor == null) return;
    int submitted = 0;
    while (submitted < chunkDecoderThreads) {
      if (chunkInFlight.get() >= chunkDecoderThreads) return;
      Capture selected = null;
      PendingChunk pending = null;
      for (Capture capture : captures.values()) {
        pending = capture.chunkQueue.poll();
        if (pending != null) { selected = capture; break; }
      }
      if (selected == null) return;
      Capture target = selected;
      PendingChunk work = pending;
      chunkInFlight.incrementAndGet();
      try {
        chunkExecutor.execute(() -> {
          try {
            decodeChunk(target, work);
          } finally {
            chunkInFlight.decrementAndGet();
          }
        });
      } catch (RejectedExecutionException rejected) {
        chunkInFlight.decrementAndGet();
        target.chunkQueue.add(work);
        return;
      }
      submitted++;
    }
  }

  /**
   * Decodes the PacketEvents palette directly for modern (1.21.11+) chunks.
   * PacketEvents exposes the packed storage publicly; walking it avoids the
   * expensive per-block DataPalette#get -> storage/palette indirection while
   * preserving exactly the same global block-state ids.
   */
  private void decodeChunk(Capture capture, PendingChunk pending) {
    long startedNanos = System.nanoTime();
    try {
      Map<dev.phantom.ac.world.Pos, dev.phantom.ac.world.BlockState> states = new LinkedHashMap<>();
      ConcurrentHashMap<Integer, dev.phantom.ac.world.BlockState> stateCache = coreStateCache.computeIfAbsent(pending.clientVersion, ignored -> new ConcurrentHashMap<>());
      BaseChunk[] sections = pending.column.getChunks();
      int minSection = Math.floorDiv(pending.minY, 16);
      int maxSectionExclusive = Math.floorDiv(pending.maxY - 1, 16) + 1;
      int baseX = pending.column.getX() * 16;
      int baseZ = pending.column.getZ() * 16;
      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
        BaseChunk section = sections[sectionIndex];
        if (section == null || section.isEmpty()) continue;
        int sectionY = minSection + sectionIndex;
        if (sectionY < minSection || sectionY >= maxSectionExclusive) continue;
        int baseY = sectionY * 16;

        boolean packedDecoded = false;
        if (section instanceof com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18 modernSection) {
          com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette palette = modernSection.getChunkData();
          if (palette.storage == null) {
            int globalId = palette.palette.idToState(0);
            dev.phantom.ac.world.BlockState core = stateCache.get(globalId);
            if (core == null) {
              WrappedBlockState state = globalId == 0 ? null : WrappedBlockState.getByGlobalId(pending.clientVersion, globalId, false);
              core = state == null || state.getType().isAir() ? dev.phantom.ac.world.BlockState.air() : toCoreState(state);
              dev.phantom.ac.world.BlockState existing = stateCache.putIfAbsent(globalId, core);
              if (existing != null) core = existing;
            }
            if (!core.isAir()) {
              for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                  for (int localX = 0; localX < 16; localX++) {
                    states.put(new dev.phantom.ac.world.Pos(baseX + localX, baseY + localY, baseZ + localZ), core);
                  }
                }
              }
            }
            packedDecoded = true;
          } else if (palette.storage instanceof com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage storage) {
            long[] data = storage.getData();
            int bits = storage.getBitsPerEntry();
            int valuesPerLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            int linearIndex = 0;
            for (int cellIndex = 0; cellIndex < data.length && linearIndex < 4096; cellIndex++) {
              long cell = data[cellIndex];
              int values = Math.min(valuesPerLong, 4096 - linearIndex);
              for (int slot = 0; slot < values; slot++, linearIndex++) {
                int paletteId = (int) ((cell >>> (slot * bits)) & mask);
                int globalId = palette.palette.idToState(paletteId);
                if (globalId <= 0) continue;
                dev.phantom.ac.world.BlockState core = stateCache.get(globalId);
                if (core == null) {
                  WrappedBlockState state = WrappedBlockState.getByGlobalId(pending.clientVersion, globalId, false);
                  core = state == null || state.getType().isAir() ? dev.phantom.ac.world.BlockState.air() : toCoreState(state);
                  dev.phantom.ac.world.BlockState existing = stateCache.putIfAbsent(globalId, core);
                  if (existing != null) core = existing;
                }
                if (!core.isAir()) {
                  int localY = linearIndex >>> 8;
                  int localZ = (linearIndex >>> 4) & 15;
                  int localX = linearIndex & 15;
                  states.put(new dev.phantom.ac.world.Pos(baseX + localX, baseY + localY, baseZ + localZ), core);
                }
              }
            }
            packedDecoded = true;
          }
        }

        if (packedDecoded) continue;
        for (int localX = 0; localX < 16; localX++) {
          for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
              int globalId = section.getBlockId(localX, localY, localZ);
              if (globalId <= 0) continue;
              dev.phantom.ac.world.BlockState core = stateCache.get(globalId);
              if (core == null) {
                WrappedBlockState state = WrappedBlockState.getByGlobalId(pending.clientVersion, globalId, false);
                core = state == null || state.getType().isAir() ? dev.phantom.ac.world.BlockState.air() : toCoreState(state);
                dev.phantom.ac.world.BlockState existing = stateCache.putIfAbsent(globalId, core);
                if (existing != null) core = existing;
              }
              if (!core.isAir()) {
                states.put(new dev.phantom.ac.world.Pos(baseX + localX, baseY + localY, baseZ + localZ), core);
              }
            }
          }
        }
      }
      capture.packets.add(new RawPacket(pending.sequence(), pending.receivedNanos(),
          new Packets.ChunkStates(new dev.phantom.ac.world.Chunk(pending.column.getX(), pending.column.getZ()), states)));
      long decoded = capture.decodedChunks.incrementAndGet();
      if (phase8Debug && capture.decodedChunkLogCounter.incrementAndGet() <= 8) {
        long micros = (System.nanoTime() - startedNanos) / 1_000L;
        getLogger().info("[Phase8][CHUNK] seq=" + pending.sequence() + " chunk=" + pending.column.getX() + "," + pending.column.getZ()
            + " nonAirStates=" + states.size() + " clientVersion=" + pending.clientVersion
            + " decodeMicros=" + micros + " decodedTotal=" + decoded);
      }
    } catch (RuntimeException failure) {
      capture.chunkDecodeFailures.incrementAndGet();
      getLogger().warning("client chunk decode failed for " + pending.column.getX() + "," + pending.column.getZ() + ": " + failure.getClass().getSimpleName()
          + (failure.getMessage() == null ? "" : " - " + failure.getMessage()));
    }
  }

  private void evaluateCapturesAsync() {
    for (Map.Entry<UUID, Capture> entry : captures.entrySet()) {
      Capture capture = entry.getValue();
      if (!capture.validationRunning.compareAndSet(false, true)) continue;
      List<RawPacket> raw = capture.copy();
      if (raw.isEmpty()) { capture.validationRunning.set(false); continue; }
      UUID playerId = entry.getKey();
      Player player = getServer().getPlayer(playerId);
      if (player == null) { capture.validationRunning.set(false); continue; }
      String playerName = player.getName();
      try {
        Timeline.Snapshot timeline = Timeline.assign(new Packets.Normalizer().normalize(raw), capture.epochNanos, 50_000_000L);
        Phase8LiveValidation.Report report = Phase8LiveValidation.analyze(playerName, timeline, validationBudget, Phase7Timing.Config.defaultConfig());
        if (phase8Debug) logPhase8Run(playerName, capture, raw, timeline, report, "scheduled");
        getServer().getScheduler().runTask(this, () -> applyPhase8Result(playerId, capture, report));
      } catch (RuntimeException failure) {
        capture.validationRunning.set(false);
        getLogger().warning("Phase 8 validation skipped for " + playerId + ": " + failure.getClass().getSimpleName()
            + (failure.getMessage() == null ? "" : " - " + failure.getMessage()));
      }
    }
  }

  private void applyPhase8Result(UUID playerId, Capture capture, Phase8LiveValidation.Report report) {
    try {
      Player player = getServer().getPlayer(playerId);
      if (player == null) return;
      synchronized (capture) {
        int start = Math.min(capture.processedPhase8Results, report.results().size());
        Phase8MovementValidation.Config config = new Phase8MovementValidation.Config(2, 20, alertsEnabled, true);
        for (int i = start; i < report.results().size(); i++) {
          Phase8MovementValidation.Result result = report.results().get(i);
          Phase8MovementValidation.Accumulated accumulated = capture.phase8Accumulator.accept(result.evidence(), config);
          capture.phase8Accumulator = accumulated.state();
          accumulated.alert().ifPresent(alert -> sendOperatorAlert(alert.message(), broadcastAlerts));
        }
        capture.processedPhase8Results = report.results().size();
        if (phase8Debug) {
          Phase8MovementValidation.State state = capture.phase8Accumulator.players().get(player.getName() + "/MOVEMENT_REACHABILITY");
          getLogger().info("[Phase8][ACCUM] player=" + player.getName() + " processed=" + capture.processedPhase8Results
              + " consecutiveImpossible=" + (state == null ? 0 : state.consecutiveImpossible())
              + " supportingImpossible=" + (state == null ? 0 : state.supportingImpossible())
              + " uncertaintyPeriods=" + (state == null ? 0 : state.uncertaintyPeriods())
              + " recoveries=" + (state == null ? 0 : state.recoveries()));
        }
      }
    } finally {
      capture.validationRunning.set(false);
    }
  }

  @EventHandler public void joined(PlayerJoinEvent event) {
    captures.put(event.getPlayer().getUniqueId(), new Capture(System.nanoTime()));
  }

  @EventHandler public void changedWorld(PlayerChangedWorldEvent event) {
    captures.put(event.getPlayer().getUniqueId(), new Capture(System.nanoTime()));
  }

  @EventHandler public void quit(PlayerQuitEvent event) {
    captures.remove(event.getPlayer().getUniqueId());
  }

  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!command.getName().equalsIgnoreCase("phantom")) return false;
    boolean showAlerts = args.length == 2 && args[0].equalsIgnoreCase("alerts");
    boolean validate = args.length == 2 && args[0].equalsIgnoreCase("validate");
    String target = (showAlerts || validate) ? args[1] : args.length == 1 ? args[0] : null;
    if (target == null) {
      sender.sendMessage("Usage: /phantom <player> | /phantom alerts <player> | /phantom validate <player>");
      return true;
    }
    Player player = getServer().getPlayerExact(target);
    if (player == null) { sender.sendMessage("Player not found."); return true; }
    Capture capture = captures.get(player.getUniqueId());
    if (capture == null) { sender.sendMessage("No capture for player."); return true; }

    if (validate) {
      List<RawPacket> raw = capture.copy();
      String playerName = player.getName();
      long captureEpoch = capture.epochNanos;
      sender.sendMessage("Phase 8 validation started asynchronously for " + playerName + " (" + raw.size() + " captured packets).");
      getServer().getScheduler().runTaskAsynchronously(this, () -> {
        try {
          Timeline.Snapshot timeline = Timeline.assign(new Packets.Normalizer().normalize(raw), captureEpoch, 50_000_000L);
          Phase8LiveValidation.Report report = Phase8LiveValidation.analyze(playerName, timeline, validationBudget, Phase7Timing.Config.defaultConfig());
          logPhase8Run(playerName, capture, raw, timeline, report, "manual");
          getServer().getScheduler().runTask(this, () -> sendPhase8Diagnostic(sender, playerName, capture, report));
        } catch (RuntimeException failure) {
          String detail = failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
          getServer().getScheduler().runTask(this, () -> sender.sendMessage("Phase 8 validation failed safely off-thread: " + detail));
        }
      });
      return true;
    }

    List<Packets.NormalizedPacket> normalized = new Packets.Normalizer().normalize(capture.copy());
    Timeline.Snapshot timeline = Timeline.assign(normalized, capture.epochNanos, 50_000_000L);
    Diagnostics.Report report = Diagnostics.audit(timeline);
    if (!showAlerts) {
      sender.sendMessage("Phantom raw capture: " + report.movementPackets() + " moves, " + report.inputPackets() + " input changes, "
          + report.teleportConfirms() + " teleport confirms, " + capture.chunkPackets.get() + " chunk packets observed; "
          + timeline.events().size() + " timeline events; " + report.alerts().size() + " diagnostic alerts.");
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

  private void logPhase8Run(String playerName, Capture capture, List<RawPacket> raw,
                            Timeline.Snapshot timeline, Phase8LiveValidation.Report report, String source) {
    long duplicate = timeline.events().stream().filter(e -> e.packet().flags().contains(Packets.PacketFlag.DUPLICATE)).count();
    long outOfOrder = timeline.events().stream().filter(e -> e.packet().flags().contains(Packets.PacketFlag.OUT_OF_ORDER)).count();
    long gaps = timeline.events().stream().filter(e -> e.packet().flags().contains(Packets.PacketFlag.SEQUENCE_GAP)).count();
    long beforeEpoch = timeline.events().stream().filter(e -> e.packet().flags().contains(Packets.PacketFlag.BEFORE_CAPTURE_EPOCH)).count();
    Map<Class<?>, Integer> packetTypes = new LinkedHashMap<>();
    for (Timeline.Event event : timeline.events()) packetTypes.merge(event.packet().packet().getClass(), 1, Integer::sum);
    getLogger().info("[Phase8][RUN] source=" + source + " player=" + playerName + " raw=" + raw.size()
        + " timelineEvents=" + timeline.events().size() + " moves=" + report.movementObservations()
        + " possible=" + report.possible() + " uncertain=" + report.uncertain() + " impossible=" + report.impossible()
        + " chunksSeen=" + capture.chunkPackets.get() + " chunksDecoded=" + capture.decodedChunks.get()
        + " chunkDecodeFailures=" + capture.chunkDecodeFailures.get() + " chunkQueue=" + capture.chunkQueue.size()
        + " flags{duplicate=" + duplicate + ",outOfOrder=" + outOfOrder + ",gaps=" + gaps + ",beforeEpoch=" + beforeEpoch + "}"
        + " packetTypes=" + packetTypes.entrySet().stream().map(e -> e.getKey().getSimpleName() + "=" + e.getValue()).toList());

    int logged = 0;
    for (Phase8MovementValidation.Result result : report.results()) {
      if (result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE && logged >= 1) continue;
      Phase8MovementValidation.Evidence evidence = result.evidence();
      getLogger().info("[Phase8][OBS] verdict=" + result.verdict()
          + " tick=" + evidence.serverTick()
          + " clientTicks=" + evidence.clientTickMin() + ".." + evidence.clientTickMax()
          + " candidates=" + evidence.reachableCandidateCount()
          + " matches=" + evidence.matchingCandidateCount()
          + " eliminated=" + evidence.candidatesEliminated()
          + " reason=" + evidence.eliminationReason()
          + " uncertainty=" + evidence.uncertaintySources()
          + " diagnostics=" + evidence.simulationDiagnostics());
      if (++logged >= 6) break;
    }
  }

  private void sendPhase8Diagnostic(CommandSender sender, String playerName, Capture capture,
                                    Phase8LiveValidation.Report report) {
    sender.sendMessage("Phase 8 movement diagnostic for " + playerName + ": " + report.movementObservations()
        + " observations; " + report.possible() + " possible, " + report.uncertain() + " uncertain, " + report.impossible() + " impossible.");
    sender.sendMessage("Phase 8 accumulator: impossible-support="
        + capture.phase8Accumulator.players().values().stream().mapToInt(Phase8MovementValidation.State::supportingImpossible).sum());
    report.results().stream().filter(r -> r.verdict() != Phase8MovementValidation.Verdict.POSSIBLE).limit(5).forEach(result -> {
      Phase8MovementValidation.Evidence evidence = result.evidence();
      sender.sendMessage("[" + result.verdict() + "] tick " + evidence.serverTick() + ": " + evidence.eliminationReason()
          + " candidates=" + evidence.reachableCandidateCount() + " eliminated=" + evidence.candidatesEliminated());
    });
    sender.sendMessage("Observation only: Phase 8 never kicks/bans/punishes. Replay references are included in the evidence.");
  }

  private void record(Player player, Packets.Packet packet) {
    record(captures.computeIfAbsent(player.getUniqueId(), ignored -> new Capture(System.nanoTime())), packet);
  }

  private void record(Capture capture, Packets.Packet packet) {
    capture.packets.add(new RawPacket(capture.sequence.incrementAndGet(), System.nanoTime(), packet));
  }

  private void recordBlockState(Player player, dev.phantom.ac.world.Pos position, dev.phantom.ac.world.BlockState state) {
    Packets.Packet packet = state.isUnsupported() ? new Packets.UnsupportedBlockStateChange(position, state) : new Packets.BlockStateChange(position, state);
    record(player, packet);
  }

  private void sendOperatorAlert(String message, boolean broadcast) {
    if (broadcast) { getServer().broadcastMessage(message); return; }
    for (Player recipient : getServer().getOnlinePlayers()) {
      if (recipient.hasPermission("phantom.admin")) recipient.sendMessage(message);
    }
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
    final AtomicLong decodedChunks = new AtomicLong();
    final AtomicLong chunkDecodeFailures = new AtomicLong();
    final AtomicLong decodedChunkLogCounter = new AtomicLong();
    final Queue<PendingChunk> chunkQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final List<RawPacket> packets = Collections.synchronizedList(new ArrayList<>());
    Phase8MovementValidation.Accumulator phase8Accumulator = Phase8MovementValidation.Accumulator.empty();
    final AtomicBoolean validationRunning = new AtomicBoolean();
    int processedPhase8Results;
    Capture(long epochNanos) { this.epochNanos = epochNanos; }
    List<RawPacket> copy() { synchronized (packets) { return List.copyOf(packets); } }
  }
}