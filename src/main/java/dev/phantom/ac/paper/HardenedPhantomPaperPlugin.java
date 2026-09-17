package dev.phantom.ac.paper;

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
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import dev.phantom.ac.ClientTickTracker;
import dev.phantom.ac.CompensatedClientWorld;
import dev.phantom.ac.Contracts;
import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.Packets;
import dev.phantom.ac.Packets.RawPacket;
import dev.phantom.ac.Phase5Mechanics;
import dev.phantom.ac.Phase7Timing;
import dev.phantom.ac.Phase8LiveValidation;
import dev.phantom.ac.Phase8MovementValidation;
import dev.phantom.ac.SetbackPolicy;
import dev.phantom.ac.Timeline;
import dev.phantom.ac.ValidationResultGate;
import dev.phantom.ac.World;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Hardened Paper boundary: Bukkit work is main-thread only; validation is pure and asynchronous. */
public final class HardenedPhantomPaperPlugin extends JavaPlugin implements Listener {
  private static final int MAX_CAPTURE_PACKETS=12_000,MAX_VALIDATION_PACKETS=6_000,MAX_CHUNK_QUEUE=512;
  private final Map<UUID,Capture> captures=new ConcurrentHashMap<>();
  private final Map<UUID,Boolean> debugPlayers=new ConcurrentHashMap<>();
  private final Map<ClientVersion,ConcurrentHashMap<Integer,dev.phantom.ac.world.BlockState>> stateCache=new ConcurrentHashMap<>();
  private org.bukkit.scheduler.BukkitTask chunkDrainTask,stateTask,validationTask;
  private ExecutorService decoder; private int decoderThreads,validationBudget; private boolean alertsEnabled,broadcastAlerts,setbacksEnabled,setbacksOnlyExhaustive;
  private final Map<UUID,Boolean> setbackOverrides=new ConcurrentHashMap<>();

