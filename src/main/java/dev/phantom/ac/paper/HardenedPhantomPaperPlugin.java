package dev.phantom.ac.paper;

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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSlotStateChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges;
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
import dev.phantom.ac.GrimAlertPolicy;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase5Mechanics;
import dev.phantom.ac.Phase7Timing;
import dev.phantom.ac.Phase8PredictionRunner;
import dev.phantom.ac.ProductionCheckEngine;
import dev.phantom.ac.Phase8MovementValidation;
import dev.phantom.ac.PhantomDebugFormatter;
import dev.phantom.ac.PhantomPlayerState;
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
  private static final long PAPER_MOVE_FAILURE_WINDOW_NANOS=1_000_000_000L;
  private static final int PAPER_MOVE_FAILURE_THRESHOLD=1;
  private static final long VALIDATION_SLOW_RUN_NANOS=50_000_000L;

  private final Map<UUID,Capture> captures=new ConcurrentHashMap<>();
  enum DebugLevel {
    OFF, SUMMARY, FOCUS, IMPOSSIBLE, TRACE;
    boolean summary(){return this==SUMMARY;}
    boolean focus(){return this==FOCUS || this==TRACE;}
    boolean impossibleOnly(){return this==IMPOSSIBLE;}
    boolean trace(){return this==TRACE;}
  }

  static DebugLevel parseDebugMode(String mode){
    if(mode==null || mode.isBlank())return DebugLevel.SUMMARY;
    return switch(mode.toLowerCase(Locale.ROOT)){
      case "off" -> DebugLevel.OFF;
      case "summary" -> DebugLevel.SUMMARY;
      case "focus" -> DebugLevel.FOCUS;
      case "impossible", "flag" -> DebugLevel.IMPOSSIBLE;
      case "trace" -> DebugLevel.TRACE;
      default -> null;
    };
  }

  private final Map<UUID,DebugLevel> debugPlayers=new ConcurrentHashMap<>();
  private org.bukkit.scheduler.BukkitTask stateTask;
  private int validationBudget;
  private ProductionCheckEngine.Config productionCheckConfig;
  private boolean alertsEnabled,broadcastAlerts,printAlertsToConsole,setbacksEnabled,setbacksOnlyExhaustive;
  private GrimAlertPolicy.Config alertPolicy;
  private String alertPermission;
  private int alertIntervalTicks;
  private double violationIncrement,violationDecayPerTick,maximumViolationLevel;
  private boolean kickEnabled,punishmentEnabled,enforcementOnlyExhaustive,permissionExempt;
  private double alertViolationThreshold;
  private double minimumSetbackViolationLevel,minimumKickViolationLevel,minimumPunishmentViolationLevel;
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
        Packets.MovementKind movementKind =
            packet.hasPositionChanged() && packet.hasRotationChanged()
                ? Packets.MovementKind.POSITION_ROTATION
                : packet.hasPositionChanged()
                    ? Packets.MovementKind.POSITION
                    : packet.hasRotationChanged()
                        ? Packets.MovementKind.ROTATION
                        : Packets.MovementKind.STATUS;
        Packets.Move move=new Packets.Move(
            packet.hasPositionChanged()?vector(location.getX(),location.getY(),location.getZ()):null,
            packet.hasRotationChanged()?location.getYaw():null,
            packet.hasRotationChanged()?location.getPitch():null,
            packet.isOnGround(),
            clientTick,
            movementKind);
        if (move.position()!=null) {
          capture.lastServerX=move.position().x();
          capture.lastServerY=move.position().y();
          capture.lastServerZ=move.position().z();
        }
        String sourceId=tickObservation.hasSeenTickEnd()?"paper-client-tick-boundary":"paper-relative-first-tick";
        long sequence=capture.sequence.incrementAndGet();
        long receivedNanos=System.nanoTime();
        if(debugLevel(capture.playerId).trace())
          logMovementPacketDebug(capture.playerName,capture,sequence,receivedNanos,event.getPacketType().toString(),packet,move,tickObservation);
        appendPacket(capture,new RawPacket(sequence,receivedNanos,move,
            new Packets.CaptureProvenance(
                sourceId,
                "CLIENT_TO_SERVER",
                event.getPacketType().toString(),
                authoritativeTick,
                clientTick)));
        schedulePredictionValidation(capture);
      }else if(event.getPacketType()==PacketType.Play.Client.INTERACT_ENTITY){
        var interaction=new WrapperPlayClientInteractEntity(event);
        if(interaction.getAction()!=null){
          Packets.InteractAction action=switch(interaction.getAction()){
            case ATTACK -> Packets.InteractAction.ATTACK;
            case INTERACT -> Packets.InteractAction.INTERACT;
            case INTERACT_AT -> Packets.InteractAction.INTERACT_AT;
          };
          Packets.InteractEntity packet=new Packets.InteractEntity(interaction.getEntityId(),action);
          record(capture,packet);
          schedulePredictionValidation(capture);
        }
      }else if(event.getPacketType()==PacketType.Play.Client.CLICK_WINDOW){
        var click=new WrapperPlayClientClickWindow(event);
        var clickType=click.getWindowClickType();
        Packets.InventoryClick packet=new Packets.InventoryClick(
            click.getWindowId(),click.getSlot(),click.getButton(),
            clickType==null ? "UNKNOWN" : clickType.name());
        record(capture,packet);
        schedulePredictionValidation(capture);
      }else if(event.getPacketType()==PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT){
        var placement=new WrapperPlayClientPlayerBlockPlacement(event);
        var position=placement.getBlockPosition();
        if(position!=null){
          var cursor=placement.getCursorPosition();
          Packets.BlockPlace packet=new Packets.BlockPlace(
              new dev.phantom.ac.world.Pos(position.x,position.y,position.z),
              placement.getFaceId(),
              cursor==null
                  ?dev.phantom.ac.Maths.Vec3.ZERO
                  :new dev.phantom.ac.Maths.Vec3(cursor.x,cursor.y,cursor.z),
              cursor!=null);
          record(capture,packet);
          schedulePredictionValidation(capture);
        }
      }else if(event.getPacketType()==PacketType.Play.Client.VEHICLE_MOVE){
        var vehicle=new WrapperPlayClientVehicleMove(event);
        var position=vehicle.getPosition();
        if(position!=null){
          Packets.VehicleMove packet=new Packets.VehicleMove(
              vector(position.x,position.y,position.z),
              vehicle.getYaw(),vehicle.getPitch(),vehicle.isOnGround());
          record(capture,packet);
          schedulePredictionValidation(capture);
        }
      }else if(event.getPacketType()==PacketType.Play.Client.PLAYER_INPUT){
        var input=new WrapperPlayClientPlayerInput(event);
        Packets.ClientInput clientInput=new Packets.ClientInput(
            input.isForward(),input.isBackward(),input.isLeft(),input.isRight(),
            input.isJump(),input.isShift(),input.isSprint());
        record(capture,clientInput);
        if(debugLevel(capture.playerId).trace())
          logClientInputDebug(capture.playerName,capture.sequence.get(),clientInput);
      }else if(event.getPacketType()==PacketType.Play.Client.SLOT_STATE_CHANGE){
        var slotState=new WrapperPlayClientSlotStateChange(event);
        Packets.SlotStateChange packet=new Packets.SlotStateChange(
            slotState.getWindowId(),slotState.getSlot(),slotState.isState());
        record(capture,packet);
        schedulePredictionValidation(capture);
      }else if(event.getPacketType()==PacketType.Play.Client.USE_ITEM){
        var useItem=new WrapperPlayClientUseItem(event);
        Packets.UseItem packet=new Packets.UseItem(
            useItem.getHand().getId(), useItem.getSequence(), useItem.getYaw(), useItem.getPitch());
        record(capture,packet);
        schedulePredictionValidation(capture);
      }else if(event.getPacketType()==PacketType.Play.Client.HELD_ITEM_CHANGE){
        var held=new WrapperPlayClientHeldItemChange(event);
        Packets.HeldItemChange packet=new Packets.HeldItemChange(held.getSlot());
        record(capture,packet);
        schedulePredictionValidation(capture);
      }else if(event.getPacketType()==PacketType.Play.Client.ENTITY_ACTION){
        var action=new WrapperPlayClientEntityAction(event);
        if(action.getAction()!=null){
          Packets.EntityAction packet=new Packets.EntityAction(action.getAction().name(),action.getJumpBoost());
          record(capture,packet);
          schedulePredictionValidation(capture);
        }
      }else if(event.getPacketType()==PacketType.Play.Client.PLAYER_DIGGING){
        WrapperPlayClientPlayerDigging digging=new WrapperPlayClientPlayerDigging(event);
        var digBlockPosition=digging.getBlockPosition();
        if(digBlockPosition!=null){
          int diggingSequence=digging.getSequence();
          Packets.DigAction digAction=new Packets.DigAction(
              digging.getAction().name(),
              new dev.phantom.ac.world.Pos(digBlockPosition.x,digBlockPosition.y,digBlockPosition.z),
              diggingSequence);
          record(capture,digAction);
        }
        if(digging.getAction()==DiggingAction.FINISHED_DIGGING){
          var finishedPosition=digging.getBlockPosition();
          if(finishedPosition==null) return;
          var position=new dev.phantom.ac.world.Pos(
              finishedPosition.getX(),finishedPosition.getY(),finishedPosition.getZ());
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
        capture.playerState.completeResync();
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
        if (!flags.has(RelativeFlag.X) && !flags.has(RelativeFlag.Y) && !flags.has(RelativeFlag.Z)) {
          capture.lastServerX=packet.getX();
          capture.lastServerY=packet.getY();
          capture.lastServerZ=packet.getZ();
        }
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
        capture.clientWorld.markEntityTrackingIncomplete();
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
      }else if(event.getPacketType()==PacketType.Play.Server.SET_SLOT){
        var slot=new WrapperPlayServerSetSlot(event);
        var item=slot.getItem();
        Packets.ContainerState packet=new Packets.ContainerState(
            slot.getWindowId(),slot.getStateId(),slot.getSlot(),
            item==null || item.isEmpty()?0:item.getAmount(),false);
        record(capture,packet);
      }else if(event.getPacketType()==PacketType.Play.Server.WINDOW_ITEMS){
        var window=new WrapperPlayServerWindowItems(event);
        var carried=window.getCarriedItem();
        int totalItems=window.getItems()==null?0:window.getItems().size();
        Packets.ContainerState packet=new Packets.ContainerState(
            window.getWindowId(),window.getStateId(),-1,totalItems,
            carried.isPresent() && !carried.get().isEmpty());
        record(capture,packet);
      }else if(event.getPacketType()==PacketType.Play.Server.SET_PLAYER_INVENTORY){
        var inventory=new WrapperPlayServerSetPlayerInventory(event);
        var item=inventory.getStack();
        Packets.ContainerState packet=new Packets.ContainerState(
            0,-1,inventory.getSlot(),item==null || item.isEmpty()?0:item.getAmount(),false);
        record(capture,packet);
      }else if(event.getPacketType()==PacketType.Play.Server.SET_CURSOR_ITEM){
        var cursor=new WrapperPlayServerSetCursorItem(event);
        var item=cursor.getStack();
        Packets.ContainerState packet=new Packets.ContainerState(
            -1,-1,-2,item==null || item.isEmpty()?0:item.getAmount(),item!=null && !item.isEmpty());
        record(capture,packet);
      }else if(event.getPacketType()==PacketType.Play.Server.ACKNOWLEDGE_BLOCK_CHANGES){
        var ack=new WrapperPlayServerAcknowledgeBlockChanges(event);
        Packets.BlockAck packet=new Packets.BlockAck(ack.getSequence());
        record(capture,packet);
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
    alertViolationThreshold=Math.max(0.000001,getConfig().getDouble("alerts.violation-alert-threshold",100.0));
    minimumSetbackViolationLevel=Math.max(0.0,getConfig().getDouble("enforcement.setback-violation-level",10.0));
    minimumKickViolationLevel=Math.max(0.0,getConfig().getDouble("enforcement.kick-violation-level",100.0));
    minimumPunishmentViolationLevel=Math.max(0.0,getConfig().getDouble("enforcement.punishment-violation-level",100.0));
    minimumEnforcementConfidence=Math.max(0.0,Math.min(1.0,getConfig().getDouble("enforcement.minimum-confidence",1.0)));
    punishmentCommand=getConfig().getString("enforcement.punishment-command","warn {player} Phantom movement evidence");
    exemptionPermission=getConfig().getString("enforcement.permission","phantom.exempt");
    alertPolicy=loadAlertPolicy();
    validationBudget=Math.max(1,getConfig().getInt("validation.candidate-budget",4096));
    productionCheckConfig=new ProductionCheckEngine.Config(
        getConfig().getBoolean("checks.enabled",true),
        Math.max(0.000001,getConfig().getDouble("checks.alert-violation-threshold",100.0)),
        Math.max(1,getConfig().getInt("checks.reset-after-ticks",40)),
        Math.max(0,getConfig().getInt("checks.alert-debounce-ticks",20)),
        Math.max(1.0,getConfig().getDouble("checks.attack-reach",4.0)),
        Math.max(1.0,getConfig().getDouble("checks.block-interaction-reach",5.0)),
        getConfig().getBoolean("checks.timer-enabled",true),
        Math.max(4,getConfig().getInt("checks.timer-window-ticks",20)),
        Math.max(1_000_000L,getConfig().getLong("checks.timer-window-nanos",250_000_000L)),
        violationIncrement,
        violationDecayPerTick,
        maximumViolationLevel,
        Math.max(0.000001,getConfig().getDouble("checks.violation-alert-interval",40.0)),
        alertPolicy);
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
    stateTask=getServer().getScheduler().runTaskTimer(this,this::drainChunkQueues,1L,1L);
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
    UUID playerId=event.getPlayer().getUniqueId();
    captures.computeIfAbsent(playerId,id->new Capture(id,System.nanoTime(),validationBudget));
  }

  // Re-anchoring is driven by the clientbound position-correction packet.
  // Bukkit teleport events never enter movement detection state.

  @EventHandler public void onRespawn(PlayerRespawnEvent event){
    UUID playerId=event.getPlayer().getUniqueId();
    captures.put(playerId,new Capture(playerId,System.nanoTime(),validationBudget));
  }

  @EventHandler public void onWorldChange(PlayerChangedWorldEvent event){
    UUID playerId=event.getPlayer().getUniqueId();
    captures.put(playerId,new Capture(playerId,System.nanoTime(),validationBudget));
    setbackOverrides.remove(playerId);
  }

  @EventHandler public void onQuit(PlayerQuitEvent event){
    Capture existing=captures.get(event.getPlayer().getUniqueId());
    if(existing!=null) existing.playerState.disconnect();
    captures.remove(event.getPlayer().getUniqueId());
    debugPlayers.remove(event.getPlayer().getUniqueId());
    setbackOverrides.remove(event.getPlayer().getUniqueId());
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
    long alertNowMillis=System.currentTimeMillis();
    var accumulated=capture.accumulator.accept(evidence,
        new Phase8MovementValidation.Config(
            alertViolationThreshold, 0, alertsEnabled, true,
            violationIncrement, violationDecayPerTick, maximumViolationLevel,
            Math.max(0.000001, getConfig().getDouble("alerts.violation-alert-interval",40.0)),
            alertPolicy),
        alertNowMillis);
    capture.accumulator=accumulated.state();
    capture.processedResults++;
    accumulated.log().ifPresent(log->{
      if(printAlertsToConsole) getLogger().info("[PhantomAC][FLAG] "
          +log.serverMessage(capture.playerName)
          +" reason="+log.evidence()
          +" replay="+log.replayReference());
    });
    accumulated.alert().ifPresent(alert->{
      if(printAlertsToConsole) getLogger().warning("[PhantomAC][ALERT] "+alert.debugMessage());
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
      long runs=captures.values().stream().mapToLong(c->c.validationRuns.get()).sum();
      long totalNanos=captures.values().stream().mapToLong(c->c.validationNanosTotal.get()).sum();
      long slowRuns=captures.values().stream().mapToLong(c->c.validationSlowRuns.get()).sum();
      long possible=captures.values().stream().mapToLong(c->c.validationPossible.get()).sum();
      long uncertain=captures.values().stream().mapToLong(c->c.validationUncertain.get()).sum();
      long impossible=captures.values().stream().mapToLong(c->c.validationImpossible.get()).sum();
      long queued=captures.values().stream().filter(c->c.predictionValidationQueued.get()).count();
      long avgMicros=runs==0?0:(totalNanos/1_000L)/runs;
      sender.sendMessage("PhantomAC captures="+captures.size()
          +" visibleChunks="+captures.values().stream().mapToInt(c->c.clientWorld.visibleChunkCount()).sum()
          +" pendingBarriers="+captures.values().stream().mapToInt(c->c.clientWorld.pendingBarrierCount()).sum()
          +" validationQueued="+queued
          +" validationRuns="+runs
          +" avgValidationMicros="+avgMicros
          +" slowRuns="+slowRuns
          +" verdicts="+possible+"/"+uncertain+"/"+impossible
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
              +" (movement/input/timing decisions, including UNCERTAIN).");
        }
        case "impossible", "flag" -> {
          debugPlayers.put(target.getUniqueId(),DebugLevel.IMPOSSIBLE);
          sender.sendMessage("Phase 8 impossible-only flagging enabled for "+target.getName()
              +" (detailed IMPOSSIBLE evidence only; UNCERTAIN is suppressed).");
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
        default -> sender.sendMessage("Usage: /phantom debug <player> [summary|focus|impossible|flag|trace|dump|off|status]");
      }
      return true;
    }

    if(args[0].equalsIgnoreCase("flag")&&args.length>=2){
      Player target=getServer().getPlayerExact(args[1]);
      if(target==null){sender.sendMessage("Player not found: "+args[1]);return true;}
      String mode=args.length>=3?args[2].toLowerCase(Locale.ROOT):"on";
      switch(mode){
        case "on", "impossible" -> {
          debugPlayers.put(target.getUniqueId(),DebugLevel.IMPOSSIBLE);
          sender.sendMessage("Phase 8 impossible-only flagging enabled for "+target.getName()
              +" (detailed IMPOSSIBLE evidence only; UNCERTAIN is suppressed).");
        }
        case "off" -> {
          debugPlayers.remove(target.getUniqueId());
          sender.sendMessage("Phase 8 impossible-only flagging disabled for "+target.getName());
        }
        case "status" -> sender.sendMessage("Phase 8 flag mode for "+target.getName()+": "
            +(debugPlayers.getOrDefault(target.getUniqueId(),DebugLevel.OFF)==DebugLevel.IMPOSSIBLE?"IMPOSSIBLE":"OFF"));
        default -> sender.sendMessage("Usage: /phantom flag <player> [on|off|status]");
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

    sender.sendMessage("Usage: /phantom status | /phantom debug <player> [summary|focus|impossible|flag|trace|dump|off|status] | /phantom flag <player> [on|off|status] | /phantom setback <player> [on|off]");
    return true;
  }

  private static int enchantmentLevel(org.bukkit.inventory.ItemStack item, String key){
    if(item==null||key==null||key.isBlank())return -1;
    org.bukkit.enchantments.Enchantment enchantment =
        org.bukkit.enchantments.Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(key));
    return enchantment==null ? -1 : item.getEnchantmentLevel(enchantment);
  }

  private static Channel asNettyChannel(Object channel){
    return channel instanceof Channel nettyChannel ? nettyChannel : null;
  }

  /**
   * Advances the per-tick ping-pong sandwich.
   *
   * <p>Each server tick has exactly one boundary ping. That ping closes the
   * previous tick interval, so all clientbound mutations emitted since the prior
   * boundary are held behind this transaction. The same ping is also the opening
   * marker for the next interval. This is the bounded "one big sandwich" model:
   * PING(n) -> tick state changes -> PING(n+1).</p>
   */
  private void requestStateBarrier(Player player,Capture capture){
    sendStateBarrier(player,capture);
  }

  private void sendStateBarrier(Player player,Capture capture){
    Packets.PlayerContext context=capture.playerState.pendingAuthoritativeContext();

    short transactionId=capture.nextWorldTransaction();
    long sequenceBoundary=capture.sequence.get();

    // World mutations since the previous boundary belong to this closing ping.
    capture.clientWorld.openBarrier(transactionId,sequenceBoundary);

    Packets.PlayerContext barrierContext=
        context==null ? null : context.withTransactionBarrier(transactionId);

    // The first boundary has no preceding authority sample, but it still has
    // to exist as the opening marker for the first client-tick sandwich.
    if(barrierContext!=null){
      capture.playerState.markBarrierSent(transactionId,barrierContext);
    }
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

      if(barrierContext!=null){
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
      }
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

  private static Long authoritativeTick(Capture capture){
    return null;
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
      State.Player anchor=null;
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
          -1L);

      Phase8PredictionRunner.Report report=incremental;
      ProductionCheckEngine.Report productionChecks =
          ProductionCheckEngine.analyze(playerName, raw, incremental, productionCheckConfig,
              capture.productionCheckState, capture.accuracyState);
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
      }else if(debug.impossibleOnly()){
        logImpossibleDebug(playerName,incremental);
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
      long validationElapsedNanos=System.nanoTime()-startedNanos;
      capture.lastValidationElapsedMicros=validationElapsedNanos/1_000L;
      capture.lastValidationCompletedNanos=System.nanoTime();
      capture.validationNanosTotal.addAndGet(validationElapsedNanos);
      capture.validationSlowRuns.addAndGet(validationElapsedNanos>=VALIDATION_SLOW_RUN_NANOS ? 1L : 0L);
      capture.validationPossible.addAndGet(incremental.possible());
      capture.validationUncertain.addAndGet(incremental.uncertain());
      capture.validationImpossible.addAndGet(incremental.impossible());
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
      getServer().getScheduler().runTask(this,()->applyResult(capture,report,productionChecks));
    }catch(RuntimeException failure){
      capture.lastValidationElapsedMicros=(System.nanoTime()-startedNanos)/1_000L;
      getLogger().log(java.util.logging.Level.WARNING,
          "[PhantomAC][PHASE8] async validation failed for "+capture.playerId,
          failure);
    }
  }

  private void applyResult(
      Capture capture,
      Phase8PredictionRunner.Report report,
      ProductionCheckEngine.Report productionChecks){
    DebugLevel debugLevel=debugLevel(capture.playerId);
    if (debugLevel.trace()) {
      for(Phase8MovementValidation.Result result:report.results())
        logValidationDebug(getServer().getPlayer(capture.playerId)==null
            ?capture.playerId.toString()
            :getServer().getPlayer(capture.playerId).getName(),result);
    }

    long alertNowMillis=System.currentTimeMillis();
    for(ProductionCheckEngine.Finding finding:productionChecks.findings()){
      var accumulatedCheck=capture.productionChecks.accept(finding,productionCheckConfig,alertNowMillis);
      capture.productionChecks=accumulatedCheck.state();
      accumulatedCheck.log().ifPresent(log->{
        if(printAlertsToConsole) getLogger().info("[PhantomAC][FLAG] "
            +log.message(capture.playerName)
            +" reason="+log.reason()
            +" replay="+log.replayReference());
      });
      accumulatedCheck.alert().ifPresent(alert->{
        String message=alert.message(capture.playerName);
        if(printAlertsToConsole)getLogger().warning("[PhantomAC][ALERT] "+message
            +" reason="+alert.reason()+" severity="
            +String.format(Locale.ROOT,"%.2f",alert.severity())
            +" replay="+alert.replayReference());
        if(broadcastAlerts){
          getServer().broadcastMessage(message);
        }else{
          for(Player recipient:getServer().getOnlinePlayers())
            if(recipient.hasPermission(alertPermission))
              recipient.sendMessage(message);
        }
      });
    }

    Phase8MovementValidation.Evidence latestSetbackEvidence=null;
    long latestSetbackTick=Long.MIN_VALUE;

    Phase8MovementValidation.Config accumulatorConfig =
        new Phase8MovementValidation.Config(
            alertViolationThreshold, 0, alertsEnabled, true,
            violationIncrement, violationDecayPerTick, maximumViolationLevel,
            Math.max(0.000001, getConfig().getDouble("alerts.violation-alert-interval",40.0)),
            alertPolicy);

    Phase8EnforcementPolicy.Config enforcementConfig =
        new Phase8EnforcementPolicy.Config(
            setbackEnabled(capture.playerId),
            kickEnabled,
            punishmentEnabled,
            enforcementOnlyExhaustive,
            minimumSetbackViolationLevel,
            minimumKickViolationLevel,
            minimumPunishmentViolationLevel,
            minimumEnforcementConfidence,
            punishmentCommand);

    for(Phase8MovementValidation.Result result:report.results()){
      Phase8MovementValidation.Evidence evidence=result.evidence();
      if(!capture.validationGate.accept(evidence.replayReference(),result.verdict()))continue;

      var accumulated=capture.accumulator.accept(evidence,accumulatorConfig,alertNowMillis);
      capture.accumulator=accumulated.state();

      accumulated.log().ifPresent(log->{
        if(printAlertsToConsole) getLogger().info("[PhantomAC][FLAG] "
            +log.serverMessage(capture.playerName)
            +" reason="+log.evidence()
            +" replay="+log.replayReference());
      });

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

  private GrimAlertPolicy.Config loadAlertPolicy(){
    long fallbackWindowSeconds=Math.max(1L,
        getConfig().getLong("punishments.remove-violations-after-seconds",300L));
    long fallbackWindowMillis=fallbackWindowSeconds*1000L;
    GrimAlertPolicy.CommandRule fallbackAlert=new GrimAlertPolicy.CommandRule(
        Math.max(0.000001,getConfig().getDouble("alerts.violation-alert-threshold",100.0)),
        Math.max(0.0,getConfig().getDouble("alerts.violation-alert-interval",40.0)));
    GrimAlertPolicy.CommandRule fallbackLog=GrimAlertPolicy.CommandRule.parse("1:1");

    List<GrimAlertPolicy.Group> groups=new ArrayList<>();
    org.bukkit.configuration.ConfigurationSection groupsSection =
        getConfig().getConfigurationSection("punishments.groups");
    boolean configuredBlatant=false;

    if(groupsSection!=null){
      for(String groupName:groupsSection.getKeys(false)){
        org.bukkit.configuration.ConfigurationSection group=
            groupsSection.getConfigurationSection(groupName);
        if(group==null) continue;
        List<String> checks=group.getStringList("checks");
        if(checks.isEmpty()) continue;
        if(groupName.equalsIgnoreCase("Blatant")) configuredBlatant=true;
        long windowSeconds=Math.max(1L,
            group.getLong("remove-violations-after-seconds",fallbackWindowSeconds));
        try{
          GrimAlertPolicy.CommandRule alert=GrimAlertPolicy.CommandRule.parse(
              group.getString("alert","100:40"));
          GrimAlertPolicy.CommandRule log=GrimAlertPolicy.CommandRule.parse(
              group.getString("log","1:1"));
          groups.add(new GrimAlertPolicy.Group(
              groupName,windowSeconds*1000L,checks,alert,log));
        }catch(RuntimeException invalid){
          getLogger().warning("[PhantomAC] Ignoring invalid punishment group "
              +groupName+": "+invalid.getMessage());
        }
      }
    }

    /*
     * Preserve the immediate hard-finding path even for servers upgrading from
     * an older config.yml. saveDefaultConfig() intentionally does not overwrite
     * an existing file, so relying on the resource-only Blatant group would leave
     * legacy installs on Grim's 100:40 Simulation alert threshold.
     */
    if(!configuredBlatant){
      groups.add(0,new GrimAlertPolicy.Group(
          "Blatant",
          fallbackWindowMillis,
          List.of("Flight","Step","Speed","Jesus","NoFall","FastBreak","FarBreak","FarPlace"),
          GrimAlertPolicy.CommandRule.parse("1:1"),
          GrimAlertPolicy.CommandRule.parse("1:1")));
      getLogger().info("[PhantomAC] Built-in immediate alert policy enabled for deterministic movement contradictions");
    }

    return new GrimAlertPolicy.Config(groups,fallbackAlert,fallbackLog,fallbackWindowMillis);
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

  private void logImpossibleDebug(String playerName, Phase8PredictionRunner.Report report){
    if(report==null || report.results().isEmpty())return;
    List<Phase8MovementValidation.Evidence> impossibleEvidence=new ArrayList<>();
    for(Phase8MovementValidation.Result result:report.results()){
      if(result.verdict()!=Phase8MovementValidation.Verdict.IMPOSSIBLE)continue;
      impossibleEvidence.add(result.evidence());
      logValidationDebug(playerName,result);
    }
    if(impossibleEvidence.isEmpty())return;

    for(Phase8PredictionRunner.PredictionFrame frame:report.frames()){
      boolean relevant=false;
      for(Phase8MovementValidation.Evidence evidence:impossibleEvidence){
        if(evidence.replayReference().endsWith(":"+frame.sequence())){
          relevant=true;
          break;
        }
      }
      if(!relevant)continue;
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
            || line.startsWith("ROOT_")){
          if(!emittedTraceLines.add(line))continue;
          getLogger().info("[PhantomAC][PHASE8][FLAG] player="+playerName
              +" seq="+frame.sequence()+" "+line);
        }
      }
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
    // Entity dimensions are intentionally not sourced from Bukkit. Until the
    // packet metadata/entity-type hitbox catalogue is complete, keep collision
    // state explicitly incomplete so dependent checks become UNCERTAIN.
    capture.clientEntities.remove(entityId);
    capture.clientWorld.markEntityTrackingIncomplete();
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
            target.clientWorld.queue(worldEvent);
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
      Map<Integer,dev.phantom.ac.Phase4WorldReplica.PackedSection> sections){}


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
    return new DecodedChunk(Map.copyOf(sections));  }

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
    volatile ProductionCheckEngine.Accumulator productionChecks=ProductionCheckEngine.Accumulator.empty();
    final AccuracyChecks.State accuracyState=new AccuracyChecks.State();
    final ProductionCheckEngine.SessionState productionCheckState=new ProductionCheckEngine.SessionState();
    final ValidationResultGate validationGate=new ValidationResultGate();
    final AtomicLong validationRuns=new AtomicLong();
    final AtomicLong validationPackets=new AtomicLong();
    final AtomicLong validationMovements=new AtomicLong();
    final AtomicLong validationNanosTotal=new AtomicLong();
    final AtomicLong validationSlowRuns=new AtomicLong();
    final AtomicLong validationPossible=new AtomicLong();
    final AtomicLong validationUncertain=new AtomicLong();
    final AtomicLong validationImpossible=new AtomicLong();
    volatile long lastValidationElapsedMicros=-1L;
    volatile long lastValidationCompletedNanos=-1L;
    volatile int lastValidationBatchPackets;
    volatile int lastValidationBatchMovements;
    int processedResults;
    volatile int minY=-64,maxY=319;
    volatile double lastServerX,lastServerY,lastServerZ;

    Capture(UUID id,long epoch,int candidateBudget){
      playerId=id;epochNanos=epoch;
      movementRunner=new Phase8PredictionRunner(candidateBudget);
      playerState=new PhantomPlayerState(id);
      // Movement collision is resolved from the immutable packet-visible world.
      // No live Bukkit/Paper collision shape is installed here.
      clientWorld.markEntityTrackingIncomplete();
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