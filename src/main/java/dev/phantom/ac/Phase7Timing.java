package dev.phantom.ac;

import dev.phantom.ac.Packets.*;
import java.io.Serializable;
import java.util.*;
import java.util.function.LongFunction;

/**
 * Phase 7 client/server temporal-envelope and synchronization engine.
 *
 * <p>Phase 7 reconstructs possible timing histories only. It never decides
 * whether a movement is legitimate or illegitimate.</p>
 */
public final class Phase7Timing {
  private Phase7Timing() {}

  public enum Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT, UNKNOWN }

  public enum EventKind {
    MOVEMENT, INPUT, CLIENT_TICK_END, TELEPORT_CORRECTION, TELEPORT_ACK,
    VELOCITY, WORLD, WORLD_TRANSACTION_SEND, WORLD_TRANSACTION_ACK,
    EFFECT, GAMEMODE, FLIGHT_TOGGLE, OTHER
  }

  public enum TimingSource {
    EXPLICIT_CLIENT_TICK,
    CAPTURED_TICK_WATERMARK,
    LATENCY_BOUNDED,
    RELATIVE_CLIENT_ANCHOR,
    SERVER_CAPTURE_ONLY
  }

  public enum SyncStatus {
    SYNCHRONIZED,
    PARTIALLY_SYNCHRONIZED,
    AMBIGUOUS,
    RECOVERING,
    UNKNOWN
  }

  public enum WindowKind {
    TELEPORT, VELOCITY, ACKNOWLEDGEMENT, WORLD_UPDATE, PACKET_GAP,
    SERVER_TICK_GAP, REORDERING, DUPLICATE, RECOVERY, STARTUP,
    TIMING_BUDGET
  }

  public enum Consistency { CONSISTENT, UNCERTAIN, INCONSISTENT }

  public record Range(long min, long max) implements Serializable {
    public Range {
      if (min > max) throw new IllegalArgumentException("range min must not exceed max");
    }
    public static Range exact(long value) { return new Range(value, value); }
    public static Range empty() { return Range.exact(0); }
    public long width() { return max - min; }
    public long cardinality() {
      if (max == Long.MAX_VALUE) return Long.MAX_VALUE;
      return max - min + 1L;
    }
    public boolean isExact() { return min == max; }
    public boolean contains(long value) { return value >= min && value <= max; }
    public Range union(Range other) {
      Objects.requireNonNull(other);
      return new Range(Math.min(min, other.min), Math.max(max, other.max));
    }
  }

  public record TimeRange(long minNanos, long maxNanos) implements Serializable {
    public TimeRange {
      if (minNanos < 0 || maxNanos < minNanos) {
        throw new IllegalArgumentException("invalid time range");
      }
    }
    public static TimeRange exact(long value) { return new TimeRange(value, value); }
    public boolean isExact() { return minNanos == maxNanos; }
  }

  public record LatencyBounds(long minNanos, long maxNanos) implements Serializable {
    public LatencyBounds {
      if (minNanos < 0 || maxNanos < minNanos) {
        throw new IllegalArgumentException("invalid latency bounds");
      }
    }
    public long jitterNanos() { return maxNanos - minNanos; }
    public boolean isExact() { return minNanos == maxNanos; }
  }

  public record TickDelayBounds(long minTicks, long maxTicks) implements Serializable {
    public TickDelayBounds {
      if (minTicks < 0 || maxTicks < minTicks) {
        throw new IllegalArgumentException("invalid tick delay bounds");
      }
    }
  }

  /**
   * All timing assumptions are immutable inputs. No wall-clock value is read
   * during reconstruction.
   *
   * <p>{@code maxTimingCandidates} limits discrete materialization of one
   * timing envelope. {@code maxTimingHistories} bounds the conservative
   * Cartesian-product history count. Neither budget narrows a real range:
   * exceeding either budget makes the reconstruction non-exhaustive.</p>
   */
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
      int maxTimingCandidates,
      long maxTimingHistories) implements Serializable {

    public Config(
        long serverTickNanos,
        long clientTickMinNanos,
        long clientTickMaxNanos,
        LatencyBounds upstreamLatency,
        LatencyBounds downstreamLatency,
        TickDelayBounds inputToSimulation,
        TickDelayBounds simulationToPacket,
        long packetGapThresholdNanos,
        int recoveryStableEvents,
        int maxTimingCandidates) {
      this(serverTickNanos, clientTickMinNanos, clientTickMaxNanos,
          upstreamLatency, downstreamLatency, inputToSimulation, simulationToPacket,
          packetGapThresholdNanos, recoveryStableEvents, maxTimingCandidates, 65_536L);
    }

    public Config {
      if (serverTickNanos <= 0) {
        throw new IllegalArgumentException("server tick duration must be positive");
      }
      if (clientTickMinNanos <= 0 || clientTickMaxNanos < clientTickMinNanos) {
        throw new IllegalArgumentException("invalid client tick bounds");
      }
      Objects.requireNonNull(upstreamLatency);
      Objects.requireNonNull(downstreamLatency);
      Objects.requireNonNull(inputToSimulation);
      Objects.requireNonNull(simulationToPacket);
      if (packetGapThresholdNanos <= 0 || recoveryStableEvents < 1
          || maxTimingCandidates < 1 || maxTimingHistories < 1) {
        throw new IllegalArgumentException("invalid timing configuration");
      }
    }

    public static Config defaultConfig() {
      long tick = 50_000_000L;
      return new Config(
          tick, tick, tick,
          new LatencyBounds(0, 100_000_000L),
          new LatencyBounds(0, 100_000_000L),
          new TickDelayBounds(0, 1),
          new TickDelayBounds(0, 1),
          250_000_000L, 3, 128, 65_536L);
    }
  }

  /** A materialized or conservatively bounded client-tick possibility set. */
  public record TickEnvelope(
      boolean known,
      Range range,
      List<Long> candidates,
      boolean exhaustive) implements Serializable {

    public TickEnvelope {
      Objects.requireNonNull(range);
      Objects.requireNonNull(candidates);
      candidates = List.copyOf(candidates);
      if (candidates.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("tick candidates may not be null");
      }
      if (!candidates.isEmpty()) {
        long previous = Long.MIN_VALUE;
        for (long candidate : candidates) {
          if (candidate < range.min() || candidate > range.max()) {
            throw new IllegalArgumentException("tick candidate outside envelope");
          }
          if (candidate <= previous) {
            throw new IllegalArgumentException("tick candidates must be strictly ordered");
          }
          previous = candidate;
        }
      }
      if (!known && exhaustive) {
        throw new IllegalArgumentException("unknown envelope cannot be exhaustive");
      }
      if (exhaustive && !candidatesCoverRange(range, candidates)) {
        throw new IllegalArgumentException("exhaustive envelope must materialize every candidate");
      }
    }

    public static TickEnvelope unknown() {
      return new TickEnvelope(false, Range.empty(), List.of(), false);
    }

    public static TickEnvelope exact(long tick) {
      return new TickEnvelope(true, Range.exact(tick), List.of(tick), true);
    }

    private static TickEnvelope bounded(Range range, int maximumCandidates) {
      long count = range.cardinality();
      if (count > maximumCandidates) {
        return new TickEnvelope(true, range, List.of(), false);
      }
      ArrayList<Long> values = new ArrayList<>((int) count);
      for (long tick = range.min(); ; tick++) {
        values.add(tick);
        if (tick == range.max()) break;
      }
      return new TickEnvelope(true, range, values, true);
    }

    public boolean isExact() { return known && range.isExact(); }
    public long candidateCount() { return candidates.size(); }

    private static boolean candidatesCoverRange(Range range, List<Long> candidates) {
      long count = range.cardinality();
      return count != Long.MAX_VALUE && count == candidates.size();
    }
  }

  public record OrderingConstraint(
      long firstSequence,
      long secondSequence,
      String relation) implements Serializable {
    public OrderingConstraint {
      if (firstSequence < 0 || secondSequence < 0) {
        throw new IllegalArgumentException("ordering sequence must be non-negative");
      }
      if (relation == null || relation.isBlank()) {
        throw new IllegalArgumentException("ordering relation is required");
      }
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
      Objects.requireNonNull(possibleClientTicks);
      if (firstServerTick < 0 || lastServerTick < firstServerTick) {
        throw new IllegalArgumentException("invalid server window");
      }
      if (reason == null || reason.isBlank() || triggerSequence < 0) {
        throw new IllegalArgumentException("invalid synchronization window");
      }
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
      if (stableEvents < 0 || synchronizationEpoch < 0) {
        throw new IllegalArgumentException("invalid synchronization state");
      }
      activeWindows = List.copyOf(activeWindows);
      reasons = List.copyOf(reasons);
    }

    public boolean synchronizedEnough() {
      return status == SyncStatus.SYNCHRONIZED;
    }

    public static SynchronizationState initial() {
      return new SynchronizationState(
          SyncStatus.UNKNOWN,
          Range.empty(),
          new TimeRange(0, Long.MAX_VALUE),
          OptionalInt.empty(),
          0,
          0,
          List.of(),
          List.of("no client/server synchronization anchor exists"));
    }
  }

  public record EventTiming(
      int timelineIndex,
      long sequence,
      long serverTick,
      long captureNanos,
      Direction direction,
      EventKind kind,
      Packets.CaptureProvenance provenance,
      OptionalLong authoritativeServerTick,
      TimeRange packetGenerationNanos,
      TimeRange clientProcessingNanos,
      TickEnvelope packetGenerationClientTickEnvelope,
      TickEnvelope clientProcessingClientTickEnvelope,
      TickEnvelope simulationClientTickEnvelope,
      TickEnvelope inputClientTickEnvelope,
      OptionalLong explicitClientTick,
      TimingSource source,
      boolean uncertain,
      List<OrderingConstraint> orderingConstraints,
      List<SynchronizationWindow> windows,
      List<String> reasons) implements Serializable {

    public EventTiming {
      if (timelineIndex < 0 || sequence < 0 || serverTick < 0 || captureNanos < 0) {
        throw new IllegalArgumentException("invalid timing metadata");
      }
      Objects.requireNonNull(direction);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(provenance);
      Objects.requireNonNull(authoritativeServerTick);
      Objects.requireNonNull(packetGenerationNanos);
      Objects.requireNonNull(clientProcessingNanos);
      Objects.requireNonNull(packetGenerationClientTickEnvelope);
      Objects.requireNonNull(clientProcessingClientTickEnvelope);
      Objects.requireNonNull(simulationClientTickEnvelope);
      Objects.requireNonNull(inputClientTickEnvelope);
      Objects.requireNonNull(explicitClientTick);
      Objects.requireNonNull(source);
      orderingConstraints = List.copyOf(orderingConstraints);
      windows = List.copyOf(windows);
      reasons = List.copyOf(reasons);
    }

    // Compatibility accessors retained so Phase 6 and existing integrations do not
    // need to know the internal TickEnvelope representation.
    public Range packetGenerationClientTicks() {
      return packetGenerationClientTickEnvelope.range();
    }
    public Range simulationClientTicks() {
      return simulationClientTickEnvelope.range();
    }
    public Range inputClientTicks() {
      return inputClientTickEnvelope.range();
    }

    public List<Long> possiblePacketGenerationClientTicks() {
      return packetGenerationClientTickEnvelope.candidates();
    }
    public List<Long> possibleSimulationClientTicks() {
      return simulationClientTickEnvelope.candidates();
    }
    public List<Long> possibleInputClientTicks() {
      return inputClientTickEnvelope.candidates();
    }

    public boolean packetGenerationCandidatesExhaustive() {
      return packetGenerationClientTickEnvelope.exhaustive();
    }
    public boolean simulationCandidatesExhaustive() {
      return simulationClientTickEnvelope.exhaustive();
    }
    public boolean inputCandidatesExhaustive() {
      return inputClientTickEnvelope.exhaustive();
    }
  }

  public record Frame(
      EventTiming timing,
      SynchronizationState before,
      SynchronizationState after) implements Serializable {
    public Frame {
      Objects.requireNonNull(timing);
      Objects.requireNonNull(before);
      Objects.requireNonNull(after);
    }
  }

  public record TimingMetrics(
      long events,
      long materializedTimingCandidates,
      int peakMaterializedTimingCandidates,
      long timingHistoryUpperBound,
      long synchronizationTransitions,
      boolean budgetReached) implements Serializable {
    public TimingMetrics {
      if (events < 0 || materializedTimingCandidates < 0
          || peakMaterializedTimingCandidates < 0
          || timingHistoryUpperBound < 0 || synchronizationTransitions < 0) {
        throw new IllegalArgumentException("negative timing metric");
      }
    }
  }

  public record FirstDivergence(
      int eventIndex,
      long sequence,
      List<String> differingFields,
      Optional<EventTiming> expected,
      Optional<EventTiming> actual,
      Optional<SynchronizationState> expectedSynchronization,
      Optional<SynchronizationState> actualSynchronization,
      List<String> reasons) implements Serializable {
    public FirstDivergence {
      if (eventIndex < 0 || sequence < 0) {
        throw new IllegalArgumentException("invalid timing divergence");
      }
      differingFields = List.copyOf(differingFields);
      Objects.requireNonNull(expected);
      Objects.requireNonNull(actual);
      Objects.requireNonNull(expectedSynchronization);
      Objects.requireNonNull(actualSynchronization);
      reasons = List.copyOf(reasons);
    }
  }

  public record Reconstruction(
      String modelVersion,
      Config config,
      OptionalLong anchorSequence,
      Range anchorClientTick,
      TimeRange anchorGenerationNanos,
      List<Frame> frames,
      Consistency consistency,
      List<String> consistencyReasons,
      TimingMetrics metrics) implements Serializable {

    public Reconstruction(
        String modelVersion,
        Config config,
        OptionalLong anchorSequence,
        Range anchorClientTick,
        TimeRange anchorGenerationNanos,
        List<Frame> frames,
        Consistency consistency,
        List<String> consistencyReasons) {
      this(
          modelVersion, config, anchorSequence, anchorClientTick, anchorGenerationNanos,
          frames, consistency, consistencyReasons,
          computeMetrics(config, frames));
    }

    public Reconstruction {
      Contracts.requireTargetVersion(modelVersion);
      Objects.requireNonNull(config);
      Objects.requireNonNull(anchorSequence);
      Objects.requireNonNull(anchorClientTick);
      Objects.requireNonNull(anchorGenerationNanos);
      frames = List.copyOf(frames);
      Objects.requireNonNull(consistency);
      consistencyReasons = List.copyOf(consistencyReasons);
      Objects.requireNonNull(metrics);
    }

    public Map<Long, EventTiming> bySequence() {
      Map<Long, EventTiming> out = new LinkedHashMap<>();
      for (Frame frame : frames) out.put(frame.timing().sequence(), frame.timing());
      return Map.copyOf(out);
    }

    public Optional<EventTiming> timingFor(long sequence) {
      return frames.stream()
          .map(Frame::timing)
          .filter(timing -> timing.sequence() == sequence)
          .findFirst();
    }

    public SynchronizationState finalState() {
      return frames.isEmpty() ? SynchronizationState.initial() : frames.getLast().after();
    }

    public String canonicalText() {
      StringBuilder out = new StringBuilder("phase7-reconstruction-v2\n");
      out.append("model=").append(modelVersion).append('\n');
      out.append("config=").append(config).append('\n');
      out.append("anchorSequence=").append(anchorSequence).append('\n');
      out.append("anchorClientTick=").append(anchorClientTick).append('\n');
      out.append("anchorGenerationNanos=").append(anchorGenerationNanos).append('\n');
      out.append("consistency=").append(consistency).append(' ').append(consistencyReasons).append('\n');
      out.append("metrics=").append(metrics).append('\n');
      for (Frame frame : frames) {
        EventTiming timing = frame.timing();
        out.append("event=").append(timing.timelineIndex())
            .append(" seq=").append(timing.sequence())
            .append(" serverTick=").append(timing.serverTick())
            .append(" authoritativeServerTick=").append(timing.authoritativeServerTick())
            .append(" direction=").append(timing.direction())
            .append(" kind=").append(timing.kind())
            .append(" provenance=").append(timing.provenance())
            .append(" generation=").append(timing.packetGenerationNanos())
            .append(" processing=").append(timing.clientProcessingNanos())
            .append(" packetTicks=").append(timing.packetGenerationClientTickEnvelope())
            .append(" processingTicks=").append(timing.clientProcessingClientTickEnvelope())
            .append(" simulationTicks=").append(timing.simulationClientTickEnvelope())
            .append(" inputTicks=").append(timing.inputClientTickEnvelope())
            .append(" explicit=").append(timing.explicitClientTick())
            .append(" source=").append(timing.source())
            .append(" uncertain=").append(timing.uncertain())
            .append(" ordering=").append(timing.orderingConstraints())
            .append(" windows=").append(formatWindows(timing.windows()))
            .append(" reasons=").append(timing.reasons()).append('\n');
        out.append("before=").append(formatSync(frame.before())).append('\n');
        out.append("after=").append(formatSync(frame.after())).append('\n');
      }
      return out.toString();
    }
  }

  public record Phase6TimingEnvelope(
      Range simulationClientTicks,
      List<Long> simulationTickCandidates,
      boolean simulationCandidatesExhaustive,
      SyncStatus synchronizationStatus,
      List<SynchronizationWindow> synchronizationWindows,
      boolean uncertain,
      List<String> reasons) implements Serializable {
    public Phase6TimingEnvelope {
      Objects.requireNonNull(simulationClientTicks);
      simulationTickCandidates = List.copyOf(simulationTickCandidates);
      Objects.requireNonNull(synchronizationStatus);
      synchronizationWindows = List.copyOf(synchronizationWindows);
      reasons = List.copyOf(reasons);
    }
  }

  private record BoundaryObservation(
      long boundaryIndex,
      TimeRange generationNanos,
      long sequence,
      long serverTick) {}

  private record TimingBounds(
      TimeRange packetGenerationNanos,
      TimeRange clientProcessingNanos,
      TimeRange clientEventNanos,
      LatencyBounds latency,
      boolean uncertain,
      List<String> reasons) {}

  private record TickDerivation(
      TickEnvelope envelope,
      TimingSource source,
      boolean evidenceConflict,
      List<String> reasons) {}

  public static Reconstruction reconstruct(Timeline.Snapshot timeline) {
    return reconstruct(timeline, Config.defaultConfig());
  }

  public static Reconstruction reconstruct(
      Timeline.Snapshot timeline,
      Config config) {
    Objects.requireNonNull(timeline);
    Objects.requireNonNull(config);

    SynchronizationState sync = SynchronizationState.initial();
    List<Frame> frames = new ArrayList<>(timeline.events().size());
    List<BoundaryObservation> boundaries = new ArrayList<>();
    OptionalLong anchorSequence = OptionalLong.empty();
    Range anchorTick = Range.empty();
    TimeRange anchorGeneration =
        TimeRange.exact(Math.max(0, timeline.metadata().captureEpochNanos()));
    boolean anchorSet = false;

    Consistency consistency = Consistency.CONSISTENT;
    LinkedHashSet<String> consistencyReasons = new LinkedHashSet<>();
    long previousCapture = -1;
    long previousServerTick = -1;
    long previousSequence = -1;
    long clientBoundaryCount = 0;
    int index = 0;
    long timingHistoryUpperBound = 1L;
    long materializedTimingCandidates = 0L;
    int peakMaterializedTimingCandidates = 0;
    long synchronizationTransitions = 0L;
    boolean budgetReached = false;

    for (Timeline.Event event : timeline.events()) {
      NormalizedPacket normalized = event.packet();
      Packet packet = normalized.packet();
      Direction direction = direction(packet);
      EventKind kind = kind(packet);
      long capture = normalized.receivedNanos();
      TimingBounds bounds = timingBounds(packet, capture, config);
      OptionalLong explicit =
          packet instanceof Move move && move.clientTick() != null
              ? OptionalLong.of(move.clientTick()) : OptionalLong.empty();
      boolean duplicate = normalized.flags().contains(PacketFlag.DUPLICATE);
      SynchronizationState before = sync;
      List<OrderingConstraint> ordering = new ArrayList<>();
      if (previousSequence >= 0) {
        ordering.add(new OrderingConstraint(
            previousSequence, normalized.sequence(), "capture/timeline order"));
      }

      boolean boundaryEvent = kind == EventKind.CLIENT_TICK_END && !duplicate;
      if (boundaryEvent) {
        clientBoundaryCount = safeAdd(clientBoundaryCount, 1);
        boundaries.add(new BoundaryObservation(
            clientBoundaryCount,
            bounds.packetGenerationNanos,
            normalized.sequence(),
            event.serverTick()));
        TickEnvelope boundaryEnvelope = boundaryEnvelope(
            clientBoundaryCount, config.maxTimingCandidates());
        if (!anchorSet) {
          anchorSet = true;
          anchorSequence = OptionalLong.of(normalized.sequence());
          anchorGeneration = bounds.packetGenerationNanos;
          anchorTick = new Range(
              Math.max(0L, clientBoundaryCount - 1L), clientBoundaryCount);
          sync = new SynchronizationState(
              SyncStatus.PARTIALLY_SYNCHRONIZED,
              anchorTick,
              latencyRange(bounds.latency),
              OptionalInt.empty(),
              1,
              1,
              List.of(new SynchronizationWindow(
                  WindowKind.STARTUP,
                  event.serverTick(),
                  event.serverTick(),
                  boundaryEnvelope.range(),
                  "CLIENT_TICK_END establishes a relative client-clock boundary, not a movement timestamp",
                  normalized.sequence())),
              List.of("client tick-end boundary established; absolute client clock origin remains unknown"));
          synchronizationTransitions++;
        }

        List<SynchronizationWindow> windows = new ArrayList<>();
        List<String> reasons = new ArrayList<>(bounds.reasons);
        if (normalized.flags().contains(PacketFlag.SEQUENCE_GAP)) {
          windows.add(new SynchronizationWindow(
              WindowKind.SERVER_TICK_GAP,
              event.serverTick(),
              event.serverTick(),
              boundaryEnvelope.range(),
              "capture sequence gap remains unknown",
              normalized.sequence()));
          reasons.add("capture sequence gap; missing records remain unknown");
        }
        boolean uncertain = bounds.uncertain
            || !boundaryEnvelope.exhaustive()
            || normalized.flags().contains(PacketFlag.SEQUENCE_GAP)
            || normalized.flags().contains(PacketFlag.OUT_OF_ORDER)
            || normalized.flags().contains(PacketFlag.DUPLICATE);
        if (!ordering.isEmpty() && normalized.flags().contains(PacketFlag.OUT_OF_ORDER)) {
          windows.add(new SynchronizationWindow(
              WindowKind.REORDERING, event.serverTick(), event.serverTick(),
              boundaryEnvelope.range(),
              "arrival chronology is preserved; capture order does not establish client generation order",
              normalized.sequence()));
          reasons.add("out-of-order capture sequence");
        }
        if (duplicate) {
          windows.add(new SynchronizationWindow(
              WindowKind.DUPLICATE, event.serverTick(), event.serverTick(),
              boundaryEnvelope.range(),
              "duplicate capture retained as evidence but does not advance client chronology",
              normalized.sequence()));
          reasons.add("duplicate capture sequence");
        }

        EventTiming timing = new EventTiming(
            index++, normalized.sequence(), event.serverTick(), capture, direction, kind,
            normalized.provenance(),
            optionalLong(normalized.provenance().authoritativeServerTick()),
            bounds.packetGenerationNanos, bounds.clientProcessingNanos,
            boundaryEnvelope, boundaryEnvelope,
            boundaryEnvelope, TickEnvelope.unknown(),
            explicit, TimingSource.RELATIVE_CLIENT_ANCHOR, uncertain,
            ordering, windows, reasons);
        EventTiming finalized = timing;
        frames.add(new Frame(finalized, before, sync));
        continue;
      }

      if (!anchorSet && direction == Direction.CLIENT_TO_SERVER
          && !duplicate) {
        TickDerivation anchorDerivation = deriveClientTicks(
            bounds.packetGenerationNanos, explicit, boundaries, true,
            anchorGeneration, anchorTick, config);
        anchorSet = true;
        anchorSequence = OptionalLong.of(normalized.sequence());
        anchorGeneration = bounds.packetGenerationNanos;
        anchorTick = anchorDerivation.envelope.range();
        sync = new SynchronizationState(
            anchorDerivation.envelope.isExact()
                ? SyncStatus.SYNCHRONIZED : SyncStatus.PARTIALLY_SYNCHRONIZED,
            anchorDerivation.envelope.range(),
            latencyRange(bounds.latency),
            OptionalInt.empty(),
            1,
            1,
            List.of(new SynchronizationWindow(
                WindowKind.STARTUP,
                event.serverTick(),
                event.serverTick(),
                anchorDerivation.envelope.range(),
                explicit.isPresent()
                    ? "capture-supplied client tick metadata establishes the relative chronology anchor"
                    : "first client event establishes a relative chronology anchor; absolute client clock origin is unknown",
                normalized.sequence())),
            List.of(explicit.isPresent()
                ? "client tick metadata is capture provenance, not a packet-wire timestamp"
                : "first client event establishes the relative client clock origin"));
        synchronizationTransitions++;
      }

      TickDerivation packetDerivation;
      if (direction == Direction.CLIENT_TO_SERVER) {
        packetDerivation = deriveClientTicks(
            bounds.packetGenerationNanos, explicit, boundaries, false,
            anchorGeneration, anchorTick, config);
      } else if (direction == Direction.SERVER_TO_CLIENT) {
        if (anchorSet) {
          Range range = relativeClientTicks(
              bounds.clientProcessingNanos, anchorGeneration, anchorTick, config);
          packetDerivation = new TickDerivation(
              TickEnvelope.bounded(range, config.maxTimingCandidates()),
              TimingSource.LATENCY_BOUNDED,
              false,
              List.of("server-to-client processing tick is bounded by downstream latency; server send time is distinct"));
        } else {
          packetDerivation = new TickDerivation(
              TickEnvelope.unknown(),
              TimingSource.SERVER_CAPTURE_ONLY,
              false,
              List.of("server-to-client event precedes any client timing anchor"));
        }
      } else {
        packetDerivation = new TickDerivation(
            TickEnvelope.unknown(),
            TimingSource.SERVER_CAPTURE_ONLY,
            false,
            List.of("packet direction is unknown"));
      }

      TickEnvelope packetTicks = packetDerivation.envelope;
      TickEnvelope simulationTicks;
      TickEnvelope inputTicks;
      TickEnvelope processingTicks;

      if (kind == EventKind.INPUT && direction == Direction.CLIENT_TO_SERVER) {
        inputTicks = packetTicks;
        simulationTicks = shiftEnvelope(
            packetTicks, config.inputToSimulation(), false, config.maxTimingCandidates());
      } else {
        inputTicks = TickEnvelope.unknown();
        if (kind == EventKind.MOVEMENT && direction == Direction.CLIENT_TO_SERVER) {
          simulationTicks = shiftEnvelope(
              packetTicks, config.simulationToPacket(), true, config.maxTimingCandidates());
        } else if (direction == Direction.SERVER_TO_CLIENT) {
          simulationTicks = packetTicks;
        } else {
          simulationTicks = packetTicks;
        }
      }
      processingTicks = direction == Direction.SERVER_TO_CLIENT
          ? packetTicks : TickEnvelope.unknown();

      List<SynchronizationWindow> windows = new ArrayList<>();
      List<String> reasons = new ArrayList<>(bounds.reasons);
      boolean uncertain = bounds.uncertain || !packetTicks.exhaustive()
          || !simulationTicks.exhaustive() || !processingTicks.exhaustive();
      if (packetDerivation.evidenceConflict()) {
        uncertain = true;
        if (consistency == Consistency.CONSISTENT) consistency = Consistency.UNCERTAIN;
        consistencyReasons.addAll(packetDerivation.reasons);
      }

      if (previousCapture >= 0
          && capture - previousCapture > config.packetGapThresholdNanos()) {
        windows.add(new SynchronizationWindow(
            WindowKind.PACKET_GAP,
            Math.max(0, previousServerTick),
            event.serverTick(),
            packetTicks.range(),
            "capture gap exceeds configured threshold; missing observations do not imply client inactivity",
            normalized.sequence()));
        reasons.add("observation gap does not imply client inactivity");
        uncertain = true;
        sync = enterRecovery(sync, windows.getLast());
        synchronizationTransitions++;
      }

      if (previousServerTick >= 0 && event.serverTick() > previousServerTick + 1L) {
        windows.add(new SynchronizationWindow(
            WindowKind.SERVER_TICK_GAP,
            previousServerTick,
            event.serverTick(),
            packetTicks.range(),
            "server ticks between observations were not captured",
            normalized.sequence()));
        reasons.add("server tick interval contains unobserved ticks");
        uncertain = true;
      }

      if (normalized.flags().contains(PacketFlag.SEQUENCE_GAP)) {
        reasons.add("capture sequence gap; absent records remain unknown");
        uncertain = true;
      }
      if (normalized.flags().contains(PacketFlag.OUT_OF_ORDER)) {
        windows.add(new SynchronizationWindow(
            WindowKind.REORDERING,
            event.serverTick(),
            event.serverTick(),
            packetTicks.range(),
            "arrival order is retained and cannot establish client generation order",
            normalized.sequence()));
        reasons.add("out-of-order capture sequence");
        uncertain = true;
      }
      if (duplicate) {
        windows.add(new SynchronizationWindow(
            WindowKind.DUPLICATE,
            event.serverTick(),
            event.serverTick(),
            packetTicks.range(),
            "duplicate observation has no second semantic effect",
            normalized.sequence()));
        reasons.add("duplicate capture sequence");
        uncertain = true;
      }

      if (packetDerivation.evidenceConflict()) {
        windows.add(new SynchronizationWindow(
            WindowKind.TIMING_BUDGET,
            event.serverTick(),
            event.serverTick(),
            packetTicks.range(),
            "capture-side client tick metadata conflicts with independently bounded packet-generation time; both possibilities are retained",
            normalized.sequence()));
      }

      if (packet instanceof Teleport && !duplicate) {
        Teleport teleport = (Teleport) packet;
        windows.add(new SynchronizationWindow(
            WindowKind.TELEPORT,
            event.serverTick(),
            safeAdd(event.serverTick(), config.recoveryStableEvents()),
            processingTicks.range(),
            "server correction begins a new synchronization epoch; client application time is bounded",
            normalized.sequence()));
        sync = new SynchronizationState(
            SyncStatus.RECOVERING,
            processingTicks.known ? processingTicks.range : packetTicks.range(),
            latencyRange(bounds.latency),
            OptionalInt.of(teleport.id()),
            0,
            safeAdd(sync.synchronizationEpoch(), 1),
            append(sync.activeWindows(), windows.getLast()),
            List.of("teleport/correction boundary entered"));
        synchronizationTransitions++;
        uncertain = true;
        reasons.add("pre-correction prediction timing is not authoritative after correction");
      } else if (packet instanceof TeleportConfirm && !duplicate) {
        TeleportConfirm confirm = (TeleportConfirm) packet;
        windows.add(new SynchronizationWindow(
            WindowKind.ACKNOWLEDGEMENT,
            event.serverTick(),
            safeAdd(event.serverTick(), 1),
            packetTicks.range(),
            "teleport acknowledgement is asynchronous and may be delayed or missing",
            normalized.sequence()));
        if (sync.pendingTeleportId().isPresent()
            && sync.pendingTeleportId().getAsInt() == confirm.id()) {
          sync = new SynchronizationState(
              SyncStatus.RECOVERING,
              packetTicks.range(),
              sync.observedLatency(),
              OptionalInt.empty(),
              0,
              sync.synchronizationEpoch(),
              append(sync.activeWindows(), windows.getLast()),
              List.of("matching correction acknowledgement received; stable post-correction observations are still required"));
          synchronizationTransitions++;
          uncertain = true;
        } else {
          sync = new SynchronizationState(
              SyncStatus.AMBIGUOUS,
              packetTicks.range(),
              sync.observedLatency(),
              sync.pendingTeleportId(),
              0,
              sync.synchronizationEpoch(),
              append(sync.activeWindows(), windows.getLast()),
              List.of("unexpected, delayed, or duplicate teleport acknowledgement"));
          synchronizationTransitions++;
          uncertain = true;
          reasons.add("teleport acknowledgement did not match the pending correction");
        }
      } else if (packet instanceof Velocity && !duplicate) {
        windows.add(new SynchronizationWindow(
            WindowKind.VELOCITY,
            event.serverTick(),
            safeAdd(event.serverTick(), config.recoveryStableEvents()),
            processingTicks.range(),
            "server velocity arrival is distinct from the client simulation tick that applies it",
            normalized.sequence()));
        sync = withWindow(sync, windows.getLast());
        synchronizationTransitions++;
        uncertain = true;
        reasons.add("velocity application timing remains a client-tick possibility");
      } else if (kind == EventKind.WORLD && !duplicate) {
        windows.add(new SynchronizationWindow(
            WindowKind.WORLD_UPDATE,
            event.serverTick(),
            safeAdd(event.serverTick(), 1),
            processingTicks.range(),
            "server knows a world update at send time; client visibility is bounded separately by downstream processing latency",
            normalized.sequence()));
        // World timing uncertainty belongs to the world visibility envelope. It must
        // not falsely claim that the client clock itself is unsynchronized.
        uncertain = true;
        reasons.add("world-update visibility timing is bounded by downstream delivery");
      } else if (kind == EventKind.WORLD_TRANSACTION_SEND && !duplicate) {
        windows.add(new SynchronizationWindow(
            WindowKind.WORLD_UPDATE,
            event.serverTick(),
            safeAdd(event.serverTick(), 1),
            processingTicks.range(),
            "world transaction send is server-side evidence of delivery initiation, not client receipt",
            normalized.sequence()));
        uncertain = true;
        reasons.add("world transaction visibility depends on client processing");
      } else if (kind == EventKind.WORLD_TRANSACTION_ACK && !duplicate) {
        windows.add(new SynchronizationWindow(
            WindowKind.ACKNOWLEDGEMENT,
            event.serverTick(),
            safeAdd(event.serverTick(), 1),
            packetTicks.range(),
            "world transaction acknowledgement constrains receipt ordering but not exact client simulation time",
            normalized.sequence()));
        uncertain = true;
        reasons.add("world transaction acknowledgement has bounded client processing chronology");
      }

      if (sync.status() == SyncStatus.RECOVERING
          && direction == Direction.CLIENT_TO_SERVER
          && !uncertain
          && kind == EventKind.MOVEMENT
          && sync.pendingTeleportId().isEmpty()) {
        int stable = sync.stableEvents() + 1;
        if (stable >= config.recoveryStableEvents()) {
          sync = new SynchronizationState(
              SyncStatus.SYNCHRONIZED,
              packetTicks.range(),
              sync.observedLatency(),
              OptionalInt.empty(),
              stable,
              sync.synchronizationEpoch(),
              sync.activeWindows(),
              List.of("synchronization re-established after stable client observations"));
        } else {
          sync = new SynchronizationState(
              SyncStatus.RECOVERING,
              packetTicks.range(),
              sync.observedLatency(),
              OptionalInt.empty(),
              stable,
              sync.synchronizationEpoch(),
              sync.activeWindows(),
              List.of("recovery requires additional stable client observations"));
        }
        synchronizationTransitions++;
      } else if (sync.status() != SyncStatus.RECOVERING
          && direction == Direction.CLIENT_TO_SERVER
          && !uncertain
          && kind != EventKind.CLIENT_TICK_END) {
        int stable = sync.stableEvents() + 1;
        SyncStatus status = stable >= 2
            ? SyncStatus.SYNCHRONIZED
            : SyncStatus.PARTIALLY_SYNCHRONIZED;
        sync = new SynchronizationState(
            status,
            packetTicks.range(),
            latencyRange(bounds.latency),
            sync.pendingTeleportId(),
            stable,
            sync.synchronizationEpoch(),
            sync.activeWindows(),
            List.of("clean client observation incorporated"));
        synchronizationTransitions++;
      } else if (uncertain
          && sync.status() == SyncStatus.SYNCHRONIZED
          && affectsMovementSynchronization(kind)) {
        sync = new SynchronizationState(
            SyncStatus.AMBIGUOUS,
            packetTicks.range(),
            sync.observedLatency(),
            sync.pendingTeleportId(),
            0,
            sync.synchronizationEpoch(),
            sync.activeWindows(),
            List.of("timing ambiguity prevents strong movement synchronization"));
        synchronizationTransitions++;
      }

      if (!packetTicks.exhaustive() || !simulationTicks.exhaustive()
          || (kind == EventKind.INPUT && !inputTicks.exhaustive())) {
        budgetReached = true;
        windows.add(new SynchronizationWindow(
            WindowKind.TIMING_BUDGET,
            event.serverTick(),
            event.serverTick(),
            simulationTicks.range(),
            "timing envelope could not be fully materialized within maxTimingCandidates; the range is retained but discrete history is incomplete",
            normalized.sequence()));
        reasons.add("timing candidate materialization budget reached");
        uncertain = true;
      }

      long eventHistoryCardinality =
          Math.max(1L, simulationTicks.known
              ? simulationTicks.range.cardinality() : config.maxTimingCandidates() + 1L);
      timingHistoryUpperBound = saturatingMultiply(timingHistoryUpperBound, eventHistoryCardinality);
      if (timingHistoryUpperBound > config.maxTimingHistories()) {
        timingHistoryUpperBound = config.maxTimingHistories() + 1L;
        if (!budgetReached) {
          budgetReached = true;
          windows.add(new SynchronizationWindow(
              WindowKind.TIMING_BUDGET,
              event.serverTick(),
              event.serverTick(),
              simulationTicks.range(),
              "Cartesian timing-history upper bound exceeded maxTimingHistories; possibilities remain bounded by ranges but are not exhaustively enumerated",
              normalized.sequence()));
          reasons.add("timing history budget reached");
        }
        uncertain = true;
      }

      materializedTimingCandidates += packetTicks.candidateCount()
          + processingTicks.candidateCount()
          + simulationTicks.candidateCount()
          + inputTicks.candidateCount();
      peakMaterializedTimingCandidates = Math.max(
          peakMaterializedTimingCandidates,
          packetTicks.candidateCount()
              + processingTicks.candidateCount()
              + simulationTicks.candidateCount()
              + inputTicks.candidateCount());

      EventTiming timing = new EventTiming(
          index++, normalized.sequence(), event.serverTick(), capture, direction, kind,
          normalized.provenance(),
          optionalLong(normalized.provenance().authoritativeServerTick()),
          bounds.packetGenerationNanos,
          bounds.clientProcessingNanos,
          packetTicks,
          processingTicks,
          simulationTicks,
          inputTicks,
          explicit,
          packetDerivation.source(),
          uncertain,
          ordering,
          windows,
          reasons);

      if (packetDerivation.evidenceConflict()
          && consistency == Consistency.CONSISTENT) {
        consistency = Consistency.UNCERTAIN;
      }

      EventTiming finalized = withSyncUncertainty(timing, sync, kind);
      if (finalized.uncertain() && consistency == Consistency.CONSISTENT) {
        consistency = Consistency.UNCERTAIN;
        consistencyReasons.add("one or more timing envelopes are non-exact or synchronization is not fully established");
      }
      frames.add(new Frame(finalized, before, sync));

      previousCapture = capture;
      previousServerTick = event.serverTick();
      previousSequence = normalized.sequence();
    }

    if (frames.stream().anyMatch(frame -> frame.timing().uncertain())
        && consistency == Consistency.CONSISTENT) {
      consistency = Consistency.UNCERTAIN;
      consistencyReasons.add("one or more events have bounded timing uncertainty");
    }

    TimingMetrics metrics = new TimingMetrics(
        frames.size(),
        materializedTimingCandidates,
        peakMaterializedTimingCandidates,
        timingHistoryUpperBound,
        synchronizationTransitions,
        budgetReached);

    return new Reconstruction(
        Contracts.TARGET_VERSION,
        config,
        anchorSequence,
        anchorTick,
        anchorGeneration,
        frames,
        consistency,
        List.copyOf(consistencyReasons),
        metrics);
  }

  public static Phase6TimingEnvelope toPhase6Envelope(
      EventTiming timing,
      SynchronizationState synchronization) {
    Objects.requireNonNull(timing);
    Objects.requireNonNull(synchronization);
    LinkedHashSet<String> reasons = new LinkedHashSet<>(timing.reasons());
    reasons.addAll(synchronization.reasons());
    if (synchronization.status() != SyncStatus.SYNCHRONIZED) {
      reasons.add("Phase 7 synchronization state is " + synchronization.status());
    }
    return new Phase6TimingEnvelope(
        timing.simulationClientTicks(),
        timing.possibleSimulationClientTicks(),
        timing.simulationCandidatesExhaustive(),
        synchronization.status(),
        synchronization.activeWindows(),
        timing.uncertain() || synchronization.status() != SyncStatus.SYNCHRONIZED,
        List.copyOf(reasons));
  }

  public static Validation.SyncWindow toPhase6Window(EventTiming timing) {
    Objects.requireNonNull(timing);
    return new Validation.SyncWindow(
        Math.max(0L, timing.simulationClientTicks().min()),
        Math.max(0L, timing.simulationClientTicks().max()),
        timing.uncertain(),
        timing.reasons());
  }

  public static boolean worldTimingExhaustive(
      Reconstruction reconstruction,
      long sequence) {
    Optional<EventTiming> timing = reconstruction.timingFor(sequence);
    return timing.isPresent()
        && timing.get().kind() == EventKind.WORLD
        && timing.get().simulationCandidatesExhaustive()
        && !timing.get().uncertain();
  }

  public static List<Long> possibleSimulationTicks(EventTiming timing) {
    Objects.requireNonNull(timing);
    return timing.possibleSimulationClientTicks();
  }

  public static List<Long> possibleInputTicks(EventTiming timing) {
    Objects.requireNonNull(timing);
    return timing.possibleInputClientTicks();
  }

  public static boolean simulationTickEnumerationComplete(EventTiming timing) {
    return Objects.requireNonNull(timing).simulationCandidatesExhaustive();
  }

  public static boolean inputTickEnumerationComplete(EventTiming timing) {
    return Objects.requireNonNull(timing).inputCandidatesExhaustive();
  }

  public static Optional<FirstDivergence> firstDivergence(
      Reconstruction expected,
      Reconstruction actual) {
    Objects.requireNonNull(expected);
    Objects.requireNonNull(actual);
    int count = Math.min(expected.frames().size(), actual.frames().size());
    for (int i = 0; i < count; i++) {
      Frame left = expected.frames().get(i);
      Frame right = actual.frames().get(i);
      List<String> differences = new ArrayList<>();
      compareTiming(left.timing(), right.timing(), differences);
      compareSync(left.before(), right.before(), "before", differences);
      compareSync(left.after(), right.after(), "after", differences);
      if (!differences.isEmpty()) {
        return Optional.of(new FirstDivergence(
            i,
            right.timing().sequence(),
            differences,
            Optional.of(left.timing()),
            Optional.of(right.timing()),
            Optional.of(left.after()),
            Optional.of(right.after()),
            List.of("first Phase 7 reconstruction divergence at canonical event index " + i)));
      }
    }
    if (expected.frames().size() != actual.frames().size()) {
      int index = count;
      Frame source = index < actual.frames().size()
          ? actual.frames().get(index) : expected.frames().get(index);
      return Optional.of(new FirstDivergence(
          index,
          source.timing().sequence(),
          List.of("frameCount"),
          index < expected.frames().size()
              ? Optional.of(expected.frames().get(index).timing()) : Optional.empty(),
          index < actual.frames().size()
              ? Optional.of(actual.frames().get(index).timing()) : Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          List.of("Phase 7 replay histories have different event cardinality")));
    }
    return Optional.empty();
  }

  private static TickDerivation deriveClientTicks(
      TimeRange generation,
      OptionalLong explicit,
      List<BoundaryObservation> boundaries,
      boolean anchorDerivation,
      TimeRange anchorGeneration,
      Range anchorTick,
      Config config) {
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    boolean known = !anchorDerivation;
    Range wall = known
        ? relativeClientTicks(generation, anchorGeneration, anchorTick, config)
        : Range.empty();
    Range constrained = wall;
    if (!boundaries.isEmpty()) {
      BoundaryConstraint result = applyBoundaryConstraints(generation, boundaries);
      if (result.known()) {
        if (known) {
          Range intersected = intersect(wall, result.range());
          if (intersected != null) {
            constrained = intersected;
          } else {
            constrained = wall.union(result.range());
            reasons.add("boundary-derived and latency-derived client tick envelopes conflict; preserving both possibilities");
          }
        } else {
          constrained = result.range();
          known = true;
        }
      }
    }

    if (explicit.isPresent()) {
      long tick = explicit.getAsLong();
      if (!known) {
        return new TickDerivation(
            TickEnvelope.bounded(Range.exact(tick), config.maxTimingCandidates()),
            TimingSource.EXPLICIT_CLIENT_TICK,
            false,
            List.of("capture-supplied client tick metadata is used as a discrete timing witness"));
      }
      if (constrained.contains(tick)) {
        return new TickDerivation(
            TickEnvelope.bounded(Range.exact(tick), config.maxTimingCandidates()),
            TimingSource.EXPLICIT_CLIENT_TICK,
            false,
            List.of("capture-supplied client tick metadata agrees with the bounded chronology envelope"));
      }
      Range widened = constrained.union(Range.exact(tick));
      reasons.add("capture-supplied client tick metadata disagrees with independently derived timing; exactness is widened");
      return new TickDerivation(
          TickEnvelope.bounded(widened, config.maxTimingCandidates()),
          TimingSource.CAPTURED_TICK_WATERMARK,
          true,
          List.copyOf(reasons));
    }

    if (!known) {
      return new TickDerivation(
          TickEnvelope.unknown(),
          TimingSource.SERVER_CAPTURE_ONLY,
          false,
          List.of("client generation time exists only as a network bound until a relative client anchor exists"));
    }

    if (!generation.isExact() || !anchorTick.isExact()) {
      reasons.add("client tick is inferred from bounded generation time and remains a range where evidence overlaps");
    } else {
      reasons.add("client tick inferred from relative client-clock chronology");
    }
    return new TickDerivation(
        TickEnvelope.bounded(nonNegative(constrained), config.maxTimingCandidates()),
        TimingSource.RELATIVE_CLIENT_ANCHOR,
        false,
        List.copyOf(reasons));
  }

  private record BoundaryConstraint(boolean known, Range range) {}

  private static BoundaryConstraint applyBoundaryConstraints(
      TimeRange generation,
      List<BoundaryObservation> boundaries) {
    long minimum = 0L;
    long maximum = Long.MAX_VALUE;
    boolean constrained = false;
    for (BoundaryObservation boundary : boundaries) {
      if (generation.maxNanos() < boundary.generationNanos().minNanos()) {
        maximum = Math.min(maximum, Math.max(0L, boundary.boundaryIndex() - 1L));
        constrained = true;
      } else if (generation.minNanos() > boundary.generationNanos().maxNanos()) {
        minimum = Math.max(minimum, boundary.boundaryIndex());
        constrained = true;
      }
    }
    if (!constrained) return new BoundaryConstraint(false, Range.empty());
    if (minimum > maximum) {
      return new BoundaryConstraint(true, Range.exact(minimum));
    }
    return new BoundaryConstraint(true, new Range(minimum, maximum));
  }

  private static TickEnvelope boundaryEnvelope(
      long boundaryIndex,
      int maximumCandidates) {
    Range range = new Range(
        Math.max(0L, boundaryIndex - 1L), boundaryIndex);
    return TickEnvelope.bounded(range, maximumCandidates);
  }

  private static TickEnvelope shiftEnvelope(
      TickEnvelope source,
      TickDelayBounds delay,
      boolean subtract,
      int maximumCandidates) {
    if (!source.known()) return TickEnvelope.unknown();
    Range range = shiftTicks(source.range(), delay, subtract);
    if (!source.exhaustive()) {
      return new TickEnvelope(true, range, List.of(), false);
    }
    return TickEnvelope.bounded(range, maximumCandidates);
  }

  private static SynchronizationState stabilizeOrRetain(
      SynchronizationState current,
      EventTiming boundaryTiming,
      Config config,
      long serverTick,
      List<BoundaryObservation> boundaries) {
    return current;
  }

  private static SynchronizationState withSyncUncertainty(
      EventTiming timing,
      SynchronizationState sync,
      EventKind kind) {
    boolean affects = affectsMovementSynchronization(kind);
    if (affects && sync.status() != SyncStatus.SYNCHRONIZED) {
      if (timing.uncertain()) {
        return sync;
      }
      return sync;
    }
    return sync;
  }

  private static TimingMetrics computeMetrics(
      Config config,
      List<Frame> frames) {
    long materialized = 0L;
    int peak = 0;
    long history = 1L;
    long transitions = 0L;
    boolean budget = false;
    for (Frame frame : frames) {
      EventTiming timing = frame.timing();
      int count = timing.packetGenerationClientTickEnvelope().candidates().size()
          + timing.clientProcessingClientTickEnvelope().candidates().size()
          + timing.simulationClientTickEnvelope().candidates().size()
          + timing.inputClientTickEnvelope().candidates().size();
      materialized += count;
      peak = Math.max(peak, count);
      long card = timing.simulationClientTickEnvelope().known()
          ? timing.simulationClientTickEnvelope().range().cardinality()
          : config.maxTimingCandidates() + 1L;
      history = saturatingMultiply(history, Math.max(1L, card));
      if (history > config.maxTimingHistories()) {
        history = config.maxTimingHistories() + 1L;
        budget = true;
      }
      if (frame.before().status() != frame.after().status()
          || frame.before().synchronizationEpoch() != frame.after().synchronizationEpoch()) {
        transitions++;
      }
      budget |= !timing.simulationCandidatesExhaustive()
          || !timing.inputCandidatesExhaustive()
          || !timing.packetGenerationCandidatesExhaustive()
          || !timing.clientProcessingClientTickEnvelope().exhaustive();
    }
    return new TimingMetrics(frames.size(), materialized, peak, history, transitions, budget);
  }

  private static void compareTiming(
      EventTiming left,
      EventTiming right,
      List<String> differences) {
    if (left.sequence() != right.sequence()) differences.add("sequence");
    if (left.serverTick() != right.serverTick()) differences.add("serverTick");
    if (!left.authoritativeServerTick().equals(right.authoritativeServerTick())) differences.add("authoritativeServerTick");
    if (left.captureNanos() != right.captureNanos()) differences.add("captureNanos");
    if (left.direction() != right.direction()) differences.add("direction");
    if (left.kind() != right.kind()) differences.add("kind");
    if (!left.provenance().equals(right.provenance())) differences.add("provenance");
    if (!left.packetGenerationNanos().equals(right.packetGenerationNanos())) differences.add("packetGenerationNanos");
    if (!left.clientProcessingNanos().equals(right.clientProcessingNanos())) differences.add("clientProcessingNanos");
    if (!left.packetGenerationClientTickEnvelope().equals(right.packetGenerationClientTickEnvelope())) differences.add("packetGenerationClientTickEnvelope");
    if (!left.clientProcessingClientTickEnvelope().equals(right.clientProcessingClientTickEnvelope())) differences.add("clientProcessingClientTickEnvelope");
    if (!left.simulationClientTickEnvelope().equals(right.simulationClientTickEnvelope())) differences.add("simulationClientTickEnvelope");
    if (!left.inputClientTickEnvelope().equals(right.inputClientTickEnvelope())) differences.add("inputClientTickEnvelope");
    if (!left.explicitClientTick().equals(right.explicitClientTick())) differences.add("explicitClientTick");
    if (left.source() != right.source()) differences.add("source");
    if (left.uncertain() != right.uncertain()) differences.add("uncertain");
    if (!left.orderingConstraints().equals(right.orderingConstraints())) differences.add("orderingConstraints");
    if (!left.windows().equals(right.windows())) differences.add("windows");
    if (!left.reasons().equals(right.reasons())) differences.add("reasons");
  }

  private static void compareSync(
      SynchronizationState left,
      SynchronizationState right,
      String prefix,
      List<String> differences) {
    if (left.status() != right.status()) differences.add(prefix + ".status");
    if (!left.possibleClientTicks().equals(right.possibleClientTicks())) differences.add(prefix + ".possibleClientTicks");
    if (!left.observedLatency().equals(right.observedLatency())) differences.add(prefix + ".observedLatency");
    if (!left.pendingTeleportId().equals(right.pendingTeleportId())) differences.add(prefix + ".pendingTeleportId");
    if (left.stableEvents() != right.stableEvents()) differences.add(prefix + ".stableEvents");
    if (left.synchronizationEpoch() != right.synchronizationEpoch()) differences.add(prefix + ".synchronizationEpoch");
    if (!left.activeWindows().equals(right.activeWindows())) differences.add(prefix + ".activeWindows");
    if (!left.reasons().equals(right.reasons())) differences.add(prefix + ".reasons");
  }

  private static Direction direction(Packet packet) {
    if (packet instanceof Move
        || packet instanceof ClientInput
        || packet instanceof ClientTickEnd
        || packet instanceof TeleportConfirm
        || packet instanceof WorldTransactionAck
        || packet instanceof FlightToggle) {
      return Direction.CLIENT_TO_SERVER;
    }
    if (packet instanceof Teleport
        || packet instanceof Velocity
        || packet instanceof Effect
        || packet instanceof Gamemode
        || packet instanceof PlayerContext
        || packet instanceof WorldTransactionSend
        || packet.mutatesWorld()) {
      return Direction.SERVER_TO_CLIENT;
    }
    return Direction.UNKNOWN;
  }

  private static EventKind kind(Packet packet) {
    if (packet instanceof Move) return EventKind.MOVEMENT;
    if (packet instanceof ClientInput) return EventKind.INPUT;
    if (packet instanceof ClientTickEnd) return EventKind.CLIENT_TICK_END;
    if (packet instanceof Teleport) return EventKind.TELEPORT_CORRECTION;
    if (packet instanceof TeleportConfirm) return EventKind.TELEPORT_ACK;
    if (packet instanceof Velocity) return EventKind.VELOCITY;
    if (packet instanceof ChunkData
        || packet instanceof ChunkStates
        || packet instanceof ChunkUnload
        || packet instanceof BlockChange
        || packet instanceof BlockStateChange
        || packet instanceof UnsupportedBlockStateChange) return EventKind.WORLD;
    if (packet instanceof WorldTransactionSend) return EventKind.WORLD_TRANSACTION_SEND;
    if (packet instanceof WorldTransactionAck) return EventKind.WORLD_TRANSACTION_ACK;
    if (packet instanceof Effect) return EventKind.EFFECT;
    if (packet instanceof Gamemode) return EventKind.GAMEMODE;
    if (packet instanceof FlightToggle) return EventKind.FLIGHT_TOGGLE;
    return EventKind.OTHER;
  }

  private static TimeRange timingGenerationFor(Packet packet, long capture, Config config) {
    return timingBounds(packet, capture, config).packetGenerationNanos;
  }

  private static TimingBounds timingBounds(
      Packet packet,
      long capture,
      Config config) {
    Direction direction = direction(packet);
    if (direction == Direction.CLIENT_TO_SERVER) {
      TimeRange generation = new TimeRange(
          Math.max(0L, safeAdd(capture, -config.upstreamLatency().maxNanos())),
          Math.max(0L, safeAdd(capture, -config.upstreamLatency().minNanos())));
      return new TimingBounds(
          generation,
          generation,
          generation,
          config.upstreamLatency(),
          !config.upstreamLatency().isExact(),
          List.of("server packet arrival is observed; client packet generation is bounded by upstream latency"));
    }
    if (direction == Direction.SERVER_TO_CLIENT) {
      TimeRange processing = new TimeRange(
          safeAdd(capture, config.downstreamLatency().minNanos()),
          safeAdd(capture, config.downstreamLatency().maxNanos()));
      return new TimingBounds(
          TimeRange.exact(capture),
          processing,
          processing,
          config.downstreamLatency(),
          !config.downstreamLatency().isExact(),
          List.of("server packet send/capture time is observed; client processing time is bounded by downstream latency"));
    }
    return new TimingBounds(
        TimeRange.exact(capture),
        TimeRange.exact(capture),
        TimeRange.exact(capture),
        new LatencyBounds(0, 0),
        true,
        List.of("packet direction is unknown"));
  }

  private static Range relativeClientTicks(
      TimeRange event,
      TimeRange anchor,
      Range anchorTick,
      Config config) {
    long deltaMin = safeAdd(event.minNanos(), -anchor.maxNanos());
    long deltaMax = safeAdd(event.maxNanos(), -anchor.minNanos());
    long min = Math.floorDiv(deltaMin, config.clientTickMaxNanos());
    long max = Math.floorDiv(deltaMax, config.clientTickMinNanos());
    return new Range(
        safeAdd(anchorTick.min(), min),
        safeAdd(anchorTick.max(), max));
  }

  private static Range shiftTicks(
      Range ticks,
      TickDelayBounds delay,
      boolean subtract) {
    return subtract
        ? new Range(
            safeAdd(ticks.min(), -delay.maxTicks()),
            safeAdd(ticks.max(), -delay.minTicks()))
        : new Range(
            safeAdd(ticks.min(), delay.minTicks()),
            safeAdd(ticks.max(), delay.maxTicks()));
  }

  private static Range nonNegative(Range range) {
    return new Range(Math.max(0L, range.min()), Math.max(0L, range.max()));
  }

  private static Range intersect(Range left, Range right) {
    long min = Math.max(left.min(), right.min());
    long max = Math.min(left.max(), right.max());
    return min <= max ? new Range(min, max) : null;
  }

  private static OptionalLong optionalLong(Long value) {
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static TimeRange latencyRange(LatencyBounds bounds) {
    return new TimeRange(bounds.minNanos(), bounds.maxNanos());
  }

  private static List<SynchronizationWindow> append(
      List<SynchronizationWindow> current,
      SynchronizationWindow window) {
    ArrayList<SynchronizationWindow> result = new ArrayList<>(current);
    result.add(window);
    if (result.size() > 16) {
      result = new ArrayList<>(result.subList(result.size() - 16, result.size()));
    }
    return List.copyOf(result);
  }

  private static SynchronizationState enterRecovery(
      SynchronizationState old,
      SynchronizationWindow window) {
    return new SynchronizationState(
        SyncStatus.RECOVERING,
        window.possibleClientTicks(),
        old.observedLatency(),
        old.pendingTeleportId(),
        0,
        old.synchronizationEpoch(),
        append(old.activeWindows(), window),
        List.of("synchronization recovery entered"));
  }

  private static SynchronizationState withWindow(
      SynchronizationState old,
      SynchronizationWindow window) {
    SyncStatus status = old.status() == SyncStatus.RECOVERING
        ? SyncStatus.RECOVERING : SyncStatus.AMBIGUOUS;
    return new SynchronizationState(
        status,
        window.possibleClientTicks(),
        old.observedLatency(),
        old.pendingTeleportId(),
        0,
        old.synchronizationEpoch(),
        append(old.activeWindows(), window),
        List.of(window.reason()));
  }

  private static EventTiming withSyncUncertainty(
      EventTiming timing,
      SynchronizationState sync,
      EventKind kind) {
    if (!affectsMovementSynchronization(kind)
        || sync.status() == SyncStatus.SYNCHRONIZED
        || timing.uncertain()) {
      return timing;
    }
    List<String> reasons = new ArrayList<>(timing.reasons());
    reasons.add("Phase 7 synchronization state is " + sync.status()
        + "; movement timing is not fully synchronized");
    return new EventTiming(
        timing.timelineIndex(), timing.sequence(), timing.serverTick(),
        timing.captureNanos(), timing.direction(), timing.kind(),
        timing.provenance(), timing.authoritativeServerTick(),
        timing.packetGenerationNanos(), timing.clientProcessingNanos(),
        timing.packetGenerationClientTickEnvelope(),
        timing.clientProcessingClientTickEnvelope(),
        timing.simulationClientTickEnvelope(),
        timing.inputClientTickEnvelope(),
        timing.explicitClientTick(), timing.source(), true,
        timing.orderingConstraints(), timing.windows(), reasons);
  }

  private static boolean affectsMovementSynchronization(EventKind kind) {
    return switch (kind) {
      case MOVEMENT, INPUT, TELEPORT_CORRECTION, TELEPORT_ACK, VELOCITY -> true;
      case CLIENT_TICK_END, WORLD, WORLD_TRANSACTION_SEND, WORLD_TRANSACTION_ACK,
          EFFECT, GAMEMODE, FLIGHT_TOGGLE, OTHER -> false;
    };
  }

  private static String formatSync(SynchronizationState state) {
    return state.status()
        + " ticks=" + state.possibleClientTicks()
        + " latency=" + state.observedLatency()
        + " pendingTeleport=" + state.pendingTeleportId()
        + " stable=" + state.stableEvents()
        + " epoch=" + state.synchronizationEpoch()
        + " windows=" + formatWindows(state.activeWindows())
        + " reasons=" + state.reasons();
  }

  private static String formatWindows(List<SynchronizationWindow> windows) {
    List<String> out = new ArrayList<>();
    for (SynchronizationWindow window : windows) {
      out.add(window.kind() + "@" + window.firstServerTick() + ".."
          + window.lastServerTick() + " client=" + window.possibleClientTicks()
          + " seq=" + window.triggerSequence());
    }
    return out.toString();
  }

  private static long saturatingMultiply(long left, long right) {
    if (left == 0 || right == 0) return 0;
    if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
    return left * right;
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }
}