  private final PacketListenerAbstract listener=new PacketListenerAbstract(){
    @Override public void onPacketReceive(PacketReceiveEvent event){
      Object sender=event.getPlayer();
      if(!(sender instanceof Player player))return;
      Capture capture=captures.computeIfAbsent(player.getUniqueId(),ignored->new Capture(player.getUniqueId(),System.nanoTime()));
      if(event.getPacketType()==PacketType.Play.Client.CLIENT_TICK_END){
        capture.clientTickTracker.onClientTickEnd();
        return;
      }
      if(WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())){
        var packet=new WrapperPlayClientPlayerFlying(event);
        var location=packet.getLocation();
        ClientTickTracker.MovementObservation tickObservation=capture.clientTickTracker.onMovement();
        if(!tickObservation.oneToOne())capture.multiMovementPackets.incrementAndGet();
        Long clientTick=tickObservation.clientTick();
        Packets.Move move=new Packets.Move(packet.hasPositionChanged()?vector(location.getX(),location.getY(),location.getZ()):null,
            packet.hasRotationChanged()?location.getYaw():null,
            packet.hasRotationChanged()?location.getPitch():null,
            packet.isOnGround(),
            clientTick);
        String sourceId=tickObservation.hasSeenTickEnd()?"paper-client-tick-boundary":"paper-relative-first-tick";
        appendPacket(capture,new RawPacket(capture.sequence.incrementAndGet(),System.nanoTime(),move,
            Packets.CaptureProvenance.fromAdapter(sourceId,move,null)));
      }else if(event.getPacketType()==PacketType.Play.Client.PLAYER_INPUT){
        var input=new WrapperPlayClientPlayerInput(event);
        record(player,new Packets.ClientInput(input.isForward(),input.isBackward(),input.isLeft(),input.isRight(),input.isJump(),input.isShift(),input.isSprint()));
      }else if(event.getPacketType()==PacketType.Play.Client.TELEPORT_CONFIRM){
        record(player,new Packets.TeleportConfirm(new WrapperPlayClientTeleportConfirm(event).getTeleportId()));
      }else if(event.getPacketType()==PacketType.Play.Client.PONG){
        int id=new WrapperPlayClientPong(event).getId();
        if(id>=0)return;
        short transaction=(short)id;
        if(capture.outstandingTransactions.remove(transaction)&&capture.clientWorld.acknowledge(transaction)){
          record(player,new Packets.WorldTransactionAck(transaction));
        }
      }
    }
    @Override public void onPacketSend(PacketSendEvent event){Object sender=event.getPlayer();if(!(sender instanceof Player player))return;Capture capture=captures.computeIfAbsent(player.getUniqueId(),ignored->new Capture(player.getUniqueId(),System.nanoTime()));if(event.getPacketType()==PacketType.Play.Server.PLAYER_POSITION_AND_LOOK){var packet=new WrapperPlayServerPlayerPositionAndLook(event);RelativeFlag flags=packet.getRelativeFlags();record(player,new Packets.Teleport(packet.getTeleportId(),vector(packet.getX(),packet.getY(),packet.getZ()),packet.getYaw(),packet.getPitch(),flags.has(RelativeFlag.X),flags.has(RelativeFlag.Y),flags.has(RelativeFlag.Z),flags.has(RelativeFlag.YAW),flags.has(RelativeFlag.PITCH)));}else if(event.getPacketType()==PacketType.Play.Server.ENTITY_VELOCITY){var packet=new WrapperPlayServerEntityVelocity(event);if(packet.getEntityId()==player.getEntityId()){var v=packet.getVelocity();record(player,new Packets.Velocity(vector(v.getX(),v.getY(),v.getZ())));}}else if(event.getPacketType()==PacketType.Play.Server.BLOCK_CHANGE){var packet=new WrapperPlayServerBlockChange(event);var p=packet.getBlockPosition();short tx=capture.nextWorldTransaction();capture.clientWorld.queueForBarrier(tx,new CompensatedClientWorld.Mutation.Block(new dev.phantom.ac.world.Pos(p.getX(),p.getY(),p.getZ()),toCoreState(packet.getBlockState())));recordBlockState(player,new dev.phantom.ac.world.Pos(p.getX(),p.getY(),p.getZ()),toCoreState(packet.getBlockState()));event.getTasksAfterSend().add(()->sendWorldTransaction(player,capture,tx));}else if(event.getPacketType()==PacketType.Play.Server.MULTI_BLOCK_CHANGE){var packet=new WrapperPlayServerMultiBlockChange(event);short tx=capture.nextWorldTransaction();for(var change:packet.getBlocks()){var pos=new dev.phantom.ac.world.Pos(change.getX(),change.getY(),change.getZ());var state=toCoreState(change.getBlockState(event.getUser().getClientVersion()));capture.clientWorld.queueForBarrier(tx,new CompensatedClientWorld.Mutation.Block(pos,state));recordBlockState(player,pos,state);}event.getTasksAfterSend().add(()->sendWorldTransaction(player,capture,tx));}else if(event.getPacketType()==PacketType.Play.Server.UNLOAD_CHUNK){var packet=new WrapperPlayServerUnloadChunk(event);short tx=capture.nextWorldTransaction();var chunk=new World.Chunk(packet.getChunkX(),packet.getChunkZ());capture.clientWorld.queueForBarrier(tx,new CompensatedClientWorld.Mutation.ChunkUnload(new dev.phantom.ac.world.Chunk(packet.getChunkX(),packet.getChunkZ())));record(player,new Packets.ChunkUnload(chunk));event.getTasksAfterSend().add(()->sendWorldTransaction(player,capture,tx));}else if(event.getPacketType()==PacketType.Play.Server.CHUNK_DATA){var packet=new WrapperPlayServerChunkData(event);Column column=packet.getColumn();short tx=capture.nextWorldTransaction();long sequence=capture.sequence.incrementAndGet();PendingChunk pending=new PendingChunk(sequence,System.nanoTime(),column,capture.minY,capture.maxY,event.getUser().getClientVersion(),tx);capture.chunkPackets.incrementAndGet();while(capture.chunkQueue.size()>=MAX_CHUNK_QUEUE){capture.chunkQueue.poll();capture.droppedChunks.incrementAndGet();}capture.chunkQueue.offer(pending);event.getTasksAfterSend().add(()->sendWorldTransaction(player,capture,tx));}}
  };

  @Override public void onEnable(){saveDefaultConfig();alertsEnabled=getConfig().getBoolean("alerts.enabled",true);broadcastAlerts=getConfig().getBoolean("alerts.broadcast",false);setbacksEnabled=getConfig().getBoolean("setbacks.enabled",false);setbacksOnlyExhaustive=getConfig().getBoolean("setbacks.only-when-exhaustive",true);validationBudget=Math.max(1,getConfig().getInt("validation.candidate-budget",4096));getServer().getPluginManager().registerEvents(this,this);PacketEvents.getAPI().getEventManager().registerListener(listener);int processors=Math.max(2,Runtime.getRuntime().availableProcessors());decoderThreads=Math.max(1,Math.min(4,processors/2));decoder=Executors.newFixedThreadPool(decoderThreads,r->{Thread t=new Thread(r,"Phantom-ChunkDecoder");t.setDaemon(true);return t;});chunkDrainTask=getServer().getScheduler().runTaskTimer(this,this::drainChunkQueue,1L,1L);stateTask=getServer().getScheduler().runTaskTimer(this,this::captureLiveContext,1L,1L);int interval=Math.max(1,getConfig().getInt("validation.interval-ticks",10));validationTask=getServer().getScheduler().runTaskTimer(this,this::scheduleValidations,interval,interval);getLogger().info("[PhantomAC] Hardened Phase 8 adapter enabled");}
  @Override public void onDisable(){getServer().getScheduler().cancelTasks(this);if(chunkDrainTask!=null)chunkDrainTask.cancel();if(stateTask!=null)stateTask.cancel();if(validationTask!=null)validationTask.cancel();PacketEvents.getAPI().getEventManager().unregisterListener(listener);if(decoder!=null)decoder.shutdownNow();stateCache.clear();captures.clear();debugPlayers.clear();setbackOverrides.clear();}
  @EventHandler public void onJoin(PlayerJoinEvent e){captures.put(e.getPlayer().getUniqueId(),new Capture(e.getPlayer().getUniqueId(),System.nanoTime()));}
  @EventHandler public void onWorldChange(PlayerChangedWorldEvent e){captures.put(e.getPlayer().getUniqueId(),new Capture(e.getPlayer().getUniqueId(),System.nanoTime()));setbackOverrides.remove(e.getPlayer().getUniqueId());}
  @EventHandler public void onQuit(PlayerQuitEvent e){captures.remove(e.getPlayer().getUniqueId());debugPlayers.remove(e.getPlayer().getUniqueId());setbackOverrides.remove(e.getPlayer().getUniqueId());}
  @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
    if(!command.getName().equalsIgnoreCase("phantom"))return false;
    if(args.length==0||args[0].equalsIgnoreCase("status")){
      sender.sendMessage("PhantomAC captures="+captures.size()
          +" decoderQueue="+captures.values().stream().mapToInt(c->c.chunkQueue.size()).sum()
          +" setbacksDefault="+setbacksEnabled);
      return true;
    }
    if(args[0].equalsIgnoreCase("debug")&&args.length>=2){
      Player target=getServer().getPlayerExact(args[1]);
      if(target==null){sender.sendMessage("Player not found: "+args[1]);return true;}
      if(args.length>=3&&args[2].equalsIgnoreCase("off")){
        debugPlayers.remove(target.getUniqueId());
        sender.sendMessage("Phase 8 debug disabled for "+target.getName());
      }else{
        debugPlayers.put(target.getUniqueId(),Boolean.TRUE);
        sender.sendMessage("Phase 8 debug enabled for "+target.getName());
      }
      return true;
    }
    if(args[0].equalsIgnoreCase("setback")&&args.length>=2){
      Player target=getServer().getPlayerExact(args[1]);
      if(target==null){sender.sendMessage("Player not found: "+args[1]);return true;}
      if(args.length<3){
        setbackOverrides.put(target.getUniqueId(),Boolean.TRUE);
        sender.sendMessage("Strict 1:1 setback enabled for "+target.getName());
        return true;
      }
      if(args[2].equalsIgnoreCase("on")){
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
    sender.sendMessage("Usage: /phantom status | /phantom debug <player> [off] | /phantom setback <player> [on|off]");
    return true;
  }

  private void drainChunkQueue(){
    if(decoder==null)return;
    int submitted=0;
    while(submitted<decoderThreads){
      Capture selected=null;
      PendingChunk chosen=null;
      long bestDistance=Long.MAX_VALUE;

      for(Capture capture:captures.values()){
        Player player=getServer().getPlayer(capture.playerId);
        if(player==null)continue;
        int playerChunkX=Math.floorDiv(player.getLocation().getBlockX(),16);
        int playerChunkZ=Math.floorDiv(player.getLocation().getBlockZ(),16);
        for(PendingChunk pending:capture.chunkQueue){
          long dx=(long)pending.column.getX()-playerChunkX;
          long dz=(long)pending.column.getZ()-playerChunkZ;
          long distance=dx*dx+dz*dz;
          if(distance<bestDistance){
            bestDistance=distance;
            selected=capture;
            chosen=pending;
          }
        }
      }

      if(selected==null||chosen==null)return;
      if(!selected.chunkQueue.remove(chosen))continue;
      try{
        Capture target=selected;
        PendingChunk work=chosen;
        decoder.execute(()->decodeChunk(target,work));
      }catch(RejectedExecutionException rejected){
        selected.chunkQueue.offer(chosen);
        return;
      }
      submitted++;
    }
  }

  private void decodeChunk(Capture capture,PendingChunk pending){try{Map<dev.phantom.ac.world.Pos,dev.phantom.ac.world.BlockState> states=new LinkedHashMap<>();ConcurrentHashMap<Integer,dev.phantom.ac.world.BlockState> cache=stateCache.computeIfAbsent(pending.clientVersion,ignored->new ConcurrentHashMap<>());BaseChunk[] sections=pending.column.getChunks();int minSection=Math.floorDiv(pending.minY,16),maxSectionExclusive=Math.floorDiv(pending.maxY-1,16)+1,baseX=pending.column.getX()*16,baseZ=pending.column.getZ()*16;for(int sectionIndex=0;sectionIndex<sections.length;sectionIndex++){BaseChunk section=sections[sectionIndex];if(section==null||section.isEmpty())continue;int sectionY=minSection+sectionIndex;if(sectionY<minSection||sectionY>=maxSectionExclusive)continue;int baseY=sectionY*16;for(int localY=0;localY<16;localY++)for(int localZ=0;localZ<16;localZ++)for(int localX=0;localX<16;localX++){int globalId=section.getBlockId(localX,localY,localZ);if(globalId<=0)continue;dev.phantom.ac.world.BlockState core=cache.get(globalId);if(core==null){WrappedBlockState state=WrappedBlockState.getByGlobalId(pending.clientVersion,globalId,false);core=state==null||state.getType().isAir()?dev.phantom.ac.world.BlockState.air():toCoreState(state);var existing=cache.putIfAbsent(globalId,core);if(existing!=null)core=existing;}if(!core.isAir())states.put(new dev.phantom.ac.world.Pos(baseX+localX,baseY+localY,baseZ+localZ),core);}}appendPacket(capture,new RawPacket(pending.sequence,pending.receivedNanos,new Packets.ChunkStates(new dev.phantom.ac.world.Chunk(pending.column.getX(),pending.column.getZ()),states)));capture.clientWorld.queueForBarrier(pending.barrierId,new CompensatedClientWorld.Mutation.ChunkSnapshot(new dev.phantom.ac.world.Chunk(pending.column.getX(),pending.column.getZ()),states));capture.decodedChunks.incrementAndGet();}catch(RuntimeException failure){capture.decodeFailures.incrementAndGet();getLogger().log(java.util.logging.Level.WARNING,"[PhantomAC][CHUNK] decode failed for "+capture.playerId,failure);}}

  private void sendWorldTransaction(Player player,Capture capture,short transactionId){capture.clientWorld.openBarrier(transactionId);capture.outstandingTransactions.add(transactionId);capture.reservedTransactions.remove(transactionId);try{var tx=new Packets.WorldTransactionSend(transactionId);appendPacket(capture,new RawPacket(capture.sequence.incrementAndGet(),System.nanoTime(),tx,Packets.CaptureProvenance.fromAdapter("paper-transaction",tx,null)));PacketEvents.getAPI().getPlayerManager().sendPacket(player,new WrapperPlayServerPing((int)transactionId));}catch(RuntimeException failure){capture.outstandingTransactions.remove(transactionId);capture.reservedTransactions.remove(transactionId);capture.clientWorld.abortBarrier(transactionId);getLogger().log(java.util.logging.Level.FINE,"[PhantomAC][WORLD] transaction send failed for "+capture.playerId,failure);}}

  private void captureLiveContext(){for(Capture capture:captures.values()){Player player=getServer().getPlayer(capture.playerId);if(player==null)continue;capture.minY=player.getWorld().getMinHeight();capture.maxY=player.getWorld().getMaxHeight();AttributeInstance movement=player.getAttribute(Attribute.MOVEMENT_SPEED);double movementSpeed=movement==null?0.1:movement.getValue();Map<String,Integer> effects=new LinkedHashMap<>();for(PotionEffect effect:player.getActivePotionEffects())if(effect.getType().getKey()!=null)effects.put(effect.getType().getKey().toString(),effect.getAmplifier());Phase5Mechanics.Pose pose=player.isSleeping()?Phase5Mechanics.Pose.SLEEPING:player.isGliding()?Phase5Mechanics.Pose.FALL_FLYING:player.isSwimming()?Phase5Mechanics.Pose.SWIMMING:player.isSneaking()?Phase5Mechanics.Pose.CROUCHING:Phase5Mechanics.Pose.STANDING;boolean water=false,lava=false,climb=false;org.bukkit.util.BoundingBox box=player.getBoundingBox();int minX=(int)Math.floor(box.getMinX()),maxX=(int)Math.floor(Math.nextDown(box.getMaxX())),minY=(int)Math.floor(box.getMinY()),maxY=(int)Math.floor(Math.nextDown(box.getMaxY())),minZ=(int)Math.floor(box.getMinZ()),maxZ=(int)Math.floor(Math.nextDown(box.getMaxZ()));for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){Material material=player.getWorld().getBlockAt(x,y,z).getType();if(material==Material.WATER||material==Material.BUBBLE_COLUMN)water=true;if(material==Material.LAVA)lava=true;if(material==Material.LADDER||material==Material.VINE||material==Material.SCAFFOLDING)climb=true;}boolean sprint=player.isSprinting(),sneak=player.isSneaking();Phase5Mechanics.MovementEnvironment env=water?Phase5Mechanics.MovementEnvironment.vanillaWater(player.isOnGround(),sprint,sneak,player.isSwimming()):lava?Phase5Mechanics.MovementEnvironment.vanillaLava(player.isOnGround(),sprint,sneak):climb?Phase5Mechanics.MovementEnvironment.vanillaClimbable(player.isOnGround(),sprint,sneak):Phase5Mechanics.MovementEnvironment.dry(player.isOnGround(),sprint,sneak);List<EntityCollisions.EntityBox> entityBoxes=new ArrayList<>();for(Entity entity:player.getWorld().getNearbyEntities(player.getLocation(),4.0,4.0,4.0)){if(entity.getEntityId()==player.getEntityId())continue;org.bukkit.util.BoundingBox eb=entity.getBoundingBox();entityBoxes.add(new EntityCollisions.EntityBox(entity.getEntityId(),new dev.phantom.ac.geometry.BlockBox(eb.getMinX(),eb.getMinY(),eb.getMinZ(),eb.getMaxX(),eb.getMaxY(),eb.getMaxZ())));}entityBoxes.sort(Comparator.comparingInt(EntityCollisions.EntityBox::entityId));Packets.PlayerContext context=new Packets.PlayerContext(player.getGameMode().name().toLowerCase(Locale.ROOT),new dev.phantom.ac.Simulation.Attributes(movementSpeed),effects,pose,env,player.isSleeping(),entityBoxes);appendPacket(capture,new RawPacket(capture.sequence.incrementAndGet(),System.nanoTime(),context,Packets.CaptureProvenance.fromAdapter("paper-live",context,null)));}}

  private void scheduleValidations(){for(Capture capture:captures.values()){if(!capture.validationRunning.compareAndSet(false,true))continue;List<RawPacket> raw=capture.copy();if(raw.isEmpty()){capture.validationRunning.set(false);continue;}Player player=getServer().getPlayer(capture.playerId);if(player==null){capture.validationRunning.set(false);continue;}String playerName=player.getName();long epoch=capture.epochNanos;WorldSnapshot liveWorld=capture.clientWorld.snapshot();getServer().getScheduler().runTaskAsynchronously(this,()->{try{Timeline.Snapshot timeline=Timeline.assign(new Packets.Normalizer().normalize(raw),epoch,50_000_000L);Phase8LiveValidation.Report report=Phase8LiveValidation.analyze(playerName,timeline,validationBudget,Phase7Timing.Config.defaultConfig(),liveWorld);if(Boolean.TRUE.equals(debugPlayers.get(capture.playerId)))logPhase8Timing(playerName,capture,timeline,Phase7Timing.reconstruct(timeline,Phase7Timing.Config.defaultConfig()),report);getServer().getScheduler().runTask(this,()->applyResult(capture,report));}catch(RuntimeException failure){capture.validationRunning.set(false);getLogger().log(java.util.logging.Level.WARNING,"[PhantomAC][PHASE8] validation failed for "+capture.playerId,failure);}});}}
  private void applyResult(Capture capture,Phase8LiveValidation.Report report){
    Phase8MovementValidation.Evidence latestSetbackEvidence=null;
    long latestSetbackTick=Long.MIN_VALUE;

    for(Phase8MovementValidation.Result result:report.results()){
      Phase8MovementValidation.Evidence evidence=result.evidence();
      String key=evidence.replayReference();
      if(!capture.validationGate.accept(key,result.verdict()))continue;

      var accumulated=capture.accumulator.accept(evidence,new Phase8MovementValidation.Config(1,20,alertsEnabled,true));
      accumulated.alert().ifPresent(alert->{
        String message=alert.message();
        getLogger().warning(message);
        if(broadcastAlerts)getServer().broadcastMessage(message);
        else for(Player recipient:getServer().getOnlinePlayers())
          if(recipient.hasPermission("phantom.admin"))recipient.sendMessage(message);
      });

      if(result.verdict()==Phase8MovementValidation.Verdict.IMPOSSIBLE
          && evidence.serverTick()>=latestSetbackTick
          && setbackEnabled(capture.playerId)
          && SetbackPolicy.evaluate(evidence,true,setbacksOnlyExhaustive).allowed()){
        latestSetbackTick=evidence.serverTick();
        latestSetbackEvidence=evidence;
      }
    }

    capture.processedResults+=report.results().size();

    if(latestSetbackEvidence!=null){
      Player player=getServer().getPlayer(capture.playerId);
      if(player!=null){
        var state=latestSetbackEvidence.priorState();
        org.bukkit.Location target=new org.bukkit.Location(
            player.getWorld(),state.position().x(),state.position().y(),state.position().z(),state.yaw(),state.pitch());
        boolean moved=player.teleport(target);
        if(moved){
          getLogger().info("[PhantomAC][PHASE8][SETBACK] player="+player.getName()
              +" tick="+latestSetbackEvidence.serverTick()
              +" target="+state.position()
              +" rule="+latestSetbackEvidence.rule());
        }else{
          getLogger().warning("[PhantomAC][PHASE8][SETBACK] teleport rejected for player="+player.getName());
        }
      }
    }

    capture.validationRunning.set(false);
  }

  private boolean setbackEnabled(UUID playerId){
    return setbackOverrides.getOrDefault(playerId,setbacksEnabled);
  }


  private void logPhase8Timing(String playerName,Capture capture,Timeline.Snapshot timeline,Phase7Timing.Reconstruction timing,Phase8LiveValidation.Report report){
    Phase7Timing.EventTiming latestMovement=timing.frames().stream()
        .map(Phase7Timing.Frame::timing)
        .filter(t->t.kind()==Phase7Timing.EventKind.MOVEMENT)
        .reduce((first,second)->second)
        .orElse(null);
    Phase8MovementValidation.Result latestResult=report.results().isEmpty()?null:report.results().getLast();
    String movementSummary=latestMovement==null
        ?"none"
        :"seq="+latestMovement.sequence()
          +" serverTick="+latestMovement.serverTick()
          +" packetTicks="+latestMovement.packetGenerationClientTicks()
          +" simulationTicks="+latestMovement.simulationClientTicks()
          +" source="+latestMovement.source()
          +" uncertain="+latestMovement.uncertain()
          +" reasons="+latestMovement.reasons();
    String resultSummary=latestResult==null
        ?"none"
        :latestResult.verdict()+"/candidates="+latestResult.evidence().reachableCandidateCount()
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
        +" chunks={seen="+capture.chunkPackets.get()+",decoded="+capture.decodedChunks.get()+",pending="+capture.chunkQueue.size()+",fail="+capture.decodeFailures.get()+",dropped="+capture.droppedChunks.get()+",visible="+capture.clientWorld.visibleChunkCount()+"}"
        +" tickIntegrity={endTicks="+capture.clientTickTracker.endTickCount()+",movementInCurrentTick="+capture.clientTickTracker.movementPacketsInCurrentTick()+",multiMovementPackets="+capture.multiMovementPackets.get()+"}"
        +" syncReasons="+timing.finalState().reasons());
  }

  private void record(Player player,Packets.Packet packet){Capture capture=captures.computeIfAbsent(player.getUniqueId(),ignored->new Capture(player.getUniqueId(),System.nanoTime()));appendPacket(capture,new RawPacket(capture.sequence.incrementAndGet(),System.nanoTime(),packet));}
  private void recordBlockState(Player player,dev.phantom.ac.world.Pos position,dev.phantom.ac.world.BlockState state){record(player,state.isUnsupported()?new Packets.UnsupportedBlockStateChange(position,state):new Packets.BlockStateChange(position,state));}
  private static void appendPacket(Capture capture,RawPacket packet){synchronized(capture.packets){while(capture.packets.size()>=MAX_CAPTURE_PACKETS)capture.packets.remove(0);capture.packets.add(packet);}}
  private static Vec3 vector(double x,double y,double z){return new Vec3(x,y,z);}
  private static dev.phantom.ac.world.BlockState toCoreState(WrappedBlockState state){if(state==null||state.getType().isAir())return dev.phantom.ac.world.BlockState.air();String name=state.getType().getName();Map<String,String> properties=new LinkedHashMap<>();putEnum(properties,"type",state.getData(StateValue.TYPE));putEnum(properties,"facing",state.getData(StateValue.FACING));putEnum(properties,"half",state.getData(StateValue.HALF));putEnum(properties,"shape",state.getData(StateValue.SHAPE));putEnum(properties,"part",state.getData(StateValue.PART));putEnum(properties,"hinge",state.getData(StateValue.HINGE));putNumber(properties,"layers",state.getData(StateValue.LAYERS));putNumber(properties,"level",state.getData(StateValue.LEVEL));putNumber(properties,"candles",state.getData(StateValue.CANDLES));putNumber(properties,"pickles",state.getData(StateValue.PICKLES));putBoolean(properties,"waterlogged",state.getData(StateValue.WATERLOGGED));putBoolean(properties,"open",state.getData(StateValue.OPEN));putBoolean(properties,"powered",state.getData(StateValue.POWERED));putBoolean(properties,"up",state.getData(StateValue.UP));putBoolean(properties,"north",state.getData(StateValue.NORTH));putBoolean(properties,"south",state.getData(StateValue.SOUTH));putBoolean(properties,"west",state.getData(StateValue.WEST));putBoolean(properties,"east",state.getData(StateValue.EAST));putBoolean(properties,"lit",state.getData(StateValue.LIT));if(isFence(name)&&!hasAll(properties,"waterlogged","north","south","west","east"))return dev.phantom.ac.world.BlockState.unsupported(name);if(isWall(name)&&!hasAll(properties,"waterlogged","up","north","south","west","east"))return dev.phantom.ac.world.BlockState.unsupported(name);if(isPane(name)&&!hasAll(properties,"waterlogged","north","south","west","east"))return dev.phantom.ac.world.BlockState.unsupported(name);return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(name,properties);}
  private static boolean isFence(String name){return name.endsWith("_fence")&&!name.endsWith("_fence_gate");}private static boolean isWall(String name){return name.endsWith("_wall");}private static boolean isPane(String name){return name.endsWith("_pane");}private static boolean hasAll(Map<String,String> map,String... keys){for(String key:keys)if(!map.containsKey(key))return false;return true;}private static void putEnum(Map<String,String> p,String k,Object v){if(v!=null)p.put(k,v.toString().toLowerCase(Locale.ROOT));}private static void putNumber(Map<String,String> p,String k,Object v){if(v instanceof Number n)p.put(k,Integer.toString(n.intValue()));}private static void putBoolean(Map<String,String> p,String k,Object v){if(v instanceof Boolean b)p.put(k,Boolean.toString(b));}
  private record PendingChunk(long sequence,long receivedNanos,Column column,int minY,int maxY,ClientVersion clientVersion,short barrierId){}
  private static final class Capture{final UUID playerId;final long epochNanos;final AtomicLong sequence=new AtomicLong();final AtomicLong chunkPackets=new AtomicLong(),decodedChunks=new AtomicLong(),decodeFailures=new AtomicLong(),droppedChunks=new AtomicLong(),multiMovementPackets=new AtomicLong();final Queue<PendingChunk> chunkQueue=new ConcurrentLinkedQueue<>();final List<RawPacket> packets=new ArrayList<>();final ClientTickTracker clientTickTracker=new ClientTickTracker();final AtomicBoolean validationRunning=new AtomicBoolean();final CompensatedClientWorld clientWorld=new CompensatedClientWorld(Contracts.TARGET_VERSION,-64,319);final Set<Short> outstandingTransactions=ConcurrentHashMap.newKeySet(),reservedTransactions=ConcurrentHashMap.newKeySet();final AtomicLong transactionCounter=new AtomicLong(1);Phase8MovementValidation.Accumulator accumulator=Phase8MovementValidation.Accumulator.empty();final ValidationResultGate validationGate=new ValidationResultGate();int processedResults;volatile int minY=-64,maxY=319;Capture(UUID id,long epoch){playerId=id;epochNanos=epoch;}short nextWorldTransaction(){while(true){int raw=(int)(transactionCounter.getAndIncrement()&0x7FFF);if(raw==0)continue;short id=(short)-raw;if(outstandingTransactions.contains(id)||reservedTransactions.contains(id))continue;if(reservedTransactions.add(id))return id;}}List<RawPacket> copy(){synchronized(packets){int start=Math.max(0,packets.size()-MAX_VALIDATION_PACKETS);return List.copyOf(packets.subList(start,packets.size()));}}}
}
