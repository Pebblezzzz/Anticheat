package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.Fluid;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.UncertainDimension;
import dev.phantom.ac.Phase6Reachability.Verdict;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;
import java.util.function.LongFunction;

import static dev.phantom.ac.Maths.Vec3;

/**
 * Persistent live movement predictor.
 *
 * <p>The live path intentionally follows a stateful packet-stream model:
 *
 * <pre>
 * packet stream
 *   -> client state / prediction state
 *   -> latency-compensated packet world
 *   -> predict forward
 *   -> compare observed packet
 *   -> retain prediction state
 * </pre>
 *
 * <p>No retained packet journal is replayed through Phase 5/6 on every call.
 * The packet journal is owned by the capture adapter for diagnostics only;
 * this class advances its immutable candidate frontier incrementally.</p>
 */
public final class Phase8PredictionRunner {
  public enum Continuation {
    UNANCHORED,
    ACTIVE,
    UNCERTAIN,
    IMPOSSIBLE
  }

  public record PredictionFrame(
      long sequence,
      long receivedNanos,
      long serverTick,
      long clientTick,
      Packets.Move movement,
      Player observedBefore,
      Player observedAfter,
      Set<Candidate> predictedBefore,
      Set<Candidate> predictedAfter,
      WorldSnapshot world,
      List<String> uncertaintySources,
      List<String> trace) {
    public PredictionFrame {
      if (sequence < 0 || receivedNanos < 0 || serverTick < 0 || clientTick < 0) {
        throw new IllegalArgumentException("invalid prediction-frame provenance");
      }
      Objects.requireNonNull(movement);
      Objects.requireNonNull(observedBefore);
      Objects.requireNonNull(observedAfter);
      predictedBefore = Set.copyOf(predictedBefore);
      predictedAfter = Set.copyOf(predictedAfter);
      Objects.requireNonNull(world);
      uncertaintySources = List.copyOf(uncertaintySources);
      trace = List.copyOf(trace);
    }
  }

  public record Report(
      List<Phase8MovementValidation.Result> results,
      int packetsProcessed,
      int movementObservations,
      int possible,
      int uncertain,
      int impossible,
      long lastProcessedSequence,
      long relativeClientTick,
      Continuation continuation,
      boolean candidateFrontierRetained,
      List<PredictionFrame> frames) {
    public Report {
      results = List.copyOf(results);
      frames = List.copyOf(frames);
    }
  }

  private record AuthorityAnchor(
      long sequence,
      long receivedNanos,
      long serverTick,
      Long clientTick,
      Packets.PlayerContext context,
      boolean entityCollisionComplete) {}

  private record TimedInput(
      long sequence,
      long clientTick,
      InputConstraint constraint) {}

  private record MovementInputState(boolean sprinting, boolean sneaking) {}

  private static final double POSITION_TOLERANCE = Phase6Reachability.POSITION_MATCH_TOLERANCE;
  private static final long MAX_INCREMENTAL_HORIZON = Phase6Reachability.MAX_HORIZON_TICKS;
  private static final long PREDICTION_RESYNC_LAG_TICKS = 2L;
  private static final int MAX_TIMING_HISTORY_EVENTS = 512;

  private final int maximumCandidates;
  private final Phase7Timing.Config phase7TimingConfig;
  private final ArrayDeque<Packets.RawPacket> timingHistory = new ArrayDeque<>();
  private long timingEpochNanos = -1L;
  private boolean timingHistoryTruncated;
  private final InputConstraint neutralInput;

  private Player initialAnchor;
  private long initialAnchorReceivedNanos = -1L;

  private Player clientState;
  private InputConstraint currentInput;
  private final NavigableMap<Long, List<TimedInput>> inputHistory = new TreeMap<>();
  private AuthorityAnchor latestAuthority;
  private Set<Candidate> prediction = Set.of();
  private long predictionTick = -1L;
  private long relativeClientTick = 0L;
  private boolean hasClientTickBoundary;
  private long lastProcessedSequence = -1L;
  private long lastPositionClientTick = -1L;
  private Vec3 previousObservedMovementPosition;
  private Vec3 lastObservedMovementPosition;
  private long previousObservedMovementClientTick = -1L;
  private long lastObservedMovementClientTick = -1L;
  private boolean lastObservedMovementPriorGround;
  private long nextCandidateId;
  private Continuation latestContinuation = Continuation.UNANCHORED;

  public Phase8PredictionRunner(int maximumCandidates) {
    this(maximumCandidates, Phase7Timing.Config.defaultConfig());
  }

  public Phase8PredictionRunner(int maximumCandidates, Phase7Timing.Config phase7TimingConfig) {
    Contracts.requireCandidateBudget(maximumCandidates);
    this.maximumCandidates = maximumCandidates;
    this.phase7TimingConfig = Objects.requireNonNull(phase7TimingConfig, "phase7TimingConfig");
    this.neutralInput = InputConstraint.fromClientInput(
        new Packets.ClientInput(false, false, false, false, false, false, false));
    this.currentInput = neutralInput;
  }

  public synchronized long lastProcessedSequence() {
    return lastProcessedSequence;
  }

  public synchronized int candidateCount() {
    return prediction.size();
  }

  public synchronized Continuation continuation() {
    return latestContinuation;
  }

  public synchronized long relativeClientTick() {
    return relativeClientTick;
  }

  public synchronized boolean candidateFrontierRetained() {
    return !prediction.isEmpty();
  }

  public synchronized void reset(Player authoritativeAnchor, long ignoredEpochNanos) {
    reset(authoritativeAnchor, -1L, -1L);
  }

  public synchronized void reset(
      Player authoritativeAnchor,
      long authoritativeReceivedNanos,
      long sequenceBoundary) {
    Objects.requireNonNull(authoritativeAnchor, "authoritativeAnchor");
    initialAnchor = authoritativeAnchor;
    initialAnchorReceivedNanos = authoritativeReceivedNanos;
    clientState = authoritativeAnchor;
    currentInput = neutralInput;
    inputHistory.clear();
    timingHistory.clear();
    timingEpochNanos = -1L;
    timingHistoryTruncated = false;
    latestAuthority = null;
    prediction = Set.of();
    predictionTick = -1L;
    relativeClientTick = 0L;
    hasClientTickBoundary = false;
    lastProcessedSequence = sequenceBoundary;
    clearObservedMovementHistory();
    lastPositionClientTick = -1L;
    nextCandidateId = 1L;
    latestContinuation = Continuation.UNANCHORED;
  }

  public synchronized Report process(
      String playerId,
      List<Packets.RawPacket> raw,
      WorldSnapshot liveWorld,
      Player currentAnchor,
      long currentAnchorReceivedNanos) {
    return processWithWorldProvider(
        playerId,
        raw,
        liveWorld == null ? null : ignored -> liveWorld,
        currentAnchor,
        currentAnchorReceivedNanos);
  }

  /**
   * Processes only packets after the last consumed sequence.
   *
   * <p>The world provider is expected to return the client-visible world
   * acknowledged no later than the supplied packet sequence. The provider is
   * therefore the latency-compensation boundary for physics.</p>
   */
  public synchronized Report processWithWorldProvider(
      String playerId,
      List<Packets.RawPacket> raw,
      LongFunction<WorldSnapshot> worldProvider,
      Player currentAnchor,
      long currentAnchorReceivedNanos) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(raw);

    if (currentAnchor != null && !currentAnchor.equals(initialAnchor)) {
      reset(currentAnchor, currentAnchorReceivedNanos, -1L);
    } else if (currentAnchor != null && clientState == null) {
      reset(currentAnchor, currentAnchorReceivedNanos, -1L);
    } else if (currentAnchor != null && currentAnchorReceivedNanos >= 0) {
      initialAnchorReceivedNanos = currentAnchorReceivedNanos;
    }

    if (clientState == null) {
      latestContinuation = Continuation.UNANCHORED;
      return new Report(
          List.of(), 0, 0, 0, 0, 0,
          lastProcessedSequence, relativeClientTick, latestContinuation, false, List.of());
    }

    List<Packets.RawPacket> packets = raw.stream()
        .filter(packet -> packet.sequence() > lastProcessedSequence)
        .sorted(Comparator.comparingLong(Packets.RawPacket::sequence)
            .thenComparingLong(Packets.RawPacket::receivedNanos))
        .toList();

    if (packets.isEmpty()) {
      return new Report(
          List.of(), 0, 0, 0, 0, 0,
          lastProcessedSequence, relativeClientTick, latestContinuation,
          !prediction.isEmpty(), List.of());
    }

    /*
     * Phase 7 is the sole live client/server timing authority. The history is
     * bounded so timing reconstruction cannot grow without limit; a retained
     * prefix can be truncated only at the cost of becoming conservative.
     */
    for (Packets.RawPacket packet : packets) {
      rememberTimingPacket(packet);
    }
    Phase7Timing.Reconstruction phase7Reconstruction = reconstructPhase7Timing();
    Map<Long, Phase7Timing.EventTiming> phase7TimingBySequence =
        phase7Reconstruction.bySequence();
    List<Phase8MovementValidation.Result> results = new ArrayList<>();
    List<PredictionFrame> frames = new ArrayList<>();
    int movementObservations = 0;
    int possible = 0;
    int uncertain = 0;
    int impossible = 0;

