package dev.phantom.ac;

import dev.phantom.ac.Packets.*;
import java.io.Serializable;
import java.util.*;

/** Phase 7 client/server clock, network, chronology, and synchronization model. */
public final class Phase7Timing {
  private Phase7Timing() {}
  public enum Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT, UNKNOWN }
  public enum EventKind { MOVEMENT, INPUT, CLIENT_TICK_END, TELEPORT_CORRECTION, TELEPORT_ACK, VELOCITY, WORLD, EFFECT, GAMEMODE, OTHER }
  public enum TimingSource { EXPLICIT_CLIENT_TICK, LATENCY_BOUNDED, RELATIVE_CLIENT_ANCHOR, SERVER_CAPTURE_ONLY }
  public enum SyncStatus { SYNCHRONIZED, PARTIALLY_SYNCHRONIZED, AMBIGUOUS, RECOVERING, UNKNOWN }
  public enum WindowKind { TELEPORT, VELOCITY, ACKNOWLEDGEMENT, WORLD_UPDATE, PACKET_GAP, SERVER_TICK_GAP, REORDERING, DUPLICATE, MULTIPLE_MOVEMENT_IN_CLIENT_TICK, RECOVERY, STARTUP }
  public enum Consistency { CONSISTENT, UNCERTAIN, INCONSISTENT }

  public record Range(long min,long max) implements Serializable {
    public Range { if(min>max) throw new IllegalArgumentException("range min must not exceed max"); }
    public static Range exact(long v){ return new Range(v,v); }
    public long width(){ return max-min; }
    public boolean isExact(){ return min==max; }
    public boolean contains(long v){ return v>=min&&v<=max; }
    public static Range empty(){ return new Range(0,0); }
  }
  public record TimeRange(long minNanos,long maxNanos) implements Serializable {
    public TimeRange { if(minNanos<0||maxNanos<minNanos) throw new IllegalArgumentException("invalid time range"); }
    public static TimeRange exact(long n){ return new TimeRange(n,n); }
    public boolean isExact(){ return minNanos==maxNanos; }
  }
  public record LatencyBounds(long minNanos,long maxNanos) implements Serializable {
    public LatencyBounds { if(minNanos<0||maxNanos<minNanos) throw new IllegalArgumentException("invalid latency bounds"); }
    public long jitterNanos(){ return maxNanos-minNanos; }
    public boolean isExact(){ return minNanos==maxNanos; }
  }
  public record TickDelayBounds(long minTicks,long maxTicks) implements Serializable {
    public TickDelayBounds { if(minTicks<0||maxTicks<minTicks) throw new IllegalArgumentException("invalid tick delay bounds"); }
  }

  /** All timing assumptions are immutable input data; reconstruction has no hidden timing constants. */
  public record Config(long serverTickNanos,long clientTickMinNanos,long clientTickMaxNanos,
                       LatencyBounds upstreamLatency,LatencyBounds downstreamLatency,
                       TickDelayBounds inputToSimulation,TickDelayBounds simulationToPacket,
                       long packetGapThresholdNanos,int recoveryStableEvents,int maxTimingCandidates) implements Serializable {
    public Config {
      if(serverTickNanos<=0) throw new IllegalArgumentException("server tick duration must be positive");
      if(clientTickMinNanos<=0||clientTickMaxNanos<clientTickMinNanos) throw new IllegalArgumentException("invalid client tick bounds");
      Objects.requireNonNull(upstreamLatency); Objects.requireNonNull(downstreamLatency); Objects.requireNonNull(inputToSimulation); Objects.requireNonNull(simulationToPacket);
      if(packetGapThresholdNanos<=0||recoveryStableEvents<1||maxTimingCandidates<1) throw new IllegalArgumentException("invalid timing configuration");
    }
    public static Config defaultConfig(){
      long tick=50_000_000L;
      return new Config(tick,tick,tick,new LatencyBounds(0,100_000_000L),new LatencyBounds(0,100_000_000L),new TickDelayBounds(0,1),new TickDelayBounds(0,1),250_000_000L,3,128);
    }
  }

  public record SynchronizationWindow(WindowKind kind,long firstServerTick,long lastServerTick,Range possibleClientTicks,String reason,long triggerSequence) implements Serializable {
    public SynchronizationWindow {
      Objects.requireNonNull(kind); Objects.requireNonNull(possibleClientTicks);
      if(firstServerTick<0||lastServerTick<firstServerTick) throw new IllegalArgumentException("invalid server window");
      if(reason==null||reason.isBlank()||triggerSequence<0) throw new IllegalArgumentException("invalid synchronization window");
    }
  }
  public record SynchronizationState(SyncStatus status,Range possibleClientTicks,TimeRange observedLatency,OptionalInt pendingTeleportId,int stableEvents,long synchronizationEpoch,List<SynchronizationWindow> activeWindows,List<String> reasons) implements Serializable {
    public SynchronizationState {
      Objects.requireNonNull(status); Objects.requireNonNull(possibleClientTicks); Objects.requireNonNull(observedLatency); Objects.requireNonNull(pendingTeleportId);
      if(stableEvents<0||synchronizationEpoch<0) throw new IllegalArgumentException("invalid synchronization state");
      activeWindows=List.copyOf(activeWindows); reasons=List.copyOf(reasons);
    }
    public boolean synchronizedEnough(){ return status==SyncStatus.SYNCHRONIZED; }
    public static SynchronizationState initial(){ return new SynchronizationState(SyncStatus.UNKNOWN,Range.exact(0),new TimeRange(0,Long.MAX_VALUE),OptionalInt.empty(),0,0,List.of(),List.of("no client/server synchronization anchor exists")); }
  }
  public record EventTiming(int timelineIndex,long sequence,long serverTick,long captureNanos,Direction direction,EventKind kind,TimeRange packetGenerationNanos,TimeRange clientProcessingNanos,Range packetGenerationClientTicks,Range simulationClientTicks,Range inputClientTicks,OptionalLong explicitClientTick,TimingSource source,boolean uncertain,List<SynchronizationWindow> windows,List<String> reasons) implements Serializable {
    public EventTiming {
      if(timelineIndex<0||sequence<0||serverTick<0||captureNanos<0) throw new IllegalArgumentException("invalid timing metadata");
      Objects.requireNonNull(direction); Objects.requireNonNull(kind); Objects.requireNonNull(packetGenerationNanos); Objects.requireNonNull(clientProcessingNanos); Objects.requireNonNull(packetGenerationClientTicks); Objects.requireNonNull(simulationClientTicks); Objects.requireNonNull(inputClientTicks); Objects.requireNonNull(explicitClientTick); Objects.requireNonNull(source);
      windows=List.copyOf(windows); reasons=List.copyOf(reasons);
    }
  }
  public record Frame(EventTiming timing,SynchronizationState before,SynchronizationState after) implements Serializable { public Frame{Objects.requireNonNull(timing);Objects.requireNonNull(before);Objects.requireNonNull(after);} }
  public record Reconstruction(String modelVersion,Config config,OptionalLong anchorSequence,Range anchorClientTick,TimeRange anchorGenerationNanos,List<Frame> frames,Consistency consistency,List<String> consistencyReasons) implements Serializable {
    public Reconstruction { Contracts.requireTargetVersion(modelVersion); Objects.requireNonNull(config); Objects.requireNonNull(anchorSequence); Objects.requireNonNull(anchorClientTick); Objects.requireNonNull(anchorGenerationNanos); frames=List.copyOf(frames); Objects.requireNonNull(consistency); consistencyReasons=List.copyOf(consistencyReasons); }
    public Map<Long,EventTiming> bySequence(){Map<Long,EventTiming> out=new LinkedHashMap<>();for(Frame f:frames)out.put(f.timing().sequence(),f.timing());return Map.copyOf(out);}
    public Optional<EventTiming> timingFor(long sequence){return frames.stream().map(Frame::timing).filter(t->t.sequence()==sequence).findFirst();}
    public SynchronizationState finalState(){return frames.isEmpty()?SynchronizationState.initial():frames.getLast().after();}
    public String canonicalText(){StringBuilder b=new StringBuilder("phase7-reconstruction-v1\n");b.append("model=").append(modelVersion).append('\n').append("config=").append(config).append('\n').append("anchorSequence=").append(anchorSequence).append('\n').append("anchorClientTick=").append(anchorClientTick).append('\n').append("anchorGenerationNanos=").append(anchorGenerationNanos).append('\n').append("consistency=").append(consistency).append(' ').append(consistencyReasons).append('\n');for(Frame f:frames){EventTiming t=f.timing();b.append("event=").append(t.timelineIndex()).append(" seq=").append(t.sequence()).append(" serverTick=").append(t.serverTick()).append(" direction=").append(t.direction()).append(" kind=").append(t.kind()).append(" generation=").append(t.packetGenerationNanos()).append(" processing=").append(t.clientProcessingNanos()).append(" packetTicks=").append(t.packetGenerationClientTicks()).append(" simulationTicks=").append(t.simulationClientTicks()).append(" inputTicks=").append(t.inputClientTicks()).append(" explicit=").append(t.explicitClientTick()).append(" source=").append(t.source()).append(" uncertain=").append(t.uncertain()).append(" windows=").append(formatWindows(t.windows())).append(" reasons=").append(t.reasons()).append('\n');b.append("before=").append(formatSync(f.before())).append('\n').append("after=").append(formatSync(f.after())).append('\n');}return b.toString();}
  }

  public static Reconstruction reconstruct(Timeline.Snapshot timeline){return reconstruct(timeline,Config.defaultConfig());}
  public static Reconstruction reconstruct(Timeline.Snapshot timeline,Config config){
    Objects.requireNonNull(timeline); Objects.requireNonNull(config);
    SynchronizationState sync=SynchronizationState.initial(); List<Frame> frames=new ArrayList<>(); OptionalLong anchorSequence=OptionalLong.empty(); Range anchorTick=Range.exact(0); TimeRange anchorGeneration=TimeRange.exact(Math.max(0,timeline.metadata().captureEpochNanos())); boolean anchorSet=false; Consistency consistency=Consistency.CONSISTENT; List<String> consistencyReasons=new ArrayList<>(); long previousCapture=-1,previousServerTick=-1; long clientBoundaryTick=0; boolean clientBoundarySeen=false; int movementPacketsInClientInterval=0; int index=0;
    for(Timeline.Event event:timeline.events()){
      NormalizedPacket normalized=event.packet(); Packet packet=normalized.packet(); Direction direction=direction(packet); EventKind kind=kind(packet); long capture=normalized.receivedNanos(); TimingBounds bounds=timingBounds(packet,capture,config); OptionalLong explicit=packet instanceof Move m&&m.clientTick()!=null?OptionalLong.of(m.clientTick()):OptionalLong.empty(); boolean duplicate=normalized.flags().contains(PacketFlag.DUPLICATE); SynchronizationState before=sync; if(kind==EventKind.CLIENT_TICK_END&&!duplicate){clientBoundaryTick=safeAdd(clientBoundaryTick,1);clientBoundarySeen=true;movementPacketsInClientInterval=0;}
      if(kind==EventKind.MOVEMENT&&!duplicate)movementPacketsInClientInterval++;
      if(!anchorSet&&direction==Direction.CLIENT_TO_SERVER&&!duplicate&&kind!=EventKind.CLIENT_TICK_END){anchorSet=true;anchorSequence=OptionalLong.of(normalized.sequence());anchorGeneration=bounds.packetGenerationNanos;anchorTick=explicit.isPresent()?Range.exact(explicit.getAsLong()):(clientBoundarySeen?Range.exact(clientBoundaryTick):Range.exact(0));sync=new SynchronizationState(explicit.isPresent()?SyncStatus.SYNCHRONIZED:SyncStatus.PARTIALLY_SYNCHRONIZED,anchorTick,latencyRange(bounds.latency),OptionalInt.empty(),1,1,List.of(new SynchronizationWindow(WindowKind.STARTUP,event.serverTick(),event.serverTick(),anchorTick,"first client event anchors relative client chronology",normalized.sequence())),List.of(explicit.isPresent()?"explicit client movement tick establishes the clock anchor":clientBoundarySeen?"client tick-end boundaries establish a relative chronology anchor":"first client event anchors relative chronology; absolute client clock origin is unknown"));}
      Range packetTicks; TimingSource source;
      if(explicit.isPresent()){packetTicks=Range.exact(explicit.getAsLong());source=TimingSource.EXPLICIT_CLIENT_TICK;
        // An explicit CLIENT_TICK_END-derived movement tick is stronger chronology
        // evidence than a wall-clock estimate derived from an independently bounded
        // network delay. Only compare the two as a hard consistency check when the
        // network and client tick clocks are both exact; otherwise retain the explicit
        // tick and report any network uncertainty separately.
        if(anchorSet&&normalized.sequence()!=anchorSequence.orElse(-1)
            && bounds.latency.isExact()
            && config.clientTickMinNanos()==config.clientTickMaxNanos()
            && !isWithinDerivedWindow(packetTicks,bounds.clientEventNanos,anchorGeneration,anchorTick,config)){
          consistency=Consistency.INCONSISTENT;
          consistencyReasons.add("explicit client tick "+explicit.getAsLong()+" is outside timing bounds; exact wall-clock comparison failed for sequence "+normalized.sequence());
        }}
      else if(kind==EventKind.CLIENT_TICK_END&&clientBoundarySeen){packetTicks=Range.exact(clientBoundaryTick);source=TimingSource.RELATIVE_CLIENT_ANCHOR;}
      else if(clientBoundarySeen&&direction==Direction.CLIENT_TO_SERVER){packetTicks=Range.exact(clientBoundaryTick);source=TimingSource.RELATIVE_CLIENT_ANCHOR;}
      else if(anchorSet&&direction!=Direction.UNKNOWN){TimeRange clockTime=direction==Direction.CLIENT_TO_SERVER?bounds.packetGenerationNanos:bounds.clientProcessingNanos;packetTicks=relativeClientTicks(clockTime,anchorGeneration,anchorTick,config);source=TimingSource.RELATIVE_CLIENT_ANCHOR;}
      else{packetTicks=Range.empty();source=TimingSource.SERVER_CAPTURE_ONLY;}
      Range inputTicks=kind==EventKind.INPUT?nonNegative(packetTicks):Range.empty(); Range simulationTicks;
      if(kind==EventKind.INPUT)simulationTicks=nonNegative(shiftTicks(packetTicks,config.inputToSimulation,false));
      else if(kind==EventKind.MOVEMENT&&direction==Direction.CLIENT_TO_SERVER)simulationTicks=nonNegative(shiftTicks(packetTicks,config.simulationToPacket,true));
      else simulationTicks=nonNegative(packetTicks);
      List<SynchronizationWindow> windows=new ArrayList<>(); List<String> reasons=new ArrayList<>(bounds.reasons); boolean timingTickUncertain=!packetTicks.isExact(); boolean networkTimingUncertain=(direction==Direction.SERVER_TO_CLIENT||source==TimingSource.SERVER_CAPTURE_ONLY)&&!bounds.latency.isExact(); boolean uncertain=source==TimingSource.SERVER_CAPTURE_ONLY||timingTickUncertain||networkTimingUncertain;
      if(previousCapture>=0&&capture-previousCapture>config.packetGapThresholdNanos){windows.add(new SynchronizationWindow(WindowKind.PACKET_GAP,previousServerTick<0?event.serverTick():previousServerTick,event.serverTick(),packetTicks,"capture gap exceeded threshold; missing observations are not treated as inactivity",normalized.sequence()));uncertain=true;reasons.add("observation gap does not imply client inactivity");sync=enterRecovery(sync,windows.getLast());}
      if(previousServerTick>=0&&event.serverTick()>previousServerTick+1){windows.add(new SynchronizationWindow(WindowKind.SERVER_TICK_GAP,previousServerTick,event.serverTick(),packetTicks,"unobserved server ticks exist between observations",normalized.sequence()));uncertain=true;reasons.add("server tick interval contains unobserved ticks");}
      if(duplicate){windows.add(new SynchronizationWindow(WindowKind.DUPLICATE,event.serverTick(),event.serverTick(),packetTicks,"duplicate capture sequence is retained as evidence but has no semantic effect",normalized.sequence()));uncertain=true;reasons.add("duplicate capture sequence");}
      if(normalized.flags().contains(PacketFlag.OUT_OF_ORDER)){windows.add(new SynchronizationWindow(WindowKind.REORDERING,event.serverTick(),event.serverTick(),packetTicks,"arrival chronology is preserved and reordering remains explicit",normalized.sequence()));uncertain=true;reasons.add("out-of-order capture sequence");}
      if(normalized.flags().contains(PacketFlag.SEQUENCE_GAP)){uncertain=true;reasons.add("capture sequence gap; absent records remain unknown");}
      if(kind==EventKind.MOVEMENT&&!duplicate&&clientBoundarySeen&&explicit.isEmpty()&&movementPacketsInClientInterval>1){
        windows.add(new SynchronizationWindow(WindowKind.MULTIPLE_MOVEMENT_IN_CLIENT_TICK,event.serverTick(),event.serverTick(),packetTicks,
            "multiple movement packets arrived within one client-tick interval; this observation is not treated as a separate exact physics tick",
            normalized.sequence()));
        uncertain=true;
        reasons.add("multiple movement packets in the same client-tick interval");
      }

      // Duplicate records never trigger a second semantic correction, velocity, world,
      // or acknowledgement event. Their chronology remains visible only as uncertainty.
      if(!duplicate){
        if(packet instanceof Teleport teleport){
          windows.add(new SynchronizationWindow(WindowKind.TELEPORT,event.serverTick(),safeAdd(event.serverTick(),config.recoveryStableEvents),packetTicks,"server correction starts a new synchronization epoch",normalized.sequence()));
          sync=new SynchronizationState(SyncStatus.RECOVERING,packetTicks,latencyRange(bounds.latency),OptionalInt.of(teleport.id()),0,safeAdd(sync.synchronizationEpoch(),1),append(sync.activeWindows(),windows.getLast()),List.of("teleport/correction boundary entered"));
          uncertain=true; reasons.add("pre-correction predictions are not authoritative after correction");
        } else if(packet instanceof TeleportConfirm confirm){
          windows.add(new SynchronizationWindow(WindowKind.ACKNOWLEDGEMENT,event.serverTick(),safeAdd(event.serverTick(),1),packetTicks,"teleport acknowledgement is asynchronous and can be delayed or missing",normalized.sequence()));
          if(sync.pendingTeleportId().isPresent()&&sync.pendingTeleportId().getAsInt()==confirm.id()){
            sync=new SynchronizationState(SyncStatus.RECOVERING,packetTicks,sync.observedLatency(),OptionalInt.empty(),0,sync.synchronizationEpoch(),append(sync.activeWindows(),windows.getLast()),List.of("matching correction acknowledgement received; stable post-correction movement observations are still required"));
            uncertain=true;
          } else {
            sync=new SynchronizationState(SyncStatus.AMBIGUOUS,packetTicks,sync.observedLatency(),sync.pendingTeleportId(),0,sync.synchronizationEpoch(),append(sync.activeWindows(),windows.getLast()),List.of("unexpected, delayed, or duplicate teleport acknowledgement"));
            uncertain=true; reasons.add("teleport acknowledgement did not match the pending correction");
          }
        } else if(packet instanceof Velocity){
          windows.add(new SynchronizationWindow(WindowKind.VELOCITY,event.serverTick(),safeAdd(event.serverTick(),config.recoveryStableEvents),packetTicks,"velocity application precedes subsequent client simulation; packet arrival is not movement time",normalized.sequence()));
          sync=withWindow(sync,windows.getLast()); uncertain=true;
        } else if(kind==EventKind.WORLD){
          windows.add(new SynchronizationWindow(WindowKind.WORLD_UPDATE,event.serverTick(),safeAdd(event.serverTick(),1),packetTicks,"world data becomes available to the client after network transit, not server capture",normalized.sequence()));
          // A world update is still a synchronization hazard when the subsequent
          // movement has no explicit client tick. An explicit client movement tick
          // can recover exact chronology without relying on this server-send time.
          sync=withWindow(sync,windows.getLast()); uncertain=true;
        }
        if(sync.status()==SyncStatus.RECOVERING&&direction==Direction.CLIENT_TO_SERVER&&!uncertain&&kind==EventKind.MOVEMENT&&sync.pendingTeleportId().isEmpty()){
          int stable=sync.stableEvents()+1;
          if(stable>=config.recoveryStableEvents) sync=new SynchronizationState(SyncStatus.SYNCHRONIZED,packetTicks,sync.observedLatency(),OptionalInt.empty(),stable,sync.synchronizationEpoch(),sync.activeWindows(),List.of("synchronization re-established after stable observations"));
          else sync=new SynchronizationState(SyncStatus.RECOVERING,packetTicks,sync.observedLatency(),OptionalInt.empty(),stable,sync.synchronizationEpoch(),sync.activeWindows(),List.of("recovery requires additional stable movement observations"));
        } else if(sync.status()!=SyncStatus.RECOVERING&&direction==Direction.CLIENT_TO_SERVER&&!uncertain&&kind!=EventKind.CLIENT_TICK_END){
          int stable=sync.stableEvents()+1; sync=new SynchronizationState(stable>=2?SyncStatus.SYNCHRONIZED:SyncStatus.PARTIALLY_SYNCHRONIZED,packetTicks,latencyRange(bounds.latency),sync.pendingTeleportId(),stable,sync.synchronizationEpoch(),sync.activeWindows(),List.of("clean client observation incorporated"));
        } else if(uncertain&&sync.status()==SyncStatus.SYNCHRONIZED&&affectsMovementSynchronization(kind)){
          sync=new SynchronizationState(SyncStatus.AMBIGUOUS,packetTicks,sync.observedLatency(),sync.pendingTeleportId(),0,sync.synchronizationEpoch(),sync.activeWindows(),List.of("timing ambiguity prevents strong synchronization"));
        }
      } else if(uncertain&&sync.status()==SyncStatus.SYNCHRONIZED){
        sync=new SynchronizationState(SyncStatus.AMBIGUOUS,sync.possibleClientTicks(),sync.observedLatency(),sync.pendingTeleportId(),0,sync.synchronizationEpoch(),sync.activeWindows(),List.of("duplicate capture prevents strong semantic synchronization"));
      }

      boolean syncUncertainForEvent=affectsMovementSynchronization(kind)
          &&sync.status()!=SyncStatus.SYNCHRONIZED
          &&!(kind==EventKind.MOVEMENT&&explicit.isPresent());
      EventTiming timing=new EventTiming(index++,normalized.sequence(),event.serverTick(),capture,direction,kind,bounds.packetGenerationNanos,bounds.clientProcessingNanos,packetTicks,simulationTicks,inputTicks,explicit,source,uncertain||syncUncertainForEvent,windows,reasons);frames.add(new Frame(timing,before,sync));previousCapture=capture;previousServerTick=event.serverTick();
    }
    if(consistency!=Consistency.INCONSISTENT&&frames.stream().anyMatch(f->f.timing().uncertain())){consistency=Consistency.UNCERTAIN;consistencyReasons.add("one or more events have bounded but non-exact timing");}
    return new Reconstruction(Contracts.TARGET_VERSION,config,anchorSequence,anchorTick,anchorGeneration,frames,consistency,consistencyReasons);
  }
  public static Validation.SyncWindow toPhase6Window(EventTiming timing){Objects.requireNonNull(timing);Range r=timing.simulationClientTicks();return new Validation.SyncWindow(Math.max(0,r.min()),Math.max(0,r.max()),timing.uncertain(),timing.reasons());}
  public static boolean worldTimingExhaustive(Reconstruction reconstruction,long sequence){Optional<EventTiming> timing=reconstruction.timingFor(sequence);return timing.isPresent()&&!timing.get().uncertain()&&timing.get().kind()==EventKind.WORLD;}

  private record TimingBounds(TimeRange packetGenerationNanos,TimeRange clientProcessingNanos,TimeRange clientEventNanos,LatencyBounds latency,boolean uncertain,List<String> reasons){}
  private static TimingBounds timingBounds(Packet packet,long capture,Config config){Direction direction=direction(packet);if(direction==Direction.CLIENT_TO_SERVER){TimeRange generation=new TimeRange(Math.max(0,safeAdd(capture,-config.upstreamLatency.maxNanos())),Math.max(0,safeAdd(capture,-config.upstreamLatency.minNanos())));return new TimingBounds(generation,generation,generation,config.upstreamLatency,!config.upstreamLatency.isExact(),List.of("server arrival is observed; client packet generation is bounded by upstream latency"));}if(direction==Direction.SERVER_TO_CLIENT){TimeRange processing=new TimeRange(safeAdd(capture,config.downstreamLatency.minNanos()),safeAdd(capture,config.downstreamLatency.maxNanos()));return new TimingBounds(TimeRange.exact(capture),processing,processing,config.downstreamLatency,!config.downstreamLatency.isExact(),List.of("server send capture is observed; client processing time is bounded by downstream latency"));}return new TimingBounds(TimeRange.exact(capture),TimeRange.exact(capture),TimeRange.exact(capture),new LatencyBounds(0,0),true,List.of("packet direction is unknown"));}
  private static Range relativeClientTicks(TimeRange event,TimeRange anchor,Range anchorTick,Config config){long deltaMin=safeAdd(event.minNanos(),-anchor.maxNanos());long deltaMax=safeAdd(event.maxNanos(),-anchor.minNanos());long min=Math.floorDiv(deltaMin,config.clientTickMaxNanos());long max=Math.floorDiv(deltaMax,config.clientTickMinNanos());return new Range(safeAdd(anchorTick.min(),min),safeAdd(anchorTick.max(),max));}
  private static Range shiftTicks(Range ticks,TickDelayBounds delay,boolean subtract){return subtract?new Range(safeAdd(ticks.min(),-delay.maxTicks()),safeAdd(ticks.max(),-delay.minTicks())):new Range(safeAdd(ticks.min(),delay.minTicks()),safeAdd(ticks.max(),delay.maxTicks()));}
  private static Range nonNegative(Range range){return new Range(Math.max(0,range.min()),Math.max(0,range.max()));}
  private static boolean isWithinDerivedWindow(Range explicit,TimeRange event,TimeRange anchor,Range anchorTick,Config config){Range derived=relativeClientTicks(event,anchor,anchorTick,config);return derived.contains(explicit.min())&&derived.contains(explicit.max());}
  private static TimeRange latencyRange(LatencyBounds b){return new TimeRange(b.minNanos(),b.maxNanos);}
  private static List<SynchronizationWindow> append(List<SynchronizationWindow> current,SynchronizationWindow window){List<SynchronizationWindow> result=new ArrayList<>(current);result.add(window);if(result.size()>16)result=result.subList(result.size()-16,result.size());return List.copyOf(result);}
  private static SynchronizationState enterRecovery(SynchronizationState old,SynchronizationWindow window){return new SynchronizationState(SyncStatus.RECOVERING,window.possibleClientTicks(),old.observedLatency(),old.pendingTeleportId(),0,old.synchronizationEpoch(),append(old.activeWindows(),window),List.of("synchronization recovery entered"));}
  private static SynchronizationState withWindow(SynchronizationState old,SynchronizationWindow window){SyncStatus status=old.status()==SyncStatus.RECOVERING?SyncStatus.RECOVERING:SyncStatus.AMBIGUOUS;return new SynchronizationState(status,old.possibleClientTicks(),old.observedLatency(),old.pendingTeleportId(),0,old.synchronizationEpoch(),append(old.activeWindows(),window),List.of(window.reason()));}
  /**
   * Only events that can change the client movement chronology or the causal state
   * used by prediction are allowed to downgrade synchronization. Server-side
   * observation metadata and compensated world delivery have their own uncertainty
   * and must not make an exact client movement tick ambiguous.
   */
  private static boolean affectsMovementSynchronization(EventKind kind){
    return switch(kind){
      case MOVEMENT,INPUT,TELEPORT_CORRECTION,TELEPORT_ACK,VELOCITY -> true;
      case CLIENT_TICK_END,WORLD,EFFECT,GAMEMODE,OTHER -> false;
    };
  }

  private static Direction direction(Packet packet){if(packet instanceof Move||packet instanceof ClientInput||packet instanceof ClientTickEnd||packet instanceof TeleportConfirm)return Direction.CLIENT_TO_SERVER;if(packet instanceof Teleport||packet instanceof Velocity||packet instanceof Effect||packet instanceof Gamemode||packet.mutatesWorld())return Direction.SERVER_TO_CLIENT;return Direction.UNKNOWN;}
  private static EventKind kind(Packet packet){if(packet instanceof Move)return EventKind.MOVEMENT;if(packet instanceof ClientInput)return EventKind.INPUT;if(packet instanceof ClientTickEnd)return EventKind.CLIENT_TICK_END;if(packet instanceof Teleport)return EventKind.TELEPORT_CORRECTION;if(packet instanceof TeleportConfirm)return EventKind.TELEPORT_ACK;if(packet instanceof Velocity)return EventKind.VELOCITY;if(packet instanceof ChunkData||packet instanceof ChunkStates||packet instanceof ChunkUnload||packet instanceof BlockChange||packet instanceof BlockStateChange||packet instanceof UnsupportedBlockStateChange)return EventKind.WORLD;if(packet instanceof Effect)return EventKind.EFFECT;if(packet instanceof Gamemode)return EventKind.GAMEMODE;return EventKind.OTHER;}
  private static String formatSync(SynchronizationState s){return s.status()+" ticks="+s.possibleClientTicks()+" latency="+s.observedLatency()+" pendingTeleport="+s.pendingTeleportId()+" stable="+s.stableEvents()+" epoch="+s.synchronizationEpoch()+" windows="+formatWindows(s.activeWindows())+" reasons="+s.reasons();}
  private static String formatWindows(List<SynchronizationWindow> ws){List<String> out=new ArrayList<>();for(SynchronizationWindow w:ws)out.add(w.kind()+"@"+w.firstServerTick()+".."+w.lastServerTick()+" client="+w.possibleClientTicks()+" seq="+w.triggerSequence());return out.toString();}
  private static long safeAdd(long a,long b){try{return Math.addExact(a,b);}catch(ArithmeticException e){return b>=0?Long.MAX_VALUE:Long.MIN_VALUE;}}
}
