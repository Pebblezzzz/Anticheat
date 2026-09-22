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

  /*
   * A ClientInput whose Phase 7 input-tick envelope could not be exhaustively
   * materialized is still a real held-state transition. Keeping its sequence
   * and earliest possible tick lets Phase 8 enumerate the safe finite input
   * envelope instead of silently replacing that state with neutral input.
   */
  private record UncertainInput(long sequence, long earliestClientTick) {}

  private record InputEventAlternatives(
      long sequence,
      InputConstraint constraint,
      List<Long> possibleTicks) {
    InputEventAlternatives {
      if (sequence < 0L) throw new IllegalArgumentException("input sequence must be non-negative");
      Objects.requireNonNull(constraint);
      possibleTicks = List.copyOf(possibleTicks);
    }
  }

  private record InputChronology(
      NavigableMap<Long, List<TimedInput>> history) {
    InputChronology {
      Objects.requireNonNull(history);
      NavigableMap<Long, List<TimedInput>> copy = new TreeMap<>();
      for (var entry : history.entrySet()) {
        copy.put(entry.getKey(), List.copyOf(entry.getValue()));
      }
      history = Collections.unmodifiableNavigableMap(copy);
    }
  }

  private record MovementInputState(boolean sprinting, boolean sneaking) {}
  private record SpatialRebaseResult(Set<Candidate> candidates, boolean rebased) {
    SpatialRebaseResult {
      candidates = Set.copyOf(candidates);
    }
  }

  private static final double POSITION_TOLERANCE = Phase6Reachability.POSITION_MATCH_TOLERANCE;
  /*
   * Phase 6 keeps its strict 0.01 block matching envelope. Phase 8 may only use
   * this separate, twice-the-match-tolerance band for recovery after an
   * exhaustive no-match, and the recovery is explicitly UNCERTAIN rather than
   * POSSIBLE. This prevents a tiny collision/precision drift from poisoning the
   * persistent client-velocity frontier without widening the actual reachability
   * proof.
   */
  private static final double POSITION_RECONCILIATION_TOLERANCE =
      POSITION_TOLERANCE * 2.0;
  private static final long MAX_INCREMENTAL_HORIZON = Phase6Reachability.MAX_HORIZON_TICKS;
  private static final long PREDICTION_RESYNC_LAG_TICKS = 2L;

  private final int maximumCandidates;
  private final GrimPredictionEngine grimPredictionEngine = new GrimPredictionEngine();
  private final Phase7Timing.Config phase7TimingConfig;
  private final ArrayDeque<Packets.RawPacket> timingHistory = new ArrayDeque<>();
  private long timingEpochNanos = -1L;
  private final InputConstraint neutralInput;
  private final List<UncertainInput> uncertainInputs = new ArrayList<>();
  private List<InputChronology> inputChronologies = List.of();
  private Player initialAnchor;
  private long initialAnchorReceivedNanos = -1L;

  private Player clientState;
  private InputConstraint currentInput;
  private long currentInputSequence = -1L;
  private final NavigableMap<Long, List<TimedInput>> inputHistory = new TreeMap<>();
  private AuthorityAnchor latestAuthority;
  private Set<Candidate> prediction = Set.of();
  private long predictionTick = -1L;
  private Phase8ClientModel.ClientPhysicsState clientPhysicsState;
  private Phase8ClientModel.CompensatedWorld compensatedWorld;
  private Phase8ClientModel.TickReliabilityState tickReliability =
      Phase8ClientModel.TickReliabilityState.assess(0L, false, false, true, false);
  /*
   * An authoritative zero-delta witness proves the current observation but its
   * server velocity is not a client-tick physics state. Keep the physics
   * frontier suppressed until the next position-bearing movement can establish
   * a verified client-boundary velocity.
   */
  private boolean physicsFrontierSuppressedUntilPositionMovement;
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

  public synchronized Phase8ClientModel.ClientPhysicsState clientPhysicsState() {
    return clientPhysicsState;
  }

  public synchronized Phase8ClientModel.CompensatedWorld compensatedWorld() {
    return compensatedWorld;
  }

  public synchronized Phase8ClientModel.TickReliabilityState tickReliability() {
    return tickReliability;
  }

  public synchronized List<Phase8ClientModel.MovementHypothesis> movementHypotheses() {
    return Phase8ClientModel.hypotheses(prediction);
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
    clientPhysicsState = Phase8ClientModel.ClientPhysicsState.initial(authoritativeAnchor);
    compensatedWorld = null;
    tickReliability =
        Phase8ClientModel.TickReliabilityState.assess(0L, false, false, true, false);
    currentInput = neutralInput;
    currentInputSequence = -1L;
    inputHistory.clear();
    uncertainInputs.clear();
    inputChronologies = List.of();


    timingHistory.clear();
    timingEpochNanos = -1L;

    latestAuthority = null;
    prediction = Set.of();
    predictionTick = -1L;
    physicsFrontierSuppressedUntilPositionMovement = false;
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
     * Phase 7 is the sole live client/server timing authority. Preserve the
     * complete connection chronology instead of imposing a second, artificial
     * packet-history cutoff in Phase 8.
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
        if (clientPhysicsState != null) {
          clientPhysicsState = clientPhysicsState.withServerAuthority(
              clientPhysicsState.clientTick(),
              effectiveAuthority.serverPosition(),
              effectiveAuthority.serverVelocity(),
              packet.provenance().authoritativeServerTick());
        }
        if (!prediction.isEmpty()) {
          Set<Candidate> updated =
              overlayAuthorityState(prediction, effectiveAuthority, maximumCandidates);
          if (!updated.isEmpty()) prediction = updated;
        }
        continue;
      }

      if (value instanceof Packets.ClientInput input) {
        clientState = State.apply(clientState, normalized);
        // PLAYER_INPUT is a held-state update. Its causal simulation tick is
        // reconstructed by Phase 7 rather than guessed from packet arrival.
        currentInput = InputConstraint.fromClientInput(input);
        currentInputSequence = sequence;
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
      boolean stationaryPositionObservation = move.position() != null
          && positionExactlyMatches(observedBefore.position(), observedAfter.position())
          && observedBefore.onGround()
          && observedAfter.onGround();
      boolean observationOnlyMovement = move.position() == null || stationaryPositionObservation;
      if (observationOnlyMovement && tick.timingUncertain()) {
        tick = tick.withTimingUncertaintyResolved(
            "observation-only movement does not advance client physics, so Phase 7 chronology uncertainty is not kinematic");
      }
      boolean packetSequenceGap = sequence > previousSequence + 1L && previousSequence >= 0L;
      tickReliability = Phase8ClientModel.TickReliabilityState.assess(
          tick.clientTick(),
          tick.known(),
          tick.exact(),
          tick.timingUncertain(),
          packetSequenceGap);
      trace.add("CLIENT_TICK " + tick.display()
          + " exact=" + tick.exact()
          + " source=" + tick.source()
          + " timingUncertain=" + tick.timingUncertain());
      trace.add("TICK_RELIABILITY level=" + tickReliability.reliability()
          + " exact=" + tickReliability.exact()
          + " timingUncertain=" + tickReliability.timingUncertain()
          + " sequenceGap=" + tickReliability.sequenceGap()
          + " reasons=" + tickReliability.reasons());

      // Grim's KnownInput is a single held-state value consumed by movement.
      InputConstraint tickInput = currentInput;
      trace.add("INPUT_STATE currentKeyState=" + currentInput
          + " simulationKeyState=" + tickInput
          + " simulationTick=" + (tick.known() ? Math.max(0L, tick.clientTick() - 1L) : -1L));

      WorldSnapshot world = worldProvider == null ? null : worldProvider.apply(sequence);
      if (world != null) {
        compensatedWorld = Phase8ClientModel.CompensatedWorld.forMovement(
            world, sequence, "latency-compensated-packet-world");
        if (!compensatedWorld.causallyBounded()) {
          trace.add("COMPENSATED_WORLD causalBounded=false"
              + " causalSequence=" + compensatedWorld.causalSequence()
              + " movementSequence=" + compensatedWorld.movementSequence());
        }
      }
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
      if (latestAuthority != null) {
        MovementEnvironment authorityEnvironment = latestAuthority.context().movementEnvironment();
        trace.add("AUTHORITY_MOVEMENT_STATE sprint=" + authorityEnvironment.sprinting()
            + " sneak=" + authorityEnvironment.sneaking()
            + " ground=" + authorityEnvironment.onGround()
            + " velocity=" + latestAuthority.context().serverVelocity()
            + " position=" + latestAuthority.context().serverPosition()
            + " authoritySeq=" + latestAuthority.sequence()
            + " authorityServerTick=" + latestAuthority.serverTick()
            + " authorityClientTick=" + latestAuthority.clientTick());
      }

      Set<Candidate> predictedBefore = prediction;
      boolean predictionWasEmptyBeforeRoot = prediction.isEmpty();
      boolean bootstrapRecoveryRequired =
          physicsFrontierSuppressedUntilPositionMovement;
      boolean rootRebasedForMovement = false;

      if (bootstrapRecoveryRequired) {
        trace.add("FRONTIER_ROOT_SUPPRESSED reason=authoritative-observation-witness"
            + " positionBearing=" + (move.position() != null));
      } else {
        ensureRoot(playerId, packet, move, observedBefore, tick, trace);
        rootRebasedForMovement =
            refreshFromCausalAuthorityIfStale(packet, move, observedBefore, tick, world, trace);
      }

      boolean priorPositionObservationSameTick =
          move.position() != null
              && tick.known()
              && lastObservedMovementClientTick == tick.clientTick();
      if (move.position() != null) {
        rememberObservedMovement(observedBefore, observedAfter, tick);
      }

      if (move.position() == null || stationaryPositionObservation) {
        boolean positionlessRotationObservation = move.position() == null;

        if (physicsFrontierSuppressedUntilPositionMovement) {
          /*
           * A stationary or look-only packet is an observation, not a physics
           * tick. The suppressed physics frontier therefore does not make the
           * packet uncertain. Prefer a fresh causal server witness when one
           * agrees with the observed state; otherwise validate the observation
           * against a temporary client-observation witness. Neither witness is
           * retained as the physics frontier.
           */
          AuthorityAnchor authority = freshCausalAuthority(packet);
          boolean authorityMatches = authority != null
              && positionsMatch(authority.context().serverPosition(), observedAfter.position())
              && authority.context().movementEnvironment().onGround() == observedAfter.onGround();

          Candidate witness = authorityMatches
              ? authoritativeObservationWitness(
                  authority, observedAfter, tick.clientTick(), sequence)
              : candidateFromPlayer(
                  observedAfter,
                  tick.clientTick(),
                  "OBSERVATION_ONLY",
                  -1L,
                  EntityCollisions.NONE_TRACKED);

          Set<Phase6Reachability.ObservedField> observedFields =
              positionlessRotationObservation
                  ? EnumSet.of(Phase6Reachability.ObservedField.ROTATION)
                  : EnumSet.of(
                      Phase6Reachability.ObservedField.POSITION,
                      Phase6Reachability.ObservedField.ROTATION,
                      Phase6Reachability.ObservedField.GROUND);
          SearchResult observationSearch = new SearchResult(
              Verdict.POSSIBLE,
              Set.of(witness),
              0,
              1,
              0, 0, 0, 0,
              List.of(authorityMatches
                  ? (positionlessRotationObservation
                      ? "rotation-only observation matched fresh causal authority; no physics root retained"
                      : "stationary observation matched fresh causal authority; no physics root retained")
                  : (positionlessRotationObservation
                      ? "rotation-only observation validated without advancing physics; no physics root retained"
                      : "stationary observation validated without advancing physics; no physics root retained")));
          Phase8MovementValidation.Result result = validate(
              playerId, packet, move, observedBefore, observedAfter, world,
              tick, List.of(), observationSearch, true, observedFields);
          results.add(result);
          if (result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE) {
            possible++;
            latestContinuation = Continuation.ACTIVE;
            lastPositionClientTick = tick.clientTick();
          } else if (result.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE) {
            impossible++;
            latestContinuation = Continuation.IMPOSSIBLE;
          } else {
            uncertain++;
            latestContinuation = Continuation.UNCERTAIN;
          }
          trace.add("FRONTIER_SUPPRESSED_OBSERVATION result=" + result.verdict()
              + " positionBearing=" + !positionlessRotationObservation
              + " authorityWitness=" + authorityMatches
              + " retained=" + !prediction.isEmpty());
          frames.add(frame(
              sequence, packet, tick, move, observedBefore, observedAfter,
              predictedBefore, prediction, world, result.evidence().uncertaintySources(), trace));
          continue;
        }
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
      if (compensatedWorld != null && !compensatedWorld.causallyBounded()) {
        uncertaintySources.add(
            "latency-compensated world snapshot has no causal boundary at or before this movement");
      }
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
          latestContinuation = Continuation.ACTIVE;
          lastPositionClientTick = tick.clientTick();
          rememberObservedMovement(observedBefore, observedAfter, tick);

          /*
           * This candidate is an observation witness, not a physics state. Its
           * position is trustworthy for this packet, but its velocity comes from
           * a server-side snapshot whose timing is not atomic with the client
           * movement. Retaining it as the next prediction frontier would combine
           * an observed client position with a potentially non-corresponding
           * server velocity and manufacture kinematic drift on the next tick.
           *
           * Leave the physics frontier empty. The next position-bearing movement
           * can rebuild from a fresh causal authority and, when available, the
           * observed displacement bootstrap will reconstruct a client-boundary
           * velocity without inventing one from the witness.
           */
          prediction = Set.of();
          predictionTick = -1L;
          physicsFrontierSuppressedUntilPositionMovement = true;
          trace.add("EVIDENCE POSSIBLE reason=AUTHORITATIVE_ZERO_DELTA_WITNESS"
              + " authoritySequence=" + freshAuthority.sequence()
              + " authorityServerTick=" + freshAuthority.serverTick());
          trace.add("FRONTIER_CLEARED source=AUTHORITATIVE_ZERO_DELTA_WITNESS"
              + " reason=observation-witness-is-not-a-physics-root");
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

      SpatialRebaseResult spatialRebase = rebasePredictionToObservedBefore(
          prediction, observedBefore, tick, trace);
      prediction = spatialRebase.candidates();

      /*
       * Grim keeps a client-side movement velocity separate from the server's
       * instantaneous velocity. Mirror that principle at bootstrap: when the
       * current frontier is empty OR is merely an authoritative root, a fresh
       * authority sample matching the observed pre-movement state can be used to
       * reconstruct the hidden client-tick start velocity from the actual movement
       * observation. This avoids treating Bukkit's server-side velocity as an
       * atomic client-tick velocity.
       */
      if ((predictionWasEmptyBeforeRoot || rootRebasedForMovement || spatialRebase.rebased())
          && move.position() != null) {
        Optional<Set<Candidate>> bootstrap = bootstrapPredictionFromObservedMovement(
            packet, move, observedBefore, observedAfter, tick, world, trace);
        if (bootstrap.isPresent()) {
          Set<Candidate> bootstrapCandidates = bootstrap.orElseThrow();
          prediction = Set.copyOf(bootstrapCandidates);
          predictionTick = tick.clientTick();
          physicsFrontierSuppressedUntilPositionMovement = false;
          latestContinuation = Continuation.ACTIVE;
          lastPositionClientTick = tick.clientTick();

          SearchResult bootstrapSearch = new SearchResult(
              Verdict.POSSIBLE,
              bootstrapCandidates,
              1,
              bootstrapCandidates.size(),
              0, 0, 0, 0,
              List.of(
                  "client movement bootstrap reconstructed the hidden start velocity from the observed tick",
                  "canonical Phase 5 replay reproduced the observed movement exactly",
                  "physical sprint/sneak state was preserved as explicit candidate alternatives when key-state and server movement-state evidence disagreed"));
          List<String> bootstrapUncertainty = List.of(
              "client-side starting velocity was reconstructed from observed movement because the server velocity is not an atomic client-tick state");
          Phase7Timing.EventTiming bootstrapTiming = phase7TimingBySequence.get(sequence);
          boolean bootstrapTimingExhaustive =
              explicitTimingRangeIsExhaustive(move, bootstrapTiming)
                  && !phase7TimingHasUnmodeledChronology(bootstrapTiming);
          TickResolution bootstrapValidationTick = bootstrapTimingExhaustive
              ? tick.withTimingUncertaintyResolved(
                  "Phase 7 bounded simulation timing was exhaustively evaluated for every permitted offset")
              : tick;
          boolean bootstrapGroundClaimMismatch = bootstrapCandidates.stream()
              .allMatch(candidate ->
                  move.onGround() != null
                      && candidate.context().player().onGround() != move.onGround());
          if (bootstrapGroundClaimMismatch) {
            trace.add("GROUND_CLAIM_MISMATCH"
                + " observed=" + move.onGround()
                + " predictedCandidates=" + bootstrapCandidates.size()
                + " movementReachability=not-impossible");
            bootstrapUncertainty = new ArrayList<>(bootstrapUncertainty);
            bootstrapUncertainty.add(
                "client ground claim differs from the reconstructed physical ground state; bootstrap reachability ignores that client-only claim");
            bootstrapValidationTick = new TickResolution(
                tick.clientTick(),
                tick.known(),
                tick.exact(),
                true,
                tick.source(),
                "client ground claim differs from the reconstructed physical ground state; movement reachability does not treat this claim mismatch as an IMPOSSIBLE contradiction");
          }

          Set<Phase6Reachability.ObservedField> bootstrapObservedFields =
              bootstrapGroundClaimMismatch
                  ? EnumSet.of(
                      Phase6Reachability.ObservedField.POSITION,
                      Phase6Reachability.ObservedField.ROTATION)
                  : EnumSet.of(
                      Phase6Reachability.ObservedField.POSITION,
                      Phase6Reachability.ObservedField.ROTATION,
                      Phase6Reachability.ObservedField.GROUND);

          Phase8MovementValidation.Result result = validate(
              playerId, packet, move, observedBefore, observedAfter, world,
              bootstrapValidationTick, bootstrapUncertainty, bootstrapSearch,
              bootstrapTimingExhaustive && !bootstrapGroundClaimMismatch,
              bootstrapObservedFields);
          results.add(result);
          switch (result.verdict()) {
            case POSSIBLE -> {
              possible++;
              latestContinuation = Continuation.ACTIVE;
            }
            case UNCERTAIN -> {
              uncertain++;
              latestContinuation = Continuation.UNCERTAIN;
            }
            case IMPOSSIBLE -> {
              impossible++;
              latestContinuation = Continuation.IMPOSSIBLE;
            }
          }
          rememberObservedMovement(observedBefore, observedAfter, tick);

          /*
           * Preserve the bootstrap frontier for continuity, but carry the reason
           * for an UNCERTAIN bootstrap into the candidate context. A bootstrap
           * reproduces the current observation, yet its hidden state is not fully
           * proven; the following packet must therefore not treat that candidate
           * as a fully deterministic physics root.
           */
          if (result.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN) {
            Set<Phase6Reachability.UncertainDimension> dimensions =
                EnumSet.noneOf(Phase6Reachability.UncertainDimension.class);
            if (bootstrapGroundClaimMismatch) {
              dimensions.add(Phase6Reachability.UncertainDimension.GROUND);
            }
            if (!bootstrapTimingExhaustive) {
              dimensions.add(Phase6Reachability.UncertainDimension.TIMING);
            }
            if (dimensions.isEmpty()) {
              dimensions.add(Phase6Reachability.UncertainDimension.VELOCITY);
            }
            prediction = markCandidatesUncertain(bootstrapCandidates, dimensions);
            trace.add("FRONTIER_MARKED_UNCERTAIN source=CLIENT_MOVEMENT_BOOTSTRAP"
                + " dimensions=" + dimensions
                + " tick=" + tick.clientTick());
          }

          trace.add("EVIDENCE POSSIBLE reason=CLIENT_MOVEMENT_BOOTSTRAP"
              + " reconstructedStartVelocityVerified=true"
              + " candidateLocomotionAlternatives=" + bootstrapCandidates.size()
              + " timingExhaustive=" + bootstrapTimingExhaustive
              + " validationVerdict=" + result.verdict());
          trace.add("FRONTIER_BOOTSTRAPPED source=CLIENT_MOVEMENT_OBSERVATION"
              + " tick=" + tick.clientTick()
              + " retained=" + !prediction.isEmpty());
          frames.add(frame(
              sequence, packet, tick, move, observedBefore, observedAfter,
              predictedBefore, prediction, world, bootstrapUncertainty, trace));
          continue;
        }
      }

      if (prediction.isEmpty()
          && bootstrapRecoveryRequired
          && tick.known()
          && initialAnchor != null
          && positionsMatch(initialAnchor.position(), observedBefore.position())) {
        long recoveryRootTick = Math.max(0L, tick.clientTick() - 1L);
        Player recoveryRoot = withClientRotation(
            initialAnchor,
            observedBefore.yaw(),
            observedBefore.pitch());
        prediction = Set.of(candidateFromPlayer(
            recoveryRoot,
            recoveryRootTick,
            "INITIAL_AUTHORITATIVE_ANCHOR_RECOVERY",
            -1L,
            entityCollisions(latestAuthority)));
        predictionTick = recoveryRootTick;
        physicsFrontierSuppressedUntilPositionMovement = false;
        latestContinuation = Continuation.ACTIVE;
        trace.add("FRONTIER_RECOVERY source=INITIAL_AUTHORITATIVE_ANCHOR"
            + " reason=client-movement-bootstrap-unavailable"
            + " rootTick=" + recoveryRootTick);
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
          priorPositionObservationSameTick
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

      Vec3 observedMovementReference = new Vec3(
          observedAfter.position().x() - observedBefore.position().x(),
          observedAfter.position().y() - observedBefore.position().y(),
          observedAfter.position().z() - observedBefore.position().z());

      AuthorityAnchor freshPredictionAuthority = freshCausalAuthority(packet);
      MovementEnvironment authoritativeMovementEnvironment =
          freshPredictionAuthority == null
              ? null
              : freshPredictionAuthority.context().movementEnvironment();

      AdvanceResult advance;
      if (explicitTimingFullyRepresented && movementTiming != null) {
        long earliestSimulationTick = movementTiming.simulationClientTicks().min();
        long latestSimulationTick = movementTiming.simulationClientTicks().max();
        advance = advancePredictionAcrossTimingRange(
            prediction,
            earliestSimulationTick,
            latestSimulationTick,
            inputChronologies,
            world,
            maximumCandidates,
            sequence,
            observedMovementReference,
            observedBefore.onGround(),
            authoritativeMovementEnvironment);
        trace.add("TIMING_OFFSETS range=" + earliestSimulationTick + ".."
            + latestSimulationTick
            + " candidates=" + movementTiming.possibleSimulationClientTicks()
            + " exhaustive=" + advance.exhaustive());
      } else {
        advance = advancePrediction(
            prediction,
            startTick,
            targetTick,
            inputChronologies,
            world,
            maximumCandidates,
            sequence,
            observedMovementReference,
            observedBefore.onGround(),
            authoritativeMovementEnvironment);
      }
      trace.add("PREDICT_FORWARD startTick=" + startTick
          + " targetTick=" + targetTick
          + " steps=" + advance.simulatedTicks()
          + " exhaustive=" + advance.exhaustive());
      trace.addAll(advance.trace());

      /*
       * Grim keeps its possible-vector frontier moving even when one source of
       * timing/input chronology is uncertain. An incomplete search means we
       * cannot prove POSSIBLE/IMPOSSIBLE, but the candidates we did model are
       * still valid client states and must become the next causal frontier.
       * Freezing the old root here causes the same movement to be simulated from
       * an increasingly stale tick on every later packet.
       */
      if (!advance.candidates().isEmpty()) {
        prediction = advance.candidates();
        predictionTick = prediction.stream()
            .mapToLong(candidate -> candidate.context().simulationTick())
            .max()
            .orElse(targetTick);
      }

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
        trace.add("FRONTIER_ADVANCED_UNCERTAIN after=" + prediction.size()
            + " tick=" + predictionTick
            + " modeledSteps=" + advance.simulatedTicks());
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            predictedBefore, prediction, world, uncertaintySources, trace));
        continue;
      }

      prediction = advance.candidates();
      predictionTick = targetTick;

      Vec3 observedDelta = observedMovementReference;
      trace.add("OBSERVATION_DELTA observed=" + observedDelta
          + " priorPosition=" + observedBefore.position()
          + " observedPosition=" + observedAfter.position());
      Candidate closestPrediction = prediction.stream()
          .min(Comparator.comparingDouble(candidate ->
              positionDistanceSquared(candidate.context().player().position(),
                  observedAfter.position())))
          .orElse(null);
      if (closestPrediction != null) {
        Vec3 predictedDelta = new Vec3(
            closestPrediction.context().player().position().x() - observedBefore.position().x(),
            closestPrediction.context().player().position().y() - observedBefore.position().y(),
            closestPrediction.context().player().position().z() - observedBefore.position().z());
        MovementEnvironment closestEnvironment = closestPrediction.context().movementEnvironment();
        trace.add("PREDICTION_COMPARE closestId=" + closestPrediction.id()
            + " predictedDelta=" + predictedDelta
            + " deltaError=" + new Vec3(
                predictedDelta.x() - observedDelta.x(),
                predictedDelta.y() - observedDelta.y(),
                predictedDelta.z() - observedDelta.z())
            + " physicalSprint=" + closestEnvironment.sprinting()
            + " physicalSneak=" + closestEnvironment.sneaking()
            + " candidateInput=" + closestPrediction.provenance().input());
      }

      Set<Candidate> fullMatches = matchingCandidates(prediction, observedAfter, move);
      Set<Candidate> positionRotationMatches =
          matchingCandidatesWithoutGround(prediction, observedAfter, move);
      boolean groundClaimMismatch =
          fullMatches.isEmpty()
              && move.onGround() != null
              && !positionRotationMatches.isEmpty();
      if (groundClaimMismatch) {
        trace.add("GROUND_CLAIM_MISMATCH"
            + " observed=" + observedAfter.onGround()
            + " predictedCandidates=" + positionRotationMatches.size()
            + " movementReachability=not-impossible");
      }

      /*
       * An exhaustive search can be kinematically complete without being a
       * mathematically exact representation of every client/server collision
       * boundary. Grim keeps the live client velocity and actual movement as
       * separate state, so a tiny post-collision position delta must not be
       * allowed to destroy the carried client-velocity frontier.
       *
       * This path does not weaken Phase 6 matching or turn the close candidate
       * into a clean POSSIBLE result. It marks the observation UNCERTAIN and
       * rebases the retained candidate to the observed position for the next
       * client tick.
       */
      Optional<Candidate> reconciliation = Optional.empty();
      if (fullMatches.isEmpty()
          && !groundClaimMismatch
          && uncertaintySources.isEmpty()) {
        reconciliation = reconcileClosePredictionMismatch(
            prediction, observedAfter, trace);
      }
      if (reconciliation.isPresent()) {
        Candidate reconciled = reconciliation.orElseThrow();
        prediction = Set.of(reconciled);
        predictionTick = targetTick;
        latestContinuation = Continuation.UNCERTAIN;
        uncertaintySources.add(
            "exhaustive prediction was within the Phase 8 position-reconciliation envelope but not the strict Phase 6 match tolerance");

        SearchResult reconciliationSearch = new SearchResult(
            Verdict.UNCERTAIN,
            prediction,
            advance.simulatedTicks(),
            prediction.size(),
            0, 0, 0, 0,
            List.of(
                "strict Phase 6 position matching failed",
                "closest deterministic candidate remained within the bounded Phase 8 reconciliation envelope",
                "client position was rebased while the persistent client velocity was retained"));
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, world,
            tick, uncertaintySources, reconciliationSearch, false,
            EnumSet.of(
                Phase6Reachability.ObservedField.POSITION,
                Phase6Reachability.ObservedField.ROTATION,
                Phase6Reachability.ObservedField.GROUND));
        results.add(result);
        uncertain++;
        rememberObservedMovement(observedBefore, observedAfter, tick);
        trace.add("FRONTIER_RECONCILED reason=CLOSE_EXHAUSTIVE_MISMATCH"
            + " targetTick=" + targetTick
            + " retainedClientVelocity=" + reconciled.context().clientVelocity());
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            predictedBefore, prediction, world, uncertaintySources, trace));
        continue;
      }

      Optional<Candidate> inertialRecovery = Optional.empty();
      if (fullMatches.isEmpty() && !groundClaimMismatch) {
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
                  "observed displacement is a canonical inertial continuation of the preceding client movement",
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
              && !tick.timingUncertain()
              && uncertaintySources.isEmpty()
              && !groundClaimMismatch;
      /*
       * Exhaustively enumerating the permitted simulation offsets can eliminate
       * offset ambiguity, but it cannot erase an independent Phase 7 chronology
       * uncertainty. Keep that signal intact so evidence stays honest.
       */
      TickResolution validationTick;
      if (groundClaimMismatch) {
        validationTick = new TickResolution(
            tick.clientTick(),
            tick.known(),
            tick.exact(),
            true,
            tick.source(),
            "client ground claim differs from the simulated physical ground state; movement reachability does not treat this claim mismatch as an IMPOSSIBLE contradiction");
      } else {
        validationTick =
            explicitTimingFullyRepresented
                ? tick.withTimingUncertaintyResolved(
                    "Phase 7 bounded simulation timing was exhaustively evaluated for every permitted offset")
                : tick;
      }
      Set<Phase6Reachability.ObservedField> validationFields =
          groundClaimMismatch
              ? EnumSet.of(
                  Phase6Reachability.ObservedField.POSITION,
                  Phase6Reachability.ObservedField.ROTATION)
              : EnumSet.of(
                  Phase6Reachability.ObservedField.POSITION,
                  Phase6Reachability.ObservedField.ROTATION,
                  Phase6Reachability.ObservedField.GROUND);
      Phase8MovementValidation.Result result = validate(
          playerId, packet, move, observedBefore, observedAfter, world,
          validationTick, uncertaintySources, search, timingExhaustive, validationFields);

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
           * Keep the persistent client-physics state and observed chronology, but
           * do not promote a contradicted candidate set into the next validation
           * baseline. A fresh causal authority or movement bootstrap can establish
           * the next trusted root.
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
           * Preserve the last complete physics frontier through timing/world/input
           * uncertainty. The missing fact changes the confidence of this observation,
           * not the client trajectory already established by earlier complete ticks.
           */
          trace.add("FRONTIER_RETAINED reason=UNCERTAIN_OBSERVATION"
              + " candidates=" + prediction.size()
              + " tick=" + predictionTick);
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

  private boolean refreshFromCausalAuthorityIfStale(
      Packets.RawPacket movementPacket,
      Packets.Move move,
      Player observedBefore,
      TickResolution tick,
      WorldSnapshot world,
      List<String> trace) {
    if (prediction.isEmpty() || !tick.known()) return false;
    AuthorityAnchor authority = latestCausalAuthority(movementPacket);
    if (authority == null || authority.clientTick() == null) return false;

    long authorityTick = authority.clientTick();
    long lag = tick.clientTick() - predictionTick;
    if (predictionTick < 0L || lag <= PREDICTION_RESYNC_LAG_TICKS) return false;
    if (authorityTick > tick.clientTick()) return false;

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
    return true;
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
    MovementEnvironment authoritativeEnvironment = context.movementEnvironment();
    trace.add("ROOT_MOVEMENT_STATE sprint=" + authoritativeEnvironment.sprinting()
        + " sneak=" + authoritativeEnvironment.sneaking()
        + " ground=" + authoritativeEnvironment.onGround());
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

  private Optional<Candidate> physicalCandidateAtObservedPosition(
      Vec3 position,
      long maximumSimulationTick) {
    return prediction.stream()
        .filter(candidate -> candidate.context().simulationTick() <= maximumSimulationTick)
        .filter(candidate -> positionMatches(
            candidate.context().player().position(),
            position))
        .min(Comparator
            .comparingLong((Candidate candidate) ->
                Math.abs(maximumSimulationTick - candidate.context().simulationTick()))
            .thenComparingDouble(candidate ->
                positionDistanceSquared(candidate.context().player().position(), position)));
  }

  private static Optional<Candidate> reconcileClosePredictionMismatch(
      Set<Candidate> candidates,
      Player observedAfter,
      List<String> trace) {
    Candidate closest = candidates.stream()
        .min(Comparator.comparingDouble(candidate ->
            positionDistanceSquared(
                candidate.context().player().position(),
                observedAfter.position())))
        .orElse(null);
    if (closest == null) return Optional.empty();

    double distance = Math.sqrt(positionDistanceSquared(
        closest.context().player().position(),
        observedAfter.position()));
    if (!Double.isFinite(distance)
        || distance <= POSITION_TOLERANCE
        || distance > POSITION_RECONCILIATION_TOLERANCE) {
      return Optional.empty();
    }

    Context old = closest.context();
    Player oldPlayer = old.player();
    Player rebasedPlayer = new Player(
        observedAfter.position(),
        oldPlayer.velocity(),
        observedAfter.yaw(),
        observedAfter.pitch(),
        oldPlayer.onGround(),
        oldPlayer.gamemode(),
        oldPlayer.effects(),
        oldPlayer.awaitingTeleport(),
        oldPlayer.uncertain(),
        oldPlayer.input(),
        oldPlayer.attributes(),
        oldPlayer.pose(),
        oldPlayer.environment(),
        observedAfter.clientTickRange(),
        oldPlayer.provenance(),
        oldPlayer.uncertaintyReasons());

    Context rebasedContext = new Context(
        old.simulationTick(),
        rebasedPlayer,
        old.clientVelocity(),
        old.environment(),
        old.attributes(),
        old.effects(),
        old.pose(),
        old.movementEnvironment(),
        old.sleeping(),
        old.entityCollisions(),
        old.uncertainty(),
        null,
        old.lastOnGround());

    trace.add("CLOSE_MISMATCH candidate=" + closest.id()
        + " distance=" + distance
        + " strictTolerance=" + POSITION_TOLERANCE
        + " reconciliationTolerance=" + POSITION_RECONCILIATION_TOLERANCE
        + " oldPosition=" + oldPlayer.position()
        + " observedPosition=" + observedAfter.position()
        + " clientVelocity=" + old.clientVelocity());

    return Optional.of(new Candidate(
        closest.id(),
        rebasedContext,
        closest.provenance()));
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

    if (!positionMatches(lastObservedMovementPosition, observedBefore.position())) {
      return Optional.empty();
    }

    /*
     * Move.onGround is a client claim. Use the retained physical prediction
     * state at this position instead of feeding that claim back into physics.
     */
    Optional<Candidate> physicalBeforeCandidate =
        physicalCandidateAtObservedPosition(observedBefore.position(), tick.clientTick() - 1L);
    if (physicalBeforeCandidate.isEmpty()) {
      return Optional.empty();
    }
    Candidate physicalBefore = physicalBeforeCandidate.orElseThrow();
    Player physicalBeforePlayer = physicalBefore.context().player();
    MovementEnvironment physicalBeforeEnvironment =
        physicalBefore.context().movementEnvironment();
    boolean physicalBeforeGround = physicalBeforePlayer.onGround();

    AuthorityAnchor authority = freshCausalAuthority(movementPacket);
    if (authority == null
        || !positionsMatch(authority.context().serverPosition(), observedBefore.position())) {
      return Optional.empty();
    }

    MovementEnvironment environment = authority.context().movementEnvironment();
    if (environment.fluid() != Fluid.NONE
        || environment.climbable()
        || environment.gliding()) {
      return Optional.empty();
    }

    double previousDx = lastObservedMovementPosition.x() - previousObservedMovementPosition.x();
    double previousDy = lastObservedMovementPosition.y() - previousObservedMovementPosition.y();
    double previousDz = lastObservedMovementPosition.z() - previousObservedMovementPosition.z();
    double currentDx = observedAfter.position().x() - observedBefore.position().x();
    double currentDy = observedAfter.position().y() - observedBefore.position().y();
    double currentDz = observedAfter.position().z() - observedBefore.position().z();

    double horizontalFactor;
    if (physicalBeforeGround) {
      int supportX = (int) Math.floor(previousObservedMovementPosition.x());
      int supportY = (int) Math.floor(previousObservedMovementPosition.y() - 1.0E-4);
      int supportZ = (int) Math.floor(previousObservedMovementPosition.z());
      if (world.coverageAt(supportX, supportY, supportZ)
          != dev.phantom.ac.world.Coverage.KNOWN) {
        return Optional.empty();
      }
      var support = world.requireBlockAt(supportX, supportY, supportZ);
      if (support == null || support.isUnsupported()) return Optional.empty();
      horizontalFactor =
          dev.phantom.ac.world.v12111.BlockCatalogue12111.slipperiness(support)
              * Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION;
    } else {
      horizontalFactor = Vanilla12111RichPhysics.AIR_HORIZONTAL_FRICTION;
    }

    double expectedDx = previousDx * horizontalFactor;
    double expectedDz = previousDz * horizontalFactor;
    double expectedDy;
    if (physicalBeforeGround) {
      if (Math.abs(currentDy) > POSITION_TOLERANCE) return Optional.empty();
      expectedDy = 0.0;
    } else {
      MovementEffects effects = movementEffects(observedBefore);
      double gravity = Vanilla12111RichPhysics.GRAVITY
          * authority.context().movementEnvironment().gravityMultiplier();
      expectedDy =
          (previousDy - gravity * effects.fallGravityMultiplier())
              * Vanilla12111RichPhysics.AIR_VERTICAL_DRAG;
    }

    double continuationError = Math.sqrt(
        Math.pow(currentDx - expectedDx, 2.0)
            + Math.pow(currentDy - expectedDy, 2.0)
            + Math.pow(currentDz - expectedDz, 2.0));
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
        physicalBeforeGround,
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
        simulationEnvironmentFor(
            preserveClientLocomotionState(
                physicalBeforeEnvironment,
                withVehicle(
                    authority.context().movementEnvironment(),
                    authority.context().vehicleState()),
                physicalBeforeGround)),
        reconstructedStart.attributes(),
        movementEffects(reconstructedStart),
        reconstructedStart.pose(),
        preserveClientLocomotionState(
            physicalBeforeEnvironment,
            withVehicle(
                authority.context().movementEnvironment(),
                authority.context().vehicleState()),
            physicalBeforeGround),
        reconstructedStart.pose() == Pose.SLEEPING,
        false,
        EntityCollisions.of(authority.context().entityBoxes()),
        null,
        observedBefore.onGround());
    Vanilla12111RichPhysics.StepResult step =
        new Vanilla12111RichPhysics().step(context);

    if (step.state().uncertain()
        || step.collided()
        || !positionsMatch(step.state().position(), observedAfter.position())) {
      return Optional.empty();
    }

    if (move.onGround() != null && move.onGround() != step.state().onGround()) {
      trace.add("GROUND_CLAIM_SEPARATED recoveryPhysicalGround="
          + step.state().onGround()
          + " clientClaim=" + move.onGround());
    }

    trace.add("INERTIAL_RECOVERY previousDelta="
        + new Vec3(previousDx, previousDy, previousDz)
        + " expectedCurrentDelta=" + new Vec3(expectedDx, expectedDy, expectedDz)
        + " observedCurrentDelta=" + new Vec3(currentDx, currentDy, currentDz)
        + " friction=" + horizontalFactor
        + " priorPhysicalGround=" + physicalBeforeGround
        + " clientGroundClaimBefore=" + observedBefore.onGround()
        + " inputHistoryExplanation=neutral-continuation");

    return Optional.of(candidateFromPlayer(
        step.state(),
        tick.clientTick(),
        "CLIENT_OBSERVED_INERTIAL_CONTINUATION",
        authority.sequence(),
        EntityCollisions.of(authority.context().entityBoxes()),
        environment,
        step.clientVelocityAfterTick()));
  }

  private Optional<Set<Candidate>> bootstrapPredictionFromObservedMovement(
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

    boolean authorityMatchesObservedBefore =
        positionsMatch(authority.context().serverPosition(), observedBefore.position());
    if (!authorityMatchesObservedBefore) {
      trace.add("BOOTSTRAP_AUTHORITY_SPATIAL_MISMATCH"
          + " authorityPosition=" + authority.context().serverPosition()
          + " observedBefore=" + observedBefore.position()
          + " using-authority-for-dynamic-state=true");
    }

    Optional<Candidate> physicalBeforeCandidate =
        physicalCandidateAtObservedPosition(observedBefore.position(), tick.clientTick() - 1L);

    LinkedHashSet<Boolean> physicalGroundOptions = new LinkedHashSet<>();
    if (physicalBeforeCandidate.isPresent()) {
      physicalGroundOptions.add(
          physicalBeforeCandidate.orElseThrow().context().player().onGround());
    } else if (authorityMatchesObservedBefore) {
      physicalGroundOptions.add(authority.context().movementEnvironment().onGround());
    } else {
      /*
       * After an observation witness clears the physics frontier, neither the
       * client ground claim nor a post-movement authority sample is atomic
       * physical state. Enumerate both bounded physical-ground possibilities.
       */
      physicalGroundOptions.add(false);
      physicalGroundOptions.add(true);
    }

    long simulationTick = tick.clientTick() - 1L;
    // Grim's bootstrap path consumes the current held-state input as well.
    InputConstraint input = currentInput;
    Optional<Simulation.AdvancedInput> keyInput =
        inputConstraintToAdvancedInput(input);
    if (keyInput.isEmpty()) return Optional.empty();

    MovementEnvironment authorityEnvironment = authority.context().movementEnvironment();
    Simulation.AdvancedInput keyState = keyInput.orElseThrow();
    LinkedHashSet<MovementInputState> locomotionOptions = new LinkedHashSet<>();
    locomotionOptions.add(new MovementInputState(
        authorityEnvironment.sprinting(), authorityEnvironment.sneaking()));
    locomotionOptions.add(new MovementInputState(
        keyState.sprint(), keyState.sneak()));
    trace.add("BOOTSTRAP_LOCOMOTION_OPTIONS authority="
        + authorityEnvironment.sprinting() + "/" + authorityEnvironment.sneaking()
        + " key=" + keyState.sprint() + "/" + keyState.sneak()
        + " alternatives=" + locomotionOptions);

    Player authorityState = playerFromAuthority(authority.context());
    float yaw = move.yaw() == null ? observedBefore.yaw() : move.yaw();
    float pitch = move.pitch() == null ? observedBefore.pitch() : move.pitch();
    Vec3 observedDelta = new Vec3(
        observedAfter.position().x() - observedBefore.position().x(),
        observedAfter.position().y() - observedBefore.position().y(),
        observedAfter.position().z() - observedBefore.position().z());

    Set<Candidate> candidates = new LinkedHashSet<>();
    for (boolean physicalGround : physicalGroundOptions) {
      for (MovementInputState locomotion : locomotionOptions) {
        MovementEnvironment environment = preserveClientLocomotionState(
            physicalBeforeCandidate
                .map(candidate -> candidate.context().movementEnvironment())
                .orElse(authorityEnvironment),
            withVehicle(authorityEnvironment, authority.context().vehicleState()),
            physicalGround);
        environment = withLocomotionState(
            environment, locomotion.sprinting(), locomotion.sneaking());
      Simulation.AdvancedInput advancedInput = new Simulation.AdvancedInput(
          keyState.forward(),
          keyState.strafe(),
          keyState.jump(),
          locomotion.sprinting(),
          locomotion.sneaking());

      Player startTemplate = new Player(
          observedBefore.position(),
          authorityState.velocity(),
          yaw,
          pitch,
          physicalGround,
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

      Optional<Vec3> startVelocity = reconstructCollisionFreeStartVelocity(
          startTemplate, advancedInput, observedDelta, world, environment);
      if (startVelocity.isEmpty()) {
        trace.add("BOOTSTRAP_REJECTED locomotion=" + locomotion
            + " reason=start-velocity-reconstruction-unavailable");
        continue;
      }

      double horizontalSpeed = Math.hypot(
          startVelocity.orElseThrow().x(), startVelocity.orElseThrow().z());
      if (!Double.isFinite(horizontalSpeed) || horizontalSpeed > 1.25
          || Math.abs(startVelocity.orElseThrow().y()) > 4.0) {
        trace.add("BOOTSTRAP_REJECTED locomotion=" + locomotion
            + " reason=starting-velocity-outside-conservative-bound"
            + " velocity=" + startVelocity.orElseThrow());
        continue;
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
          advancedInput,
          world,
          simulationEnvironmentFor(environment),
          reconstructedStart.attributes(),
          movementEffects(reconstructedStart),
          reconstructedStart.pose(),
          environment,
          reconstructedStart.pose() == Pose.SLEEPING,
          false,
        EntityCollisions.of(authority.context().entityBoxes()),
        observedDelta,
        observedBefore.onGround());
      Vanilla12111RichPhysics.StepResult step =
          new Vanilla12111RichPhysics().step(context);

      if (step.state().uncertain()
          || !positionsMatch(step.state().position(), observedAfter.position())) {
        trace.add("BOOTSTRAP_REJECTED locomotion=" + locomotion
            + " reason=canonical-physics-replay-did-not-reproduce"
            + " reconstructed=" + step.state().position()
            + " observed=" + observedAfter.position()
            + " reconstructedGround=" + step.state().onGround()
            + " clientGroundClaim=" + observedAfter.onGround());
        continue;
      }

      if (move.onGround() != null && move.onGround() != step.state().onGround()) {
        trace.add("GROUND_CLAIM_SEPARATED bootstrapPhysicalGround="
            + step.state().onGround()
            + " clientClaim=" + move.onGround());
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

      candidates.add(candidateFromPlayer(
          after,
          tick.clientTick(),
          "CLIENT_MOVEMENT_BOOTSTRAP",
          authority.sequence(),
          EntityCollisions.of(authority.context().entityBoxes()),
          environment,
          step.clientVelocityAfterTick()));
        trace.add("BOOTSTRAP_START simulationTick=" + simulationTick
            + " observedDelta=" + observedDelta
            + " reconstructedStartVelocity=" + startVelocity.orElseThrow()
            + " authoritativeVelocity=" + authority.context().serverVelocity()
            + " input=" + advancedInput
            + " inputSelection=grim-held-state"
            + " locomotion=" + locomotion
            + " physicalGround=" + physicalGround);
      }
    }

    return candidates.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(candidates));
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
      double offGroundSpeed = environment.sprinting()
          ? Vanilla12111RichPhysics.SPRINT_AIR_ACCEL
          : Vanilla12111RichPhysics.AIR_ACCEL;
      inputAcceleration = inputMagnitude > 1.0
          ? offGroundSpeed
          : offGroundSpeed * Vanilla12111RichPhysics.INPUT_FRICTION;
    }

    double yaw = Math.toRadians(start.yaw());
    // AdvancedInput uses the project wire/trace convention: strafe +1 is right.
    double vanillaStrafe = -input.strafe();
    double accelerationX = inputScale * (
        vanillaStrafe * inputAcceleration * Math.cos(yaw)
            - input.forward() * inputAcceleration * Math.sin(yaw));
    double accelerationZ = inputScale * (
        input.forward() * inputAcceleration * Math.cos(yaw)
            + vanillaStrafe * inputAcceleration * Math.sin(yaw));

    double boostX = 0.0;
    double boostZ = 0.0;
    boolean jumped = input.jump()
        && start.onGround()
        && start.pose() != Pose.SLEEPING
        && environment.fluid() == Fluid.NONE
        && !environment.climbable()
        && !environment.gliding();
    if (jumped && environment.sprinting()) {
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
    MovementEnvironment environment = movementEnvironmentOf(
        witnessPlayer, Phase5Mechanics.VehicleState.NONE);
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
    return candidateFromPlayer(
        player, simulationTick, source, parentSequence, entityCollisions,
        movementEnvironmentOverride, player.velocity());
  }

  private Candidate candidateFromPlayer(
      Player player,
      long simulationTick,
      String source,
      long parentSequence,
      EntityCollisions entityCollisions,
      MovementEnvironment movementEnvironmentOverride,
      Vec3 clientVelocity) {
    MovementEnvironment environment = movementEnvironmentOverride == null
        ? movementEnvironmentOf(player, latestAuthority == null
            ? Phase5Mechanics.VehicleState.NONE
            : latestAuthority.context().vehicleState())
        : movementEnvironmentOverride;
    Context context = new Context(
        simulationTick,
        player,
        clientVelocity,
        simulationEnvironmentFor(environment),
        player.attributes(),
        movementEffects(player),
        player.pose(),
        environment,
        player.pose() == Pose.SLEEPING,
        entityCollisions == null ? EntityCollisions.NONE_TRACKED : entityCollisions,
        Set.of(),
        null,
        player.onGround());
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

  private static SpatialRebaseResult rebasePredictionToObservedBefore(
      Set<Candidate> candidates,
      Player observedBefore,
      TickResolution tick,
      List<String> trace) {
    if (!tick.known() || tick.clientTick() <= 0L || candidates.isEmpty()) {
      return new SpatialRebaseResult(candidates, false);
    }

    long expectedRootTick = Math.max(0L, tick.clientTick() - 1L);
    Set<Candidate> rebased = new LinkedHashSet<>();
    int rebasedCount = 0;

    for (Candidate candidate : candidates) {
      Context context = candidate.context();
      if (context.simulationTick() != expectedRootTick
          || context.player().onGround() != observedBefore.onGround()) {
        rebased.add(candidate);
        continue;
      }

      double distanceSquared =
          positionDistanceSquared(context.player().position(), observedBefore.position());
      if (distanceSquared > POSITION_TOLERANCE * POSITION_TOLERANCE) {
        rebased.add(candidate);
        continue;
      }

      if (positionExactlyMatches(context.player().position(), observedBefore.position())) {
        rebased.add(candidate);
        continue;
      }

      Player player = context.player();
      Player rebasedPlayer = new Player(
          observedBefore.position(),
          player.velocity(),
          player.yaw(),
          player.pitch(),
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

      rebased.add(new Candidate(
          candidate.id(),
          new Context(
              context.simulationTick(),
              rebasedPlayer,
              context.clientVelocity(),
              context.environment(),
              context.attributes(),
              context.effects(),
              context.pose(),
              context.movementEnvironment(),
              context.sleeping(),
              context.entityCollisions(),
              context.uncertainty(),
              // A spatial rebase establishes a new observed pre-movement root.
              // Do not carry the prior tick's collision movement reference across
              // that boundary; Grim keeps clientVelocity persistent but derives
              // actualMovement again from the next movement tick.
              null,
              observedBefore.onGround()),
          candidate.provenance()));
      rebasedCount++;
    }

    if (rebasedCount > 0) {
      trace.add("FRONTIER_SPATIAL_REBASE count=" + rebasedCount
          + " expectedRootTick=" + expectedRootTick
          + " observedPosition=" + observedBefore.position()
          + " tolerance=" + POSITION_TOLERANCE);
    }
    return new SpatialRebaseResult(Set.copyOf(rebased), rebasedCount > 0);
  }

  private static Context withLocomotionState(
      Context context,
      boolean sprinting,
      boolean sneaking) {
    return new Context(
        context.simulationTick(),
        context.player(),
        context.clientVelocity(),
        context.environment(),
        context.attributes(),
        context.effects(),
        context.pose(),
        withLocomotionState(context.movementEnvironment(), sprinting, sneaking),
        context.sleeping(),
        context.entityCollisions(),
        context.uncertainty(),
        context.actualMovementReference(),
        context.lastOnGround());
  }

  private static MovementEnvironment withLocomotionState(
      MovementEnvironment base,
      boolean sprinting,
      boolean sneaking) {
    return new MovementEnvironment(
        base.fluid(),
        base.submerged(),
        base.climbable(),
        base.onGround(),
        sprinting,
        sneaking,
        base.swimmingInput(),
        base.gliding(),
        base.fluidSpeedMultiplier(),
        base.fluidDrag(),
        base.gravityMultiplier(),
        base.vehicle());
  }

  private static MovementEnvironment withVehicle(
      MovementEnvironment environment,
      Phase5Mechanics.VehicleState vehicle) {
    return new MovementEnvironment(
        environment.fluid(),
        environment.submerged(),
        environment.climbable(),
        environment.onGround(),
        environment.sprinting(),
        environment.sneaking(),
        environment.swimmingInput(),
        environment.gliding(),
        environment.fluidSpeedMultiplier(),
        environment.fluidDrag(),
        environment.gravityMultiplier(),
        vehicle);
  }

  private static MovementEnvironment movementEnvironmentOf(
      Player player,
      Phase5Mechanics.VehicleState vehicle) {
    return switch (player.environment()) {
      case WATER -> withVehicle(
          MovementEnvironment.vanillaWater(
              player.onGround(), false, false, player.pose() == Pose.SWIMMING), vehicle);
      case LAVA -> withVehicle(
          MovementEnvironment.vanillaLava(player.onGround(), false, false), vehicle);
      case CLIMBABLE -> withVehicle(
          MovementEnvironment.vanillaClimbable(player.onGround(), false, false), vehicle);
      case DRY -> withVehicle(
          MovementEnvironment.dry(player.onGround(), false, false), vehicle);
      case UNKNOWN -> withVehicle(
          MovementEnvironment.dry(player.onGround(), false, false), vehicle);
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

      /*
       * Server-side movement context is authority evidence, not an atomic client
       * locomotion state. In particular, a later PlayerContext may legitimately
       * report sprint=false while the retained client-side prediction still has
       * an active sprint state. Never erase client physics sprint/sneak state just
       * because an asynchronous server snapshot says otherwise.
       */
      MovementEnvironment authorityEnvironment = withVehicle(
          authority.movementEnvironment(), authority.vehicleState());
      MovementEnvironment clientMovementEnvironment =
          preserveClientLocomotionState(
              candidate.context().movementEnvironment(),
              authorityEnvironment,
              merged.onGround());

      result.add(rebuildCandidate(
          candidate, merged, collisions, clientMovementEnvironment));
    }
    return Set.copyOf(result);
  }

  static MovementEnvironment preserveClientLocomotionState(
      MovementEnvironment client,
      MovementEnvironment authority,
      boolean onGround) {
    Objects.requireNonNull(client);
    Objects.requireNonNull(authority);
    return new MovementEnvironment(
        authority.fluid(),
        authority.submerged(),
        authority.climbable(),
        onGround,
        client.sprinting(),
        client.sneaking(),
        authority.swimmingInput(),
        authority.gliding(),
        authority.fluidSpeedMultiplier(),
        authority.fluidDrag(),
        authority.gravityMultiplier(),
        authority.vehicle());
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
        old.clientVelocity(),
        simulationEnvironmentFor(environment),
        player.attributes(),
        movementEffects(player),
        player.pose(),
        environment,
        player.pose() == Pose.SLEEPING,
        entityCollisions == null ? EntityCollisions.NONE_TRACKED : entityCollisions,
        old.uncertainty(),
        old.actualMovementReference(),
        old.lastOnGround());
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
      List<InputChronology> inputChronologies,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence,
      Vec3 actualMovementReference,
      boolean lastOnGroundForPrediction,
      MovementEnvironment authoritativeMovementEnvironment) {
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
        start, targetTick, inputChronologies, world, maximumCandidates, movementSequence,
        actualMovementReference, lastOnGroundForPrediction, authoritativeMovementEnvironment);
  }

  private AdvanceResult advancePredictionAcrossTimingRange(
      Set<Candidate> start,
      long earliestTick,
      long latestTick,
      List<InputChronology> inputChronologies,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence,
      Vec3 actualMovementReference,
      boolean lastOnGroundForPrediction,
      MovementEnvironment authoritativeMovementEnvironment) {
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
          start, target, inputChronologies, world, maximumCandidates, movementSequence,
          actualMovementReference, lastOnGroundForPrediction,
          authoritativeMovementEnvironment);
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

  private static Set<Candidate> markCandidatesUncertain(
      Set<Candidate> candidates,
      Set<Phase6Reachability.UncertainDimension> dimensions) {
    Set<Candidate> result = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      result.add(new Candidate(
          candidate.id(),
          candidate.context().withUncertainty(
              dimensions.toArray(Phase6Reachability.UncertainDimension[]::new)),
          candidate.provenance(),
          candidate.serverTickAssociation(),
          candidate.timingReference(),
          candidate.worldReference(),
          candidate.worldKnowledge(),
          candidate.movementMode(),
          candidate.inputAssumption(),
          candidate.transitionDiagnostics(),
          candidate.entityCollisionReference()));
    }
    return Set.copyOf(result);
  }

  private AdvanceResult advancePredictionToTarget(
      Set<Candidate> start,
      long targetTick,
      List<InputChronology> inputChronologies,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence,
      Vec3 actualMovementReference,
      boolean lastOnGroundForPrediction,
      MovementEnvironment authoritativeMovementEnvironment) {
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

    List<InputChronology> chronologies = inputChronologies.isEmpty()
        ? List.of(new InputChronology(new TreeMap<>()))
        : inputChronologies;

    for (InputChronology chronology : chronologies) {
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
          // Match Grim: simulated ticks consume the current held input state.
          List<InputConstraint> inputOptions = List.of(currentInput);

          Candidate beforeCandidate = local.stream().findFirst().orElse(null);
          if (beforeCandidate != null) {
            MovementEnvironment frontierEnvironment =
                beforeCandidate.context().movementEnvironment();
            trace.add("SIM_INPUT_OPTIONS tick=" + simulationTick
                + " keyOptions=" + inputOptions
                + " physicalSprint=" + frontierEnvironment.sprinting()
                + " physicalSneak=" + frontierEnvironment.sneaking()
                + " startPos=" + beforeCandidate.context().player().position()
                + " startVel=" + beforeCandidate.context().player().velocity()
                + " startGround=" + beforeCandidate.context().player().onGround()
                + " inputSelection=grim-held-state");
          }

          GrimPredictionEngine.TickResult engineResult = grimPredictionEngine.tick(
              local,
              inputOptions,
              world,
              maximumCandidates,
              movementSequence,
              simulationTick,
              targetTick,
              actualMovementReference,
              lastOnGroundForPrediction,
              authoritativeMovementEnvironment);

          trace.addAll(engineResult.trace());
          LinkedHashSet<String> stepReasons = new LinkedHashSet<>(engineResult.reasons());
          boolean stepExhaustive = engineResult.exhaustive();
          Set<Candidate> stepCandidates = engineResult.candidates();

          simulatedTicks++;
          if (!stepExhaustive) {
            reasons.addAll(stepReasons);
            reasons.add("prediction step " + simulationTick
                + " was not exhaustively modeled");
            trace.add("SIM_STEP tick=" + simulationTick
                + " exhaustive=false"
                + " branchCandidates=" + stepCandidates.size()
                + " reasons=" + stepReasons);
            exhaustive = false;
          }

          if (stepCandidates.isEmpty()) {
            local = Set.of();
            break;
          }

          if (stepCandidates.size() > maximumCandidates) {
            return new AdvanceResult(Set.of(), false, simulatedTicks,
                List.of("prediction candidate budget exceeded across input chronologies"),
                List.copyOf(trace));
          }

          /*
           * Grim continues its possible-vector frontier after a non-exhaustive
           * input/timing step. We cannot promote the result to POSSIBLE, but a
           * non-empty modeled vector is still a valid causal state and must be
           * carried into the next tick rather than freezing the parent frontier.
           */
          local = Set.copyOf(stepCandidates);
          Candidate afterCandidate = local.stream().findFirst().orElse(null);
          if (afterCandidate != null) {
            MovementEnvironment resultEnvironment =
                afterCandidate.context().movementEnvironment();
            trace.add("SIM_STEP tick=" + simulationTick
                + " exhaustive=true"
                + " resultPos=" + afterCandidate.context().player().position()
                + " resultVel=" + afterCandidate.context().player().velocity()
                + " resultGround=" + afterCandidate.context().player().onGround()
                + " resultPhysicalSprint=" + resultEnvironment.sprinting()
                + " resultPhysicalSneak=" + resultEnvironment.sneaking());
          }

          localTick++;
        }

        union.addAll(local);
        if (union.size() > maximumCandidates) {
          return new AdvanceResult(Set.of(), false, simulatedTicks,
              List.of("combined prediction candidate budget exceeded across input chronologies"),
              List.copyOf(trace));
        }
      }
    }

    reasons.add("persistent prediction advanced using Grim-style held input state");
    return new AdvanceResult(
        Set.copyOf(union), exhaustive, simulatedTicks,
        List.copyOf(reasons), List.copyOf(trace));
  }

  private String inputSelectionDebug(
      NavigableMap<Long, List<TimedInput>> history,
      long simulationTick,
      long movementSequence) {
    String selected = "selected=none";
    if (!history.isEmpty()) {
      outer:
      for (var entry : history.headMap(simulationTick, true).descendingMap().entrySet()) {
        for (TimedInput input : entry.getValue()) {
          if (input.sequence() > movementSequence) continue;
          selected = "selectedSeq=" + input.sequence()
              + ",selectedTick=" + input.clientTick()
              + ",selectedInput=" + input.constraint();
          break outer;
        }
      }
    }
    StringBuilder uncertain = new StringBuilder();
    for (UncertainInput input : uncertainInputs) {
      if (input.sequence() > movementSequence || simulationTick < input.earliestClientTick()) continue;
      if (uncertain.length() > 0) uncertain.append(';');
      uncertain.append("seq=").append(input.sequence())
          .append("@tick>=").append(input.earliestClientTick());
    }
    if (uncertain.length() > 0) {
      selected += ",uncertainReplacements=" + uncertain;
    }
    return selected;
  }

  private static Set<Candidate> matchingCandidates(
      Set<Candidate> candidates,
      Player observed,
      Packets.Move move) {
    return matchingCandidatesWithoutGround(candidates, observed, move, true);
  }

  private static Set<Candidate> matchingCandidatesWithoutGround(
      Set<Candidate> candidates,
      Player observed,
      Packets.Move move) {
    return matchingCandidatesWithoutGround(candidates, observed, move, false);
  }

  private static Set<Candidate> matchingCandidatesWithoutGround(
      Set<Candidate> candidates,
      Player observed,
      Packets.Move move,
      boolean includeGround) {
    Set<Candidate> matching = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      Player state = candidate.context().player();
      if (!positionMatches(state.position(), observed.position())) continue;
      if (move.yaw() != null && Float.compare(state.yaw(), observed.yaw()) != 0) continue;
      if (move.pitch() != null && Float.compare(state.pitch(), observed.pitch()) != 0) continue;
      if (includeGround
          && move.onGround() != null
          && state.onGround() != observed.onGround()) continue;
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
    assumptions.add("client physics velocity is tracked separately from instantaneous server velocity");
    assumptions.add("server position and velocity are authority evidence, not an atomic client-tick physics state");
    assumptions.add("server position is used only for anchor/correction state, never as the predicted client position");
    assumptions.add("packet world is selected at or before the movement sequence and is therefore latency-compensated");
    assumptions.add("movement hypotheses remain as a bounded set of candidate client states rather than one forced trajectory");
    assumptions.add("tick reliability is tracked independently from movement physics so timing uncertainty is not mistaken for kinematic impossibility");
    assumptions.add("trusted prediction candidates are retained only while they remain valid physics states; observation witnesses are not reused as physics roots");
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
      NavigableMap<Long, List<TimedInput>> ignoredHistory,
      long simulationTick,
      long targetTick,
      long movementSequence) {
    /*
     * Match Grim's PacketPlayerSteer: PLAYER_INPUT is a held state. The live
     * prediction engine consumes the newest state observed before the movement
     * packet instead of branching over every possible historical input tick.
     */
    if (simulationTick < 0L
        || currentInputSequence < 0L
        || currentInputSequence > movementSequence) {
      return List.of(neutralInput);
    }
    return List.of(currentInput);
  }

  /**
   * Rebuild the live input history from the same Phase 7 timing reconstruction
   * used by the causal offline pipeline. A packet is inserted at every client
   * tick that Phase 7 exhaustively proves possible; raw packet arrival is never
   * treated as the simulation tick.
   */
  private void rebuildCausalInputHistory(
      Map<Long, Phase7Timing.EventTiming> phase7TimingBySequence) {
    inputHistory.clear();
    uncertainInputs.clear();
    inputChronologies = List.of();

    List<Packets.RawPacket> history = List.copyOf(timingHistory);
    List<Packets.NormalizedPacket> normalized =
        new Packets.Normalizer().normalize(history);
    Map<Long, Packets.NormalizedPacket> normalizedBySequence = new HashMap<>();
    for (Packets.NormalizedPacket packet : normalized) {
      normalizedBySequence.put(packet.sequence(), packet);
    }

    List<InputEventAlternatives> exactEvents = new ArrayList<>();

    for (Packets.RawPacket packet : history) {
      if (!(packet.packet() instanceof Packets.ClientInput input)) continue;

      Packets.NormalizedPacket canonical = normalizedBySequence.get(packet.sequence());
      if (canonical == null
          || canonical.flags().contains(Packets.PacketFlag.DUPLICATE)) {
        continue;
      }

      if (canonical.flags().contains(Packets.PacketFlag.OUT_OF_ORDER)
          || canonical.flags().contains(Packets.PacketFlag.SEQUENCE_GAP)) {
        uncertainInputs.add(new UncertainInput(packet.sequence(), 0L));
        continue;
      }

      Phase7Timing.EventTiming timing = phase7TimingBySequence.get(packet.sequence());
      if (timing == null) {
        uncertainInputs.add(new UncertainInput(packet.sequence(), 0L));
        continue;
      }

      InputConstraint constraint = InputConstraint.fromClientInput(input);
      if (!Phase7Timing.simulationTickEnumerationComplete(timing)) {
        long earliestClientTick = timing.inputClientTickEnvelope().known()
            ? alignClientTick(Math.max(0L, timing.inputClientTicks().min()))
            : 0L;
        uncertainInputs.add(new UncertainInput(
            packet.sequence(), earliestClientTick));
        continue;
      }

      /*
       * For a held ClientInput state, the relevant timestamp for movement
       * prediction is the simulation tick on which that state can begin taking
       * effect. Phase 7 derives this separately from packet-generation time via
       * the configured input-to-simulation delay.
       */
      List<Long> candidates = alignClientTicks(
          Phase7Timing.possibleSimulationTicks(timing));
      if (candidates.isEmpty()) {
        uncertainInputs.add(new UncertainInput(packet.sequence(), 0L));
        continue;
      }

      exactEvents.add(new InputEventAlternatives(
          packet.sequence(), constraint, candidates));
    }

    /*
     * Candidate ticks in one Phase 7 envelope are alternatives, not simultaneous
     * events. Build causally ordered held-state chronologies so a later
     * simulation tick cannot combine mutually exclusive assignments from
     * different alternatives. There is deliberately no fixed Phase 8 chronology
     * cutoff: the candidate budget is enforced by the prediction engine itself,
     * while this layer preserves every timing-consistent input chronology.
     */
    List<NavigableMap<Long, List<TimedInput>>> chronologies = new ArrayList<>();
    chronologies.add(new TreeMap<>());

    for (InputEventAlternatives event : exactEvents) {
      List<NavigableMap<Long, List<TimedInput>>> next = new ArrayList<>();

      for (NavigableMap<Long, List<TimedInput>> chronology : chronologies) {
        long minimumTick =
            chronology.isEmpty() ? Long.MIN_VALUE : chronology.lastKey();

        for (long clientTick : event.possibleTicks()) {
          if (clientTick < minimumTick) continue;

          NavigableMap<Long, List<TimedInput>> branch =
              copyInputHistory(chronology);
          branch.computeIfAbsent(clientTick, ignored -> new ArrayList<>())
              .add(new TimedInput(
                  event.sequence(), clientTick, event.constraint()));
          next.add(branch);
        }

      }

      if (next.isEmpty()) {
        long earliestClientTick = Math.max(
            0L, event.possibleTicks().getFirst());
        uncertainInputs.add(new UncertainInput(
            event.sequence(), earliestClientTick));
        continue;
      }
      chronologies = next;
    }

    inputChronologies = chronologies.stream()
        .map(InputChronology::new)
        .toList();

    if (inputChronologies.isEmpty()) {
      inputChronologies = List.of(new InputChronology(new TreeMap<>()));
    }

    inputHistory.putAll(copyInputHistory(
        inputChronologies.getFirst().history()));
  }

  private long alignClientTick(long clientTick) {
    return clientTick;
  }

  private List<Long> alignClientTicks(List<Long> clientTicks) {
    return List.copyOf(clientTicks);
  }

  private static NavigableMap<Long, List<TimedInput>> copyInputHistory(
      NavigableMap<Long, List<TimedInput>> source) {
    NavigableMap<Long, List<TimedInput>> copy = new TreeMap<>();
    for (var entry : source.entrySet()) {
      copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    return copy;
  }

  private InputConstraint inputForSimulationTickExact(
      NavigableMap<Long, List<TimedInput>> history,
      long simulationTick,
      long movementSequence) {
    if (simulationTick < 0L) return neutralInput;

    TimedInput selected = null;
    if (!history.isEmpty()) {
      for (var entry : history.headMap(simulationTick, true).descendingMap().entrySet()) {
        for (TimedInput input : entry.getValue()) {
          if (input.sequence() > movementSequence) break;
          selected = input;
        }
        if (selected != null) break;
      }
    }

    /*
     * A later input update whose exact tick is not recoverable can already have
     * replaced the selected held state. Keep that interval unconstrained rather
     * than silently asserting the previous or neutral input.
     */
    for (UncertainInput input : uncertainInputs) {
      if (input.sequence() > movementSequence) continue;
      if (simulationTick < input.earliestClientTick()) continue;
      if (selected == null || input.sequence() > selected.sequence()) {
        return InputConstraint.any();
      }
    }

    return selected == null ? neutralInput : selected.constraint();
  }

  private static boolean exhaustivelyEnumeratedInputEnvelope(
      SearchResult result,
      InputConstraint input) {
    if (result.verdict() != Verdict.UNCERTAIN
        || result.metrics().budgetReached()
        || result.nonExhaustiveWorldBranches() != 0
        || result.uncertainTransitions() != 0
        || result.candidates().isEmpty()
        || input.enumerate().isEmpty()) {
      return false;
    }
    if (result.reasons().stream().anyMatch(reason ->
        reason.contains("maximum ")
            || reason.contains("world hypothesis")
            || reason.contains("world ")
            || reason.contains("Phase 5")
            || reason.contains("physics")
            || reason.contains("initial state carries explicit uncertainty")
            || reason.contains("no deterministic candidate survived"))) {
      return false;
    }
    return result.candidates().stream()
        .allMatch(candidate -> candidate.context().uncertainty().stream()
            .allMatch(dimension -> dimension == UncertainDimension.INPUT));
  }

  private InputConstraint inputForSimulationTick(
      NavigableMap<Long, List<TimedInput>> ignoredHistory,
      long simulationTick,
      long movementSequence) {
    // Grim treats PLAYER_INPUT as a held state. Use the latest packet that
    // precedes this movement; historical tick assignments are not materialized.
    if (simulationTick < 0L
        || currentInputSequence < 0L
        || currentInputSequence > movementSequence) {
      return neutralInput;
    }
    return currentInput;
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
    Vec3 actualMovement = new Vec3(
        observedAfter.position().x() - observedBefore.position().x(),
        observedAfter.position().y() - observedBefore.position().y(),
        observedAfter.position().z() - observedBefore.position().z());
    Vec3 clientVelocity = clientPhysicsState == null
        ? actualMovement
        : clientPhysicsState.clientVelocity();
    Vec3 predictedVelocity = predictedAfter.isEmpty()
        ? (clientPhysicsState == null ? observedAfter.velocity() : clientPhysicsState.predictedVelocity())
        : predictedAfter.stream()
            .min(Comparator.comparingLong(candidate -> candidate.id()))
            .orElseThrow()
            .context().player().velocity();
    Vec3 serverVelocity = latestAuthority == null
        ? (clientPhysicsState == null ? observedAfter.velocity() : clientPhysicsState.serverVelocity())
        : latestAuthority.context().serverVelocity();
    Long authoritativeServerTick =
        latestAuthority == null ? null : latestAuthority.serverTick();
    long modelTick = tick.known() ? tick.clientTick()
        : (clientPhysicsState == null ? 0L : clientPhysicsState.clientTick());
    clientPhysicsState = clientPhysicsState == null
        ? new Phase8ClientModel.ClientPhysicsState(
            Math.max(0L, modelTick),
            observedAfter.position(),
            clientVelocity,
            predictedVelocity,
            serverVelocity,
            actualMovement,
            observedAfter.input(),
            observedAfter.onGround(),
            observedAfter.onGround(),
            authoritativeServerTick,
            "movement-observation")
        : clientPhysicsState.observe(
            modelTick,
            observedAfter,
            actualMovement,
            clientVelocity,
            predictedVelocity,
            serverVelocity,
            authoritativeServerTick,
            "movement-observation");
    List<Phase8ClientModel.MovementHypothesis> hypotheses =
        Phase8ClientModel.hypotheses(predictedAfter);
    mergedTrace.add("OBSERVED position=" + observedAfter.position()
        + " yaw=" + observedAfter.yaw()
        + " pitch=" + observedAfter.pitch()
        + " ground=" + observedAfter.onGround());
    mergedTrace.add("CLIENT_PHYSICS_STATE clientVelocity=" + clientPhysicsState.clientVelocity()
        + " predictedVelocity=" + clientPhysicsState.predictedVelocity()
        + " serverVelocity=" + clientPhysicsState.serverVelocity()
        + " actualMovement=" + clientPhysicsState.actualMovement()
        + " lastOnGround=" + clientPhysicsState.lastOnGround()
        + " onGround=" + clientPhysicsState.onGround()
        + " tick=" + clientPhysicsState.clientTick());
    mergedTrace.add("HYPOTHESIS_SET count=" + hypotheses.size()
        + " ids=" + hypotheses.stream()
            .map(Phase8ClientModel.MovementHypothesis::candidateId)
            .toList());
    mergedTrace.add("COMPENSATED_WORLD "
        + (compensatedWorld == null
            ? "available=false"
            : "available=true causalSequence=" + compensatedWorld.causalSequence()
                + " movementSequence=" + compensatedWorld.movementSequence()
                + " causallyBounded=" + compensatedWorld.causallyBounded()));
    mergedTrace.add("TICK_RELIABILITY level=" + tickReliability.reliability()
        + " reasons=" + tickReliability.reasons());
    mergedTrace.add("FRONTIER candidates=" + predictedAfter.size()
        + " predictionTick=" + predictionTick
        + " retained=" + !predictedAfter.isEmpty());
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