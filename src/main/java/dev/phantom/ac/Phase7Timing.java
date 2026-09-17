package dev.phantom.ac;

import dev.phantom.ac.Packets.BlockChange;
import dev.phantom.ac.Packets.BlockStateChange;
import dev.phantom.ac.Packets.ChunkData;
import dev.phantom.ac.Packets.ChunkStates;
import dev.phantom.ac.Packets.ChunkUnload;
import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Packets.Effect;
import dev.phantom.ac.Packets.Gamemode;
import dev.phantom.ac.Packets.Move;
import dev.phantom.ac.Packets.NormalizedPacket;
import dev.phantom.ac.Packets.Packet;
import dev.phantom.ac.Packets.PacketFlag;
import dev.phantom.ac.Packets.Teleport;
import dev.phantom.ac.Packets.TeleportConfirm;
import dev.phantom.ac.Packets.UnsupportedBlockStateChange;
import dev.phantom.ac.Packets.Velocity;

import java.io.Serializable;
import java.util.*;

/**
 * Phase 7 client/server clock, network, chronology, and synchronization model.
 *
 * <p>This layer deliberately does not make movement or cheating decisions. It
 * converts the canonical Phase 1/3 packet timeline into bounded client-side
 * timing hypotheses for Phase 5/6. Missing timing information is represented as
 * a range or ambiguity state instead of an invented exact tick.</p>
 */
public final class Phase7Timing {
  private Phase7Timing() {}

