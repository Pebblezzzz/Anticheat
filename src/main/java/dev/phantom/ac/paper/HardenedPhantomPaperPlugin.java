package dev.phantom.ac.paper;

import io.papermc.paper.event.player.PlayerFailMoveEvent;
import io.netty.channel.Channel;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPainting;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import dev.phantom.ac.ClientTickTracker;
import dev.phantom.ac.Contracts;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase5Mechanics;
import dev.phantom.ac.Phase7Timing;
import dev.phantom.ac.Phase8PredictionRunner;
import dev.phantom.ac.Phase8MovementValidation;
import dev.phantom.ac.PhantomDebugFormatter;
import dev.phantom.ac.Phase8EnforcementPolicy;
import dev.phantom.ac.State;
import dev.phantom.ac.Timeline;
import dev.phantom.ac.ValidationResultGate;
import dev.phantom.ac.World;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Hardened Paper boundary: Bukkit work is main-thread only; validation and packet-world decoding are asynchronous. */
public final class HardenedPhantomPaperPlugin extends JavaPlugin implements Listener {
  private static final int MAX_CAPTURE_PACKETS=12_000,MAX_VALIDATION_PACKETS=6_000;
  private static final int LOCAL_SNAPSHOT_RADIUS_CHUNKS=2;
  private static final long WORLD_TRANSACTION_MIN_INTERVAL_NANOS=2_000_000L;
  private static final long PAPER_MOVE_FAILURE_WINDOW_NANOS=1_000_000_000L;
  private static final int PAPER_MOVE_FAILURE_THRESHOLD=1;

  private final Map<UUID,Capture> captures=new ConcurrentHashMap<>();
  private enum DebugLevel {
    OFF, SUMMARY, FOCUS, TRACE;
    boolean summary(){return this==SUMMARY;}
    boolean focus(){return this==FOCUS || this==TRACE;}
    boolean trace(){return this==TRACE;}
  }

  private final Map<UUID,DebugLevel> debugPlayers=new ConcurrentHashMap<>();
  private org.bukkit.scheduler.BukkitTask stateTask;
  private int validationBudget;
  private boolean alertsEnabled,broadcastAlerts,printAlertsToConsole,setbacksEnabled,setbacksOnlyExhaustive;
  private String alertPermission;
  private int alertIntervalTicks;
  private double violationIncrement,violationDecayPerTick,maximumViolationLevel;
  private boolean kickEnabled,punishmentEnabled,enforcementOnlyExhaustive,permissionExempt;
  private int minimumImpossibleObservations;
  private double minimumEnforcementConfidence;
  private String punishmentCommand,exemptionPermission;
  private final Map<UUID,Boolean> setbackOverrides=new ConcurrentHashMap<>();
  private ExecutorService chunkExecutor;
  private ExecutorService worldPublishExecutor;
  private ExecutorService validationExecutor;
  private volatile int chunkDecoderThreads;
  private final AtomicInteger chunkInFlight=new AtomicInteger();

