package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

/** Replayable Phase 7 history joining packet timing, player-state history, synchronization, and world references. */
public final class Phase7History {
  private Phase7History() {}

  public record WorldReference(long serverTick, Phase7Timing.Range possibleClientTicks, long triggeringSequence, boolean timingExhaustive, String description) implements Serializable {
    public WorldReference {
      if(serverTick<0||triggeringSequence<0)throw new IllegalArgumentException("invalid world reference");
      Objects.requireNonNull(possibleClientTicks);if(description==null||description.isBlank())throw new IllegalArgumentException("world description required");
    }
  }

  public record EventFrame(int index, Timeline.Event event, Phase7Timing.EventTiming timing,
                           State.StateFrame playerState, WorldReference worldReference,
                           Phase7Timing.SynchronizationState synchronizationBefore,
                           Phase7Timing.SynchronizationState synchronizationAfter) implements Serializable {
    public EventFrame {
      if(index<0)throw new IllegalArgumentException("index must be non-negative");
      Objects.requireNonNull(event);Objects.requireNonNull(timing);Objects.requireNonNull(playerState);Objects.requireNonNull(worldReference);Objects.requireNonNull(synchronizationBefore);Objects.requireNonNull(synchronizationAfter);
    }
  }

  public record Reconstruction(String schemaVersion, Phase7Timing.Config config, Timeline.Snapshot timeline,
                               State.Seed seed, Phase7Timing.Reconstruction timing,
                               State.Reconstruction playerState, List<EventFrame> frames) implements Serializable {
    public static final String SCHEMA_VERSION="phase7-history-v1";
    public Reconstruction {
      if(!SCHEMA_VERSION.equals(schemaVersion))throw new IllegalArgumentException("unsupported Phase 7 history schema");
      Objects.requireNonNull(config);Objects.requireNonNull(timeline);Objects.requireNonNull(seed);Objects.requireNonNull(timing);Objects.requireNonNull(playerState);frames=List.copyOf(frames);
    }
    public String canonicalText(){StringBuilder out=new StringBuilder(SCHEMA_VERSION).append('\n');out.append("config=").append(config).append('\n');out.append("frames=").append(frames.size()).append('\n');for(EventFrame f:frames){out.append(f.index()).append('|').append(f.event().serverTick()).append('|').append(f.event().packet().sequence()).append('|').append(f.timing().simulationClientTicks()).append('|').append(f.synchronizationAfter().status()).append('|').append(f.worldReference().timingExhaustive()).append('|').append(f.playerState().after()).append('\n');}return out.toString();}
  }

  public static Reconstruction reconstruct(Timeline.Snapshot timeline, State.Seed seed, Phase7Timing.Config config){
    Objects.requireNonNull(timeline);Objects.requireNonNull(seed);Objects.requireNonNull(config);
    Phase7Timing.Reconstruction timing=Phase7Timing.reconstruct(timeline,config);
    State.Reconstruction state=State.reconstruct(seed,timeline);
    if(state.frames().size()!=timeline.events().size()||timing.frames().size()!=timeline.events().size())throw new IllegalStateException("Phase 7 history component lengths diverged");
    List<EventFrame> frames=new ArrayList<>(timeline.events().size());
    for(int i=0;i<timeline.events().size();i++){
      Timeline.Event event=timeline.events().get(i);Phase7Timing.Frame tf=timing.frames().get(i);State.StateFrame sf=state.frames().get(i);
      boolean worldExhaustive=Phase7Timing.worldTimingExhaustive(timing,event.packet().sequence());
      WorldReference world=new WorldReference(event.serverTick(),tf.timing().simulationClientTicks(),event.packet().sequence(),worldExhaustive,
          worldExhaustive?"historical client-visible world timing is exact for this event":"world timing remains bounded/uncertain; replay must not substitute latest server state");
      frames.add(new EventFrame(i,event,tf.timing(),sf,world,tf.before(),tf.after()));
    }
    return new Reconstruction(Reconstruction.SCHEMA_VERSION,config,timeline,seed,timing,state,frames);
  }
}