    for (Packets.RawPacket packet : packets) {
      long sequence = packet.sequence();
      long previousSequence = lastProcessedSequence;
      if (sequence > previousSequence + 1L && previousSequence >= 0L) {
        /*
         * A missing packet is not allowed to destroy the frontier. It makes
         * the next affected observation uncertain, but the existing prediction
         * remains the legitimate baseline for continued forward simulation.
         */
        latestContinuation = Continuation.UNCERTAIN;
      }
      lastProcessedSequence = sequence;

      EnumSet<Packets.PacketFlag> flags = EnumSet.of(Packets.PacketFlag.NORMAL);

      Packets.NormalizedPacket normalized = new Packets.NormalizedPacket(
          sequence,
          packet.receivedNanos(),
          packet.packet(),
          flags,
          packet.provenance());

      Packets.Packet value = packet.packet();

      if (value instanceof Packets.ClientTickEnd) {
        clientState = State.apply(clientState, normalized);
        relativeClientTick = Math.addExact(relativeClientTick, 1L);
        hasClientTickBoundary = true;
        continue;
      }

      if (value instanceof Packets.PlayerContext authority) {
        clientState = State.apply(clientState, normalized);
        long authorityServerTick = packet.provenance().authoritativeServerTick() == null
            ? 0L
            : packet.provenance().authoritativeServerTick();
        Packets.PlayerContext effectiveAuthority = authority;
        boolean entityCollisionComplete =
            !"entity-collision-incomplete".equals(packet.provenance().sourceId());
        latestAuthority = new AuthorityAnchor(
            sequence,
            packet.receivedNanos(),
            authorityServerTick,
            packet.provenance().authoritativeClientTick(),
            effectiveAuthority,
            entityCollisionComplete);
        if (!prediction.isEmpty()) {
          Set<Candidate> updated =
              overlayAuthorityState(prediction, effectiveAuthority, maximumCandidates);
          if (!updated.isEmpty()) prediction = updated;
        }
        continue;
      }

      if (value instanceof Packets.ClientInput input) {
        clientState = State.apply(clientState, normalized);
        currentInput = InputConstraint.fromClientInput(input);
        long inputTick = hasClientTickBoundary ? relativeClientTick : 0L;
        inputHistory.computeIfAbsent(inputTick, ignored -> new ArrayList<>())
            .add(new TimedInput(sequence, inputTick, currentInput));
        if (!prediction.isEmpty()) {
          Set<Candidate> updated = overlayClientInput(prediction, clientState, maximumCandidates);
          if (!updated.isEmpty()) prediction = updated;
        }
        continue;
      }

      if (value instanceof Packets.Teleport teleport) {
        clientState = State.apply(clientState, normalized);
        long correctionTick = resolvedCorrectionTick();
        Candidate correction = candidateFromPlayer(
            clientState,
            correctionTick,
            "SERVER_CORRECTION",
            sequence,
            entityCollisions(latestAuthority));
        prediction = Set.of(correction);
        predictionTick = correctionTick;
        clearObservedMovementHistory();
        lastPositionClientTick = correctionTick;
        latestContinuation = Continuation.ACTIVE;
        continue;
      }

      if (value instanceof Packets.TeleportConfirm
          || value instanceof Packets.Velocity
          || value instanceof Packets.Effect
          || value instanceof Packets.Gamemode) {
        clientState = State.apply(clientState, normalized);
        if (value instanceof Packets.Velocity velocity) {
          if (!prediction.isEmpty()) {
            Set<Candidate> updated = overlayVelocity(prediction, velocity.velocity(), maximumCandidates);
            if (!updated.isEmpty()) prediction = updated;
          }
        } else {
          if (!prediction.isEmpty()) {
            Set<Candidate> updated = overlayClientState(
                prediction, clientState, maximumCandidates, true);
            if (!updated.isEmpty()) prediction = updated;
          }
        }
        continue;
      }

      if (value instanceof Packets.FlightToggle toggle) {
        if (toggle.flying()) {
          Phase8MovementValidation.Result violation =
              unauthorizedFlightResult(playerId, packet, clientState);
          if (violation != null) {
            results.add(violation);
            switch (violation.verdict()) {
              case POSSIBLE -> possible++;
              case UNCERTAIN -> {
                uncertain++;
                latestContinuation = Continuation.UNCERTAIN;
              }
              case IMPOSSIBLE -> {
                impossible++;
                latestContinuation = Continuation.IMPOSSIBLE;
              }
            }
          }
        }
        continue;
      }

      if (value instanceof Packets.WorldTransactionSend
          || value instanceof Packets.WorldTransactionAck
          || value instanceof Packets.PaperMovementRejection
          || value.mutatesWorld()) {
        continue;
      }

      if (!(value instanceof Packets.Move move)) {
        continue;
      }

      movementObservations++;
      Player observedBefore = clientState;
      clientState = State.apply(clientState, normalized);
      Player observedAfter = clientState;

      List<String> trace = new ArrayList<>();
      trace.add("PACKET seq=" + sequence
          + " receivedNanos=" + packet.receivedNanos()
          + " clientStatePosition=" + observedAfter.position());

      TickResolution tick = resolveMovementTick(
          packet, move, phase7TimingBySequence);
      trace.add("CLIENT_TICK " + tick.display()
          + " exact=" + tick.exact()
          + " source=" + tick.source());

      WorldSnapshot world = worldProvider == null ? null : worldProvider.apply(sequence);
      if (world == null) {
        latestContinuation = Continuation.UNCERTAIN;
        List<String> sources = List.of(
            "latency-compensated client world is unavailable for this packet");
        SearchResult search = uncertainSearch(
            prediction,
            sources.getFirst());
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, worldOrEmpty(world),
            tick, sources, search, false);
        results.add(result);
        uncertain++;
        if (move.position() != null) {
          rememberObservedMovement(observedBefore, observedAfter, tick);
        }
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            prediction, prediction, worldOrEmpty(world), sources, trace));
        continue;
      }

      trace.add("WORLD source=latency-compensated-packet-world"
          + " causalSequence=" + world.causalSequence()
          + " chunks=" + world.loadedChunks().size());

      Set<Candidate> predictedBefore = prediction;
      boolean predictionWasEmptyBeforeRoot = prediction.isEmpty();

      ensureRoot(playerId, packet, move, observedBefore, tick, trace);
      refreshFromCausalAuthorityIfStale(packet, move, observedBefore, tick, world, trace);

      /*
       * Grim keeps a client-side movement velocity separate from the server's
       * instantaneous velocity. Mirror that principle at bootstrap: when the
       * current frontier is empty OR is merely an authoritative root, a fresh
       * authority sample matching the observed pre-movement state can be used to
       * reconstruct the hidden client-tick start velocity from the actual movement
       * observation. This avoids treating Bukkit's server-side velocity as an
       * atomic client-tick velocity.
       */
      if (predictionWasEmptyBeforeRoot && move.position() != null) {
        Optional<Candidate> bootstrap = bootstrapPredictionFromObservedMovement(
            packet, move, observedBefore, observedAfter, tick, world, trace);
        if (bootstrap.isPresent()) {
          prediction = Set.of(bootstrap.orElseThrow());
          predictionTick = tick.clientTick();
          latestContinuation = Continuation.ACTIVE;
          lastPositionClientTick = tick.clientTick();

          Candidate candidate = bootstrap.orElseThrow();
          SearchResult bootstrapSearch = new SearchResult(
              Verdict.POSSIBLE,
              Set.of(candidate),
              1,
              1,
              0, 0, 0, 0,
              List.of(
                  "client movement bootstrap reconstructed the hidden start velocity from the observed tick",
                  "canonical Phase 5 replay reproduced the observed movement exactly"));
          List<String> bootstrapUncertainty = List.of(
              "client-side starting velocity was reconstructed from observed movement because the server velocity is not an atomic client-tick state");
          Phase8MovementValidation.Result result = validate(
              playerId, packet, move, observedBefore, observedAfter, world,
              tick, bootstrapUncertainty, bootstrapSearch, false);
          results.add(result);
          possible++;
          rememberObservedMovement(observedBefore, observedAfter, tick);
          trace.add("EVIDENCE POSSIBLE reason=CLIENT_MOVEMENT_BOOTSTRAP"
              + " reconstructedStartVelocityVerified=true");
          trace.add("FRONTIER_BOOTSTRAPPED source=CLIENT_MOVEMENT_OBSERVATION"
              + " tick=" + tick.clientTick());
          frames.add(frame(
              sequence, packet, tick, move, observedBefore, observedAfter,
              predictedBefore, prediction, world, bootstrapUncertainty, trace));
          continue;
        }
      }

      if (move.position() != null) {
        rememberObservedMovement(observedBefore, observedAfter, tick);
      }

      boolean stationaryPositionObservation = move.position() != null
          && positionExactlyMatches(observedBefore.position(), observedAfter.position())
          && observedBefore.onGround()
          && observedAfter.onGround();

      if (move.position() == null || stationaryPositionObservation) {
        boolean positionlessRotationObservation = move.position() == null;
        prediction = retargetRotation(prediction, move, maximumCandidates);
        Set<Candidate> rotated = prediction;
        boolean possibleObservation = !rotated.isEmpty();
        Set<Phase6Reachability.ObservedField> observedFields =
            positionlessRotationObservation
                ? EnumSet.of(Phase6Reachability.ObservedField.ROTATION)
                : EnumSet.of(
                    Phase6Reachability.ObservedField.POSITION,
                    Phase6Reachability.ObservedField.ROTATION,
                    Phase6Reachability.ObservedField.GROUND);
        SearchResult observationSearch = possibleObservation
            ? new SearchResult(
                Verdict.POSSIBLE,
                rotated,
                0,
                rotated.size(),
                0, 0, 0, 0,
                List.of(positionlessRotationObservation
                    ? "rotation is a retained client-state observation; no physics step is advanced"
                    : "stationary position/rotation observation; no physics step is advanced"))
            : uncertainSearch(prediction, "no prediction root exists for observation-only movement packet");
        /*
         * Observation-only look/stationary packets do not advance physics, so
         * their exact client tick is not required to model a trajectory. This
         * prevents harmless head movement from inheriting Phase 5 world-coverage
         * uncertainty from a zero-displacement physics step.
         */
        boolean timingExhaustive = possibleObservation;
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, world,
            tick, List.of(), observationSearch, timingExhaustive, observedFields);
        results.add(result);
        if (result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE) {
          possible++;
          latestContinuation = Continuation.ACTIVE;
          lastPositionClientTick = tick.clientTick();
        } else if (result.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE) {
          impossible++;
          latestContinuation = Continuation.IMPOSSIBLE;
          lastPositionClientTick = tick.clientTick();
        } else {
          uncertain++;
          latestContinuation = Continuation.UNCERTAIN;
        }
        trace.add(positionlessRotationObservation
            ? "OBSERVATION rotation-only prediction frontier retained=" + !prediction.isEmpty()
            : "OBSERVATION stationary-position packet retained=" + !prediction.isEmpty());
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            prediction, prediction, world, List.of(), trace));
        continue;
      }

      List<String> uncertaintySources = new ArrayList<>();
      Phase7Timing.EventTiming movementTiming = phase7TimingBySequence.get(sequence);
      boolean explicitTimingRangeExhaustive =
          explicitTimingRangeIsExhaustive(move, movementTiming);
      boolean phase7ChronologyUnmodeled =
          phase7TimingHasUnmodeledChronology(movementTiming);
      boolean explicitTimingFullyRepresented =
          explicitTimingRangeExhaustive
              && !phase7ChronologyUnmodeled;

      if (move.clientTick() != null && movementTiming != null) {
        trace.add("TIMING_GATE explicitRangeExhaustive=" + explicitTimingRangeExhaustive
            + " chronologyUnmodeled=" + phase7ChronologyUnmodeled
            + " historyTruncated=" + timingHistoryTruncated
            + " simulationRange=" + movementTiming.simulationClientTicks()
            + " simulationCandidates=" + movementTiming.possibleSimulationClientTicks());
        trace.add("PHASE7_WINDOWS " + phase7TimingWindows(movementTiming));
        trace.add("PHASE7_REASONS " + movementTiming.reasons());
      }

      /*
       * A fresh server snapshot that exactly matches the client's reported position
       * is a causal witness, not a physics root. Treat it as POSSIBLE and establish
       * the observed state as the new trusted frontier. This prevents a stale
       * prediction candidate from drifting away from a server-accepted movement and
       * then manufacturing an IMPOSSIBLE result from that drift.
       */
      if (move.position() != null) {
        AuthorityAnchor freshAuthority = freshCausalAuthority(packet);
        if (freshAuthority != null
            && positionsMatch(freshAuthority.context().serverPosition(), observedAfter.position())
            && freshAuthority.context().movementEnvironment().onGround() == observedAfter.onGround()
            && (move.onGround() == null
                || move.onGround() == freshAuthority.context().movementEnvironment().onGround())) {
          Candidate witness = authoritativeObservationWitness(
              freshAuthority, observedAfter, tick.clientTick(), sequence);
          SearchResult witnessSearch = new SearchResult(
              Verdict.POSSIBLE,
              Set.of(witness),
              0,
              1,
              0, 0, 0, 0,
              List.of("fresh causal server snapshot matches the observed movement state; no physics replay required"));
          Phase8MovementValidation.Result result = validate(
              playerId, packet, move, observedBefore, observedAfter, world,
              tick, uncertaintySources, witnessSearch, true);
          results.add(result);
          possible++;
          prediction = Set.of(witness);
          predictionTick = tick.clientTick();
          latestContinuation = Continuation.ACTIVE;
          lastPositionClientTick = tick.clientTick();
          rememberObservedMovement(observedBefore, observedAfter, tick);
          trace.add("EVIDENCE POSSIBLE reason=AUTHORITATIVE_ZERO_DELTA_WITNESS"
              + " authoritySequence=" + freshAuthority.sequence()
              + " authorityServerTick=" + freshAuthority.serverTick());
          trace.add("FRONTIER_REESTABLISHED source=AUTHORITATIVE_ZERO_DELTA_WITNESS"
              + " tick=" + tick.clientTick());
          frames.add(frame(
              sequence, packet, tick, move, observedBefore, observedAfter,
              predictedBefore, prediction, world, List.of(), trace));
          continue;
        }
      }

      if (!tick.known()) {
        uncertaintySources.add("client simulation tick has not been established by a client-tick boundary");
      }
      if (tick.timingUncertain() && !explicitTimingFullyRepresented) {
        uncertaintySources.add(tick.uncertaintyReason());
      }

      if (prediction.isEmpty()) {
        uncertaintySources.add("persistent prediction frontier is not anchored to an authoritative or correction state");
        latestContinuation = Continuation.UNCERTAIN;
        SearchResult search = uncertainSearch(prediction, String.join("; ", uncertaintySources));
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, world,
            tick, uncertaintySources, search, false);
        results.add(result);
        uncertain++;
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            predictedBefore, prediction, world, uncertaintySources, trace));
        continue;
      }

      Set<Candidate> withEntities =
          overlayEntityCollisions(prediction, entityCollisions(latestAuthority), maximumCandidates);
      if (!withEntities.isEmpty()) prediction = withEntities;
      Set<Candidate> withRotation = retargetRotation(prediction, move, maximumCandidates);
      if (!withRotation.isEmpty()) prediction = withRotation;

      if (!tick.exact()) {
        uncertaintySources.add("movement timing is not exact; the persistent predictor requires a bounded client-tick state");
      }

      if (predictionTick >= 0L && tick.clientTick() < predictionTick) {
        uncertaintySources.add(
            "movement client tick regressed behind the retained prediction frontier");
        latestContinuation = Continuation.UNCERTAIN;
        SearchResult search = uncertainSearch(
            prediction, String.join("; ", uncertaintySources));
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, world,
            tick, uncertaintySources, search, false);
        results.add(result);
        uncertain++;
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            predictedBefore, prediction, world, uncertaintySources, trace));
        continue;
      }

      boolean movedWithinCurrentClientTick =
          lastPositionClientTick == tick.clientTick()
              && !positionMatches(observedBefore.position(), observedAfter.position());

      if (movedWithinCurrentClientTick) {
        uncertaintySources.add(
            "multiple position-bearing packets changed position within one client tick; sub-tick trajectory is retained as unresolved evidence");
        latestContinuation = Continuation.UNCERTAIN;
        SearchResult search = uncertainSearch(
            prediction,
            String.join("; ", uncertaintySources));
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, world,
            tick, uncertaintySources, search, false);
        results.add(result);
        uncertain++;
        trace.add("FRONTIER_RETAINED reason=SUB_TICK_TRAJECTORY_UNMODELED");
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            predictedBefore, prediction, world, uncertaintySources, trace));
        continue;
      }

      if (!uncertaintySources.isEmpty()) {
        /*
         * Keep the prediction state. A timing problem is an evidence limitation,
         * not a license to replace the candidate frontier with the server state.
         */
        latestContinuation = Continuation.UNCERTAIN;
      }

      long targetTick = tick.clientTick();
      long startTick = predictionTick < 0L
          ? prediction.stream().mapToLong(c -> c.context().simulationTick()).max().orElse(targetTick)
          : predictionTick;
      if (predictionTick < 0L) {
        predictionTick = startTick;
      }

      long deltaTicks = targetTick - startTick;
      if (deltaTicks > MAX_INCREMENTAL_HORIZON) {
        uncertaintySources.add(
            "movement is too far beyond the retained prediction clock for bounded incremental simulation");
        latestContinuation = Continuation.UNCERTAIN;
        SearchResult search = uncertainSearch(prediction, String.join("; ", uncertaintySources));
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, world,
            tick, uncertaintySources, search, false);
        results.add(result);
        uncertain++;
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            predictedBefore, prediction, world, uncertaintySources, trace));
        continue;
      }

      AdvanceResult advance;
      if (explicitTimingFullyRepresented && movementTiming != null) {
        long earliestSimulationTick = movementTiming.simulationClientTicks().min();
        long latestSimulationTick = movementTiming.simulationClientTicks().max();
        advance = advancePredictionAcrossTimingRange(
            prediction,
            earliestSimulationTick,
            latestSimulationTick,
            inputHistory,
            world,
            maximumCandidates,
            sequence);
        trace.add("TIMING_OFFSETS range=" + earliestSimulationTick + ".."
            + latestSimulationTick
            + " candidates=" + movementTiming.possibleSimulationClientTicks()
            + " exhaustive=" + advance.exhaustive());
      } else {
        advance = advancePrediction(
            prediction,
            startTick,
            targetTick,
            inputHistory,
            world,
            maximumCandidates,
            sequence);
      }
      trace.add("PREDICT_FORWARD startTick=" + startTick
          + " targetTick=" + targetTick
          + " steps=" + advance.simulatedTicks()
          + " exhaustive=" + advance.exhaustive());
      trace.addAll(advance.trace());

      if (!advance.exhaustive()) {
        uncertaintySources.addAll(advance.reasons());
        latestContinuation = Continuation.UNCERTAIN;
        SearchResult search = uncertainSearch(
            prediction,
            String.join("; ", uncertaintySources));
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, world,
            tick, uncertaintySources, search, false);
        results.add(result);
        uncertain++;
        trace.add("FRONTIER_RETAINED after=" + prediction.size()
            + " tick=" + predictionTick);
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            predictedBefore, prediction, world, uncertaintySources, trace));
        continue;
      }

      prediction = advance.candidates();
      predictionTick = targetTick;

      Optional<Candidate> inertialRecovery = Optional.empty();
      if (matchingCandidates(prediction, observedAfter, move).isEmpty()) {
        inertialRecovery = recoverObservedInertialContinuation(
            packet, move, observedBefore, observedAfter, tick, world, trace);
        if (inertialRecovery.isPresent()) {
          prediction = Set.of(inertialRecovery.orElseThrow());
          predictionTick = targetTick;
          trace.add("EVIDENCE POSSIBLE reason=CLIENT_OBSERVED_INERTIAL_CONTINUATION");
        }
      }

      SearchResult search = inertialRecovery.isPresent()
          ? new SearchResult(
              Verdict.POSSIBLE,
              prediction,
              1,
              1,
              0, 0, 0, 0,
              List.of(
                  "observed displacement is a canonical ground-friction continuation of the preceding client movement",
                  "canonical Phase 5 replay reproduced the observed movement with neutral input"))
          : new SearchResult(
          Verdict.POSSIBLE,
          prediction,
          advance.simulatedTicks(),
          prediction.size(),
          0, 0, 0, 0,
          advance.reasons());

      boolean timingExhaustive =
          advance.exhaustive()
              && (explicitTimingFullyRepresented || tick.exact())
              && uncertaintySources.isEmpty();
      TickResolution validationTick =
          explicitTimingFullyRepresented
              ? tick.withTimingUncertaintyResolved(
                  "Phase 7 bounded simulation timing was exhaustively evaluated for every permitted offset")
              : tick;
      Phase8MovementValidation.Result result = validate(
          playerId, packet, move, observedBefore, observedAfter, world,
          validationTick, uncertaintySources, search, timingExhaustive);

      results.add(result);
      switch (result.verdict()) {
        case POSSIBLE -> {
          possible++;
          latestContinuation = Continuation.ACTIVE;
          Set<Candidate> matching = matchingCandidates(prediction, observedAfter, move);
          if (!matching.isEmpty()) {
            prediction = Set.copyOf(matching);
            trace.add("FRONTIER_COMMITTED matching=" + prediction.size());
          } else {
            trace.add("FRONTIER_RETAINED predicted candidates remain authoritative");
          }
        }
        case IMPOSSIBLE -> {
          impossible++;
          latestContinuation = Continuation.IMPOSSIBLE;
          /*
           * An impossible observation is evidence, not a trusted trajectory.
           * Retaining the contradicted frontier causes repeated drift and can turn
           * one model mismatch into a stream of false IMPOSSIBLE observations.
           * Rebuild from fresh causal authority on the next movement instead.
           */
          prediction = Set.of();
          predictionTick = -1L;
          trace.add("FRONTIER_RESET reason=OBSERVATION_CONTRADICTION");
          trace.add("OBSERVED_MOVEMENT_HISTORY_RETAINED reason=RECOVERY_EVIDENCE");
        }
        case UNCERTAIN -> {
          uncertain++;
          latestContinuation = Continuation.UNCERTAIN;
          /*
           * An uncertain observation is likewise not a safe baseline. Preserve
           * evidence, but require the next clean observation to establish a new
           * causally aligned root instead of carrying the ambiguity forward.
           */
          prediction = Set.of();
          predictionTick = -1L;
          trace.add("FRONTIER_RESET reason=UNCERTAIN_OBSERVATION");
          trace.add("OBSERVED_MOVEMENT_HISTORY_RETAINED reason=RECOVERY_EVIDENCE");
        }
      }

      if (result.verdict() != Phase8MovementValidation.Verdict.UNCERTAIN) {
        lastPositionClientTick = targetTick;
      }
      frames.add(frame(
          sequence, packet, tick, move, observedBefore, observedAfter,
          predictedBefore, prediction, world, uncertaintySources, trace));
    }

    if (results.isEmpty() && !prediction.isEmpty()) {
      latestContinuation = Continuation.ACTIVE;
    }

    return new Report(
        results,
        packets.size(),
        movementObservations,
        possible,
        uncertain,
        impossible,
        lastProcessedSequence,
        relativeClientTick,
        latestContinuation,
        !prediction.isEmpty(),
        frames);
  }

  private record TickResolution(
      long clientTick,
      boolean known,
      boolean exact,
      boolean timingUncertain,
      String source,
      String uncertaintyReason) {
    String display() {
      return known ? Long.toString(clientTick) : "unknown";
    }

    TickResolution withTimingUncertaintyResolved(String reason) {
      return new TickResolution(clientTick, known, exact, false, source, reason);
    }
  }

  private static boolean explicitTimingRangeIsExhaustive(
      Packets.Move move,
      Phase7Timing.EventTiming timing) {
    if (move.clientTick() == null || timing == null) return false;
    if (!timing.simulationCandidatesExhaustive()) return false;
    Phase7Timing.Range range = timing.simulationClientTicks();
    long span;
    try {
      span = Math.addExact(Math.subtractExact(range.max(), range.min()), 1L);
    } catch (ArithmeticException overflow) {
      return false;
    }
    if (span < 1L || span > Phase6Reachability.MAX_TIMING_OFFSETS) return false;
    List<Long> candidates = timing.possibleSimulationClientTicks();
    return candidates.size() == span;
  }

  private static List<String> phase7TimingWindows(Phase7Timing.EventTiming timing) {
    List<String> windows = new ArrayList<>();
    for (Phase7Timing.SynchronizationWindow window : timing.windows()) {
      windows.add(window.kind() + "@server="
          + window.firstServerTick() + ".." + window.lastServerTick()
          + " client=" + window.possibleClientTicks()
          + " reason=" + window.reason()
          + " seq=" + window.triggerSequence());
    }
    return List.copyOf(windows);
  }

  private static boolean phase7TimingHasUnmodeledChronology(
      Phase7Timing.EventTiming timing) {
    if (timing == null) return true;
    for (Phase7Timing.SynchronizationWindow window : timing.windows()) {
      switch (window.kind()) {
        case PACKET_GAP, SERVER_TICK_GAP, REORDERING, DUPLICATE,
            TELEPORT, VELOCITY, ACKNOWLEDGEMENT, RECOVERY, TIMING_BUDGET -> {
          return true;
        }
        case WORLD_UPDATE, STARTUP -> {
          // World visibility and startup windows do not by themselves make
          // a movement simulation-tick envelope unrepresentable.
        }
      }
    }
    for (String reason : timing.reasons()) {
      String normalized = reason.toLowerCase(Locale.ROOT);
      if (normalized.contains("capture sequence gap")
          || normalized.contains("out-of-order")
          || normalized.contains("duplicate capture sequence")
          || normalized.contains("before capture epoch")
          || normalized.contains("does not imply client inactivity")
          || normalized.contains("timing candidate materialization budget")
          || normalized.contains("timing history budget")
          || normalized.contains("movement timing is not fully synchronized")) {
        return true;
      }
    }
    return false;
  }

  private TickResolution resolveMovementTick(
      Packets.RawPacket packet,
      Packets.Move move,
      Map<Long, Phase7Timing.EventTiming> phase7TimingBySequence) {
    Phase7Timing.EventTiming timing = phase7TimingBySequence.get(packet.sequence());
    boolean timingUncertain = timing != null && timing.uncertain();

    /*
     * The captured client tick is the strongest simulation-clock fact available
     * for a movement packet. Phase 7 is still authoritative for chronology:
     * when its envelope is uncertain, that uncertainty is retained as evidence
     * even though an explicit client tick may keep the simulation step exact.
     */
    if (move.clientTick() != null) {
      long tick = move.clientTick();
      relativeClientTick = Math.max(relativeClientTick, tick);
      String source = timing != null
          ? "phase7-explicit-client-tick"
          : "packet-client-tick";
      String reason = timingUncertain
          ? "Phase 7 timing envelope retains chronology uncertainty for an explicitly captured client tick"
          : "explicit client tick captured from the protocol movement chronology";
      /*
       * An explicitly captured client tick is self-contained timing evidence for
       * this movement. Older timing packets may have been evicted from the bounded
       * history without making this packet's own simulation tick ambiguous.
       */
      return new TickResolution(
          tick, true, true, timingUncertain, source, reason);
    }

    /*
     * Preserve the existing boundary-watermark behavior for captures that do
     * not carry an explicit client tick on the movement packet.
     */
    if (hasClientTickBoundary) {
      String source = timing != null ? "phase7-client-tick-boundary-watermark"
          : "client-tick-boundary-watermark";
      String reason = timingUncertain
          ? "Phase 7 timing envelope retains chronology uncertainty around the boundary watermark"
          : "client tick boundary established the current relative client tick";
      return new TickResolution(
          relativeClientTick, true, true, timingUncertain, source, reason);
    }

    if (timing != null && timing.simulationClientTickEnvelope().known()) {
      long tick = Math.max(0L, timing.simulationClientTicks().min());
      relativeClientTick = Math.max(relativeClientTick, tick);
      boolean exact = timing.simulationClientTickEnvelope().isExact()
          && timing.simulationCandidatesExhaustive()
          && !timingUncertain;
      String reason = timingUncertain
          ? "Phase 7 timing envelope is uncertain and no explicit client tick was captured"
          : "Phase 7 timing envelope supplied the movement simulation tick";
      return new TickResolution(
          tick, true, exact, timingUncertain,
          exact ? "phase7-temporal-envelope-exact" : "phase7-temporal-envelope-range",
          reason);
    }

    return new TickResolution(
        0L, false, false, true, "phase7-timing-missing",
        "Phase 7 timing record was unavailable and no explicit client tick was captured");
  }

  private void rememberTimingPacket(Packets.RawPacket packet) {
    if (timingEpochNanos < 0L) timingEpochNanos = packet.receivedNanos();
    timingHistory.addLast(packet);
    while (timingHistory.size() > MAX_TIMING_HISTORY_EVENTS) {
      timingHistory.removeFirst();
      timingHistoryTruncated = true;
    }
  }

  private Phase7Timing.Reconstruction reconstructPhase7Timing() {
    if (timingHistory.isEmpty()) {
      return Phase7Timing.reconstruct(
          Timeline.assign(List.of(), 0L, phase7TimingConfig.serverTickNanos()),
          phase7TimingConfig);
    }
    List<Packets.RawPacket> raw = List.copyOf(timingHistory);
    List<Packets.NormalizedPacket> normalized = new Packets.Normalizer().normalize(raw);
    Timeline.Snapshot timeline = Timeline.assign(
        normalized,
        Math.max(0L, timingEpochNanos),
        phase7TimingConfig.serverTickNanos());
    return Phase7Timing.reconstruct(timeline, phase7TimingConfig);
  }

  private long resolvedCorrectionTick() {
    return Math.max(0L, relativeClientTick);
  }

  private void refreshFromCausalAuthorityIfStale(
      Packets.RawPacket movementPacket,
      Packets.Move move,
      Player observedBefore,
      TickResolution tick,
      WorldSnapshot world,
      List<String> trace) {
    if (prediction.isEmpty() || !tick.known()) return;
    AuthorityAnchor authority = latestCausalAuthority(movementPacket);
    if (authority == null || authority.clientTick() == null) return;

    long authorityTick = authority.clientTick();
    long lag = tick.clientTick() - predictionTick;
    if (predictionTick < 0L || lag <= PREDICTION_RESYNC_LAG_TICKS) return;
    if (authorityTick > tick.clientTick()) return;

    long previousPredictionTick = predictionTick;

    /*
     * The authority sample is a current server-side spatial observation. Its
     * client-tick watermark is not an atomic timestamp for that position, so it
     * must never be used to place the spatial sample deep in the past relative
     * to the movement packet. Align the fresh server sample to the movement's
     * preceding client boundary and let the explicit movement tick provide the
     * chronology.
     */
    long rootTick = Math.max(0L, tick.clientTick() - 1L);

    Player rootPlayer = withClientRotation(
        predictionAnchorFromAuthority(authority.context(), tick.clientTick(), world, trace),
        observedBefore.yaw(),
        observedBefore.pitch());

    prediction = Set.of(candidateFromPlayer(
        rootPlayer,
        rootTick,
        "CAUSAL_AUTHORITY_RESYNC",
        authority.sequence(),
        EntityCollisions.of(authority.context().entityBoxes()),
        authority.context().movementEnvironment()));
    predictionTick = rootTick;
    latestContinuation = Continuation.ACTIVE;

    trace.add("ROOT_REFRESH reason=PREDICTION_LAG"
        + " predictionTickBefore=" + previousPredictionTick
        + " authorityClientTick=" + authorityTick
        + " rootTick=" + rootTick
        + " authoritySequence=" + authority.sequence()
        + " authorityServerTick=" + authority.serverTick()
        + " clientWatermarkUsedForSpatialRoot=false");
  }

  private void ensureRoot(
      String playerId,
      Packets.RawPacket movementPacket,
      Packets.Move move,
      Player observedBefore,
      TickResolution tick,
      List<String> trace) {
    if (!prediction.isEmpty()) return;
    long targetTick = tick.clientTick();
    AuthorityAnchor authority = latestCausalAuthority(movementPacket);
    Player rootPlayer;
    long rootTick;

    if (authority != null) {
      rootPlayer = predictionAnchorFromAuthority(authority.context(), targetTick, null, trace);
      /*
       * Same-server-tick PlayerContext is a server-side sample, not an atomic
       * pre-movement timestamp. Treat it as a prior state for target-1 rather
       * than pretending the server's same-tick position is the client state.
       */
      if (authority.serverTick() == movementServerTick(movementPacket)) {
        rootTick = Math.max(0L, targetTick - 1L);
        trace.add("ROOT authority=same-server-tick observation; aligned to target-1");
      } else if (authority.clientTick() != null && authority.clientTick() <= targetTick) {
        rootTick = authority.clientTick();
        trace.add("ROOT authority=client-watermark tick=" + rootTick);
      } else {
        rootTick = Math.max(0L, targetTick - 1L);
        trace.add("ROOT authority=causal-preceding-server-sample aligned to target-1");
      }
      rootPlayer = withClientRotation(rootPlayer, observedBefore.yaw(), observedBefore.pitch());
      prediction = Set.of(candidateFromPlayer(
          rootPlayer, rootTick, "AUTHORITATIVE_ANCHOR",
          authority.sequence(), EntityCollisions.of(authority.context().entityBoxes()),
          authority.context().movementEnvironment()));
      predictionTick = rootTick;
      latestContinuation = Continuation.ACTIVE;
      return;
    }

    if (initialAnchor != null && !initialAnchor.uncertain()) {
      rootPlayer = withClientRotation(
          initialAnchor, observedBefore.yaw(), observedBefore.pitch());
      rootTick = Math.max(0L, targetTick - 1L);
      prediction = Set.of(candidateFromPlayer(
          rootPlayer,
          rootTick,
          "INITIAL_AUTHORITATIVE_ANCHOR",
          -1L,
          entityCollisions(latestAuthority)));
      predictionTick = rootTick;
      latestContinuation = Continuation.ACTIVE;
      trace.add("ROOT initial-authoritative-anchor tick=" + rootTick);
    } else {
      latestContinuation = Continuation.UNANCHORED;
    }
  }

  private AuthorityAnchor latestCausalAuthority(Packets.RawPacket movementPacket) {
    if (latestAuthority == null) return null;
    if (latestAuthority.sequence() >= movementPacket.sequence()) return null;
    if (latestAuthority.receivedNanos() > movementPacket.receivedNanos()) return null;
    return latestAuthority;
  }

  private Player predictionAnchorFromAuthority(
      Packets.PlayerContext context,
      long targetTick,
      WorldSnapshot world,
      List<String> trace) {
    Player authority = playerFromAuthority(context);
    /*
     * The live authoritative velocity is captured from the server-side movement
     * phase. Phase 5 candidates are state boundaries after the prior client movement
     * tick, so normal dry-air vertical velocity must be advanced through the vanilla
     * gravity/drag transition before it becomes the candidate boundary velocity.
     */
    double horizontalX = authority.velocity().x();
    double horizontalZ = authority.velocity().z();
    Optional<Vec3> inferredHorizontal =
        inferredHorizontalBoundaryVelocity(context, targetTick, world);
    if (inferredHorizontal.isPresent()) {
      horizontalX = inferredHorizontal.get().x();
      horizontalZ = inferredHorizontal.get().z();
      trace.add("ROOT_HORIZONTAL source=client-observed-prev-displacement"
          + " velocity=" + inferredHorizontal.get()
          + " authorityVelocity=" + authority.velocity()
          + " authorityPositionDeltaFromLastObservation="
          + (lastObservedMovementPosition == null
              ? "unavailable"
              : new Vec3(
                  context.serverPosition().x() - lastObservedMovementPosition.x(),
                  context.serverPosition().y() - lastObservedMovementPosition.y(),
                  context.serverPosition().z() - lastObservedMovementPosition.z())));
    } else {
      trace.add("ROOT_HORIZONTAL source=authoritative-velocity"
          + " velocity=" + authority.velocity()
          + " clientInference=unavailable");
    }
    double verticalVelocity = authority.velocity().y();
    MovementEnvironment movementEnvironment = context.movementEnvironment();
    if (!authority.onGround()
        && movementEnvironment.fluid() == Fluid.NONE
        && !movementEnvironment.climbable()
        && !movementEnvironment.gliding()) {
      MovementEffects effects = movementEffects(authority);
      double gravity = Vanilla12111RichPhysics.GRAVITY
          * movementEnvironment.gravityMultiplier();
      verticalVelocity =
          (verticalVelocity - gravity * effects.fallGravityMultiplier())
              * Vanilla12111RichPhysics.AIR_VERTICAL_DRAG;
    }

    return new Player(
        authority.position(),
        new Vec3(horizontalX, verticalVelocity, horizontalZ),
        authority.yaw(), authority.pitch(), authority.onGround(), authority.gamemode(),
        authority.effects(), authority.awaitingTeleport(), authority.uncertain(), authority.input(),
        authority.attributes(), authority.pose(), authority.environment(),
        authority.clientTickRange(), authority.provenance(), authority.uncertaintyReasons());
  }

  private void clearObservedMovementHistory() {
    previousObservedMovementPosition = null;
    lastObservedMovementPosition = null;
    previousObservedMovementClientTick = -1L;
    lastObservedMovementClientTick = -1L;
    lastObservedMovementPriorGround = false;
  }

  private void rememberObservedMovement(
      Player observedBefore,
      Player observedAfter,
      TickResolution tick) {
    if (!tick.known()) return;

    /*
     * Keep the last two distinct client-tick endpoints. A client can emit
     * multiple position packets inside one client tick; treating those packets
     * as separate tick endpoints destroys the adjacent-tick displacement used
     * to reconstruct the client-side boundary velocity during stale resync.
     */
    if (lastObservedMovementClientTick == tick.clientTick()) {
      lastObservedMovementPosition = observedAfter.position();
      lastObservedMovementPriorGround = observedBefore.onGround();
      return;
    }

    previousObservedMovementPosition = lastObservedMovementPosition;
    previousObservedMovementClientTick = lastObservedMovementClientTick;
    lastObservedMovementPosition = observedAfter.position();
    lastObservedMovementClientTick = tick.clientTick();
    lastObservedMovementPriorGround = observedBefore.onGround();
  }

  private Optional<Vec3> inferredHorizontalBoundaryVelocity(
      Packets.PlayerContext authority,
      long targetTick,
      WorldSnapshot world) {
    if (world == null
        || targetTick < 2L
        || previousObservedMovementPosition == null
        || lastObservedMovementPosition == null
        || previousObservedMovementClientTick != targetTick - 2L
        || lastObservedMovementClientTick != targetTick - 1L) {
      return Optional.empty();
    }

    MovementEnvironment environment = authority.movementEnvironment();
    if (environment.fluid() != Fluid.NONE
        || environment.climbable()
        || environment.gliding()) {
      return Optional.empty();
    }

    double dx = lastObservedMovementPosition.x() - previousObservedMovementPosition.x();
    double dz = lastObservedMovementPosition.z() - previousObservedMovementPosition.z();
    if (Math.hypot(dx, dz) <= 1.0E-12) return Optional.empty();

    double horizontalFactor;
    if (lastObservedMovementPriorGround) {
      // Ground friction for the prior movement was determined from the block
      // beneath the pre-movement position, not beneath the post-jump position.
      int supportX = (int) Math.floor(previousObservedMovementPosition.x());
      int supportY = (int) Math.floor(previousObservedMovementPosition.y() - 1.0E-4);
      int supportZ = (int) Math.floor(previousObservedMovementPosition.z());
      if (world.coverageAt(supportX, supportY, supportZ) != dev.phantom.ac.world.Coverage.KNOWN) {
        return Optional.empty();
      }
      dev.phantom.ac.world.BlockState support = world.requireBlockAt(supportX, supportY, supportZ);
      if (support.isUnsupported()) return Optional.empty();
      horizontalFactor =
          dev.phantom.ac.world.v12111.BlockCatalogue12111.slipperiness(support)
              * Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION;
    } else {
      horizontalFactor = Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION;
    }

    return Optional.of(new Vec3(dx * horizontalFactor, 0.0, dz * horizontalFactor));
  }

  private boolean isAuthoritativeRootFrontier(
      Player observedBefore,
      TickResolution tick) {
    if (!tick.known() || prediction.size() != 1 || predictionTick < 0L) return false;
    if (predictionTick != Math.max(0L, tick.clientTick() - 1L)) return false;

    Candidate candidate = prediction.iterator().next();
    String source = candidate.provenance().input();
    if (!"AUTHORITATIVE_ANCHOR".equals(source)
        && !"CAUSAL_AUTHORITY_RESYNC".equals(source)) {
      return false;
    }
    return positionsMatch(candidate.context().player().position(), observedBefore.position())
        && candidate.context().player().onGround() == observedBefore.onGround();
  }

  private Optional<Candidate> recoverObservedInertialContinuation(
      Packets.RawPacket movementPacket,
      Packets.Move move,
      Player observedBefore,
      Player observedAfter,
      TickResolution tick,
      WorldSnapshot world,
      List<String> trace) {
    if (!tick.known() || !tick.exact() || move.position() == null
        || tick.clientTick() < 2L
        || previousObservedMovementPosition == null
        || lastObservedMovementPosition == null
        || previousObservedMovementClientTick != tick.clientTick() - 2L
        || lastObservedMovementClientTick != tick.clientTick() - 1L) {
      return Optional.empty();
    }

    if (!positionMatches(lastObservedMovementPosition, observedBefore.position())
        || !lastObservedMovementPriorGround
        || !observedBefore.onGround()
        || !observedAfter.onGround()
        || (move.onGround() != null && !move.onGround())) {
      return Optional.empty();
    }

    AuthorityAnchor authority = freshCausalAuthority(movementPacket);
    if (authority == null
        || !positionsMatch(authority.context().serverPosition(), observedBefore.position())
        || authority.context().movementEnvironment().onGround() != observedBefore.onGround()) {
      return Optional.empty();
    }

    MovementEnvironment environment = authority.context().movementEnvironment();
    if (environment.fluid() != Fluid.NONE
        || environment.climbable()
        || environment.gliding()) {
      return Optional.empty();
    }

    int supportX = (int) Math.floor(previousObservedMovementPosition.x());
    int supportY = (int) Math.floor(previousObservedMovementPosition.y() - 1.0E-4);
    int supportZ = (int) Math.floor(previousObservedMovementPosition.z());
    if (world.coverageAt(supportX, supportY, supportZ)
        != dev.phantom.ac.world.Coverage.KNOWN) {
      return Optional.empty();
    }
    var support = world.requireBlockAt(supportX, supportY, supportZ);
    if (support == null || support.isUnsupported()) return Optional.empty();

    double horizontalFactor =
        dev.phantom.ac.world.v12111.BlockCatalogue12111.slipperiness(support)
            * Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION;

    double previousDx = lastObservedMovementPosition.x() - previousObservedMovementPosition.x();
    double previousDz = lastObservedMovementPosition.z() - previousObservedMovementPosition.z();
    double currentDx = observedAfter.position().x() - observedBefore.position().x();
    double currentDz = observedAfter.position().z() - observedBefore.position().z();
    double currentDy = observedAfter.position().y() - observedBefore.position().y();

    if (Math.abs(currentDy) > POSITION_TOLERANCE) return Optional.empty();

    double expectedDx = previousDx * horizontalFactor;
    double expectedDz = previousDz * horizontalFactor;
    double continuationError = Math.hypot(currentDx - expectedDx, currentDz - expectedDz);
    if (!Double.isFinite(continuationError) || continuationError > 1.0E-3) {
      return Optional.empty();
    }

    Vec3 startVelocity = new Vec3(currentDx, 0.0, currentDz);
    if (Math.hypot(startVelocity.x(), startVelocity.z()) > 1.25) return Optional.empty();

    Player authorityState = playerFromAuthority(authority.context());
    Simulation.AdvancedInput neutral = new Simulation.AdvancedInput(0, 0, false, false, false);
    Player reconstructedStart = new Player(
        observedBefore.position(),
        startVelocity,
        move.yaw() == null ? observedBefore.yaw() : move.yaw(),
        move.pitch() == null ? observedBefore.pitch() : move.pitch(),
        observedBefore.onGround(),
        authorityState.gamemode(),
        authorityState.effects(),
        authorityState.awaitingTeleport(),
        false,
        Optional.of(neutral),
        authorityState.attributes(),
        authorityState.pose(),
        authorityState.environment(),
        observedBefore.clientTickRange(),
        authorityState.provenance(),
        authorityState.uncertaintyReasons());

    Vanilla12111RichPhysics.Context context = new Vanilla12111RichPhysics.Context(
        tick.clientTick() - 1L,
        reconstructedStart,
        neutral,
        world,
        simulationEnvironmentFor(environment),
        reconstructedStart.attributes(),
        movementEffects(reconstructedStart),
        reconstructedStart.pose(),
        environment,
        reconstructedStart.pose() == Pose.SLEEPING,
        EntityCollisions.of(authority.context().entityBoxes()));
    Vanilla12111RichPhysics.StepResult step =
        new Vanilla12111RichPhysics().step(context);

    if (step.state().uncertain()
        || step.collided()
        || !positionsMatch(step.state().position(), observedAfter.position())
        || step.state().onGround() != observedAfter.onGround()) {
      return Optional.empty();
    }

    trace.add("INERTIAL_RECOVERY previousDelta="
        + new Vec3(previousDx, 0.0, previousDz)
        + " expectedCurrentDelta=" + new Vec3(expectedDx, 0.0, expectedDz)
        + " observedCurrentDelta=" + new Vec3(currentDx, currentDy, currentDz)
        + " friction=" + horizontalFactor
        + " inputHistoryExplanation=neutral-continuation");

    return Optional.of(candidateFromPlayer(
        step.state(),
        tick.clientTick(),
        "CLIENT_OBSERVED_INERTIAL_CONTINUATION",
        authority.sequence(),
        EntityCollisions.of(authority.context().entityBoxes()),
        environment));
  }

  private Optional<Candidate> bootstrapPredictionFromObservedMovement(
      Packets.RawPacket movementPacket,
      Packets.Move move,
      Player observedBefore,
      Player observedAfter,
      TickResolution tick,
      WorldSnapshot world,
      List<String> trace) {
    if (!tick.known() || move.position() == null || tick.clientTick() <= 0L) {
      return Optional.empty();
    }

    AuthorityAnchor authority = freshCausalAuthority(movementPacket);
    if (authority == null) return Optional.empty();

    if (!positionsMatch(authority.context().serverPosition(), observedBefore.position())
        || authority.context().movementEnvironment().onGround() != observedBefore.onGround()
        || (move.onGround() != null && move.onGround() != observedAfter.onGround())) {
      return Optional.empty();
    }

    long simulationTick = tick.clientTick() - 1L;
    InputConstraint input = inputForSimulationTick(
        inputHistory, simulationTick, movementPacket.sequence());
    Optional<Simulation.AdvancedInput> keyInput =
        inputConstraintToAdvancedInput(input);
    if (keyInput.isEmpty()) return Optional.empty();

    MovementEnvironment environment = authority.context().movementEnvironment();
    Simulation.AdvancedInput advancedInput = new Simulation.AdvancedInput(
        keyInput.orElseThrow().forward(),
        keyInput.orElseThrow().strafe(),
        keyInput.orElseThrow().jump(),
        environment.sprinting(),
        environment.sneaking());

    Player authorityState = playerFromAuthority(authority.context());
    float yaw = move.yaw() == null ? observedBefore.yaw() : move.yaw();
    float pitch = move.pitch() == null ? observedBefore.pitch() : move.pitch();
    Player startTemplate = new Player(
        observedBefore.position(),
        authorityState.velocity(),
        yaw,
        pitch,
        observedBefore.onGround(),
        authorityState.gamemode(),
        authorityState.effects(),
        authorityState.awaitingTeleport(),
        false,
        Optional.of(advancedInput),
        authorityState.attributes(),
        authorityState.pose(),
        observedBefore.environment(),
        observedBefore.clientTickRange(),
        authorityState.provenance(),
        authorityState.uncertaintyReasons());

    // The authoritative movement environment carries actual sprint/sneak state;
    // PLAYER_INPUT sprint/sneak bits are key-state evidence, not movement-state authority.
    Vec3 observedDelta = new Vec3(
        observedAfter.position().x() - observedBefore.position().x(),
        observedAfter.position().y() - observedBefore.position().y(),
        observedAfter.position().z() - observedBefore.position().z());

    Optional<Vec3> startVelocity = reconstructCollisionFreeStartVelocity(
        startTemplate, advancedInput, observedDelta, world, environment);
    if (startVelocity.isEmpty()) return Optional.empty();

    double horizontalSpeed = Math.hypot(
        startVelocity.orElseThrow().x(), startVelocity.orElseThrow().z());
    if (!Double.isFinite(horizontalSpeed) || horizontalSpeed > 1.25
        || Math.abs(startVelocity.orElseThrow().y()) > 4.0) {
      trace.add("BOOTSTRAP_REJECTED reason=starting velocity outside conservative bound"
          + " velocity=" + startVelocity.orElseThrow());
      return Optional.empty();
    }

    Player reconstructedStart = new Player(
        startTemplate.position(),
        startVelocity.orElseThrow(),
        startTemplate.yaw(),
        startTemplate.pitch(),
        startTemplate.onGround(),
        startTemplate.gamemode(),
        startTemplate.effects(),
        startTemplate.awaitingTeleport(),
        false,
        startTemplate.input(),
        startTemplate.attributes(),
        startTemplate.pose(),
        startTemplate.environment(),
        startTemplate.clientTickRange(),
        startTemplate.provenance(),
        startTemplate.uncertaintyReasons());

    Vanilla12111RichPhysics.Context context = new Vanilla12111RichPhysics.Context(
        simulationTick,
        reconstructedStart,
        advancedInput.orElseThrow(),
        world,
        simulationEnvironmentFor(environment),
        reconstructedStart.attributes(),
        movementEffects(reconstructedStart),
        reconstructedStart.pose(),
        environment,
        reconstructedStart.pose() == Pose.SLEEPING,
        EntityCollisions.of(authority.context().entityBoxes()));
    Vanilla12111RichPhysics.StepResult step =
        new Vanilla12111RichPhysics().step(context);

    if (step.state().uncertain()
        || !positionsMatch(step.state().position(), observedAfter.position())
        || step.state().onGround() != observedAfter.onGround()) {
      trace.add("BOOTSTRAP_REJECTED reason=canonical physics replay did not reproduce observation"
          + " reconstructed=" + step.state().position()
          + " observed=" + observedAfter.position()
          + " reconstructedGround=" + step.state().onGround()
          + " observedGround=" + observedAfter.onGround());
      return Optional.empty();
    }

    Player after = new Player(
        step.state().position(),
        step.state().velocity(),
        observedAfter.yaw(),
        observedAfter.pitch(),
        step.state().onGround(),
        step.state().gamemode(),
        step.state().effects(),
        step.state().awaitingTeleport(),
        false,
        step.state().input(),
        step.state().attributes(),
        step.state().pose(),
        step.state().environment(),
        observedAfter.clientTickRange(),
        step.state().provenance(),
        step.state().uncertaintyReasons());

    Candidate candidate = candidateFromPlayer(
        after,
        tick.clientTick(),
        "CLIENT_MOVEMENT_BOOTSTRAP",
        authority.sequence(),
        EntityCollisions.of(authority.context().entityBoxes()),
        environment);
    trace.add("BOOTSTRAP_START simulationTick=" + simulationTick
        + " observedDelta=" + observedDelta
        + " reconstructedStartVelocity=" + startVelocity.orElseThrow()
        + " authoritativeVelocity=" + authority.context().serverVelocity());
    return Optional.of(candidate);
  }

  private Optional<Vec3> reconstructCollisionFreeStartVelocity(
      Player start,
      Simulation.AdvancedInput input,
      Vec3 observedDelta,
      WorldSnapshot world,
      MovementEnvironment environment) {
    if (environment.fluid() != Fluid.NONE
        || environment.climbable()
        || environment.gliding()) {
      return Optional.empty();
    }

    double inputMagnitude = Math.hypot(input.forward(), input.strafe());
    double inputScale = inputMagnitude > 1.0 ? 1.0 / Math.sqrt(2.0) : 1.0;
    double inputAcceleration;

    if (start.onGround()) {
      if (inputMagnitude == 0.0) {
        inputAcceleration = 0.0;
      } else {
        int x = (int) Math.floor(start.position().x());
        int y = (int) Math.floor(start.position().y() - 1.0E-4);
        int z = (int) Math.floor(start.position().z());
        if (world.coverageAt(x, y, z) != dev.phantom.ac.world.Coverage.KNOWN) {
          return Optional.empty();
        }
        var support = world.requireBlockAt(x, y, z);
        if (support == null || support.isUnsupported()) return Optional.empty();
        double movementSpeed = start.attributes().value() * movementEffects(start).speedMultiplier();
        if (environment.sprinting()) movementSpeed *= Vanilla12111RichPhysics.SPRINTING_SPEED_MULTIPLIER;
        if (environment.sneaking()) movementSpeed *= 0.3;
        double frictionInfluencedSpeed = movementSpeed
            * Vanilla12111RichPhysics.FRICTION_SPEED_FACTOR
            / Math.pow(dev.phantom.ac.world.v12111.BlockCatalogue12111.slipperiness(support), 3.0);
        inputAcceleration = inputMagnitude > 1.0
            ? frictionInfluencedSpeed
            : frictionInfluencedSpeed * Vanilla12111RichPhysics.INPUT_FRICTION;
      }
    } else {
      double offGroundSpeed = input.sprint()
          ? Vanilla12111RichPhysics.SPRINT_AIR_ACCEL
          : Vanilla12111RichPhysics.AIR_ACCEL;
      inputAcceleration = inputMagnitude > 1.0
          ? offGroundSpeed
          : offGroundSpeed * Vanilla12111RichPhysics.INPUT_FRICTION;
    }

    double yaw = Math.toRadians(start.yaw());
    double accelerationX = inputScale * (
        input.strafe() * inputAcceleration * Math.cos(yaw)
            - input.forward() * inputAcceleration * Math.sin(yaw));
    double accelerationZ = inputScale * (
        input.forward() * inputAcceleration * Math.cos(yaw)
            + input.strafe() * inputAcceleration * Math.sin(yaw));

    double boostX = 0.0;
    double boostZ = 0.0;
    boolean jumped = input.jump()
        && start.onGround()
        && start.pose() != Pose.SLEEPING
        && environment.fluid() == Fluid.NONE
        && !environment.climbable()
        && !environment.gliding();
    if (jumped && input.sprint()) {
      boostX = -Math.sin(yaw) * Vanilla12111RichPhysics.SPRINT_JUMP_HORIZONTAL_BOOST;
      boostZ = Math.cos(yaw) * Vanilla12111RichPhysics.SPRINT_JUMP_HORIZONTAL_BOOST;
    }

    return Optional.of(new Vec3(
        observedDelta.x() - accelerationX - boostX,
        jumped
            ? observedDelta.y() - Vanilla12111RichPhysics.JUMP - movementEffects(start).jumpVelocityAdd()
            : observedDelta.y(),
        observedDelta.z() - accelerationZ - boostZ));
  }

  private static Optional<Simulation.AdvancedInput> inputConstraintToAdvancedInput(
      InputConstraint constraint) {
    if (constraint.forward().isEmpty()
        || constraint.strafe().isEmpty()
        || constraint.jump().isEmpty()
        || constraint.sprint().isEmpty()
        || constraint.sneak().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Simulation.AdvancedInput(
        constraint.forward().getAsInt(),
        constraint.strafe().getAsInt(),
        constraint.jump().get(),
        constraint.sprint().get(),
        constraint.sneak().get()));
  }

  private AuthorityAnchor freshCausalAuthority(Packets.RawPacket movementPacket) {
    AuthorityAnchor authority = latestCausalAuthority(movementPacket);
    if (authority == null) return null;
    Long movementServerTick = movementPacket.provenance().authoritativeServerTick();
    if (movementServerTick == null) {
      /*
       * Replay/unit captures may omit the server-tick association. Sequence and
       * receipt ordering still establish causal freshness for an observation witness.
       */
      return authority;
    }
    long age = movementServerTick - authority.serverTick();
    if (age < 0L || age > 1L) return null;
    return authority;
  }

  private static boolean positionsMatch(Vec3 left, Vec3 right) {
    return positionDistanceSquared(left, right) <= POSITION_TOLERANCE * POSITION_TOLERANCE;
  }

  private static double positionDistanceSquared(Vec3 left, Vec3 right) {
    double dx = left.x() - right.x();
    double dy = left.y() - right.y();
    double dz = left.z() - right.z();
    return dx * dx + dy * dy + dz * dz;
  }

  private Candidate authoritativeObservationWitness(
      AuthorityAnchor authority,
      Player observed,
      long simulationTick,
      long movementSequence) {
    Player authoritative = playerFromAuthority(authority.context());
    Player witnessPlayer = new Player(
        observed.position(),
        authoritative.velocity(),
        observed.yaw(),
        observed.pitch(),
        observed.onGround(),
        authoritative.gamemode(),
        authoritative.effects(),
        authoritative.awaitingTeleport(),
        false,
        observed.input(),
        authoritative.attributes(),
        authoritative.pose(),
        authoritative.environment(),
        observed.clientTickRange(),
        authoritative.provenance(),
        authoritative.uncertaintyReasons());
    MovementEnvironment environment = movementEnvironmentOf(witnessPlayer);
    Context context = new Context(
        Math.max(0L, simulationTick),
        witnessPlayer,
        simulationEnvironmentFor(environment),
        witnessPlayer.attributes(),
        movementEffects(witnessPlayer),
        witnessPlayer.pose(),
        environment,
        witnessPlayer.pose() == Pose.SLEEPING,
        EntityCollisions.of(authority.context().entityBoxes()));
    return new Candidate(
        nextWitnessCandidateId(),
        context,
        new Phase6Reachability.Provenance(
            0L,
            authority.sequence(),
            Math.max(0L, simulationTick),
            "AUTHORITATIVE_ZERO_DELTA",
            "AUTHORITY",
            "None",
            List.of(
                "fresh server snapshot matches the observed client position",
                "server snapshot is used only as an observation witness, never as a physics replay root"),
            1,
            List.of()));
  }

  private long nextWitnessCandidateId() {
    return nextCandidateId++;
  }

  private static Player playerFromAuthority(Packets.PlayerContext context) {
    State.Environment environment =
        context.movementEnvironment().fluid() == Fluid.WATER
            ? State.Environment.WATER
            : context.movementEnvironment().fluid() == Fluid.LAVA
                ? State.Environment.LAVA
                : context.movementEnvironment().climbable()
                    ? State.Environment.CLIMBABLE
                    : State.Environment.DRY;
    return new Player(
        context.serverPosition(),
        context.serverVelocity(),
        0f,
        0f,
        context.movementEnvironment().onGround(),
        context.gamemode(),
        context.effects(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        context.attributes(),
        context.pose(),
        environment,
        State.TickRange.unknown(),
        State.Provenance.UNKNOWN,
        Set.of());
  }

  private Candidate candidateFromPlayer(
      Player player,
      long simulationTick,
      String source,
      long parentSequence,
      EntityCollisions entityCollisions) {
    return candidateFromPlayer(
        player, simulationTick, source, parentSequence, entityCollisions, null);
  }

  private Candidate candidateFromPlayer(
      Player player,
      long simulationTick,
      String source,
      long parentSequence,
      EntityCollisions entityCollisions,
      MovementEnvironment movementEnvironmentOverride) {
    MovementEnvironment environment = movementEnvironmentOverride == null
        ? movementEnvironmentOf(player)
        : movementEnvironmentOverride;
    Context context = new Context(
        simulationTick,
        player,
        simulationEnvironmentFor(environment),
        player.attributes(),
        movementEffects(player),
        player.pose(),
        environment,
        player.pose() == Pose.SLEEPING,
        entityCollisions == null ? EntityCollisions.NONE_TRACKED : entityCollisions);
    long id = nextCandidateId++;
    long parentId = parentSequence < 0L ? -1L : parentSequence;
    Candidate candidate = new Candidate(
        id,
        context,
        new Phase6Reachability.Provenance(
            id,
            parentId,
            simulationTick,
            source,
            "PREDICTION",
            "PACKET_WORLD",
            List.of(
                "persistent client prediction state",
                "authoritative server position is used only when establishing an anchor/correction"),
            1,
            List.of()));
    return candidate;
  }

  private static MovementEnvironment movementEnvironmentOf(Player player) {
    return switch (player.environment()) {
      case WATER -> MovementEnvironment.vanillaWater(
          player.onGround(), false, false, player.pose() == Pose.SWIMMING);
      case LAVA -> MovementEnvironment.vanillaLava(
          player.onGround(), false, false);
      case CLIMBABLE -> MovementEnvironment.vanillaClimbable(
          player.onGround(), false, false);
      case DRY -> MovementEnvironment.dry(player.onGround(), false, false);
      case UNKNOWN -> MovementEnvironment.dry(player.onGround(), false, false);
    };
  }

  private static MovementEffects movementEffects(Player player) {
    return new MovementEffects(
        amplifier(player.effects(), "minecraft:speed", "speed"),
        amplifier(player.effects(), "minecraft:slowness", "slowness"),
        amplifier(player.effects(), "minecraft:jump_boost", "jump_boost"),
        amplifier(player.effects(), "minecraft:levitation", "levitation"),
        player.effects().containsKey("minecraft:slow_falling")
            || player.effects().containsKey("slow_falling"));
  }

  private static int amplifier(Map<String, Integer> effects, String... ids) {
    for (String id : ids) {
      Integer value = effects.get(id);
      if (value != null) return value;
    }
    return -1;
  }

  private static dev.phantom.ac.Simulation.Environment simulationEnvironmentFor(
      MovementEnvironment environment) {
    if (environment.fluid() == Fluid.WATER) return dev.phantom.ac.Simulation.Environment.WATER;
    if (environment.fluid() == Fluid.LAVA) return dev.phantom.ac.Simulation.Environment.LAVA;
    if (environment.climbable()) return dev.phantom.ac.Simulation.Environment.CLIMBABLE;
    return dev.phantom.ac.Simulation.Environment.DRY;
  }

  private static Set<Candidate> overlayAuthorityState(
      Set<Candidate> candidates,
      Packets.PlayerContext authority,
      int maximumCandidates) {
    if (candidates.isEmpty() || candidates.size() > maximumCandidates) return Set.of();
    EntityCollisions collisions = EntityCollisions.of(authority.entityBoxes());
    Player base = playerFromAuthority(authority);
    Set<Candidate> result = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      Player old = candidate.context().player();
      Player merged = mergeDynamicState(old, base, old.velocity(), old.position(),
          old.yaw(), old.pitch(), old.onGround());
      result.add(rebuildCandidate(
          candidate, merged, collisions, authority.movementEnvironment()));
    }
    return Set.copyOf(result);
  }

  private static Set<Candidate> overlayClientInput(
      Set<Candidate> candidates,
      Player clientState,
      int maximumCandidates) {
    return overlayClientState(candidates, clientState, maximumCandidates, false);
  }

  private static Set<Candidate> overlayClientState(
      Set<Candidate> candidates,
      Player clientState,
      int maximumCandidates,
      boolean dynamicOnly) {
    if (candidates.isEmpty() || candidates.size() > maximumCandidates) return Set.of();
    Set<Candidate> result = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      Player old = candidate.context().player();
      Player merged = mergeDynamicState(
          old,
          clientState,
          old.velocity(),
          old.position(),
          old.yaw(),
          old.pitch(),
          old.onGround());
      result.add(rebuildCandidate(candidate, merged, candidate.context().entityCollisions()));
    }
    return Set.copyOf(result);
  }

  private static Set<Candidate> overlayVelocity(
      Set<Candidate> candidates,
      Vec3 velocity,
      int maximumCandidates) {
    if (candidates.isEmpty() || candidates.size() > maximumCandidates) return Set.of();
    Set<Candidate> result = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      Player old = candidate.context().player();
      Player merged = mergeDynamicState(
          old, old,
          velocity,
          old.position(),
          old.yaw(),
          old.pitch(),
          old.onGround());
      result.add(rebuildCandidate(candidate, merged, candidate.context().entityCollisions()));
    }
    return Set.copyOf(result);
  }

  private static Player mergeDynamicState(
      Player old,
      Player source,
      Vec3 velocity,
      Vec3 position,
      float yaw,
      float pitch,
      boolean onGround) {
    return new Player(
        position,
        velocity,
        yaw,
        pitch,
        onGround,
        source.gamemode(),
        source.effects(),
        old.awaitingTeleport(),
        old.uncertain(),
        source.input().isPresent() ? source.input() : old.input(),
        source.attributes(),
        source.pose(),
        source.environment(),
        old.clientTickRange(),
        old.provenance(),
        old.uncertaintyReasons());
  }

  private static Candidate rebuildCandidate(
      Candidate candidate,
      Player player,
      EntityCollisions entityCollisions) {
    return rebuildCandidate(candidate, player, entityCollisions, null);
  }

  private static Candidate rebuildCandidate(
      Candidate candidate,
      Player player,
      EntityCollisions entityCollisions,
      MovementEnvironment environmentOverride) {
    Context old = candidate.context();
    MovementEnvironment oldEnvironment = old.movementEnvironment();
    MovementEnvironment environment = environmentOverride == null
        ? new MovementEnvironment(
            oldEnvironment.fluid(),
            oldEnvironment.submerged(),
            oldEnvironment.climbable(),
            player.onGround(),
            oldEnvironment.sprinting(),
            oldEnvironment.sneaking(),
            oldEnvironment.swimmingInput(),
            oldEnvironment.gliding(),
            oldEnvironment.fluidSpeedMultiplier(),
            oldEnvironment.fluidDrag(),
            oldEnvironment.gravityMultiplier())
        : environmentOverride;
    Context context = new Context(
        old.simulationTick(),
        player,
        simulationEnvironmentFor(environment),
        player.attributes(),
        movementEffects(player),
        player.pose(),
        environment,
        player.pose() == Pose.SLEEPING,
        entityCollisions == null ? EntityCollisions.NONE_TRACKED : entityCollisions,
        old.uncertainty());
    return new Candidate(candidate.id(), context, candidate.provenance());
  }

  private static Set<Candidate> overlayEntityCollisions(
      Set<Candidate> candidates,
      EntityCollisions entityCollisions,
      int maximumCandidates) {
    if (candidates.isEmpty() || candidates.size() > maximumCandidates) return Set.of();
    Set<Candidate> result = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      result.add(rebuildCandidate(candidate, candidate.context().player(), entityCollisions));
    }
    return Set.copyOf(result);
  }

  private static Set<Candidate> retargetRotation(
      Set<Candidate> candidates,
      Packets.Move move,
      int maximumCandidates) {
    if (candidates.isEmpty() || candidates.size() > maximumCandidates) return Set.of();
    Set<Candidate> result = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      Player old = candidate.context().player();
      float yaw = move.yaw() == null ? old.yaw() : move.yaw();
      float pitch = move.pitch() == null ? old.pitch() : move.pitch();
      Player rotated = new Player(
          old.position(),
          old.velocity(),
          yaw,
          pitch,
          old.onGround(),
          old.gamemode(),
          old.effects(),
          old.awaitingTeleport(),
          old.uncertain(),
          old.input(),
          old.attributes(),
          old.pose(),
          old.environment(),
          old.clientTickRange(),
          old.provenance(),
          old.uncertaintyReasons());
      result.add(rebuildCandidate(candidate, rotated, candidate.context().entityCollisions()));
    }
    return Set.copyOf(result);
  }

  private record AdvanceResult(
      Set<Candidate> candidates,
      boolean exhaustive,
      int simulatedTicks,
      List<String> reasons,
      List<String> trace) {
    AdvanceResult {
      candidates = Set.copyOf(candidates);
      reasons = List.copyOf(reasons);
      trace = List.copyOf(trace);
    }
  }

  private AdvanceResult advancePrediction(
      Set<Candidate> start,
      long startTick,
      long targetTick,
      NavigableMap<Long, List<TimedInput>> inputHistory,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence) {
    if (start.isEmpty()) {
      return new AdvanceResult(Set.of(), false, 0,
          List.of("prediction frontier is empty"), List.of());
    }
    if (targetTick < startTick) {
      return new AdvanceResult(Set.copyOf(start), false, 0,
          List.of("target client tick precedes retained prediction state"), List.of());
    }
    if (targetTick - startTick > MAX_INCREMENTAL_HORIZON) {
      return new AdvanceResult(Set.copyOf(start), false, 0,
          List.of("incremental prediction horizon exceeded"), List.of());
    }
    return advancePredictionToTarget(
        start, targetTick, inputHistory, world, maximumCandidates, movementSequence);
  }

  private AdvanceResult advancePredictionAcrossTimingRange(
      Set<Candidate> start,
      long earliestTick,
      long latestTick,
      NavigableMap<Long, List<TimedInput>> inputHistory,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence) {
    if (start.isEmpty()) {
      return new AdvanceResult(Set.of(), false, 0,
          List.of("prediction frontier is empty"), List.of());
    }
    if (earliestTick < 0L || latestTick < earliestTick) {
      return new AdvanceResult(Set.copyOf(start), false, 0,
          List.of("invalid simulation timing range"), List.of());
    }

    long span;
    try {
      span = Math.addExact(Math.subtractExact(latestTick, earliestTick), 1L);
    } catch (ArithmeticException overflow) {
      return new AdvanceResult(Set.copyOf(start), false, 0,
          List.of("simulation timing range overflowed the exhaustive offset envelope"), List.of());
    }
    if (span > Phase6Reachability.MAX_TIMING_OFFSETS) {
      return new AdvanceResult(Set.copyOf(start), false, 0,
          List.of("simulation timing range exceeds the exhaustive offset envelope"), List.of());
    }

    Set<Candidate> union = new LinkedHashSet<>();
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    List<String> trace = new ArrayList<>();
    boolean exhaustive = true;
    int simulatedTicks = 0;

    for (long target = earliestTick; target <= latestTick; target++) {
      AdvanceResult one = advancePredictionToTarget(
          start, target, inputHistory, world, maximumCandidates, movementSequence);
      union.addAll(one.candidates());
      reasons.addAll(one.reasons());
      trace.add("TIMING_OFFSET target=" + target
          + " exhaustive=" + one.exhaustive()
          + " candidates=" + one.candidates().size()
          + " steps=" + one.simulatedTicks());
      trace.addAll(one.trace());
      simulatedTicks += one.simulatedTicks();

      if (!one.exhaustive()) exhaustive = false;
      if (union.size() > maximumCandidates) {
        return new AdvanceResult(Set.of(), false, simulatedTicks,
            List.of("combined timing-offset candidate budget exceeded"),
            List.copyOf(trace));
      }
    }

    reasons.add("persistent prediction exhaustively evaluated every supplied simulation-tick offset");
    return new AdvanceResult(
        Set.copyOf(union), exhaustive, simulatedTicks,
        List.copyOf(reasons), List.copyOf(trace));
  }

  private AdvanceResult advancePredictionToTarget(
      Set<Candidate> start,
      long targetTick,
      NavigableMap<Long, List<TimedInput>> inputHistory,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence) {
    if (start.isEmpty()) {
      return new AdvanceResult(Set.of(), false, 0,
          List.of("prediction frontier is empty"), List.of());
    }
    if (targetTick < 0L) {
      return new AdvanceResult(Set.of(), false, 0,
          List.of("negative client simulation tick"), List.of());
    }

    Set<Candidate> union = new LinkedHashSet<>();
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    List<String> trace = new ArrayList<>();
    boolean exhaustive = true;
    int simulatedTicks = 0;

    for (Candidate initial : start) {
      long localTick = initial.context().simulationTick();
      if (localTick > targetTick) {
        reasons.add("candidate simulation tick " + localTick
            + " is ahead of timing target " + targetTick);
        continue;
      }

      Set<Candidate> local = Set.of(initial);
      while (localTick < targetTick) {
        final long simulationTick = localTick;
        List<InputConstraint> inputOptions =
            inputPossibilitiesForSimulationTick(inputHistory, simulationTick, movementSequence);
        Candidate beforeCandidate = local.stream().findFirst().orElse(null);
        if (beforeCandidate != null) {
          trace.add("SIM_INPUT_OPTIONS tick=" + simulationTick
              + " inputs=" + inputOptions
              + " startPos=" + beforeCandidate.context().player().position()
              + " startVel=" + beforeCandidate.context().player().velocity()
              + " startGround=" + beforeCandidate.context().player().onGround());
        }

        Set<Candidate> stepCandidates = new LinkedHashSet<>();
        LinkedHashSet<String> stepReasons = new LinkedHashSet<>();
        boolean stepExhaustive = true;

        for (InputConstraint inputOption : inputOptions) {
          Map<MovementInputState, List<Context>> startsByMovementState = new LinkedHashMap<>();
          for (Candidate candidate : local) {
            MovementEnvironment movementEnvironment = candidate.context().movementEnvironment();
            MovementInputState movementState = new MovementInputState(
                movementEnvironment.sprinting(), movementEnvironment.sneaking());
            startsByMovementState
                .computeIfAbsent(movementState, ignored -> new ArrayList<>())
                .add(candidate.context().withTick(simulationTick));
          }

          for (var movementEntry : startsByMovementState.entrySet()) {
            MovementInputState movementState = movementEntry.getKey();
            InputConstraint simulationInput = new InputConstraint(
                inputOption.forward(),
                inputOption.strafe(),
                inputOption.jump(),
                Optional.of(movementState.sprinting()),
                Optional.of(movementState.sneaking()));

            SearchResult branch = new Phase6Reachability().search(
                movementEntry.getValue(),
                List.of(simulationInput),
              ignored -> List.of(new WorldBranch(
                  "packet-world@" + simulationTick,
                  world,
                  true,
                  "latency-compensated client-visible world")),
              ignored -> List.of(new Phase6Reachability.None()),
              Phase6Reachability.SearchConfig.defaults(maximumCandidates));

            trace.add("SIM_INPUT_BRANCH tick=" + simulationTick
              + " input=" + simulationInput
              + " movementSprint=" + movementState.sprinting()
              + " movementSneak=" + movementState.sneaking()
              + " exhaustive=" + branch.exhaustive()
              + " verdict=" + branch.verdict()
              + " candidates=" + branch.candidates().size());
          stepCandidates.addAll(branch.candidates());
          stepReasons.addAll(branch.reasons());
          if (!branch.exhaustive()) stepExhaustive = false;
        }

        simulatedTicks++;
        if (!stepExhaustive || stepCandidates.isEmpty()) {
          reasons.addAll(stepReasons);
          reasons.add("prediction step " + simulationTick
              + " was not exhaustively modeled");
          trace.add("SIM_STEP tick=" + simulationTick
              + " exhaustive=false"
              + " branchCandidates=" + stepCandidates.size()
              + " reasons=" + stepReasons);
          exhaustive = false;
          local = Set.of();
          break;
        }

        if (stepCandidates.size() > maximumCandidates) {
          return new AdvanceResult(Set.of(), false, simulatedTicks,
              List.of("prediction candidate budget exceeded across input-timing alternatives"),
              List.copyOf(trace));
        }

        local = Set.copyOf(stepCandidates);
        Candidate afterCandidate = local.stream().findFirst().orElse(null);
        if (afterCandidate != null) {
          trace.add("SIM_STEP tick=" + simulationTick
              + " exhaustive=true"
              + " resultPos=" + afterCandidate.context().player().position()
              + " resultVel=" + afterCandidate.context().player().velocity()
              + " resultGround=" + afterCandidate.context().player().onGround());
        }

        if (local.size() > maximumCandidates) {
          return new AdvanceResult(Set.of(), false, simulatedTicks,
              List.of("prediction candidate budget exceeded"),
              List.copyOf(trace));
        }
        localTick++;
      }

      union.addAll(local);
      if (union.size() > maximumCandidates) {
        return new AdvanceResult(Set.of(), false, simulatedTicks,
            List.of("combined prediction candidate budget exceeded"),
            List.copyOf(trace));
      }
    }

    reasons.add("persistent prediction advanced using client input history indexed by simulation tick");
    return new AdvanceResult(
        Set.copyOf(union), exhaustive, simulatedTicks,
        List.copyOf(reasons), List.copyOf(trace));
  }

  private static Set<Candidate> matchingCandidates(
      Set<Candidate> candidates,
      Player observed,
      Packets.Move move) {
    Set<Candidate> matching = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      Player state = candidate.context().player();
      if (!positionMatches(state.position(), observed.position())) continue;
      if (move.yaw() != null && Float.compare(state.yaw(), observed.yaw()) != 0) continue;
      if (move.pitch() != null && Float.compare(state.pitch(), observed.pitch()) != 0) continue;
      if (move.onGround() != null && state.onGround() != observed.onGround()) continue;
      matching.add(candidate);
    }
    return Set.copyOf(matching);
  }

  private static boolean positionMatches(Vec3 left, Vec3 right) {
    return Math.abs(left.x() - right.x()) <= POSITION_TOLERANCE
        && Math.abs(left.y() - right.y()) <= POSITION_TOLERANCE
        && Math.abs(left.z() - right.z()) <= POSITION_TOLERANCE;
  }

  private static boolean positionExactlyMatches(Vec3 left, Vec3 right) {
    return Double.compare(left.x(), right.x()) == 0
        && Double.compare(left.y(), right.y()) == 0
        && Double.compare(left.z(), right.z()) == 0;
  }

  private long movementServerTick(Packets.RawPacket packet) {
    Long value = packet.provenance().authoritativeServerTick();
    return value == null ? 0L : value;
  }

  private EntityCollisions entityCollisions(AuthorityAnchor authority) {
    return authority == null
        ? EntityCollisions.NONE_TRACKED
        : EntityCollisions.of(
            authority.context().entityBoxes(),
            authority.entityCollisionComplete());
  }

  private Phase8MovementValidation.Result validate(
      String playerId,
      Packets.RawPacket packet,
      Packets.Move move,
      Player prior,
      Player observed,
      WorldSnapshot world,
      TickResolution tick,
      List<String> uncertainty,
      SearchResult search,
      boolean timingExhaustivelyModeled) {
    return validate(
        playerId, packet, move, prior, observed, world, tick, uncertainty,
        search, timingExhaustivelyModeled,
        move.position() == null
            ? EnumSet.of(Phase6Reachability.ObservedField.ROTATION)
            : EnumSet.of(
                Phase6Reachability.ObservedField.POSITION,
                Phase6Reachability.ObservedField.ROTATION,
                Phase6Reachability.ObservedField.GROUND));
  }

  private Phase8MovementValidation.Result validate(
      String playerId,
      Packets.RawPacket packet,
      Packets.Move move,
      Player prior,
      Player observed,
      WorldSnapshot world,
      TickResolution tick,
      List<String> uncertainty,
      SearchResult search,
      boolean timingExhaustivelyModeled,
      Set<Phase6Reachability.ObservedField> observedFields) {
    List<String> timingReasons = new ArrayList<>();
    if (tick.timingUncertain()) {
      timingReasons.add(tick.uncertaintyReason());
    }
    if (!tick.exact()) {
      timingReasons.add("client simulation tick is not represented by one exact timing state");
    }
    if (timingReasons.isEmpty()) {
      timingReasons.add("persistent client-tick clock is exact for this packet");
    }
    Validation.SyncWindow timing = new Validation.SyncWindow(
        Math.max(0L, tick.clientTick()),
        Math.max(0L, tick.clientTick()),
        tick.timingUncertain() || !tick.exact(),
        List.copyOf(timingReasons));
    List<String> assumptions = new ArrayList<>();
    assumptions.add("client input is retained as held state until the next ClientInput packet");
    assumptions.add("server position is used only for anchor/correction state, never as the predicted client position");
    assumptions.add("packet world is selected at or before the movement sequence and is therefore latency-compensated");
    assumptions.add("prediction candidates are retained after POSSIBLE, UNCERTAIN, and IMPOSSIBLE observations");
    assumptions.addAll(uncertainty);

    return Phase8MovementValidation.validate(
        playerId,
        movementServerTick(packet),
        prior,
        observed,
        world,
        "packet-world:causal-sequence=" + world.causalSequence(),
        timing,
        assumptions,
        search,
        "prediction:phase8:" + playerId + ":" + packet.sequence(),
        timingExhaustivelyModeled,
        observedFields);
  }

  private Phase8MovementValidation.Result unauthorizedFlightResult(
      String playerId,
      Packets.RawPacket packet,
      Player state) {
    if (latestAuthority == null) return null;
    Packets.PlayerContext context = latestAuthority.context();
    if (context.canFly() || context.flying()) return null;
    if (!"survival".equals(context.gamemode())
        && !"adventure".equals(context.gamemode())) return null;
    TickResolution tick = new TickResolution(
        relativeClientTick,
        hasClientTickBoundary,
        hasClientTickBoundary,
        !hasClientTickBoundary,
        "flight-toggle-observation",
        hasClientTickBoundary
            ? "client-tick boundary observed"
            : "no client-tick boundary observed for authoritative flight toggle");
    return Phase8MovementValidation.authoritativeObservation(
        playerId,
        movementServerTick(packet),
        state,
        state,
        WorldSnapshot.builder(Contracts.TARGET_VERSION).build(),
        "authoritative:flight-toggle",
        new Validation.SyncWindow(
            tick.clientTick(),
            tick.clientTick(),
            !tick.exact(),
            List.of(tick.source())),
        "UNAUTHORIZED_FLIGHT_TOGGLE_ATTEMPT",
        "authoritative server state says flight is not permitted but a flight-on transition was observed",
        List.of(
            "authoritativeCanFly=false",
            "authoritativeFlying=false",
            "authoritativeGamemode=" + context.gamemode(),
            "flightToggle.flying=true",
            "flightToggle.cancelled=" + (((Packets.FlightToggle) packet.packet()).cancelled()),
            "this evidence is independent of Paper movement rejection events"),
        "prediction:flight:" + playerId + ":" + packet.sequence());
  }

  private List<InputConstraint> inputPossibilitiesForSimulationTick(
      NavigableMap<Long, List<TimedInput>> history,
      long simulationTick,
      long movementSequence) {
    if (simulationTick < 0L) return List.of(neutralInput);

    /*
     * Phase 7 explicitly bounds input-to-simulation delay to 0..1 client ticks.
     * A newly received ClientInput may therefore be the state used by this
     * simulation tick or the following one. Keep both exact states when they
     * differ instead of collapsing chronology to one arbitrary choice.
     */
    LinkedHashSet<InputConstraint> options = new LinkedHashSet<>();
    options.add(inputForSimulationTickExact(history, simulationTick, movementSequence));
    if (simulationTick > 0L) {
      options.add(inputForSimulationTickExact(history, simulationTick - 1L, movementSequence));
    }
    return List.copyOf(options);
  }

  private InputConstraint inputForSimulationTickExact(
      NavigableMap<Long, List<TimedInput>> history,
      long simulationTick,
      long movementSequence) {
    if (history.isEmpty() || simulationTick < 0L) return neutralInput;
    for (var entry : history.headMap(simulationTick, true).descendingMap().entrySet()) {
      TimedInput selected = null;
      for (TimedInput input : entry.getValue()) {
        if (input.sequence() > movementSequence) break;
        selected = input;
      }
      if (selected != null) return selected.constraint();
    }
    return neutralInput;
  }

  private InputConstraint inputForSimulationTick(
      NavigableMap<Long, List<TimedInput>> history,
      long simulationTick,
      long movementSequence) {
    return inputPossibilitiesForSimulationTick(history, simulationTick, movementSequence).getFirst();
  }

  private static SearchResult uncertainSearch(Set<Candidate> candidates, String reason) {
    return new SearchResult(
        Verdict.UNCERTAIN,
        candidates,
        0,
        candidates.size(),
        0, 0, 1, 0,
        List.of(reason));
  }

  private static WorldSnapshot worldOrEmpty(WorldSnapshot world) {
    return world == null
        ? WorldSnapshot.builder(Contracts.TARGET_VERSION).build()
        : world;
  }

  private PredictionFrame frame(
      long sequence,
      Packets.RawPacket packet,
      TickResolution tick,
      Packets.Move move,
      Player observedBefore,
      Player observedAfter,
      Set<Candidate> predictedBefore,
      Set<Candidate> predictedAfter,
      WorldSnapshot world,
      List<String> uncertaintySources,
      List<String> trace) {
    List<String> mergedTrace = new ArrayList<>(trace);
    mergedTrace.add("OBSERVED position=" + observedAfter.position()
        + " yaw=" + observedAfter.yaw()
        + " pitch=" + observedAfter.pitch()
        + " ground=" + observedAfter.onGround());
    mergedTrace.add("FRONTIER candidates=" + predictedAfter.size()
        + " predictionTick=" + predictionTick
        + " retained=true");
    return new PredictionFrame(
        sequence,
        packet.receivedNanos(),
        movementServerTick(packet),
        tick.clientTick(),
        move,
        observedBefore,
        observedAfter,
        predictedBefore,
        predictedAfter,
        world,
        uncertaintySources,
        mergedTrace);
  }

  private static Player withClientRotation(Player player, float yaw, float pitch) {
    return new Player(
        player.position(),
        player.velocity(),
        yaw,
        pitch,
        player.onGround(),
        player.gamemode(),
        player.effects(),
        player.awaitingTeleport(),
        player.uncertain(),
        player.input(),
        player.attributes(),
        player.pose(),
        player.environment(),
        player.clientTickRange(),
        player.provenance(),
        player.uncertaintyReasons());
  }
}