  public enum Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT, UNKNOWN }
  public enum EventKind { MOVEMENT, INPUT, TELEPORT_CORRECTION, TELEPORT_ACK, VELOCITY, WORLD, EFFECT, GAMEMODE, OTHER }
  public enum TimingSource { EXPLICIT_CLIENT_TICK, LATENCY_BOUNDED, RELATIVE_CLIENT_ANCHOR, SERVER_CAPTURE_ONLY, RECOVERY }
  public enum SyncStatus { SYNCHRONIZED, PARTIALLY_SYNCHRONIZED, AMBIGUOUS, RECOVERING, UNKNOWN }
  public enum WindowKind { TELEPORT, VELOCITY, ACKNOWLEDGEMENT, WORLD_UPDATE, PACKET_GAP, SERVER_TICK_GAP, REORDERING, DUPLICATE, RECOVERY, STARTUP }
  public enum Consistency { CONSISTENT, UNCERTAIN, INCONSISTENT }

  public record Range(long min, long max) implements Serializable {
    public Range {
      if (min > max) throw new IllegalArgumentException("range min must not exceed max");
    }
    public static Range exact(long value) { return new Range(value, value); }
    public long width() { return max - min; }
    public boolean isExact() { return min == max; }
    public boolean contains(long value) { return value >= min && value <= max; }
    public Range shift(long delta) { return new Range(safeAdd(min, delta), safeAdd(max, delta)); }
    public Range expand(long amount) { if (amount < 0) throw new IllegalArgumentException("negative expansion"); return new Range(safeAdd(min, -amount), safeAdd(max, amount)); }
    public static Range empty() { return new Range(0, 0); }
  }

  public record TimeRange(long minNanos, long maxNanos) implements Serializable {
    public TimeRange { if (minNanos < 0 || maxNanos < 0 || minNanos > maxNanos) throw new IllegalArgumentException("invalid time range"); }
    public static TimeRange exact(long nanos) { return new TimeRange(nanos, nanos); }
    public boolean isExact() { return minNanos == maxNanos; }
  }

  public record LatencyBounds(long minNanos, long maxNanos) implements Serializable {
    public LatencyBounds {
      if (minNanos < 0 || maxNanos < minNanos) throw new IllegalArgumentException("invalid latency bounds");
    }
    public long jitterNanos() { return maxNanos - minNanos; }
  }

  /** Processing delays are expressed in client ticks because they affect which simulation tick consumes an event. */
  public record TickDelayBounds(long minTicks, long maxTicks) implements Serializable {
    public TickDelayBounds { if (minTicks < 0 || maxTicks < minTicks) throw new IllegalArgumentException("invalid tick delay bounds"); }
  }

  /** Explicit timing assumptions. No timing threshold is hidden in the reconstruction algorithm. */
  public record Config(
      long serverTickNanos,
      long clientTickMinNanos,
      long clientTickMaxNanos,
      LatencyBounds upstreamLatency,
      LatencyBounds downstreamLatency,
      TickDelayBounds inputToSimulation,
      TickDelayBounds simulationToPacket,
      long packetGapThresholdNanos,
      int recoveryStableEvents,
      int maxTimingCandidates) implements Serializable {
    public Config {
      if (serverTickNanos <= 0) throw new IllegalArgumentException("server tick duration must be positive");
      if (clientTickMinNanos <= 0 || clientTickMaxNanos < clientTickMinNanos) throw new IllegalArgumentException("invalid client tick duration bounds");
      Objects.requireNonNull(upstreamLatency);
      Objects.requireNonNull(downstreamLatency);
      Objects.requireNonNull(inputToSimulation);
      Objects.requireNonNull(simulationToPacket);
      if (packetGapThresholdNanos <= 0) throw new IllegalArgumentException("packet gap threshold must be positive");
      if (recoveryStableEvents < 1) throw new IllegalArgumentException("recoveryStableEvents must be positive");
      if (maxTimingCandidates < 1) throw new IllegalArgumentException("maxTimingCandidates must be positive");
    }

    /** Conservative default used by the live adapter when no empirical network measurements are available. */
    public static Config defaultConfig() {
      long tick = 50_000_000L;
      return new Config(tick, tick, tick,
          new LatencyBounds(0, 100_000_000L),
          new LatencyBounds(0, 100_000_000L),
          new TickDelayBounds(0, 1),
          new TickDelayBounds(0, 1),
          250_000_000L,
          3,
          128);
    }
  }

  public record SynchronizationWindow(
      WindowKind kind,
      long firstServerTick,
      long lastServerTick,
      Range possibleClientTicks,
      String reason,
      long triggerSequence) implements Serializable {
    public SynchronizationWindow {
      Objects.requireNonNull(kind);
      if (firstServerTick < 0 || lastServerTick < firstServerTick) throw new IllegalArgumentException("invalid server window");
      Objects.requireNonNull(possibleClientTicks);
      if (reason == null || reason.isBlank()) throw new IllegalArgumentException("window reason required");
      if (triggerSequence < 0) throw new IllegalArgumentException("trigger sequence must be non-negative");
    }
  }

  public record SynchronizationState(
      SyncStatus status,
      Range possibleClientTicks,
      TimeRange observedLatency,
      OptionalInt pendingTeleportId,
      int stableEvents,
      long synchronizationEpoch,
      List<SynchronizationWindow> activeWindows,
      List<String> reasons) implements Serializable {
    public SynchronizationState {
      Objects.requireNonNull(status);
      Objects.requireNonNull(possibleClientTicks);
      Objects.requireNonNull(observedLatency);
      Objects.requireNonNull(pendingTeleportId);
      if (stableEvents < 0 || synchronizationEpoch < 0) throw new IllegalArgumentException("invalid synchronization state");
      activeWindows = List.copyOf(activeWindows);
      reasons = List.copyOf(reasons);
    }
    public boolean synchronizedEnough() { return status == SyncStatus.SYNCHRONIZED; }
    public static SynchronizationState initial() {
      return new SynchronizationState(SyncStatus.UNKNOWN, Range.exact(0), new TimeRange(0, Long.MAX_VALUE), OptionalInt.empty(), 0, 0, List.of(), List.of("no client/server synchronization anchor exists"));
    }
  }

  /** A causal timing interpretation of one canonical packet event. */
  public record EventTiming(
      int timelineIndex,
      long sequence,
      long serverTick,
      long captureNanos,
      Direction direction,
      EventKind kind,
      TimeRange packetGenerationNanos,
      TimeRange clientProcessingNanos,
      Range packetGenerationClientTicks,
      Range simulationClientTicks,
      Range inputClientTicks,
      OptionalLong explicitClientTick,
      TimingSource source,
      boolean uncertain,
      List<SynchronizationWindow> windows,
      List<String> reasons) implements Serializable {
    public EventTiming {
      if (timelineIndex < 0 || sequence < 0 || serverTick < 0 || captureNanos < 0) throw new IllegalArgumentException("invalid event timing metadata");
      Objects.requireNonNull(direction); Objects.requireNonNull(kind); Objects.requireNonNull(packetGenerationNanos); Objects.requireNonNull(clientProcessingNanos);
      Objects.requireNonNull(packetGenerationClientTicks); Objects.requireNonNull(simulationClientTicks); Objects.requireNonNull(inputClientTicks); Objects.requireNonNull(explicitClientTick); Objects.requireNonNull(source);
      windows = List.copyOf(windows); reasons = List.copyOf(reasons);
    }
  }

  public record Frame(EventTiming timing, SynchronizationState before, SynchronizationState after) implements Serializable {
    public Frame { Objects.requireNonNull(timing); Objects.requireNonNull(before); Objects.requireNonNull(after); }
  }

  public record Reconstruction(
      String modelVersion,
      Config config,
      OptionalLong anchorSequence,
      Range anchorClientTick,
      TimeRange anchorGenerationNanos,
      List<Frame> frames,
      Consistency consistency,
      List<String> consistencyReasons) implements Serializable {
    public Reconstruction {
      Contracts.requireTargetVersion(modelVersion);
      Objects.requireNonNull(config); Objects.requireNonNull(anchorSequence); Objects.requireNonNull(anchorClientTick); Objects.requireNonNull(anchorGenerationNanos);
      frames = List.copyOf(frames); Objects.requireNonNull(consistency); consistencyReasons = List.copyOf(consistencyReasons);
    }
    public Map<Long, EventTiming> bySequence() {
      Map<Long, EventTiming> result = new LinkedHashMap<>();
      for (Frame frame : frames) result.put(frame.timing().sequence(), frame.timing());
      return Map.copyOf(result);
    }
    public Optional<EventTiming> timingFor(long sequence) { return frames.stream().map(Frame::timing).filter(t -> t.sequence() == sequence).findFirst(); }
    public SynchronizationState finalState() { return frames.isEmpty() ? SynchronizationState.initial() : frames.getLast().after(); }
    public String canonicalText() {
      StringBuilder b = new StringBuilder();
      b.append("phase7-reconstruction-v1\n");
      b.append("model=").append(modelVersion).append('\n');
      b.append("config=").append(config).append('\n');
      b.append("anchorSequence=").append(anchorSequence).append('\n');
      b.append("anchorClientTick=").append(anchorClientTick).append('\n');
      b.append("anchorGenerationNanos=").append(anchorGenerationNanos).append('\n');
      b.append("consistency=").append(consistency).append(' ').append(consistencyReasons).append('\n');
      for (Frame frame : frames) {
        EventTiming t = frame.timing();
        b.append("event[").append(t.timelineIndex()).append("] seq=").append(t.sequence())
            .append(" serverTick=").append(t.serverTick())
            .append(" captureNanos=").append(t.captureNanos())
            .append(" direction=").append(t.direction())
            .append(" kind=").append(t.kind())
            .append(" generation=").append(t.packetGenerationNanos())
            .append(" processing=").append(t.clientProcessingNanos())
            .append(" packetTicks=").append(t.packetGenerationClientTicks())
            .append(" simulationTicks=").append(t.simulationClientTicks())
            .append(" inputTicks=").append(t.inputClientTicks())
            .append(" explicitClientTick=").append(t.explicitClientTick())
            .append(" source=").append(t.source())
            .append(" uncertain=").append(t.uncertain())
            .append(" windows=").append(formatWindows(t.windows()))
            .append(" reasons=").append(t.reasons()).append('\n');
        b.append("syncBefore=").append(formatSync(frame.before())).append('\n');
        b.append("syncAfter=").append(formatSync(frame.after())).append('\n');
      }
      return b.toString();
    }
  }

  public static Reconstruction reconstruct(Timeline.Snapshot timeline) { return reconstruct(timeline, Config.defaultConfig()); }

  public static Reconstruction reconstruct(Timeline.Snapshot timeline, Config config) {
    Objects.requireNonNull(timeline); Objects.requireNonNull(config);
    List<Timeline.Event> events = timeline.events();
    List<Frame> frames = new ArrayList<>();
    SynchronizationState sync = SynchronizationState.initial();
    OptionalLong anchorSequence = OptionalLong.empty();
    Range anchorTick = Range.exact(0);
    TimeRange anchorGeneration = TimeRange.exact(Math.max(0, timeline.metadata().captureEpochNanos()));
    boolean anchorSet = false;
    Consistency consistency = Consistency.CONSISTENT;
    List<String> consistencyReasons = new ArrayList<>();
    long previousCapture = -1;
    long previousServerTick = -1;
    int index = 0;

    for (Timeline.Event event : events) {
      NormalizedPacket normalized = event.packet();
      Packet packet = normalized.packet();
      Direction direction = direction(packet);
      EventKind kind = kind(packet);
      long capture = normalized.receivedNanos();
      TimingBounds bounds = timingBounds(packet, capture, config);

      OptionalLong explicitClientTick = packet instanceof Move m && m.clientTick() != null
          ? OptionalLong.of(m.clientTick()) : OptionalLong.empty();

      if (!anchorSet && direction == Direction.CLIENT_TO_SERVER) {
        anchorSet = true;
        anchorSequence = OptionalLong.of(normalized.sequence());
        anchorGeneration = bounds.packetGenerationNanos;
        anchorTick = explicitClientTick.isPresent() ? Range.exact(explicitClientTick.getAsLong()) : Range.exact(0);
        sync = new SynchronizationState(
            explicitClientTick.isPresent() ? SyncStatus.SYNCHRONIZED : SyncStatus.PARTIALLY_SYNCHRONIZED,
            anchorTick,
            new TimeRange(bounds.latency.minNanos(), bounds.latency.maxNanos()),
            OptionalInt.empty(),
            explicitClientTick.isPresent() ? 1 : 0,
            1,
            List.of(new SynchronizationWindow(WindowKind.STARTUP, event.serverTick(), event.serverTick(), anchorTick, "first client-to-server event anchors relative client chronology", normalized.sequence())),
            explicitClientTick.isPresent() ? List.of("explicit client movement tick establishes the clock anchor") : List.of("first client event anchors relative chronology; absolute client clock origin is unknown"));
      }

      Range packetTicks;
      TimingSource source;
      if (explicitClientTick.isPresent()) {
        packetTicks = Range.exact(explicitClientTick.getAsLong());
        source = TimingSource.EXPLICIT_CLIENT_TICK;
        if (anchorSet && !isWithinDerivedWindow(packetTicks, bounds.clientEventNanos, anchorGeneration, anchorTick, config)) {
          consistency = Consistency.INCONSISTENT;
          consistencyReasons.add("explicit client tick " + packetTicks + " is outside the latency/clock-derived timing bounds for sequence " + normalized.sequence());
        }
      } else if (anchorSet && direction != Direction.UNKNOWN) {
        TimeRange eventClockRange = direction == Direction.CLIENT_TO_SERVER ? bounds.packetGenerationNanos : bounds.clientProcessingNanos;
        packetTicks = relativeClientTicks(eventClockRange, anchorGeneration, anchorTick, config);
        source = TimingSource.RELATIVE_CLIENT_ANCHOR;
      } else {
        packetTicks = Range.empty();
        source = TimingSource.SERVER_CAPTURE_ONLY;
      }

      Range inputTicks = kind == EventKind.INPUT ? packetTicks : Range.empty();
      Range simulationTicks;
      if (kind == EventKind.INPUT) {
        simulationTicks = shiftTicks(packetTicks, config.inputToSimulation, false);
      } else if (direction == Direction.CLIENT_TO_SERVER && kind == EventKind.MOVEMENT) {
        simulationTicks = shiftTicks(packetTicks, config.simulationToPacket, true);
      } else if (direction == Direction.SERVER_TO_CLIENT) {
        simulationTicks = packetTicks;
      } else {
        simulationTicks = packetTicks;
      }

      List<SynchronizationWindow> windows = new ArrayList<>();
      List<String> reasons = new ArrayList<>(bounds.reasons);
      boolean uncertain = bounds.uncertain || !packetTicks.isExact() || source != TimingSource.EXPLICIT_CLIENT_TICK;

      if (previousCapture >= 0 && capture - previousCapture > config.packetGapThresholdNanos) {
        windows.add(new SynchronizationWindow(WindowKind.PACKET_GAP, previousServerTick < 0 ? event.serverTick() : previousServerTick, event.serverTick(), packetTicks, "capture gap exceeded configured threshold; no idle assumption is made", normalized.sequence()));
        uncertain = true;
        reasons.add("observation gap does not imply client inactivity");
        sync = enterRecovery(sync, WindowKind.PACKET_GAP, event, packetTicks, "packet observation gap");
      }
      if (previousServerTick >= 0 && event.serverTick() > previousServerTick + 1) {
        windows.add(new SynchronizationWindow(WindowKind.SERVER_TICK_GAP, previousServerTick, event.serverTick(), packetTicks, "server observation stream spans multiple unobserved server ticks", normalized.sequence()));
        uncertain = true;
        reasons.add("server tick interval contains unobserved ticks");
      }
      if (normalized.flags().contains(PacketFlag.DUPLICATE)) {
        windows.add(new SynchronizationWindow(WindowKind.DUPLICATE, event.serverTick(), event.serverTick(), packetTicks, "duplicate capture sequence is retained but must not advance logical state twice", normalized.sequence()));
        uncertain = true;
        reasons.add("duplicate packet sequence");
      }
      if (normalized.flags().contains(PacketFlag.OUT_OF_ORDER)) {
        windows.add(new SynchronizationWindow(WindowKind.REORDERING, event.serverTick(), event.serverTick(), packetTicks, "capture-order reordering is preserved as uncertainty", normalized.sequence()));
        uncertain = true;
        reasons.add("out-of-order capture sequence");
      }
      if (normalized.flags().contains(PacketFlag.SEQUENCE_GAP)) {
        uncertain = true;
        reasons.add("capture sequence gap detected; missing records are not treated as no movement");
      }
      if (packet instanceof Teleport teleport) {
        windows.add(new SynchronizationWindow(WindowKind.TELEPORT, event.serverTick(), safeAdd(event.serverTick(), config.recoveryStableEvents), packetTicks, "clientbound correction establishes a new synchronization epoch", normalized.sequence()));
        sync = onTeleport(sync, teleport.id(), event, packetTicks);
        uncertain = true;
        reasons.add("post-correction movement is held in recovery until confirmation and stable observations");
      } else if (packet instanceof TeleportConfirm confirm) {
        windows.add(new SynchronizationWindow(WindowKind.ACKNOWLEDGEMENT, event.serverTick(), safeAdd(event.serverTick(), 1), packetTicks, "teleport confirmation is an asynchronous server observation", normalized.sequence()));
        sync = onTeleportConfirm(sync, confirm.id(), event, packetTicks);
        if (sync.status() != SyncStatus.SYNCHRONIZED) uncertain = true;
      } else if (packet instanceof Velocity) {
        windows.add(new SynchronizationWindow(WindowKind.VELOCITY, event.serverTick(), safeAdd(event.serverTick(), config.recoveryStableEvents), packetTicks, "velocity packet is processed by the client before subsequent movement; packet arrival is not movement time", normalized.sequence()));
        sync = withWindow(sync, windows.getLast());
        uncertain = true;
      } else if (kind == EventKind.WORLD) {
        windows.add(new SynchronizationWindow(WindowKind.WORLD_UPDATE, event.serverTick(), safeAdd(event.serverTick(), 1), packetTicks, "client-visible world update is not assumed to be available to the client at server arrival time", normalized.sequence()));
        sync = withWindow(sync, windows.getLast());
        uncertain = true;
      }

      if (sync.status() == SyncStatus.RECOVERING) {
        if (direction == Direction.CLIENT_TO_SERVER && !uncertain && kind == EventKind.MOVEMENT && sync.pendingTeleportId().isEmpty()) {
          int stable = sync.stableEvents() + 1;
          if (stable >= config.recoveryStableEvents) {
            sync = new SynchronizationState(SyncStatus.SYNCHRONIZED, packetTicks, sync.observedLatency(), OptionalInt.empty(), stable, sync.synchronizationEpoch(), sync.activeWindows(), List.of("recovery completed after " + stable + " stable client observations"));
          } else {
            sync = new SynchronizationState(SyncStatus.RECOVERING, packetTicks, sync.observedLatency(), sync.pendingTeleportId(), stable, sync.synchronizationEpoch(), sync.activeWindows(), List.of("recovery requires additional stable observations"));
          }
        }
      } else if (direction == Direction.CLIENT_TO_SERVER && !uncertain) {
        int stable = sync.stableEvents() + 1;
        SyncStatus nextStatus = stable >= 2 ? SyncStatus.SYNCHRONIZED : SyncStatus.PARTIALLY_SYNCHRONIZED;
        sync = new SynchronizationState(nextStatus, packetTicks, new TimeRange(bounds.latency.minNanos(), bounds.latency.maxNanos()), sync.pendingTeleportId(), stable, sync.synchronizationEpoch(), sync.activeWindows(), List.of("clean client observation incorporated"));
      } else if (uncertain && sync.status() == SyncStatus.SYNCHRONIZED) {
        sync = new SynchronizationState(SyncStatus.AMBIGUOUS, packetTicks, sync.observedLatency(), sync.pendingTeleportId(), 0, sync.synchronizationEpoch(), sync.activeWindows(), reasons);
      }

      EventTiming timing = new EventTiming(index++, normalized.sequence(), event.serverTick(), capture, direction, kind,
          bounds.packetGenerationNanos, bounds.clientProcessingNanos, packetTicks, simulationTicks, inputTicks,
          explicitClientTick, source, uncertain || sync.status() != SyncStatus.SYNCHRONIZED, windows, reasons);
      SynchronizationState before = frames.isEmpty() ? SynchronizationState.initial() : syncBefore(frames.getLast());
      frames.add(new Frame(timing, before, sync));
      previousCapture = capture;
      previousServerTick = event.serverTick();
    }
    if (frames.isEmpty()) sync = SynchronizationState.initial();
    if (consistency != Consistency.INCONSISTENT && frames.stream().anyMatch(f -> f.timing().uncertain())) {
      consistency = Consistency.UNCERTAIN;
      consistencyReasons.add("one or more events have bounded but non-exact timing");
    }
    return new Reconstruction(Contracts.TARGET_VERSION, config, anchorSequence, anchorTick, anchorGeneration, frames, consistency, List.copyOf(consistencyReasons));
  }

  /** Converts the reconstructed event timing to the exact bounded window Phase 6 already knows how to enumerate. */
  public static Validation.SyncWindow toLegacyWindow(EventTiming timing) {
    Objects.requireNonNull(timing);
    Range range = timing.simulationClientTicks();
    if (range.max() < range.min()) return new Validation.SyncWindow(0, 0, true, List.of("invalid reconstructed timing range"));
    return new Validation.SyncWindow(range.min(), range.max(), timing.uncertain(), timing.reasons());
  }

  /** Client-tick map used by Phase 6 external-transition lookup. */
  public static Map<Long, List<Long>> clientTicksByServerSequence(Reconstruction reconstruction) {
    Map<Long, List<Long>> result = new LinkedHashMap<>();
    for (Frame frame : reconstruction.frames()) {
      Range range = frame.timing().simulationClientTicks();
      long span = range.max() - range.min() + 1;
      if (span > reconstruction.config().maxTimingCandidates()) continue;
      List<Long> ticks = new ArrayList<>();
      for (long tick = range.min(); tick <= range.max(); tick++) ticks.add(tick);
      result.put(frame.timing().sequence(), List.copyOf(ticks));
    }
    return Map.copyOf(result);
  }

  private record TimingBounds(TimeRange packetGenerationNanos, TimeRange clientProcessingNanos, TimeRange clientEventNanos, LatencyBounds latency, boolean uncertain, List<String> reasons) {}

  private static TimingBounds timingBounds(Packet packet, long capture, Config config) {
    Direction direction = direction(packet);
    if (direction == Direction.CLIENT_TO_SERVER) {
      long min = subtract(capture, config.upstreamLatency.maxNanos());
      long max = subtract(capture, config.upstreamLatency.minNanos());
      TimeRange generation = new TimeRange(min, max);
      return new TimingBounds(generation, generation, generation, config.upstreamLatency, !config.upstreamLatency.minNanosEqualsMax(), List.of("client packet generation is bounded by upstream latency; receive time is not generation time"));
    }
    if (direction == Direction.SERVER_TO_CLIENT) {
      TimeRange processing = new TimeRange(safeAdd(capture, config.downstreamLatency.minNanos()), safeAdd(capture, config.downstreamLatency.maxNanos()));
      return new TimingBounds(TimeRange.exact(capture), processing, processing, config.downstreamLatency, !config.downstreamLatency.minNanosEqualsMax(), List.of("server send capture time is known; client processing time is bounded by downstream latency"));
    }
    return new TimingBounds(TimeRange.exact(capture), TimeRange.exact(capture), TimeRange.exact(capture), new LatencyBounds(0, 0), true, List.of("packet direction is unknown"));
  }

  private static Range relativeClientTicks(TimeRange event, TimeRange anchor, Range anchorTick, Config config) {
    long deltaMin = saturatingSub(event.minNanos(), anchor.maxNanos());
    long deltaMax = saturatingSub(event.maxNanos(), anchor.minNanos());
    long min = floorDiv(deltaMin, config.clientTickMaxNanos());
    long max = floorDiv(deltaMax, config.clientTickMinNanos());
    return new Range(safeAdd(anchorTick.min(), min), safeAdd(anchorTick.max(), max));
  }

  private static Range shiftTicks(Range input, TickDelayBounds delay, boolean subtract) {
    if (input.width() > 10_000) return input;
    if (subtract) return new Range(safeAdd(input.min(), -delay.maxTicks()), safeAdd(input.max(), -delay.minTicks()));
    return new Range(safeAdd(input.min(), delay.minTicks()), safeAdd(input.max(), delay.maxTicks()));
  }

  private static boolean isWithinDerivedWindow(Range explicit, TimeRange event, TimeRange anchor, Range anchorTick, Config config) {
    return relativeClientTicks(event, anchor, anchorTick, config).contains(explicit.min()) ||
        relativeClientTicks(event, anchor, anchorTick, config).contains(explicit.max());
  }

  private static SynchronizationState onTeleport(SynchronizationState old, int id, Timeline.Event event, Range clientTicks) {
    SynchronizationWindow window = new SynchronizationWindow(WindowKind.TELEPORT, event.serverTick(), safeAdd(event.serverTick(), 3), clientTicks, "correction acknowledgement establishes the next synchronization boundary", event.packet().sequence());
    return new SynchronizationState(SyncStatus.RECOVERING, clientTicks, old.observedLatency(), OptionalInt.of(id), 0, safeAdd(old.synchronizationEpoch(), 1), appendWindow(old.activeWindows(), window), List.of("teleport/correction boundary entered"));
  }

  private static SynchronizationState onTeleportConfirm(SynchronizationState old, int id, Timeline.Event event, Range clientTicks) {
    if (old.pendingTeleportId().isPresent() && old.pendingTeleportId().getAsInt() == id) {
      return new SynchronizationState(SyncStatus.RECOVERING, clientTicks, old.observedLatency(), OptionalInt.empty(), 0, old.synchronizationEpoch(), old.activeWindows(), List.of("matching teleport confirmation received; stable post-correction observations are still required"));
    }
    return new SynchronizationState(SyncStatus.AMBIGUOUS, clientTicks, old.observedLatency(), old.pendingTeleportId(), 0, old.synchronizationEpoch(), appendWindow(old.activeWindows(), new SynchronizationWindow(WindowKind.ACKNOWLEDGEMENT, event.serverTick(), event.serverTick(), clientTicks, "teleport confirmation did not match the pending correction", event.packet().sequence())), List.of("unexpected or duplicate teleport acknowledgement"));
  }

  private static SynchronizationState enterRecovery(SynchronizationState old, WindowKind kind, Timeline.Event event, Range clientTicks, String reason) {
    return new SynchronizationState(SyncStatus.RECOVERING, clientTicks, old.observedLatency(), old.pendingTeleportId(), 0, old.synchronizationEpoch(), appendWindow(old.activeWindows(), new SynchronizationWindow(kind, Math.max(0, event.serverTick() - 1), event.serverTick(), clientTicks, reason, event.packet().sequence())), List.of("synchronization recovery entered: " + reason));
  }

  private static SynchronizationState withWindow(SynchronizationState old, SynchronizationWindow window) {
    SyncStatus status = old.status() == SyncStatus.RECOVERING ? old.status() : SyncStatus.AMBIGUOUS;
    return new SynchronizationState(status, old.possibleClientTicks(), old.observedLatency(), old.pendingTeleportId(), 0, old.synchronizationEpoch(), appendWindow(old.activeWindows(), window), List.of(window.reason()));
  }

  private static List<SynchronizationWindow> appendWindow(List<SynchronizationWindow> old, SynchronizationWindow window) {
    List<SynchronizationWindow> result = new ArrayList<>(old);
    result.add(window);
    if (result.size() > 16) result = new ArrayList<>(result.subList(result.size() - 16, result.size()));
    return List.copyOf(result);
  }

  private static SynchronizationState syncBefore(Frame frame) { return frame.before(); }

  private static Direction direction(Packet packet) {
    if (packet instanceof Move || packet instanceof ClientInput || packet instanceof TeleportConfirm) return Direction.CLIENT_TO_SERVER;
    if (packet instanceof Teleport || packet instanceof Velocity || packet instanceof Effect || packet instanceof Gamemode || packet.mutatesWorld()) return Direction.SERVER_TO_CLIENT;
    return Direction.UNKNOWN;
  }

  private static EventKind kind(Packet packet) {
    if (packet instanceof Move) return EventKind.MOVEMENT;
    if (packet instanceof ClientInput) return EventKind.INPUT;
    if (packet instanceof Teleport) return EventKind.TELEPORT_CORRECTION;
    if (packet instanceof TeleportConfirm) return EventKind.TELEPORT_ACK;
    if (packet instanceof Velocity) return EventKind.VELOCITY;
    if (packet instanceof ChunkData || packet instanceof ChunkStates || packet instanceof ChunkUnload || packet instanceof BlockChange || packet instanceof BlockStateChange || packet instanceof UnsupportedBlockStateChange) return EventKind.WORLD;
    if (packet instanceof Effect) return EventKind.EFFECT;
    if (packet instanceof Gamemode) return EventKind.GAMEMODE;
    return EventKind.OTHER;
  }

  private static String formatSync(SynchronizationState s) {
    return s.status()+" ticks="+s.possibleClientTicks()+" latency="+s.observedLatency()+" pendingTeleport="+s.pendingTeleportId()+" stable="+s.stableEvents()+" epoch="+s.synchronizationEpoch()+" windows="+formatWindows(s.activeWindows())+" reasons="+s.reasons();
  }

  private static String formatWindows(List<SynchronizationWindow> windows) {
    List<String> out = new ArrayList<>();
    for (SynchronizationWindow w : windows) out.add(w.kind()+"@"+w.firstServerTick()+".."+w.lastServerTick()+" client="+w.possibleClientTicks()+" seq="+w.triggerSequence());
    return out.toString();
  }

  private static long subtract(long value, long amount) { return Math.max(0, safeAdd(value, -amount)); }
  private static long saturatingSub(long a, long b) { if (a < b - Long.MAX_VALUE) return Long.MIN_VALUE; return a - b; }
  private static long safeAdd(long a, long b) { try { return Math.addExact(a, b); } catch (ArithmeticException e) { return b >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE; } }
  private static long floorDiv(long value, long divisor) { return Math.floorDiv(value, divisor); }
}