  private final PacketListenerAbstract listener=new PacketListenerAbstract(){
    @Override public void onPacketReceive(PacketReceiveEvent event){
      UUID playerId=event.getUser().getUUID();
      if(playerId==null)return;
      Capture capture=captures.computeIfAbsent(playerId,ignored->new Capture(playerId,System.nanoTime(),validationBudget));
      capture.nettyChannel=asNettyChannel(event.getChannel());
      capture.playerName=event.getUser().getName();

      if(event.getPacketType()==PacketType.Play.Client.CLIENT_TICK_END){
        capture.clientTickTracker.onClientTickEnd();
        Packets.ClientTickEnd boundary=new Packets.ClientTickEnd();
        Long authoritativeTick=capture.authoritativeServerTick.get()>=0
            ?capture.authoritativeServerTick.get():null;
        appendPacket(capture,new RawPacket(capture.sequence.incrementAndGet(),System.nanoTime(),boundary,
            Packets.CaptureProvenance.fromAdapter("paper-client-tick-end",boundary,authoritativeTick)));
        return;
      }

      if(WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())){
        var packet=new WrapperPlayClientPlayerFlying(event);
        var location=packet.getLocation();
        ClientTickTracker.MovementObservation tickObservation=capture.clientTickTracker.onMovement();
        if(!tickObservation.oneToOne())capture.multiMovementPackets.incrementAndGet();
        /*
         * Once CLIENT_TICK_END has been observed, its monotonic relative counter is
         * a capture-local chronology fact: it comes from the protocol boundary itself,
         * not from server-tick estimation. Persist it as Move.clientTick so Phase 7
         * can use an exact client tick instead of widening every movement by latency.
         * The pre-first-boundary case remains untimed and is handled conservatively.
         */
        Long clientTick=tickObservation.hasSeenTickEnd()
            ?tickObservation.clientTick()
            :null;
        Long authoritativeTick=capture.authoritativeServerTick.get()>=0
            ?capture.authoritativeServerTick.get():null;
        Packets.Move move=new Packets.Move(
            packet.hasPositionChanged()?vector(location.getX(),location.getY(),location.getZ()):null,
            packet.hasRotationChanged()?location.getYaw():null,
            packet.hasRotationChanged()?location.getPitch():null,
            packet.isOnGround(),
            clientTick);
        String sourceId=tickObservation.hasSeenTickEnd()?"paper-client-tick-boundary":"paper-relative-first-tick";
        long sequence=capture.sequence.incrementAndGet();
        long receivedNanos=System.nanoTime();
        if(debugLevel(capture.playerId).trace())
          logMovementPacketDebug(capture.playerName,capture,sequence,receivedNanos,event.getPacketType().toString(),packet,move,tickObservation);
        appendPacket(capture,new RawPacket(sequence,receivedNanos,move,
            Packets.CaptureProvenance.fromAdapter(sourceId,move,authoritativeTick)));
        schedulePredictionValidation(capture);
      }else if(event.getPacketType()==PacketType.Play.Client.PLAYER_INPUT){
        var input=new WrapperPlayClientPlayerInput(event);
        Packets.ClientInput clientInput=new Packets.ClientInput(
            input.isForward(),input.isBackward(),input.isLeft(),input.isRight(),
            input.isJump(),input.isShift(),input.isSprint());
        record(capture,clientInput);
        if(debugLevel(capture.playerId).trace())
          logClientInputDebug(capture.playerName,capture.sequence.get(),clientInput);
      }else if(event.getPacketType()==PacketType.Play.Client.PLAYER_DIGGING){
        WrapperPlayClientPlayerDigging digging=new WrapperPlayClientPlayerDigging(event);
        if(digging.getAction()==DiggingAction.FINISHED_DIGGING){
          var blockPosition=digging.getBlockPosition();
          var position=new dev.phantom.ac.world.Pos(
              blockPosition.getX(),blockPosition.getY(),blockPosition.getZ());
          long sequence=capture.sequence.incrementAndGet();
          long receivedNanos=System.nanoTime();
          Long clientTick=capture.clientTickTracker.hasObservedBoundary()
              ?capture.clientTickTracker.clientTickForMovement()
              :null;
          Packets.ClientBlockBreak breakPacket =
              new Packets.ClientBlockBreak(position,digging.getSequence(),clientTick);
          WorldSnapshot visibleWorld =
              capture.clientWorld.snapshotAtOrBeforeIncludingPending(sequence);
          boolean knownSolid=visibleWorld!=null
              && visibleWorld.coverageAt(position.x(),position.y(),position.z())
                  ==dev.phantom.ac.world.Coverage.KNOWN
              && visibleWorld.blockAtOrNull(position.x(),position.y(),position.z())!=null;
          if(knownSolid){
            capture.clientBreakPredictions.put(position,
                new Capture.ClientBreakPrediction(sequence,digging.getSequence(),clientTick));
            if(debugLevel(capture.playerId).trace()){
              getLogger().info("[PhantomAC][BLOCK_BREAK_PREDICT] player="+capture.playerName
                  +" position="+position
                  +" actionSequence="+digging.getSequence()
                  +" clientTick="+clientTick
                  +" captureSequence="+sequence);
            }
          }
          appendPacket(capture,new RawPacket(sequence,receivedNanos,breakPacket,
              Packets.CaptureProvenance.fromAdapter("paper-client-block-break",breakPacket,null)));
          schedulePredictionValidation(capture);
        }
      }else if(event.getPacketType()==PacketType.Play.Client.TELEPORT_CONFIRM){
        record(capture,new Packets.TeleportConfirm(new WrapperPlayClientTeleportConfirm(event).getTeleportId()));
        schedulePredictionValidation(capture);
      }else if(event.getPacketType()==PacketType.Play.Client.PONG){
        int id=new WrapperPlayClientPong(event).getId();
        if(id>=0)return;
        short transaction=(short)id;
        if(capture.outstandingTransactions.remove(transaction)){
          long sequence=capture.sequence.incrementAndGet();
          long receivedNanos=System.nanoTime();
          capture.playerState.acknowledgeBarrier(transaction,sequence);
          try{
            worldPublishExecutor.execute(()->{
              try{
                capture.clientWorld.acknowledge(transaction,sequence);
                Packets.WorldTransactionAck ack=new Packets.WorldTransactionAck(transaction);
                appendPacket(capture,new RawPacket(sequence,receivedNanos,ack,
                    Packets.CaptureProvenance.fromAdapter("paper-transaction-ack",ack,null)));
                schedulePredictionValidation(capture);
              }finally{
                capture.reservedTransactions.remove(transaction);
              }
            });
          }catch(RejectedExecutionException rejected){
            capture.outstandingTransactions.add(transaction);
          }
        }
      }
    }
    @Override public void onPacketSend(PacketSendEvent event){
      UUID playerId=event.getUser().getUUID();
      if(playerId==null)return;
      Capture capture=captures.computeIfAbsent(playerId,ignored->new Capture(playerId,System.nanoTime(),validationBudget));
      capture.nettyChannel=asNettyChannel(event.getChannel());
      capture.playerName=event.getUser().getName();

      if(event.getPacketType()==PacketType.Play.Server.PLAYER_POSITION_AND_LOOK){
        var packet=new WrapperPlayServerPlayerPositionAndLook(event);
        RelativeFlag flags=packet.getRelativeFlags();
        record(capture,new Packets.Teleport(packet.getTeleportId(),vector(packet.getX(),packet.getY(),packet.getZ()),packet.getYaw(),packet.getPitch(),
            flags.has(RelativeFlag.X),flags.has(RelativeFlag.Y),flags.has(RelativeFlag.Z),flags.has(RelativeFlag.YAW),flags.has(RelativeFlag.PITCH)));
      }else if(event.getPacketType()==PacketType.Play.Server.SPAWN_ENTITY){
        var packet=new WrapperPlayServerSpawnEntity(event);
        recordEntitySpawn(capture,packet.getEntityId(),vector(packet.getPosition().x,packet.getPosition().y,packet.getPosition().z));
      }else if(event.getPacketType()==PacketType.Play.Server.SPAWN_LIVING_ENTITY){
        var packet=new WrapperPlayServerSpawnLivingEntity(event);
        recordEntitySpawn(capture,packet.getEntityId(),vector(packet.getPosition().x,packet.getPosition().y,packet.getPosition().z));
      }else if(event.getPacketType()==PacketType.Play.Server.SPAWN_PLAYER){
        var packet=new WrapperPlayServerSpawnPlayer(event);
        recordEntitySpawn(capture,packet.getEntityId(),vector(packet.getPosition().x,packet.getPosition().y,packet.getPosition().z));
      }else if(event.getPacketType()==PacketType.Play.Server.SPAWN_PAINTING){
        var packet=new WrapperPlayServerSpawnPainting(event);
        var pos=packet.getPosition();
        recordEntitySpawn(capture,packet.getEntityId(),new Vec3(pos.getX(),pos.getY(),pos.getZ()));
      }else if(event.getPacketType()==PacketType.Play.Server.SPAWN_EXPERIENCE_ORB){
        var packet=new WrapperPlayServerSpawnExperienceOrb(event);
        recordEntitySpawn(capture,packet.getEntityId(),new Vec3(packet.getX(),packet.getY(),packet.getZ()));
      }else if(event.getPacketType()==PacketType.Play.Server.ENTITY_RELATIVE_MOVE){
        var packet=new WrapperPlayServerEntityRelativeMove(event);
        recordEntityRelativeMove(capture,packet.getEntityId(),packet.getDeltaX(),packet.getDeltaY(),packet.getDeltaZ());
      }else if(event.getPacketType()==PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION){
        var packet=new WrapperPlayServerEntityRelativeMoveAndRotation(event);
        recordEntityRelativeMove(capture,packet.getEntityId(),packet.getDeltaX(),packet.getDeltaY(),packet.getDeltaZ());
      }else if(event.getPacketType()==PacketType.Play.Server.ENTITY_TELEPORT){
        var packet=new WrapperPlayServerEntityTeleport(event);
        var pos=packet.getPosition();
        recordEntityTeleport(capture,packet.getEntityId(),new Vec3(pos.getX(),pos.getY(),pos.getZ()));
      }else if(event.getPacketType()==PacketType.Play.Server.ENTITY_METADATA){
        var packet=new WrapperPlayServerEntityMetadata(event);
        refreshEntityShape(capture,packet.getEntityId());
      }else if(event.getPacketType()==PacketType.Play.Server.DESTROY_ENTITIES){
        var packet=new WrapperPlayServerDestroyEntities(event);
        for(int entityId:packet.getEntityIds()) recordEntityDespawn(capture,entityId);
      }else if(event.getPacketType()==PacketType.Play.Server.ATTACH_ENTITY
          ||event.getPacketType()==PacketType.Play.Server.SET_PASSENGERS){
        // Riding/passenger transforms can change the effective collision state without an
        // ordinary movement packet. We do not invent a transform; invalidate entity completeness.
        capture.clientWorld.markEntityTrackingIncomplete();
      }else if(event.getPacketType()==PacketType.Play.Server.ENTITY_VELOCITY){
        var packet=new WrapperPlayServerEntityVelocity(event);
        if(packet.getEntityId()==event.getUser().getEntityId()){
          var velocity=packet.getVelocity();
          record(capture,new Packets.Velocity(vector(velocity.getX(),velocity.getY(),velocity.getZ())));
        }
      }else if(event.getPacketType()==PacketType.Play.Server.BLOCK_CHANGE){
        var packet=new WrapperPlayServerBlockChange(event);
        var blockPosition=packet.getBlockPosition();
        var pos=new dev.phantom.ac.world.Pos(blockPosition.getX(),blockPosition.getY(),blockPosition.getZ());
        var state=toCoreState(packet.getBlockState());
        capture.clientBreakPredictions.remove(pos);
        long sequence=capture.sequence.incrementAndGet();
        long receivedNanos=System.nanoTime();
        capture.clientWorld.queue(new dev.phantom.ac.Phase4WorldReplica.BlockChange(
              new dev.phantom.ac.Phase4WorldReplica.Order(authoritativeTick(capture)==null?0:authoritativeTick(capture),receivedNanos,sequence,sequence),
              new dev.phantom.ac.Phase4WorldReplica.Provenance("paper-block-change","BLOCK_CHANGE",sequence,authoritativeTick(capture)==null?0:authoritativeTick(capture),null,false,"clientbound"),pos,state));
        warmStateAsync(capture,state);
        Packets.Packet blockChange=blockStatePacket(pos,state);
        appendPacket(capture,new RawPacket(sequence,receivedNanos,blockChange,
            Packets.CaptureProvenance.fromAdapter("paper-block-change",blockChange,null)));
      }else if(event.getPacketType()==PacketType.Play.Server.MULTI_BLOCK_CHANGE){
        var packet=new WrapperPlayServerMultiBlockChange(event);
        for(var change:packet.getBlocks()){
          var pos=new dev.phantom.ac.world.Pos(change.getX(),change.getY(),change.getZ());
          var state=toCoreState(change.getBlockState(event.getUser().getClientVersion()));
          capture.clientBreakPredictions.remove(pos);
          long sequence=capture.sequence.incrementAndGet();
          long receivedNanos=System.nanoTime();
          capture.clientWorld.queue(new dev.phantom.ac.Phase4WorldReplica.BlockChange(
              new dev.phantom.ac.Phase4WorldReplica.Order(authoritativeTick(capture)==null?0:authoritativeTick(capture),receivedNanos,sequence,sequence),
              new dev.phantom.ac.Phase4WorldReplica.Provenance("paper-multi-block-change","MULTI_BLOCK_CHANGE",sequence,authoritativeTick(capture)==null?0:authoritativeTick(capture),null,false,"clientbound"),pos,state));
          warmStateAsync(capture,state);
          Packets.Packet blockChange=blockStatePacket(pos,state);
          appendPacket(capture,new RawPacket(sequence,receivedNanos,blockChange,
              Packets.CaptureProvenance.fromAdapter("paper-multi-block-change",blockChange,null)));
        }
      }else if(event.getPacketType()==PacketType.Play.Server.UNLOAD_CHUNK){
        var packet=new WrapperPlayServerUnloadChunk(event);
        var chunk=new World.Chunk(packet.getChunkX(),packet.getChunkZ());
        long sequence=capture.sequence.incrementAndGet();
        long receivedNanos=System.nanoTime();
        capture.clientWorld.queue(new dev.phantom.ac.Phase4WorldReplica.ChunkUnload(
            new dev.phantom.ac.Phase4WorldReplica.Order(authoritativeTick(capture)==null?0:authoritativeTick(capture),receivedNanos,sequence,sequence),
            new dev.phantom.ac.Phase4WorldReplica.Provenance("paper-client-chunk-unload","UNLOAD_CHUNK",sequence,authoritativeTick(capture)==null?0:authoritativeTick(capture),null,false,"clientbound"),
            new dev.phantom.ac.world.Chunk(packet.getChunkX(),packet.getChunkZ())));
        Packets.ChunkUnload unload=new Packets.ChunkUnload(chunk);
        appendPacket(capture,new RawPacket(sequence,receivedNanos,unload,
            Packets.CaptureProvenance.fromAdapter("paper-client-chunk-unload",unload,null)));
      }else if(event.getPacketType()==PacketType.Play.Server.CHUNK_DATA){
        var packet=new WrapperPlayServerChunkData(event);
        Column column=packet.getColumn();
        long sequence=capture.sequence.incrementAndGet();
        long receivedNanos=System.nanoTime();
        ClientVersion clientVersion=event.getUser().getClientVersion();
        capture.chunkPackets.incrementAndGet();
        long tick=authoritativeTick(capture)==null?0:authoritativeTick(capture);
        capture.chunkQueue.add(new PendingChunk(
            sequence,receivedNanos,tick,column,clientVersion,capture.minY,capture.maxY));
        if (debugLevel(capture.playerId).trace()) {
          getLogger().info("[PhantomAC][CHUNK] player=" + capture.playerName
              + " chunk=" + column.getX() + "," + column.getZ()
              + " cached=true fullChunk=" + column.isFullChunk()
              + " seq=" + sequence);
        }
      }
    }
  };

  @Override public void onEnable(){
    saveDefaultConfig();
    alertsEnabled=getConfig().getBoolean("alerts.enabled",true);
    broadcastAlerts=getConfig().getBoolean("alerts.broadcast",false);
    printAlertsToConsole=getConfig().getBoolean("alerts.print-to-console",true);
    alertPermission=getConfig().getString("alerts.permission","phantom.alerts");
    alertIntervalTicks=Math.max(0,getConfig().getInt("alerts.alert-interval-ticks",2));
    violationIncrement=Math.max(0.000001,getConfig().getDouble("alerts.violation-increment",1.0));
    violationDecayPerTick=Math.max(0.0,getConfig().getDouble("alerts.violation-decay-per-tick",0.005));
    maximumViolationLevel=Math.max(1.0,getConfig().getDouble("alerts.maximum-violation-level",100.0));
    setbacksEnabled=getConfig().getBoolean("setbacks.enabled",false);
    setbacksOnlyExhaustive=getConfig().getBoolean("setbacks.only-when-exhaustive",true);
    kickEnabled=getConfig().getBoolean("enforcement.kick-enabled",false);
    punishmentEnabled=getConfig().getBoolean("enforcement.punishment-enabled",false);
    enforcementOnlyExhaustive=getConfig().getBoolean("enforcement.only-when-exhaustive",true);
    permissionExempt=getConfig().getBoolean("enforcement.permission-exempt",true);
    minimumImpossibleObservations=Math.max(1,getConfig().getInt("enforcement.minimum-impossible-observations",2));
    minimumEnforcementConfidence=Math.max(0.0,Math.min(1.0,getConfig().getDouble("enforcement.minimum-confidence",1.0)));
    punishmentCommand=getConfig().getString("enforcement.punishment-command","warn {player} Phantom movement evidence");
    exemptionPermission=getConfig().getString("enforcement.permission","phantom.exempt");
    validationBudget=Math.max(1,getConfig().getInt("validation.candidate-budget",4096));
    getServer().getPluginManager().registerEvents(this,this);
    PacketEvents.getAPI().getEventManager().registerListener(listener);
    int processors=Runtime.getRuntime().availableProcessors();
    chunkDecoderThreads=Math.max(1,Math.min(4,Math.max(1,processors/2)));
    chunkExecutor=Executors.newFixedThreadPool(chunkDecoderThreads,r->{
      Thread thread=new Thread(r,"Phantom-ClientChunkDecoder");
      thread.setDaemon(true);
      return thread;
    });
    int worldPublisherThreads=Math.max(1,Math.min(2,Math.max(1,processors/4)));
    worldPublishExecutor=Executors.newFixedThreadPool(worldPublisherThreads,r->{
      Thread thread=new Thread(r,"Phantom-ClientWorldPublisher");
      thread.setDaemon(true);
      return thread;
    });
    int validationThreads=Math.max(1,Math.min(4,Math.max(1,processors/2)));
    validationExecutor=Executors.newFixedThreadPool(validationThreads,r->{
      Thread thread=new Thread(r,"Phantom-Phase8Validator");
      thread.setDaemon(true);
      return thread;
    });
    stateTask=getServer().getScheduler().runTaskTimer(this,()->{drainChunkQueues();captureLiveContext();},1L,1L);
    getLogger().info("[PhantomAC] Hardened Phase 8 adapter enabled; movement validation runs on per-connection Netty EventLoops");
  }

  @Override public void onDisable(){
    if(stateTask!=null)stateTask.cancel();
    if(chunkExecutor!=null){
      chunkExecutor.shutdownNow();
      try{chunkExecutor.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    if(validationExecutor!=null){
      validationExecutor.shutdownNow();
      try{validationExecutor.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    PacketEvents.getAPI().getEventManager().unregisterListener(listener);
    captures.clear();
    debugPlayers.clear();
    setbackOverrides.clear();
  }

  @EventHandler public void onJoin(PlayerJoinEvent event){
    Player player=event.getPlayer();
    captures.compute(player.getUniqueId(),(id,existing)->{
      if(existing!=null){
        existing.updateServerPosition(player);
        return existing;
      }
      Capture capture=new Capture(id,System.nanoTime(),validationBudget);
      capture.updateServerPosition(player);
      return capture;
    });
  }

  @EventHandler public void onTeleport(PlayerTeleportEvent event){
    Player player=event.getPlayer();
    Capture capture=captures.get(player.getUniqueId());
    if(capture==null || event.getTo()==null)return;

    /*
     * PlayerTeleportEvent supplies a server-authoritative destination. Re-anchor
     * the prediction epoch from that destination after the event rather than
     * copying the client's next reported position into the baseline.
     */
    getServer().getScheduler().runTask(this,()->{
      if(!player.isOnline() || !captures.containsKey(player.getUniqueId()))return;
      State.Player template=capture.playerState.initialState();
      if(template==null)return;
      org.bukkit.Location location=player.getLocation();
      org.bukkit.util.Vector velocity=player.getVelocity();
      State.Player authoritativeAnchor=new State.Player(
          new Vec3(location.getX(),location.getY(),location.getZ()),
          new Vec3(velocity.getX(),velocity.getY(),velocity.getZ()),
          location.getYaw(),location.getPitch(),player.isOnGround(),
          player.getGameMode().name().toLowerCase(Locale.ROOT),
          template.effects(),OptionalInt.empty(),false,Optional.empty(),
          template.attributes(),template.pose(),template.environment(),
          State.TickRange.unknown(),State.Provenance.UNKNOWN,Set.of());
      long receivedNanos=System.nanoTime();
      capture.playerState.beginResync(authoritativeAnchor,receivedNanos);
      if (debugLevel(player.getUniqueId()).trace()) {
        getLogger().info("[PhantomAC][PHASE8][REANCHOR] player="+player.getName()
            +" reason=PLAYER_TELEPORT_EVENT"
            +" sequenceBoundary="+capture.sequence.get()
            +" position="+authoritativeAnchor.position());
      }
    });
  }

  @EventHandler public void onRespawn(PlayerRespawnEvent event){
    Player player=event.getPlayer();
    getServer().getScheduler().runTask(this,()->{
      Capture capture=new Capture(player.getUniqueId(),System.nanoTime(),validationBudget);
      captures.put(player.getUniqueId(),capture);
      State.Player anchor=State.Player.initial(
          new Vec3(player.getLocation().getX(),player.getLocation().getY(),player.getLocation().getZ()));
      capture.playerState.activate(anchor,System.nanoTime());
    });
  }

  @EventHandler public void onWorldChange(PlayerChangedWorldEvent event){
    Capture capture=new Capture(event.getPlayer().getUniqueId(),System.nanoTime(),validationBudget);
    capture.updateServerPosition(event.getPlayer());
    captures.put(event.getPlayer().getUniqueId(),capture);
    setbackOverrides.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler public void onQuit(PlayerQuitEvent event){
    Capture existing=captures.get(event.getPlayer().getUniqueId());
    if(existing!=null) existing.playerState.disconnect();
    captures.remove(event.getPlayer().getUniqueId());
    debugPlayers.remove(event.getPlayer().getUniqueId());
    setbackOverrides.remove(event.getPlayer().getUniqueId());
  }

  /**
   * Paper has already performed its authoritative movement validation when this
   * event fires. Keep the rejection as corroboration/telemetry only; Phantom
   * must not manufacture an IMPOSSIBLE verdict from Paper's own rejection.
   */
  @EventHandler public void onPlayerToggleFlight(org.bukkit.event.player.PlayerToggleFlightEvent event){
    Player player=event.getPlayer();
    Capture capture=captures.computeIfAbsent(player.getUniqueId(),
        ignored->new Capture(player.getUniqueId(),System.nanoTime(),validationBudget));
    boolean attemptedFlying=event.isFlying();
    Long authoritativeTick=capture.authoritativeServerTick.get()>=0
        ?capture.authoritativeServerTick.get():null;
    Packets.FlightToggle toggle=new Packets.FlightToggle(attemptedFlying,event.isCancelled());
    long sequence=capture.sequence.incrementAndGet();
    long receivedNanos=System.nanoTime();
    if (debugLevel(capture.playerId).summary()) {
      getLogger().info("[PhantomAC][PHASE8][FLIGHT_TOGGLE] player="+player.getName()
          +" attemptedFlying="+attemptedFlying
          +" cancelled="+event.isCancelled()
          +" canFly="+player.getAllowFlight()
          +" flyingNow="+player.isFlying()
          +" gamemode="+player.getGameMode()
          +" authorityTick="+authoritativeTick);
    }
    appendPacket(capture,new RawPacket(sequence,receivedNanos,toggle,
        Packets.CaptureProvenance.fromAdapter("paper-flight-toggle",toggle,authoritativeTick)));
  }

  @EventHandler public void onPlayerFailMove(PlayerFailMoveEvent event){
    Player player=event.getPlayer();
    if (debugLevel(player.getUniqueId()).trace()) {
      getLogger().info("[PhantomAC][PHASE8][PAPER_MOVE_EVENT] player="+player.getName()
          +" reason="+event.getFailReason()
          +" allowed="+event.isAllowed()
          +" from="+event.getFrom()
          +" to="+event.getTo()
          +" logWarning="+event.getLogWarning());
    }
    if(event.isAllowed())return;
    PlayerFailMoveEvent.FailReason reason=event.getFailReason();
    if(reason!=PlayerFailMoveEvent.FailReason.MOVED_TOO_QUICKLY
        &&reason!=PlayerFailMoveEvent.FailReason.MOVED_WRONGLY)return;

    Capture capture=captures.computeIfAbsent(player.getUniqueId(),
        ignored->new Capture(player.getUniqueId(),System.nanoTime(),validationBudget));
    long now=System.nanoTime();
    if(capture.paperMoveFailureWindowStartNanos<0L
        ||now-capture.paperMoveFailureWindowStartNanos>PAPER_MOVE_FAILURE_WINDOW_NANOS){
      capture.paperMoveFailureWindowStartNanos=now;
      capture.paperMoveFailureCount=1;
    }else{
      capture.paperMoveFailureCount++;
    }

    Packets.PaperMovementRejection corroboration =
        new Packets.PaperMovementRejection(reason.name(),event.isAllowed());
    long telemetrySequence=capture.sequence.incrementAndGet();
    appendPacket(capture,new RawPacket(
        telemetrySequence,now,corroboration,
        Packets.CaptureProvenance.fromAdapter("paper-move-rejection",corroboration,authoritativeTick(capture))));

    if (debugLevel(capture.playerId).summary()) {
      getLogger().info("[PhantomAC][PHASE8][PAPER_MOVE_FAIL] player="+player.getName()
          +" reason="+reason
          +" allowed="+event.isAllowed()
          +" failuresInWindow="+capture.paperMoveFailureCount
          +" from="+event.getFrom()
          +" to="+event.getTo()
          +" logWarning="+event.getLogWarning());
    }

    if(capture.paperMoveFailureCount>=PAPER_MOVE_FAILURE_THRESHOLD
        &&debugLevel(capture.playerId).summary()){
      getLogger().warning("[PhantomAC][PHASE8][PAPER_CORROBORATION_ONLY] player="+player.getName()
          +" reason="+reason
          +" rejectedAttemptsIn1s="+capture.paperMoveFailureCount
          +" from="+event.getFrom()
          +" to="+event.getTo()
          +" NOTE=Paper rejection is telemetry only; Phantom will not emit an IMPOSSIBLE result from this event");
    }
  }

  private static Long authoritativeTick(Capture capture){
    long tick=capture.authoritativeServerTick.get();
    return tick>=0 ? tick : null;
  }

  private State.Player paperMovementState(State.Player template,org.bukkit.Location location,Player player){
    org.bukkit.util.Vector velocity=player.getVelocity();
    return new State.Player(
        vector(location.getX(),location.getY(),location.getZ()),
        vector(velocity.getX(),velocity.getY(),velocity.getZ()),
        location.getYaw(),location.getPitch(),player.isOnGround(),
        template.gamemode(),template.effects(),java.util.OptionalInt.empty(),false,
        template.input(),template.attributes(),template.pose(),template.environment(),
        State.TickRange.unknown(),State.Provenance.UNKNOWN,Set.of());
  }

  private void applyAuthoritativeEventResult(Capture capture,Phase8MovementValidation.Result result){
    Phase8MovementValidation.Evidence evidence=result.evidence();
    boolean accepted=capture.validationGate.accept(evidence.replayReference(),result.verdict());
    if (debugLevel(capture.playerId).summary()) {
      getLogger().info("[PhantomAC][PHASE8][AUTHORITATIVE_RESULT] player="+capture.playerId
          +" rule="+evidence.rule()
          +" verdict="+result.verdict()
          +" acceptedByGate="+accepted
          +" tick="+evidence.serverTick()
          +" replay="+evidence.replayReference());
    }
    if(!accepted)return;
    var accumulated=capture.accumulator.accept(evidence,
        new Phase8MovementValidation.Config(minimumImpossibleObservations,alertIntervalTicks,alertsEnabled,true,
            violationIncrement,violationDecayPerTick,maximumViolationLevel,
            Math.max(0.000001, getConfig().getDouble("alerts.violation-alert-interval", minimumImpossibleObservations))));
    capture.accumulator=accumulated.state();
    capture.processedResults++;
    accumulated.alert().ifPresent(alert->{
      if(printAlertsToConsole) getLogger().warning(alert.debugMessage());
      if(broadcastAlerts){
        getServer().broadcastMessage(alert.serverMessage(capture.playerName));
      }else{
        String message=alert.serverMessage(capture.playerName);
        for(Player staff:getServer().getOnlinePlayers()){
          if(staff.hasPermission(alertPermission)) staff.sendMessage(message);
        }
      }
    });
  }

  @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
    if(!command.getName().equalsIgnoreCase("phantom"))return false;
    if(args.length==0||args[0].equalsIgnoreCase("status")){
      sender.sendMessage("PhantomAC captures="+captures.size()
          +" visibleChunks="+captures.values().stream().mapToInt(c->c.clientWorld.visibleChunkCount()).sum()
          +" pendingBarriers="+captures.values().stream().mapToInt(c->c.clientWorld.pendingBarrierCount()).sum()
          +" setbacksDefault="+setbacksEnabled);
      return true;
    }

    if(args[0].equalsIgnoreCase("debug")&&args.length>=2){
      Player target=getServer().getPlayerExact(args[1]);
      if(target==null){sender.sendMessage("Player not found: "+args[1]);return true;}
      String mode=args.length>=3?args[2].toLowerCase(Locale.ROOT):"summary";
      switch(mode){
        case "off" -> {
          debugPlayers.remove(target.getUniqueId());
          sender.sendMessage("Phase 8 debug disabled for "+target.getName());
        }
        case "summary" -> {
          debugPlayers.put(target.getUniqueId(),DebugLevel.SUMMARY);
          sender.sendMessage("Phase 8 summary debug enabled for "+target.getName()
              +" (important verdicts immediately, otherwise at most once per second).");
        }
        case "focus" -> {
          debugPlayers.put(target.getUniqueId(),DebugLevel.FOCUS);
          sender.sendMessage("Phase 8 focused debug enabled for "+target.getName()
              +" (movement/input/timing decisions only).");
        }
        case "trace" -> {
          debugPlayers.put(target.getUniqueId(),DebugLevel.TRACE);
          sender.sendMessage("Phase 8 trace debug enabled for "+target.getName()+" (full packet/frame detail).");
        }
        case "dump" -> {
          Capture capture=captures.get(target.getUniqueId());
          logFocusedDebug(target.getName(),capture==null?null:capture.lastDebugReport);
          sender.sendMessage("Phase 8 focused debug dumped to console for "+target.getName());
        }
        case "status" -> sender.sendMessage("Phase 8 debug for "+target.getName()+": "
            +debugPlayers.getOrDefault(target.getUniqueId(),DebugLevel.OFF));
        default -> sender.sendMessage("Usage: /phantom debug <player> [summary|focus|trace|dump|off|status]");
      }
      return true;
    }

    if(args[0].equalsIgnoreCase("setback")&&args.length>=2){
      Player target=getServer().getPlayerExact(args[1]);
      if(target==null){sender.sendMessage("Player not found: "+args[1]);return true;}
      if(args.length<3||args[2].equalsIgnoreCase("on")){
        setbackOverrides.put(target.getUniqueId(),Boolean.TRUE);
        sender.sendMessage("Strict 1:1 setback enabled for "+target.getName());
      }else if(args[2].equalsIgnoreCase("off")){
        setbackOverrides.put(target.getUniqueId(),Boolean.FALSE);
        sender.sendMessage("Strict 1:1 setback disabled for "+target.getName());
      }else{
        sender.sendMessage("Usage: /phantom setback <player> [on|off]");
      }
      return true;
    }

    sender.sendMessage("Usage: /phantom status | /phantom debug <player> [summary|focus|trace|dump|off|status] | /phantom setback <player> [on|off]");
    return true;
  }

  private static Channel asNettyChannel(Object channel){
    return channel instanceof Channel nettyChannel ? nettyChannel : null;
  }

  private void requestStateBarrier(Player player,Capture capture){
    if(capture.pendingChunkDecodes.get()>0)return;
    long now=System.nanoTime();
    long last=capture.lastWorldBarrierNanos.get();
    if(last!=Long.MIN_VALUE&&now-last<WORLD_TRANSACTION_MIN_INTERVAL_NANOS)return;
    sendStateBarrier(player,capture);
  }

  private void sendStateBarrier(Player player,Capture capture){
    Packets.PlayerContext context=capture.playerState.pendingAuthoritativeContext();
    if(context==null)return;

    short transactionId=capture.nextWorldTransaction();
    long sequenceBoundary=capture.sequence.get();
    capture.clientWorld.openBarrier(transactionId,sequenceBoundary);

    Packets.PlayerContext barrierContext=context.withTransactionBarrier(transactionId);
    capture.playerState.markBarrierSent(transactionId,barrierContext);
    capture.outstandingTransactions.add(transactionId);

    try{
      PacketEvents.getAPI().getPlayerManager().sendPacket(
          player,new WrapperPlayServerPing((int)transactionId));
      capture.lastWorldBarrierNanos.set(System.nanoTime());

      Packets.WorldTransactionSend tx=new Packets.WorldTransactionSend(transactionId);
      long txSequence=capture.sequence.incrementAndGet();
      long txNanos=System.nanoTime();
      appendPacket(capture,new RawPacket(
          txSequence,txNanos,tx,
          Packets.CaptureProvenance.fromAdapter(
              "paper-transaction",tx,authoritativeTick(capture))));

      long contextSequence=capture.sequence.incrementAndGet();
      long contextNanos=System.nanoTime();
      appendPacket(capture,new RawPacket(
          contextSequence,
          contextNanos,
          barrierContext,
          Packets.CaptureProvenance.fromAdapter(
              "paper-live-transaction",
              barrierContext,
              authoritativeTick(capture),
              capture.clientTickTracker.hasObservedBoundary()
                  ?capture.clientTickTracker.clientTickForMovement()
                  :null)));
      capture.playerState.markContextPublished();
    }catch(RuntimeException failure){
      capture.outstandingTransactions.remove(transactionId);
      capture.reservedTransactions.remove(transactionId);
      capture.playerState.abortBarrier(transactionId);
      capture.clientWorld.abortBarrier(transactionId);
      getLogger().log(
          java.util.logging.Level.FINE,
          "[PhantomAC][WORLD] transaction send failed for "+capture.playerId,
          failure);
    }
  }

  private static boolean isNearChunk(Capture capture,Column column){
    double chunkCenterX=column.getX()*16.0+8.0;
    double chunkCenterZ=column.getZ()*16.0+8.0;
    return Math.abs(capture.lastServerX-chunkCenterX)<16.0&&Math.abs(capture.lastServerZ-chunkCenterZ)<16.0;
  }

  private static boolean isNearChunk(Capture capture,int chunkX,int chunkZ){
    double chunkCenterX=chunkX*16.0+8.0;
    double chunkCenterZ=chunkZ*16.0+8.0;
    return Math.abs(capture.lastServerX-chunkCenterX)<16.0&&Math.abs(capture.lastServerZ-chunkCenterZ)<16.0;
  }

  private static boolean isNearBlock(Capture capture,dev.phantom.ac.world.Pos position){
    return Math.abs(capture.lastServerX-position.x())<16.0
        &&Math.abs(capture.lastServerY-position.y())<16.0
        &&Math.abs(capture.lastServerZ-position.z())<16.0;
  }

  /**
   * Observe the currently ridden entity as a compact equivalent of Grim's
   * vehicle packet-entity state. Unknown vehicle details are left conservative.
   */
  private static Phase5Mechanics.VehicleState observeVehicleState(Player player){
    Entity vehicle=player.getVehicle();
    if(vehicle==null)return Phase5Mechanics.VehicleState.NONE;

    String typeName=vehicle.getType().name().toLowerCase(Locale.ROOT);
    Phase5Mechanics.VehicleType type;
    if(typeName.contains("chest_boat")) type=Phase5Mechanics.VehicleType.CHEST_BOAT;
    else if(typeName.equals("boat")) type=Phase5Mechanics.VehicleType.BOAT;
    else if(typeName.contains("minecart")) type=Phase5Mechanics.VehicleType.MINECART;
    else if(typeName.equals("pig")) type=Phase5Mechanics.VehicleType.PIG;
    else if(typeName.equals("strider")) type=Phase5Mechanics.VehicleType.STRIDER;
    else if(typeName.equals("camel")) type=Phase5Mechanics.VehicleType.CAMEL;
    else if(typeName.equals("horse")||typeName.equals("donkey")||typeName.equals("mule"))
      type=Phase5Mechanics.VehicleType.HORSE;
    else if(typeName.contains("nautilus")) type=Phase5Mechanics.VehicleType.NAUTILUS;
    else if(typeName.contains("happy_ghast")) type=Phase5Mechanics.VehicleType.HAPPY_GHAST;
    else type=Phase5Mechanics.VehicleType.OTHER;

    boolean controlling=!vehicle.getPassengers().isEmpty()
        &&vehicle.getPassengers().getFirst().getEntityId()==player.getEntityId();
    org.bukkit.util.Vector velocity=vehicle.getVelocity();
    org.bukkit.Location location=vehicle.getLocation();

    double movementSpeed=0.1;
    if(vehicle instanceof org.bukkit.attribute.Attributable attributable){
      AttributeInstance speed=attributable.getAttribute(Attribute.MOVEMENT_SPEED);
      if(speed!=null)movementSpeed=speed.getValue();
      if(type==Phase5Mechanics.VehicleType.HAPPY_GHAST){
        try{
          Attribute flying=Attribute.valueOf("FLYING_SPEED");
          AttributeInstance flyingSpeed=attributable.getAttribute(flying);
          if(flyingSpeed!=null)movementSpeed=flyingSpeed.getValue();
        }catch(IllegalArgumentException ignored){}
      }
    }

    boolean cold=false;
    if(type==Phase5Mechanics.VehicleType.STRIDER){
      org.bukkit.block.Block below=vehicle.getWorld().getBlockAt(
          location.getBlockX(),location.getBlockY()-1,location.getBlockZ());
      String material=below.getType().name();
      cold=!(material.equals("LAVA")||material.contains("MAGMA")||material.contains("FIRE"));
    }

    boolean dashReady=type==Phase5Mechanics.VehicleType.CAMEL
        &&invokeBoolean(vehicle,"isDashing")
        &&invokeInt(vehicle,"getDashCooldown",-1)<=0;

    return new Phase5Mechanics.VehicleState(
        type,
        controlling,
        new Phase5Mechanics.Vec3Like(velocity.getX(),velocity.getY(),velocity.getZ()),
        location.getYaw(),
        location.getPitch(),
        vehicle.isOnGround(),
        movementSpeed,
        cold,
        dashReady);
  }

  private static boolean invokeBoolean(Entity entity,String method){
    try{
      Object value=entity.getClass().getMethod(method).invoke(entity);
      return value instanceof Boolean b && b;
    }catch(ReflectiveOperationException|SecurityException ignored){
      return false;
    }
  }

  private static int invokeInt(Entity entity,String method,int fallback){
    try{
      Object value=entity.getClass().getMethod(method).invoke(entity);
      return value instanceof Number n?n.intValue():fallback;
    }catch(ReflectiveOperationException|SecurityException ignored){
      return fallback;
    }
  }

  private void captureLiveContext(){
    for(Capture capture:captures.values()){
      Player player=getServer().getPlayer(capture.playerId);
      if(player==null)continue;
      long authoritativeTick=capture.authoritativeServerTick.incrementAndGet();
      Long authoritativeClientTick=capture.clientTickTracker.hasObservedBoundary()
          ?capture.clientTickTracker.clientTickForMovement():null;
      capture.updateServerPosition(player);
      capture.lastAuthoritativePosition=new Vec3(player.getLocation().getX(),player.getLocation().getY(),player.getLocation().getZ());
      org.bukkit.util.Vector authoritativeVelocity=player.getVelocity();
      capture.lastAuthoritativeVelocity=new Vec3(authoritativeVelocity.getX(),authoritativeVelocity.getY(),authoritativeVelocity.getZ());
      capture.lastAuthoritativeOnGround=player.isOnGround();
      capture.lastAuthoritativeCanFly=player.getAllowFlight();
      capture.lastAuthoritativeFlying=player.isFlying();
      capture.minY=player.getWorld().getMinHeight();
      capture.maxY=player.getWorld().getMaxHeight();

      AttributeInstance movement=player.getAttribute(Attribute.MOVEMENT_SPEED);
      double rawMovementSpeed=movement==null?0.1:movement.getValue();
      boolean sprint=player.isSprinting(),sneak=player.isSneaking();
      /*
       * Paper's effective attribute value already includes vanilla's sprint
       * movement-speed modifier while the player is sprinting. Phantom's
       * canonical physics applies sprinting as a separate 1.3 multiplier, so
       * feeding the sprint-adjusted Bukkit value into that model double-counts
       * sprint and produces a systematic horizontal overshoot.
       *
       * Normalize back to the pre-sprint effective value while preserving other
       * attribute modifiers. Sprint remains a separate physical movement state.
       */
      double movementSpeed = sprint
          ? rawMovementSpeed / dev.phantom.ac.Vanilla12111RichPhysics.SPRINTING_SPEED_MULTIPLIER
          : rawMovementSpeed;
      Map<String,Integer> effects=new LinkedHashMap<>();
      for(PotionEffect effect:player.getActivePotionEffects())
        if(effect.getType().getKey()!=null)
          effects.put(effect.getType().getKey().toString(),effect.getAmplifier());

      Phase5Mechanics.Pose pose=
          player.isSleeping()?Phase5Mechanics.Pose.SLEEPING:
          player.isGliding()?Phase5Mechanics.Pose.FALL_FLYING:
          player.isSwimming()?Phase5Mechanics.Pose.SWIMMING:
          player.isSneaking()?Phase5Mechanics.Pose.CROUCHING:
          Phase5Mechanics.Pose.STANDING;

      boolean water=false,lava=false,climb=false;
      org.bukkit.util.BoundingBox box=player.getBoundingBox();
      int minX=(int)Math.floor(box.getMinX()),maxX=(int)Math.floor(Math.nextDown(box.getMaxX()));
      int minY=(int)Math.floor(box.getMinY()),maxY=(int)Math.floor(Math.nextDown(box.getMaxY()));
      int minZ=(int)Math.floor(box.getMinZ()),maxZ=(int)Math.floor(Math.nextDown(box.getMaxZ()));

      for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
        Material material=player.getWorld().getBlockAt(x,y,z).getType();
        if(material==Material.WATER||material==Material.BUBBLE_COLUMN)water=true;
        if(material==Material.LAVA)lava=true;
        if(material==Material.LADDER||material==Material.VINE||material==Material.SCAFFOLDING)climb=true;
      }

      if (debugLevel(capture.playerId).trace() && Math.abs(rawMovementSpeed - movementSpeed) > 1.0E-9) {
        getLogger().info("[PhantomAC][AUTHORITY_SPEED] player="+capture.playerName
            +" rawEffective="+rawMovementSpeed
            +" normalizedPreSprint="+movementSpeed
            +" sprint="+sprint
            +" multiplier="+dev.phantom.ac.Vanilla12111RichPhysics.SPRINTING_SPEED_MULTIPLIER);
      }

      Phase5Mechanics.MovementEnvironment baseEnvironment=
          water?Phase5Mechanics.MovementEnvironment.vanillaWater(player.isOnGround(),sprint,sneak,player.isSwimming()):
          lava?Phase5Mechanics.MovementEnvironment.vanillaLava(player.isOnGround(),sprint,sneak):
          climb?Phase5Mechanics.MovementEnvironment.vanillaClimbable(player.isOnGround(),sprint,sneak):
          Phase5Mechanics.MovementEnvironment.dry(player.isOnGround(),sprint,sneak);
      Phase5Mechanics.VehicleState vehicleState=observeVehicleState(player);
      Phase5Mechanics.MovementEnvironment env=new Phase5Mechanics.MovementEnvironment(
          baseEnvironment.fluid(),baseEnvironment.submerged(),baseEnvironment.climbable(),
          baseEnvironment.onGround(),baseEnvironment.sprinting(),baseEnvironment.sneaking(),
          baseEnvironment.swimmingInput(),baseEnvironment.gliding(),
          baseEnvironment.fluidSpeedMultiplier(),baseEnvironment.fluidDrag(),
          baseEnvironment.gravityMultiplier(),vehicleState);

      /*
       * Entity collision state is already captured from the clientbound packet
       * stream into the per-player ClientEntityTrack map. Do not query Bukkit's
       * live entity index here: this method runs once per tracked player every
       * server tick, and the packet-backed view is both cheaper and closer to
       * what that player can actually see.
       *
       * The 6-block expansion is retained so the candidate set has the same
       * collision volume as the previous live-world query. Entity boxes remain
       * sorted by entity id for deterministic replay/validation.
       */
      dev.phantom.ac.geometry.BlockBox relevantEntityRegion=new dev.phantom.ac.geometry.BlockBox(
          box.getMinX()-6.0,box.getMinY()-6.0,box.getMinZ()-6.0,
          box.getMaxX()+6.0,box.getMaxY()+6.0,box.getMaxZ()+6.0);
      List<EntityCollisions.EntityBox> entityBoxes=new ArrayList<>();
      for(ClientEntityTrack tracked:capture.clientEntities.values()){
        if(tracked.entityId()==player.getEntityId())continue;
        if(tracked.box().intersects(relevantEntityRegion))
          entityBoxes.add(new EntityCollisions.EntityBox(tracked.entityId(),tracked.box()));
      }
      entityBoxes.sort(Comparator.comparingInt(EntityCollisions.EntityBox::entityId));

      Packets.PlayerContext context=new Packets.PlayerContext(
          player.getGameMode().name().toLowerCase(Locale.ROOT),
          new dev.phantom.ac.Simulation.Attributes(movementSpeed),
          effects,pose,env,
          new Vec3(player.getLocation().getX(),player.getLocation().getY(),player.getLocation().getZ()),
          new Vec3(authoritativeVelocity.getX(),authoritativeVelocity.getY(),authoritativeVelocity.getZ()),
          player.getAllowFlight(),player.isFlying(),player.isSleeping(),entityBoxes,vehicleState);

      if(capture.playerState.initialState()==null){
        long anchorReceivedNanos=System.nanoTime();
        State.Environment stateEnvironment=switch(env.fluid()){
          case WATER -> State.Environment.WATER;
          case LAVA -> State.Environment.LAVA;
          case NONE -> env.climbable()?State.Environment.CLIMBABLE:State.Environment.DRY;
        };
        capture.playerState.activate(
            new State.Player(
                vector(player.getLocation().getX(),player.getLocation().getY(),player.getLocation().getZ()),
                vector(authoritativeVelocity.getX(),authoritativeVelocity.getY(),authoritativeVelocity.getZ()),player.getLocation().getYaw(),player.getLocation().getPitch(),player.isOnGround(),
                player.getGameMode().name().toLowerCase(Locale.ROOT),effects,java.util.OptionalInt.empty(),false,
                java.util.Optional.empty(),new dev.phantom.ac.Simulation.Attributes(movementSpeed),pose,stateEnvironment,
                State.TickRange.unknown(),State.Provenance.UNKNOWN,Set.of()),
            anchorReceivedNanos);
      }

      capture.playerState.observeAuthoritativeContext(context);
      requestStateBarrier(player,capture);
    }
  }

  private WorldSnapshot validationSnapshot(Capture capture,double centerX,double centerZ,
                                             double observedX,double observedZ){
    double anchorX=centerX;
    double anchorZ=centerZ;
    State.Player anchor=capture.playerState.initialState();
    if(anchor!=null&&!anchor.uncertain()){
      anchorX=anchor.position().x();
      anchorZ=anchor.position().z();
    }

    double spanX=Math.abs(observedX-centerX);
    double spanZ=Math.abs(observedZ-centerZ);
    int observedRadius=Math.max(
        LOCAL_SNAPSHOT_RADIUS_CHUNKS,
        Math.min(8,(int)Math.ceil(Math.max(spanX,spanZ)/16.0)+LOCAL_SNAPSHOT_RADIUS_CHUNKS));

    WorldSnapshot snapshot=capture.clientWorld.snapshotAround(
        centerX,centerZ,LOCAL_SNAPSHOT_RADIUS_CHUNKS);

    if(Math.abs(centerX-anchorX)>16.0*LOCAL_SNAPSHOT_RADIUS_CHUNKS
        ||Math.abs(centerZ-anchorZ)>16.0*LOCAL_SNAPSHOT_RADIUS_CHUNKS){
      snapshot=WorldSnapshot.merge(snapshot,
          capture.clientWorld.snapshotAround(anchorX,anchorZ,LOCAL_SNAPSHOT_RADIUS_CHUNKS));
    }

    // The server may deliberately refuse to move a player whose client-reported
    // position is invalid. Validate against the observed destination as well as
    // the server position, otherwise a long rejected movement can fall outside
    // the acknowledged collision window and become UNKNOWN instead of impossible.
    if(Math.abs(observedX-centerX)>1.0 || Math.abs(observedZ-centerZ)>1.0){
      snapshot=WorldSnapshot.merge(snapshot,
          capture.clientWorld.snapshotAround(observedX,observedZ,observedRadius));
    }

    return snapshot;
  }

  /**
   * The expensive prediction path runs on the dedicated validation executor.
   * Packet capture stays lightweight and all Bukkit/Paper actions remain on the
   * server's main thread.
   */
  private void schedulePredictionValidation(Capture capture){
    if(capture==null)return;
    ExecutorService executor=validationExecutor;
    if(executor==null)return;

    /*
     * Validation is CPU-heavy but stateful; never execute it
     * on the packet connection's Netty EventLoop: doing so turns anti-cheat work
     * into client-visible packet/movement latency.
     *
     * predictionValidationQueued is deliberately a coalescing gate. While one batch
     * is running, additional movement/world packets only cause a single follow-up
     * batch, rather than one expensive prediction pass per packet.
     */
    if(!capture.predictionValidationQueued.compareAndSet(false,true))return;
    try{
      executor.execute(()->{
        try{
          runPredictionValidation(capture);
        }finally{
          capture.predictionValidationQueued.set(false);
          /*
           * Packets may have arrived while replay was running. Submit exactly one
           * follow-up batch so the predictor catches up without unbounded task
           * accumulation.
           */
          if(validationExecutor!=null
              && !capture.copySince(capture.movementRunner.lastProcessedSequence()).isEmpty()){
            schedulePredictionValidation(capture);
          }
        }
      });
    }catch(RejectedExecutionException rejected){
      capture.predictionValidationQueued.set(false);
    }
  }

  private WorldSnapshot clientWorldForMovement(Capture capture,long sequence){
    WorldSnapshot world=capture.clientWorld.snapshotAtOrBeforeIncludingPending(sequence);
    if(world==null)return null;

    long movementTick=capture.movementRunner.relativeClientTick();
    Iterator<Map.Entry<dev.phantom.ac.world.Pos,Capture.ClientBreakPrediction>> iterator =
        capture.clientBreakPredictions.entrySet().iterator();
    while(iterator.hasNext()){
      Map.Entry<dev.phantom.ac.world.Pos,Capture.ClientBreakPrediction> entry=iterator.next();
      Capture.ClientBreakPrediction prediction=entry.getValue();

      boolean staleBySequence=sequence-prediction.captureSequence()>32L;
      boolean staleByTick=prediction.clientTick()!=null
          && movementTick>=0L
          && movementTick-prediction.clientTick()>2L;
      if(staleBySequence||staleByTick){
        iterator.remove();
        continue;
      }

      dev.phantom.ac.world.Pos pos=entry.getKey();
      if(world.coverageAt(pos.x(),pos.y(),pos.z())
          ==dev.phantom.ac.world.Coverage.KNOWN
          && world.blockAtOrNull(pos.x(),pos.y(),pos.z())!=null){
        world=world.withBlockOverride(pos.x(),pos.y(),pos.z(),
            BlockState.air());
      }
    }
    return world;
  }

  private void runPredictionValidation(Capture capture){
    long startedNanos=System.nanoTime();
    capture.validationRuns.incrementAndGet();
    try{
      List<RawPacket> raw=capture.copySince(capture.movementRunner.lastProcessedSequence());
      if(raw.isEmpty())return;

      String playerName=capture.playerName==null?capture.playerId.toString():capture.playerName;
      State.Player anchor=capture.playerState.initialState();
      if(debugLevel(capture.playerId).trace()){
        getLogger().info("[PhantomAC][PHASE8][PREDICT_START] player="+playerName
            +" thread="+Thread.currentThread().getName()
            +" rawPackets="+raw.size()
            +" lastProcessedSequence="+capture.movementRunner.lastProcessedSequence()
            +" candidateCount="+capture.movementRunner.candidateCount()
            +" continuation="+capture.movementRunner.continuation());
      }

      Phase8PredictionRunner.Report incremental=capture.movementRunner.processWithWorldProvider(
          playerName,
          raw,
          sequence->clientWorldForMovement(capture,sequence),
          anchor,
          capture.playerState.initialStateReceivedNanos);

      Phase8PredictionRunner.Report report=incremental;
      capture.lastDebugReport=incremental;

      DebugLevel debug=debugLevel(capture.playerId);
      if(debug.trace() || debug.focus()){
        for(Phase8PredictionRunner.PredictionFrame frame:incremental.frames()){
          Phase8MovementValidation.Result frameResult=incremental.results().stream()
              .filter(result -> result.evidence().replayReference().endsWith(":"+frame.sequence()))
              .findFirst().orElse(null);
          if(frameResult==null)continue;
          if(debug.trace()){
            getLogger().info("[PhantomAC][PHASE8] "
                +PhantomDebugFormatter.movement(playerName,frame,frameResult));
            frameResult.evidence().closestCandidate()
                .ifPresent(candidate->getLogger().info("[PhantomAC][PHASE8] "
                    +PhantomDebugFormatter.candidateSummary(candidate)));
          }else if(frameResult.verdict()!=Phase8MovementValidation.Verdict.POSSIBLE){
            getLogger().warning("[PhantomAC][PHASE8] "
                +PhantomDebugFormatter.movement(playerName,frame,frameResult));
          }
        }
      }
      if(debug.summary() || debug.trace()){
        getLogger().info("[PhantomAC][PHASE8][BATCH] player="+playerName
            +" elapsedMicros="+((System.nanoTime()-startedNanos)/1_000L)
            +" packets="+incremental.packetsProcessed()
            +" movements="+incremental.movementObservations()
            +" possible="+incremental.possible()
            +" uncertain="+incremental.uncertain()
            +" impossible="+incremental.impossible()
            +" clientTick="+incremental.relativeClientTick()
            +" candidates="+capture.movementRunner.candidateCount()
            +" continuation="+incremental.continuation()
            +" frontierRetained="+incremental.candidateFrontierRetained());
      }
      capture.lastValidationElapsedMicros=(System.nanoTime()-startedNanos)/1_000L;
      capture.lastValidationBatchPackets=raw.size();
      capture.lastValidationBatchMovements=incremental.movementObservations();
      capture.validationPackets.addAndGet(raw.size());
      capture.validationMovements.addAndGet(incremental.movementObservations());

      if(debugLevel(capture.playerId).summary())
        logValidationSummary(capture,playerName,report,incremental.frames());
      if(debugLevel(capture.playerId).focus() && !debugLevel(capture.playerId).trace()) {
        for(Phase8MovementValidation.Result result:report.results()) {
          if(result.verdict()!=Phase8MovementValidation.Verdict.POSSIBLE) {
            logFocusedDebug(playerName,report);
            break;
          }
        }
      }

      // The only hop back is the immutable validation report for Bukkit actions.
      getServer().getScheduler().runTask(this,()->applyResult(capture,report));
    }catch(RuntimeException failure){
      capture.lastValidationElapsedMicros=(System.nanoTime()-startedNanos)/1_000L;
      getLogger().log(java.util.logging.Level.WARNING,
          "[PhantomAC][PHASE8] async validation failed for "+capture.playerId,
          failure);
    }
  }

  private void applyResult(Capture capture,Phase8PredictionRunner.Report report){
    DebugLevel debugLevel=debugLevel(capture.playerId);
    if (debugLevel.trace()) {
      for(Phase8MovementValidation.Result result:report.results())
        logValidationDebug(getServer().getPlayer(capture.playerId)==null
            ?capture.playerId.toString()
            :getServer().getPlayer(capture.playerId).getName(),result);
    }

    Phase8MovementValidation.Evidence latestSetbackEvidence=null;
    long latestSetbackTick=Long.MIN_VALUE;

    Phase8MovementValidation.Config accumulatorConfig =
        new Phase8MovementValidation.Config(
            minimumImpossibleObservations, 20, alertsEnabled, true);

    Phase8EnforcementPolicy.Config enforcementConfig =
        new Phase8EnforcementPolicy.Config(
            setbackEnabled(capture.playerId),
            kickEnabled,
            punishmentEnabled,
            enforcementOnlyExhaustive,
            minimumImpossibleObservations,
            minimumEnforcementConfidence,
            punishmentCommand);

    for(Phase8MovementValidation.Result result:report.results()){
      Phase8MovementValidation.Evidence evidence=result.evidence();
      if(!capture.validationGate.accept(evidence.replayReference(),result.verdict()))continue;

      var accumulated=capture.accumulator.accept(evidence,accumulatorConfig);
      capture.accumulator=accumulated.state();

      accumulated.alert().ifPresent(alert->{
        String message=alert.debugMessage();
        getLogger().warning(message);
        if(broadcastAlerts){
          getServer().broadcastMessage(alert.serverMessage(capture.playerName));
        }else{
          for(Player recipient:getServer().getOnlinePlayers())
            if(recipient.hasPermission("phantom.admin"))
              recipient.sendMessage(alert.serverMessage(capture.playerName));
        }
      });

      String episodeKey=evidence.playerId()+"/"+evidence.rule();
      Phase8MovementValidation.State episode =
          accumulated.state().players().get(episodeKey);
      if(episode==null)continue;

      Phase8EnforcementPolicy.Decision decision =
          Phase8EnforcementPolicy.evaluate(evidence,episode,enforcementConfig);

      if (debugLevel.trace() && result.verdict()!=Phase8MovementValidation.Verdict.POSSIBLE) {
        getLogger().info("[PhantomAC][PHASE8][ENFORCEMENT] player="+capture.playerId
            +" eligible="+decision.eligible()
            +" confidence="+decision.confidence()
            +" actions="+decision.actions()
            +" reason="+decision.reason()
            +" tick="+evidence.serverTick());
      }

      Player player=getServer().getPlayer(capture.playerId);
      if(player!=null && permissionExempt && exemptionPermission!=null
          && !exemptionPermission.isBlank() && player.hasPermission(exemptionPermission)){
        continue;
      }

      if(!decision.eligible())continue;

      if(decision.actions().contains(Phase8EnforcementPolicy.Action.SETBACK)
          &&evidence.serverTick()>=latestSetbackTick){
        latestSetbackTick=evidence.serverTick();
        latestSetbackEvidence=evidence;
      }

      if(decision.actions().contains(Phase8EnforcementPolicy.Action.KICK) && player!=null){
        player.kickPlayer(
            "[PhantomAC] Movement evidence exhausted the configured legitimate state space.");
        getLogger().warning("[PhantomAC][PHASE8][KICK] player="+capture.playerId
            +" tick="+evidence.serverTick()+" replay="+evidence.replayReference());
      }

      if(decision.actions().contains(Phase8EnforcementPolicy.Action.PUNISHMENT_COMMAND)){
        String command=Phase8EnforcementPolicy.renderPunishmentCommand(
            punishmentCommand,
            player==null?capture.playerId.toString():player.getName(),
            evidence);
        if(command.startsWith("/"))command=command.substring(1);
        if(!command.isBlank()){
          try{
            getServer().dispatchCommand(getServer().getConsoleSender(),command);
            getLogger().warning("[PhantomAC][PHASE8][PUNISHMENT] player="+capture.playerId
                +" tick="+evidence.serverTick()+" replay="+evidence.replayReference());
          }catch(RuntimeException failure){
            getLogger().log(java.util.logging.Level.WARNING,
                "[PhantomAC][PHASE8][PUNISHMENT] command failed for "+capture.playerId,
                failure);
          }
        }
      }
    }

    capture.processedResults+=report.results().size();

    if(latestSetbackEvidence!=null){
      Player player=getServer().getPlayer(capture.playerId);
      if(player!=null){
        var state=latestSetbackEvidence.priorState();
        org.bukkit.Location target=new org.bukkit.Location(player.getWorld(),
            state.position().x(),state.position().y(),state.position().z(),
            state.yaw(),state.pitch());
        boolean moved=player.teleport(target);
        if(moved){
          getLogger().info("[PhantomAC][PHASE8][SETBACK] player="+player.getName()
              +" tick="+latestSetbackEvidence.serverTick()
              +" target="+state.position()+" rule="+latestSetbackEvidence.rule());
        }else{
          getLogger().warning("[PhantomAC][PHASE8][SETBACK] teleport rejected for player="+player.getName());
        }
      }
    }
  }

  private boolean setbackEnabled(UUID playerId){
    return setbackOverrides.getOrDefault(playerId,setbacksEnabled);
  }

  private DebugLevel debugLevel(UUID playerId){
    return debugPlayers.getOrDefault(playerId,DebugLevel.OFF);
  }

  private boolean shouldLogDebugSummary(Capture capture,Phase8MovementValidation.Verdict verdict,String reason){
    if(debugLevel(capture.playerId)!=DebugLevel.SUMMARY)return false;
    long now=System.nanoTime();
    boolean changed=verdict!=capture.lastDebugSummaryVerdict
        || !Objects.equals(reason,capture.lastDebugSummaryReason);
    if(verdict==Phase8MovementValidation.Verdict.POSSIBLE)return false;
    boolean important=verdict==Phase8MovementValidation.Verdict.IMPOSSIBLE;
    long last=capture.lastDebugSummaryNanos;
    if(important||changed||last<0L||now-last>=5_000_000_000L){
      capture.lastDebugSummaryNanos=now;
      capture.lastDebugSummaryVerdict=verdict;
      capture.lastDebugSummaryReason=reason;
      return true;
    }
    return false;
  }

  private void logValidationSummary(Capture capture,String playerName,Phase8PredictionRunner.Report report,
                                     List<Phase8PredictionRunner.PredictionFrame> frames){
    if(report.results().isEmpty())return;

    Phase8MovementValidation.Result latest=report.results().getLast();
    for(Phase8MovementValidation.Result result:report.results()){
      if(result.verdict()==Phase8MovementValidation.Verdict.IMPOSSIBLE){
        latest=result;
        break;
      }
    }
    if(latest.verdict()!=Phase8MovementValidation.Verdict.IMPOSSIBLE){
      for(Phase8MovementValidation.Result result:report.results()){
        if(result.verdict()==Phase8MovementValidation.Verdict.UNCERTAIN){
          latest=result;
          break;
        }
      }
    }

    Phase8MovementValidation.Evidence e=latest.evidence();
    String reason=e.eliminationReason();
    if(!shouldLogDebugSummary(capture,latest.verdict(),reason))return;

    double dx=e.observedState().position().x()-e.priorState().position().x();
    double dy=e.observedState().position().y()-e.priorState().position().y();
    double dz=e.observedState().position().z()-e.priorState().position().z();
    String delta=String.format(Locale.ROOT,"(%.5f,%.5f,%.5f)",dx,dy,dz);

    String closest=e.closestCandidate().map(candidate->
        "tick="+candidate.simulationTick()
        +",pos="+candidate.position()
        +",vel="+candidate.velocity()
        +",ground="+candidate.onGround()).orElse("none");

    getLogger().info("[PhantomAC][PHASE8][SUMMARY] player="+playerName
        +" verdict="+latest.verdict()
        +" seq="+capture.movementRunner.lastProcessedSequence()
        +" tick="+e.serverTick()
        +" clientTick="+e.clientTickMin()+".."+e.clientTickMax()
        +" delta="+delta
        +" candidates="+e.reachableCandidateCount()
        +"/matches="+e.matchingCandidateCount()
        +"/eliminated="+e.candidatesEliminated()
        +" cause="+reason
        +" closest="+closest
        +" worldChunks="+capture.clientWorld.visibleChunkCount()
        +" uncertaintyCount="+e.uncertaintySources().size()
        +" diagnosticsCount="+e.simulationDiagnostics().size()
        +" paperRejects="+capture.paperMoveFailureCount
        +" replay="+e.replayReference());
  }

  private void logFocusedDebug(String playerName, Phase8PredictionRunner.Report report){
    if(report==null || report.results().isEmpty()){
      getLogger().info("[PhantomAC][PHASE8][FOCUS] player="+playerName+" no cached validation report");
      return;
    }

    Phase8MovementValidation.Result result=report.results().getLast();
    for(Phase8MovementValidation.Result candidate:report.results()){
      if(candidate.verdict()==Phase8MovementValidation.Verdict.IMPOSSIBLE){
        result=candidate;
        break;
      }
    }
    if(result.verdict()!=Phase8MovementValidation.Verdict.IMPOSSIBLE){
      for(Phase8MovementValidation.Result candidate:report.results()){
        if(candidate.verdict()==Phase8MovementValidation.Verdict.UNCERTAIN){
          result=candidate;
          break;
        }
      }
    }

    Phase8MovementValidation.Evidence e=result.evidence();
    double dx=e.observedState().position().x()-e.priorState().position().x();
    double dy=e.observedState().position().y()-e.priorState().position().y();
    double dz=e.observedState().position().z()-e.priorState().position().z();
    getLogger().info("[PhantomAC][PHASE8][FOCUS] player="+playerName
        +" verdict="+result.verdict()
        +" serverTick="+e.serverTick()
        +" clientTick="+e.clientTickMin()+".."+e.clientTickMax()
        +" prior="+e.priorState().position()
        +" observed="+e.observedState().position()
        +" observedDelta="+String.format(Locale.ROOT,"(%.6f,%.6f,%.6f)",dx,dy,dz)
        +" priorVel="+e.priorState().velocity()
        +" observedVel="+e.observedState().velocity()
        +" candidates="+e.reachableCandidateCount()
        +" matches="+e.matchingCandidateCount()
        +" eliminated="+e.candidatesEliminated()
        +" cause="+e.eliminationReason()
        +" uncertainty="+e.uncertaintySources()
        +" diagnostics="+e.simulationDiagnostics()
        +" replay="+e.replayReference());

    for(Phase8PredictionRunner.PredictionFrame frame:report.frames()){
      if(!e.replayReference().endsWith(":"+frame.sequence()))continue;
      Set<String> emittedTraceLines=new LinkedHashSet<>();
      for(String line:frame.trace()){
        if(line.startsWith("CLIENT_TICK ")
            || line.startsWith("TICK_RELIABILITY ")
            || line.startsWith("INPUT_STATE ")
            || line.startsWith("SIM_INPUT_OPTIONS ")
            || line.startsWith("SIM_INPUT_BRANCH ")
            || line.startsWith("SIM_STEP ")
            || line.startsWith("TIMING_")
            || line.startsWith("PHASE7_")
            || line.startsWith("BOOTSTRAP_")
            || line.startsWith("FRONTIER_")
            || line.startsWith("EVIDENCE ")
            || line.startsWith("ROOT_")) {
          if(!emittedTraceLines.add(line))continue;
          getLogger().info("[PhantomAC][PHASE8][FOCUS] player="+playerName
              +" seq="+frame.sequence()+" "+line);
        }
      }
      break;
    }
  }

  private void logClientInputDebug(String playerName,long sequence,Packets.ClientInput input){
    getLogger().info("[PhantomAC][PHASE8][INPUT] player="+playerName
        +" seq="+sequence
        +" forward="+input.forward()
        +" backward="+input.backward()
        +" left="+input.left()
        +" right="+input.right()
        +" jump="+input.jump()
        +" sneak="+input.sneak()
        +" sprint="+input.sprint());
  }

  private void logMovementPacketDebug(String playerName,Capture capture,long sequence,long receivedNanos,
                                        String packetType,com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying packet,
                                        Packets.Move move,ClientTickTracker.MovementObservation tickObservation){
    Vec3 position=move.position();
    String delta="n/a";
    if(position!=null&&capture.lastDebugMovePosition!=null){
      Vec3 previous=capture.lastDebugMovePosition;
      delta=vector(position.x()-previous.x(),position.y()-previous.y(),position.z()-previous.z()).toString();
    }
    if(position!=null) capture.lastDebugMovePosition=position;
    getLogger().info("[PhantomAC][PHASE8][PACKET] player="+playerName
        +" packetType="+packetType
        +" seq="+sequence
        +" receivedNanos="+receivedNanos
        +" posChanged="+packet.hasPositionChanged()
        +" rotChanged="+packet.hasRotationChanged()
        +" onGround="+move.onGround()
        +" tickInterval="+tickObservation.clientTick()
        +" packetsInTick="+tickObservation.packetsInTick()
        +" oneToOne="+tickObservation.oneToOne()
        +" hasSeenTickEnd="+tickObservation.hasSeenTickEnd()
        +" decodedPos="+position
        +" decodedYaw="+move.yaw()
        +" decodedPitch="+move.pitch()
        +" observedDelta="+delta
        +" authority={tick="+capture.authoritativeServerTick.get()
        +",serverPos="+capture.lastAuthoritativePosition
        +",serverVel="+capture.lastAuthoritativeVelocity
        +",serverGround="+capture.lastAuthoritativeOnGround
        +",canFly="+capture.lastAuthoritativeCanFly
        +",flying="+capture.lastAuthoritativeFlying+"}"
        +" evidenceHint="+(
            move.position()==null
              ? "HEARTBEAT_OR_ROTATION_ONLY"
              : (delta.equals("n/a")||delta.equals("Vec3[x=0.0, y=0.0, z=0.0]")
                  ? "POSITION_PACKET_WITH_ZERO_DELTA"
                  : "TRUE_POSITION_MOVEMENT")));
  }

  private void logValidationDebug(String playerName,Phase8MovementValidation.Result result){
    Phase8MovementValidation.Evidence e=result.evidence();
    String observedDelta=vector(
        e.observedState().position().x()-e.priorState().position().x(),
        e.observedState().position().y()-e.priorState().position().y(),
        e.observedState().position().z()-e.priorState().position().z()).toString();
    String closest=e.closestCandidate().map(c->"id="+c.candidateId()
        +" simTick="+c.simulationTick()
        +" pos="+c.position()
        +" vel="+c.velocity()
        +" ground="+c.onGround()
        +" pose="+c.pose()
        +" provenance="+c.provenance()).orElse("none");
    getLogger().info("[PhantomAC][PHASE8][RESULT] player="+playerName
        +" verdict="+result.verdict()
        +" serverTick="+e.serverTick()
        +" clientTick="+e.clientTickMin()+".."+e.clientTickMax()
        +" priorPos="+e.priorState().position()
        +" observedPos="+e.observedState().position()
        +" observedDelta="+observedDelta
        +" priorVelocity="+e.priorState().velocity()
        +" observedVelocity="+e.observedState().velocity()
        +" reachableCandidates="+e.reachableCandidateCount()
        +" matchingCandidates="+e.matchingCandidateCount()
        +" candidatesEliminated="+e.candidatesEliminated()
        +" firstInconsistentTick="+(e.firstInconsistentTick().isPresent()?Long.toString(e.firstInconsistentTick().getAsLong()):"none")
        +" rule="+e.rule()
        +" eliminationReason="+e.eliminationReason()
        +" timingAssumptions="+e.timingAssumptions()
        +" uncertaintySources="+e.uncertaintySources()
        +" simulationDiagnostics="+e.simulationDiagnostics()
        +" closestCandidate={"+closest+"}"
        +" replay="+e.replayReference()
        +" evidenceSource="+(e.rule().startsWith("PAPER_")?"PAPER_REJECTION":e.rule().startsWith("AUTHORITATIVE_")?"AUTHORITATIVE_STATE":"PREDICTION")
        +" clientState={pos="+e.observedState().position()+",ground="+e.observedState().onGround()+",gamemode="+e.observedState().gamemode()+",pose="+e.observedState().pose()+"}");
  }

  private void logPhase8Timing(String playerName,Capture capture,Timeline.Snapshot timeline,
                               Phase7Timing.Reconstruction timing,Phase8PredictionRunner.Report report){
    Phase7Timing.EventTiming latestMovement=timing.frames().stream()
        .map(Phase7Timing.Frame::timing)
        .filter(t->t.kind()==Phase7Timing.EventKind.MOVEMENT)
        .reduce((first,second)->second).orElse(null);
    Phase8MovementValidation.Result latestResult=report.results().isEmpty()?null:report.results().getLast();
    String movementSummary=latestMovement==null?"none":
        "seq="+latestMovement.sequence()+" serverTick="+latestMovement.serverTick()
        +" packetTicks="+latestMovement.packetGenerationClientTicks()
        +" simulationTicks="+latestMovement.simulationClientTicks()
        +" source="+latestMovement.source()+" uncertain="+latestMovement.uncertain()
        +" reasons="+latestMovement.reasons();
    String resultSummary=latestResult==null?"none":
        latestResult.verdict()+"/candidates="+latestResult.evidence().reachableCandidateCount()
        +"/matches="+latestResult.evidence().matchingCandidateCount()
        +"/reason="+latestResult.evidence().eliminationReason();

    getLogger().info("[PhantomAC][PHASE8][DEBUG] player="+playerName
        +" timelineEvents="+timeline.events().size()
        +" phase7Consistency="+timing.consistency()
        +" finalSync="+timing.finalState().status()
        +" endTicks="+capture.clientTickTracker.endTickCount()
        +" currentClientTick="+capture.clientTickTracker.clientTickForMovement()
        +" movement="+movementSummary
        +" result="+resultSummary
        +" chunks={seen="+capture.chunkPackets.get()
        +",compactEntries="+capture.clientWorld.compactStateEntryCount()
        +",pendingBarriers="+capture.clientWorld.pendingBarrierCount()
        +",visible="+capture.clientWorld.visibleChunkCount()+"}"
        +" tickIntegrity={endTicks="+capture.clientTickTracker.endTickCount()
        +",movementInCurrentTick="+capture.clientTickTracker.movementPacketsInCurrentTick()
        +",multiMovementPackets="+capture.multiMovementPackets.get()+"}"
        +" syncReasons="+timing.finalState().reasons());
  }

  private void record(Player player,Packets.Packet packet){
    Capture capture=captures.computeIfAbsent(player.getUniqueId(),
        ignored->new Capture(player.getUniqueId(),System.nanoTime(),validationBudget));
    appendPacket(capture,new RawPacket(capture.sequence.incrementAndGet(),System.nanoTime(),packet));
  }

  private void record(Capture capture,Packets.Packet packet){
    appendPacket(capture,new RawPacket(capture.sequence.incrementAndGet(),System.nanoTime(),packet));
  }

  /** Creates the lossless timeline representation for a client-bound block change without throwing on unsupported states. */
  static Packets.Packet blockStatePacket(dev.phantom.ac.world.Pos position,dev.phantom.ac.world.BlockState state){
    return state.isUnsupported()
        ?new Packets.UnsupportedBlockStateChange(position,state)
        :new Packets.BlockStateChange(position,state);
  }

  private void recordBlockState(Player player,dev.phantom.ac.world.Pos position,dev.phantom.ac.world.BlockState state){
    record(player,state.isUnsupported()
        ?new Packets.UnsupportedBlockStateChange(position,state)
        :new Packets.BlockStateChange(position,state));
  }

  private record ClientEntityTrack(int entityId, Vec3 packetPosition, dev.phantom.ac.geometry.BlockBox box)
      implements Serializable {}

  private void recordEntitySpawn(Capture capture,int entityId,Vec3 packetPosition){
    long sequence=capture.sequence.incrementAndGet();
    long receivedNanos=System.nanoTime();
    capture.clientEntities.remove(entityId);
    Bukkit.getScheduler().runTask(this,()->{
      Player viewer=Bukkit.getPlayer(capture.playerId);
      Entity entity=viewer==null?null:findWorldEntity(viewer,entityId);
      if(entity==null){
        capture.clientWorld.markEntityTrackingIncomplete();
        return;
      }
      dev.phantom.ac.geometry.BlockBox box=alignServerEntityBox(entity,packetPosition);
      capture.clientEntities.put(entityId,new ClientEntityTrack(entityId,packetPosition,box));
      var event=new dev.phantom.ac.Phase4WorldReplica.EntitySpawn(
          new dev.phantom.ac.Phase4WorldReplica.Order(authoritativeTick(capture)==null?0:authoritativeTick(capture),receivedNanos,sequence,sequence),
          new dev.phantom.ac.Phase4WorldReplica.Provenance("paper-entity-spawn","ENTITY_SPAWN",sequence,authoritativeTick(capture)==null?0:authoritativeTick(capture),null,true,"clientbound"),
          new EntityCollisions.EntityBox(entityId,box));
      capture.clientWorld.queue(event);
      var packet=new Packets.EntitySpawn(entityId,box);
      appendPacket(capture,new RawPacket(sequence,receivedNanos,packet,
          Packets.CaptureProvenance.fromAdapter("paper-entity-spawn",packet,authoritativeTick(capture))));
    });
  }

  private void recordEntityRelativeMove(Capture capture,int entityId,double dx,double dy,double dz){
    ClientEntityTrack prior=capture.clientEntities.get(entityId);
    if(prior==null){
      capture.clientWorld.markEntityTrackingIncomplete();
      return;
    }
    Vec3 position=new Vec3(prior.packetPosition().x()+dx,prior.packetPosition().y()+dy,prior.packetPosition().z()+dz);
    dev.phantom.ac.geometry.BlockBox box=prior.box().move(dx,dy,dz);
    ClientEntityTrack next=new ClientEntityTrack(entityId,position,box);
    capture.clientEntities.put(entityId,next);
    recordEntityMove(capture,entityId,box);
  }

  private void recordEntityTeleport(Capture capture,int entityId,Vec3 position){
    ClientEntityTrack prior=capture.clientEntities.get(entityId);
    if(prior==null){
      capture.clientWorld.markEntityTrackingIncomplete();
      return;
    }
    double dx=position.x()-prior.packetPosition().x();
    double dy=position.y()-prior.packetPosition().y();
    double dz=position.z()-prior.packetPosition().z();
    dev.phantom.ac.geometry.BlockBox box=prior.box().move(dx,dy,dz);
    capture.clientEntities.put(entityId,new ClientEntityTrack(entityId,position,box));
    recordEntityMove(capture,entityId,box);
  }

  private void recordEntityMove(Capture capture,int entityId,dev.phantom.ac.geometry.BlockBox box){
    long sequence=capture.sequence.incrementAndGet();
    long receivedNanos=System.nanoTime();
    var event=new dev.phantom.ac.Phase4WorldReplica.EntityMove(
        new dev.phantom.ac.Phase4WorldReplica.Order(authoritativeTick(capture)==null?0:authoritativeTick(capture),receivedNanos,sequence,sequence),
        new dev.phantom.ac.Phase4WorldReplica.Provenance("paper-entity-move","ENTITY_MOVE",sequence,authoritativeTick(capture)==null?0:authoritativeTick(capture),null,true,"clientbound"),
        new EntityCollisions.EntityBox(entityId,box));
    capture.clientWorld.queue(event);
    var packet=new Packets.EntityMove(entityId,box);
    appendPacket(capture,new RawPacket(sequence,receivedNanos,packet,
        Packets.CaptureProvenance.fromAdapter("paper-entity-move",packet,authoritativeTick(capture))));
  }

  private void recordEntityDespawn(Capture capture,int entityId){
    capture.clientEntities.remove(entityId);
    long sequence=capture.sequence.incrementAndGet();
    long receivedNanos=System.nanoTime();
    var event=new dev.phantom.ac.Phase4WorldReplica.EntityDespawn(
        new dev.phantom.ac.Phase4WorldReplica.Order(authoritativeTick(capture)==null?0:authoritativeTick(capture),receivedNanos,sequence,sequence),
        new dev.phantom.ac.Phase4WorldReplica.Provenance("paper-entity-despawn","DESTROY_ENTITIES",sequence,authoritativeTick(capture)==null?0:authoritativeTick(capture),null,true,"clientbound"),
        entityId);
    capture.clientWorld.queue(event);
    var packet=new Packets.EntityDespawn(entityId);
    appendPacket(capture,new RawPacket(sequence,receivedNanos,packet,
        Packets.CaptureProvenance.fromAdapter("paper-entity-despawn",packet,authoritativeTick(capture))));
  }

  private void refreshEntityShape(Capture capture,int entityId){
    ClientEntityTrack prior=capture.clientEntities.get(entityId);
    if(prior==null){
      capture.clientWorld.markEntityTrackingIncomplete();
      return;
    }
    Bukkit.getScheduler().runTask(this,()->{
      Player viewer=Bukkit.getPlayer(capture.playerId);
      Entity entity=viewer==null?null:findWorldEntity(viewer,entityId);
      if(entity==null){
        capture.clientWorld.markEntityTrackingIncomplete();
        return;
      }
      dev.phantom.ac.geometry.BlockBox box=alignServerEntityBox(entity,prior.packetPosition());
      capture.clientEntities.put(entityId,new ClientEntityTrack(entityId,prior.packetPosition(),box));
      recordEntityMove(capture,entityId,box);
    });
  }

  private static Entity findWorldEntity(Player viewer,int entityId){
    for(Entity entity:viewer.getWorld().getEntities())
      if(entity.getEntityId()==entityId)return entity;
    return null;
  }

  private static dev.phantom.ac.geometry.BlockBox alignServerEntityBox(Entity entity,Vec3 packetPosition){
    org.bukkit.Location location=entity.getLocation();
    org.bukkit.util.BoundingBox box=entity.getBoundingBox();
    return new dev.phantom.ac.geometry.BlockBox(
        box.getMinX()+packetPosition.x()-location.getX(),
        box.getMinY()+packetPosition.y()-location.getY(),
        box.getMinZ()+packetPosition.z()-location.getZ(),
        box.getMaxX()+packetPosition.x()-location.getX(),
        box.getMaxY()+packetPosition.y()-location.getY(),
        box.getMaxZ()+packetPosition.z()-location.getZ());
  }

  private void warmStateAsync(Capture capture, BlockState state){
    if(state==null||state.isUnsupported())return;
    Bukkit.getScheduler().runTask(this, () -> {
      Player player=Bukkit.getPlayer(capture.playerId);
      if(player!=null)PaperVanillaCollision.warm(player.getWorld(),state);
    });
  }

  private static void appendPacket(Capture capture,RawPacket packet){
    capture.packets.addLast(packet);
    while(capture.packets.size()>MAX_CAPTURE_PACKETS)capture.packets.pollFirst();
  }

  private static Vec3 vector(double x,double y,double z){return new Vec3(x,y,z);}

  private void drainChunkQueues(){
    if(chunkExecutor==null)return;
    int capacity=Math.max(chunkDecoderThreads*2,1);
    while(chunkInFlight.get()<capacity){
      Capture selected=null; PendingChunk pending=null;
      for(Capture capture:captures.values()){
        pending=capture.chunkQueue.poll();
        if(pending!=null){selected=capture;break;}
      }
      if(selected==null)return;
      Capture target=selected; PendingChunk work=pending;
      if(!chunkInFlight.compareAndSet(chunkInFlight.get(),chunkInFlight.get()+1)){target.chunkQueue.add(work);continue;}
      target.pendingChunkDecodes.incrementAndGet();
      try{
        chunkExecutor.execute(()->{
          try{
            DecodedChunk decoded=decodeChunk(work);
            var order=new dev.phantom.ac.Phase4WorldReplica.Order(work.serverTick(),work.receivedNanos(),work.sequence(),work.sequence());
            var provenance=new dev.phantom.ac.Phase4WorldReplica.Provenance(
                "paper-client-chunk-data","CHUNK_DATA",work.sequence(),work.serverTick(),null,false,"clientbound");
            dev.phantom.ac.Phase4WorldReplica.PackedChunkData worldEvent =
                new dev.phantom.ac.Phase4WorldReplica.PackedChunkData(
                    order,provenance,new dev.phantom.ac.world.Chunk(work.column().getX(),work.column().getZ()),
                    decoded.sections(),work.column().isFullChunk());
            Bukkit.getScheduler().runTask(this, () -> {
              Player player=Bukkit.getPlayer(target.playerId);
              if(player!=null) PaperVanillaCollision.warm(player.getWorld(),decoded.statesToWarm());
              target.clientWorld.queue(worldEvent);
            });
          }catch(RuntimeException failure){
            getLogger().log(java.util.logging.Level.WARNING,
                "[PhantomAC][CHUNK] asynchronous client chunk decode failed player="+target.playerId
                    +" chunk="+work.column().getX()+","+work.column().getZ(),failure);
          }finally{
            target.pendingChunkDecodes.decrementAndGet();
            chunkInFlight.decrementAndGet();
          }
        });
      }catch(RejectedExecutionException rejected){
        target.pendingChunkDecodes.decrementAndGet();
        chunkInFlight.decrementAndGet();
        target.chunkQueue.add(work);
        return;
      }
    }
  }

  private record PendingChunk(long sequence,long receivedNanos,long serverTick,Column column,ClientVersion clientVersion,int minY,int maxY){}
  private record DecodedChunk(
      Map<Integer,dev.phantom.ac.Phase4WorldReplica.PackedSection> sections,
      Set<BlockState> statesToWarm){}


  private static DecodedChunk decodeChunk(PendingChunk pending){
    Map<Integer,dev.phantom.ac.Phase4WorldReplica.PackedSection> sections=new TreeMap<>();
    BaseChunk[] chunks=pending.column().getChunks();

    for(int sectionIndex=0;sectionIndex<chunks.length;sectionIndex++){
      BaseChunk section=chunks[sectionIndex];
      int sectionY=Math.floorDiv(pending.minY(),16)+sectionIndex;

      if(section==null){
        // A null section in a partial column was not delivered by the client.
        // A full column may legitimately represent it as empty.
        if(pending.column().isFullChunk())
          sections.put(sectionIndex,dev.phantom.ac.Phase4WorldReplica.PackedSection.empty(sectionY));
        continue;
      }
      if(section.isEmpty()){
        // Empty is still known data for this section. Do not turn it into UNKNOWN.
        sections.put(sectionIndex,dev.phantom.ac.Phase4WorldReplica.PackedSection.empty(sectionY));
        continue;
      }

      if(section instanceof com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18 modernSection){
        var packetStorage=modernSection.getChunkData();
        if(packetStorage.storage==null){
          int globalId=packetStorage.palette.idToState(0);
          sections.put(sectionIndex,
              dev.phantom.ac.Phase4WorldReplica.PackedSection.uniform(sectionY,
                  decodeGlobalState(pending.clientVersion(),globalId)));
          continue;
        }

        int bits=packetStorage.storage.getBitsPerEntry();
        if(bits<=0){
          int globalId=packetStorage.palette.idToState(0);
          sections.put(sectionIndex,
              dev.phantom.ac.Phase4WorldReplica.PackedSection.uniform(sectionY,
                  decodeGlobalState(pending.clientVersion(),globalId)));
          continue;
        }
        if(bits>32)throw new IllegalStateException("invalid palette bits="+bits);

        long[] data=packetStorage.storage.getData();
        int maxPaletteIndex=0;
        for(int linear=0;linear<4096;linear++){
          int paletteIndex=readPackedValue(data,bits,linear);
          if(paletteIndex>maxPaletteIndex)maxPaletteIndex=paletteIndex;
        }

        BlockState[] corePalette=new BlockState[maxPaletteIndex+1];
        for(int paletteIndex=0;paletteIndex<corePalette.length;paletteIndex++){
          int globalId=packetStorage.palette.idToState(paletteIndex);
          corePalette[paletteIndex]=decodeGlobalState(pending.clientVersion(),globalId);
        }

        sections.put(sectionIndex,
            dev.phantom.ac.Phase4WorldReplica.PackedSection.fromPaletteStorage(
                sectionY,corePalette,data,bits));
        continue;
      }

      // Conservative fallback for older PacketEvents section implementations:
      // decode on the dedicated worker, never on the connection EventLoop.
      BlockState[] states=new BlockState[4096];
      Arrays.fill(states,BlockState.air());
      for(int ly=0;ly<16;ly++){
        for(int lz=0;lz<16;lz++){
          for(int lx=0;lx<16;lx++){
            WrappedBlockState raw=section.get(pending.clientVersion(),lx,ly,lz);
            if(raw==null)continue;
            states[(ly<<8)|(lz<<4)|lx]=toCoreState(raw);
          }
        }
      }
      sections.put(sectionIndex,
          dev.phantom.ac.Phase4WorldReplica.PackedSection.fromStates(sectionY,states));
    }
    Set<BlockState> statesToWarm=new HashSet<>();
    for(var section:sections.values()){
      for(int i=0;i<4096;i++){
        BlockState state=section.stateAt(i);
        if(!state.isUnsupported())statesToWarm.add(state);
      }
    }
    return new DecodedChunk(Map.copyOf(sections),Set.copyOf(statesToWarm));
  }

  private static int readPackedValue(long[] data,int bits,int index){
    if(bits==0)return 0;
    int valuesPerLong=64/bits;
    int word=index/valuesPerLong;
    int offset=(index%valuesPerLong)*bits;
    long mask=(1L<<bits)-1L;
    return (int)((data[word]>>>offset)&mask);
  }

  private static final Map<ClientVersion,ConcurrentHashMap<Integer,dev.phantom.ac.world.BlockState>> STATE_CACHE=new ConcurrentHashMap<>();
  private static dev.phantom.ac.world.BlockState decodeGlobalState(ClientVersion version,int globalId){
    if(globalId<=0)return dev.phantom.ac.world.BlockState.air();
    var cache=STATE_CACHE.computeIfAbsent(version,ignored->new ConcurrentHashMap<>());
    var cached=cache.get(globalId);if(cached!=null)return cached;
    WrappedBlockState raw=WrappedBlockState.getByGlobalId(version,globalId,false);
    if(raw==null || raw.getGlobalId()!=globalId){
      var unsupported=dev.phantom.ac.world.BlockState.unsupported("global-state-"+globalId);
      var existing=cache.putIfAbsent(globalId,unsupported);return existing==null?unsupported:existing;
    }
    var core=toCoreState(raw);
    var existing=cache.putIfAbsent(globalId,core);return existing==null?core:existing;
  }

  /** PacketEvents block-type names are not guaranteed to include the default minecraft namespace. */
  static String normalizeBlockId(String name){
    if(name==null||name.isBlank())return name;
    return name.indexOf(':')>=0?name:"minecraft:"+name;
  }

  private static dev.phantom.ac.world.BlockState toCoreState(WrappedBlockState state){
    if(state==null||state.getType().isAir())return dev.phantom.ac.world.BlockState.air();
    String name=normalizeBlockId(state.getType().getName());
    Map<String,String> properties=new LinkedHashMap<>();
    for(StateValue value:StateValue.values()){
      Object raw=state.getData(value);
      if(raw!=null)properties.put(value.getName(),raw.toString().toLowerCase(Locale.ROOT));
    }
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(name,properties);
  }

  private static boolean isFence(String name){return name.endsWith("_fence")&&!name.endsWith("_fence_gate");}
  private static boolean isWall(String name){return name.endsWith("_wall");}
  private static boolean isPane(String name){return name.endsWith("_pane");}
  private static boolean hasAll(Map<String,String> map,String... keys){for(String key:keys)if(!map.containsKey(key))return false;return true;}
  private static void putEnum(Map<String,String> map,String key,Object value){if(value!=null)map.put(key,value.toString().toLowerCase(Locale.ROOT));}
  private static void putNumber(Map<String,String> map,String key,Object value){if(value instanceof Number number)map.put(key,Integer.toString(number.intValue()));}
  private static void putBoolean(Map<String,String> map,String key,Object value){if(value instanceof Boolean bool)map.put(key,Boolean.toString(bool));}

  private static final class Capture{
    final UUID playerId;
    final long epochNanos;
    final AtomicLong sequence=new AtomicLong();
    final AtomicLong chunkPackets=new AtomicLong();
    final AtomicLong multiMovementPackets=new AtomicLong();
    final AtomicLong lastWorldBarrierNanos=new AtomicLong(Long.MIN_VALUE);
    volatile long lastDebugSummaryNanos=-1L;
    volatile Phase8MovementValidation.Verdict lastDebugSummaryVerdict;
    volatile String lastDebugSummaryReason;
    volatile Vec3 lastDebugMovePosition;
    volatile Phase8PredictionRunner.Report lastDebugReport;
    final ConcurrentLinkedDeque<RawPacket> packets=new ConcurrentLinkedDeque<>();
    final ClientTickTracker clientTickTracker=new ClientTickTracker();
    final dev.phantom.ac.Phase4WorldReplica clientWorld=new dev.phantom.ac.Phase4WorldReplica(Contracts.TARGET_VERSION);
    final ConcurrentHashMap<dev.phantom.ac.world.Pos,ClientBreakPrediction> clientBreakPredictions=new ConcurrentHashMap<>();
    volatile Channel nettyChannel;
    volatile String playerName;
    final AtomicBoolean predictionValidationQueued=new AtomicBoolean();
    final Phase8PredictionRunner movementRunner;
    final PhantomPlayerState playerState;
    final Set<Short> outstandingTransactions=ConcurrentHashMap.newKeySet();
    final Set<Short> reservedTransactions=ConcurrentHashMap.newKeySet();
    final ConcurrentLinkedQueue<PendingChunk> chunkQueue=new ConcurrentLinkedQueue<>();
    final ConcurrentHashMap<Integer,ClientEntityTrack> clientEntities=new ConcurrentHashMap<>();
    final AtomicInteger pendingChunkDecodes=new AtomicInteger();
    final AtomicLong transactionCounter=new AtomicLong(1);
    final AtomicLong paperMoveFailureSequence=new AtomicLong();
    final AtomicLong authoritativeServerTick=new AtomicLong(-1L);
    volatile Vec3 lastAuthoritativePosition=Vec3.ZERO;
    volatile Vec3 lastAuthoritativeVelocity=Vec3.ZERO;
    volatile boolean lastAuthoritativeOnGround;
    volatile boolean lastAuthoritativeCanFly;
    volatile boolean lastAuthoritativeFlying;
    volatile long paperMoveFailureWindowStartNanos=-1L;
    volatile int paperMoveFailureCount;
    volatile Phase8MovementValidation.Accumulator accumulator=Phase8MovementValidation.Accumulator.empty();
    final ValidationResultGate validationGate=new ValidationResultGate();
    final AtomicLong validationRuns=new AtomicLong();
    final AtomicLong validationPackets=new AtomicLong();
    final AtomicLong validationMovements=new AtomicLong();
    volatile long lastValidationElapsedMicros=-1L;
    volatile int lastValidationBatchPackets;
    volatile int lastValidationBatchMovements;
    int processedResults;
    volatile int minY=-64,maxY=319;
    volatile double lastServerX,lastServerY,lastServerZ;

    Capture(UUID id,long epoch,int candidateBudget){
      playerId=id;epochNanos=epoch;
      movementRunner=new Phase8PredictionRunner(candidateBudget);
      playerState=new PhantomPlayerState(id);
      clientWorld.setCollisionResolver((snapshot,state,x,y,z)->PaperVanillaCollision.resolve(state,x,y,z));      clientWorld.markEntityTrackingComplete();
    }

    void updateServerPosition(Player player){
      org.bukkit.Location location=player.getLocation();
      lastServerX=location.getX();
      lastServerY=location.getY();
      lastServerZ=location.getZ();
    }

    short nextWorldTransaction(){
      while(true){
        int raw=(int)(transactionCounter.getAndIncrement()&0x7FFF);
        if(raw==0)continue;
        short id=(short)-raw;
        if(outstandingTransactions.contains(id)||reservedTransactions.contains(id))continue;
        if(reservedTransactions.add(id))return id;
      }
    }

    record ClientBreakPrediction(long captureSequence,int actionSequence,Long clientTick) {}
    List<RawPacket> copy(){
      List<RawPacket> snapshot=new ArrayList<>(packets);
      int start=Math.max(0,snapshot.size()-MAX_VALIDATION_PACKETS);
      return List.copyOf(snapshot.subList(start,snapshot.size()));
    }

    List<RawPacket> copyAll(){
      return List.copyOf(packets);
    }

    List<RawPacket> copySince(long sequenceExclusive){
      return packets.stream().filter(packet->packet.sequence()>sequenceExclusive).toList();
    }
  }
}