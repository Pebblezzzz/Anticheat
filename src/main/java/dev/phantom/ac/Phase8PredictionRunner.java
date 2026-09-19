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
      Packets.PlayerContext context) {}

  private static final double POSITION_TOLERANCE = Phase6Reachability.POSITION_MATCH_TOLERANCE;
  private static final long MAX_INCREMENTAL_HORIZON = Phase6Reachability.MAX_HORIZON_TICKS;

  private final int maximumCandidates;
  private final InputConstraint neutralInput;

  private Player initialAnchor;
  private long initialAnchorReceivedNanos = -1L;

  private Player clientState;
  private InputConstraint currentInput;
  private AuthorityAnchor latestAuthority;
  private Set<Candidate> prediction = Set.of();
  private long predictionTick = -1L;
  private long relativeClientTick = 0L;
  private boolean hasClientTickBoundary;
  private long lastProcessedSequence = -1L;
  private long lastPositionClientTick = -1L;
  private long nextCandidateId;
  private Continuation latestContinuation = Continuation.UNANCHORED;

  public Phase8PredictionRunner(int maximumCandidates) {
    Contracts.requireCandidateBudget(maximumCandidates);
    this.maximumCandidates = maximumCandidates;
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
    latestAuthority = null;
    prediction = Set.of();
    predictionTick = -1L;
    relativeClientTick = 0L;
    hasClientTickBoundary = false;
    lastProcessedSequence = sequenceBoundary;
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
        latestAuthority = new AuthorityAnchor(
            sequence,
            packet.receivedNanos(),
            packet.provenance().authoritativeServerTick() == null
                ? 0L
                : packet.provenance().authoritativeServerTick(),
            packet.provenance().authoritativeClientTick(),
            authority);
        if (!prediction.isEmpty()) {
          Set<Candidate> updated = overlayAuthorityState(prediction, authority, maximumCandidates);
          if (!updated.isEmpty()) prediction = updated;
        }
        continue;
      }

      if (value instanceof Packets.ClientInput input) {
        clientState = State.apply(clientState, normalized);
        currentInput = InputConstraint.fromClientInput(input);
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
            impossible++;
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

      TickResolution tick = resolveMovementTick(move);
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
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            prediction, prediction, worldOrEmpty(world), sources, trace));
        continue;
      }

      trace.add("WORLD source=latency-compensated-packet-world"
          + " causalSequence=" + world.causalSequence()
          + " chunks=" + world.loadedChunks().size());

      if (move.position() == null) {
        ensureRoot(playerId, packet, move, observedBefore, tick, trace);
        prediction = retargetRotation(prediction, move, maximumCandidates);
        Set<Candidate> rotated = prediction;
        boolean possibleRotation = !rotated.isEmpty();
        SearchResult rotationSearch = possibleRotation
            ? new SearchResult(
                Verdict.POSSIBLE,
                rotated,
                0,
                rotated.size(),
                0, 0, 0, 0,
                List.of("rotation is a retained client-state observation; no physics step is advanced"))
            : uncertainSearch(prediction, "no prediction root exists for rotation observation");
        boolean exact = tick.exact() && possibleRotation;
        Phase8MovementValidation.Result result = validate(
            playerId, packet, move, observedBefore, observedAfter, world,
            tick, List.of(), rotationSearch, exact,
            EnumSet.of(Phase6Reachability.ObservedField.ROTATION));
        results.add(result);
        if (result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE) {
          possible++;
        } else if (result.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE) {
          impossible++;
        } else {
          uncertain++;
        }
        trace.add("OBSERVATION rotation-only prediction frontier retained=" + !prediction.isEmpty());
        frames.add(frame(
            sequence, packet, tick, move, observedBefore, observedAfter,
            prediction, prediction, world, List.of(), trace));
        continue;
      }

      Set<Candidate> predictedBefore = prediction;
      List<String> uncertaintySources = new ArrayList<>();

      if (!tick.known()) {
        uncertaintySources.add("client simulation tick has not been established by a client-tick boundary");
      }

      ensureRoot(playerId, packet, move, observedBefore, tick, trace);
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

      prediction = overlayEntityCollisions(
          prediction, entityCollisions(latestAuthority), maximumCandidates);
      prediction = retargetRotation(prediction, move, maximumCandidates);

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

      boolean sameClientTick = predictionTick >= 0L && tick.clientTick() == predictionTick;
      boolean movedWithinCurrentClientTick =
          sameClientTick
              && lastPositionClientTick == tick.clientTick()
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

      /*
       * A conservative certificate catches obviously excessive exact-tick
       * displacement even when the packet world is incomplete. It is deliberately
       * skipped for fluid/gliding/climbable contexts where the vanilla profile has
       * additional movement modes that need the full Phase 5 model.
       */
      if (tick.exact() && deltaTicks >= 1L) {
        Optional<Candidate> reference = prediction.stream().findFirst();
        if (reference.isPresent()
            && movementAllowsKinematicCertificate(reference.get())
            && exceedsConservativeKinematicBound(
                reference.get(), observedAfter, deltaTicks)) {
          Candidate certificateRoot = reference.get();
          SearchResult search = new SearchResult(
              Verdict.POSSIBLE,
              Set.of(certificateRoot),
              0,
              1,
              0, 0, 0, 0,
              List.of(
                  "conservative kinematic certificate exceeded; collision replay is not required for this rejection",
                  "prediction frontier remains retained for subsequent packets"));
          Phase8MovementValidation.Result result = validate(
              playerId, packet, move, observedBefore, observedAfter, world,
              tick, uncertaintySources, search, true);
          results.add(result);
          predictionTick = targetTick;
          latestPositionAdvanceWithoutObservation(certificateRoot, targetTick);
          if (result.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE) {
            latestContinuation = Continuation.IMPOSSIBLE;
            impossible++;
          } else if (result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE) {
            latestContinuation = Continuation.ACTIVE;
            possible++;
          } else {
            latestContinuation = Continuation.UNCERTAIN;
            uncertain++;
          }
          lastPositionClientTick = targetTick;
          trace.add("EVIDENCE KINEMATIC_CERTIFICATE");
          trace.add("FRONTIER_RETAINED after="+prediction.size()+" tick="+predictionTick);
          frames.add(frame(
              sequence, packet, tick, move, observedBefore, observedAfter,
              predictedBefore, prediction, world, uncertaintySources, trace));
          continue;
        }
      }

      AdvanceResult advance = advancePrediction(
          prediction, startTick, targetTick, currentInput, world, maximumCandidates);
      trace.add("PREDICT_FORWARD startTick=" + startTick
          + " targetTick=" + targetTick
          + " steps=" + advance.simulatedTicks()
          + " exhaustive=" + advance.exhaustive());

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
      SearchResult search = new SearchResult(
          Verdict.POSSIBLE,
          prediction,
          advance.simulatedTicks(),
          prediction.size(),
          0, 0, 0, 0,
          advance.reasons());

      boolean timingExhaustive = tick.exact() && uncertaintySources.isEmpty();
      Phase8MovementValidation.Result result = validate(
          playerId, packet, move, observedBefore, observedAfter, world,
          tick, uncertaintySources, search, timingExhaustive);

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
           * The prediction state is still the legitimate expected trajectory.
           * Never overwrite it with the contradicted client position and never
           * clear it merely because an observation failed.
           */
          trace.add("FRONTIER_RETAINED reason=OBSERVATION_CONTRADICTION");
        }
        case UNCERTAIN -> {
          uncertain++;
          latestContinuation = Continuation.UNCERTAIN;
          trace.add("FRONTIER_RETAINED reason=UNCERTAIN_OBSERVATION");
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

  private record TickResolution(long clientTick, boolean known, boolean exact, String source) {
    String display() {
      return known ? Long.toString(clientTick) : "unknown";
    }
  }

  private TickResolution resolveMovementTick(Packets.Move move) {
    if (move.clientTick() != null) {
      long tick = move.clientTick();
      relativeClientTick = Math.max(relativeClientTick, tick);
      return new TickResolution(tick, true, true, "packet-client-tick");
    }
    if (hasClientTickBoundary) {
      return new TickResolution(relativeClientTick, true, true, "client-tick-boundary-watermark");
    }
    return new TickResolution(0L, false, false, "pre-boundary");
  }

  private long resolvedCorrectionTick() {
    return Math.max(0L, relativeClientTick);
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
      rootPlayer = playerFromAuthority(authority.context());
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
          authority.sequence(), EntityCollisions.of(authority.context().entityBoxes())));
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
    MovementEnvironment environment = movementEnvironmentOf(player);
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
      result.add(rebuildCandidate(candidate, merged, collisions));
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
    Context old = candidate.context();
    MovementEnvironment environment = movementEnvironmentOf(player);
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
      List<String> reasons) {
    AdvanceResult {
      candidates = Set.copyOf(candidates);
      reasons = List.copyOf(reasons);
    }
  }

  private AdvanceResult advancePrediction(
      Set<Candidate> start,
      long startTick,
      long targetTick,
      InputConstraint input,
      WorldSnapshot world,
      int maximumCandidates) {
    if (start.isEmpty()) {
      return new AdvanceResult(Set.of(), false, 0, List.of("prediction frontier is empty"));
    }
    if (targetTick < startTick) {
      return new AdvanceResult(Set.copyOf(start), false, 0,
          List.of("target client tick precedes retained prediction state"));
    }
    if (targetTick - startTick > MAX_INCREMENTAL_HORIZON) {
      return new AdvanceResult(Set.copyOf(start), false, 0,
          List.of("incremental prediction horizon exceeded"));
    }

    Set<Candidate> current = Set.copyOf(start);
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    int simulatedTicks = 0;

    for (long tick = startTick; tick < targetTick; tick++) {
      SearchResult result = new Phase6Reachability(new Vanilla12111RichPhysics()).search(
          current.stream()
              .map(candidate -> candidate.context().withTick(tick))
              .toList(),
          List.of(input),
          ignored -> List.of(new WorldBranch(
              "packet-world@" + tick,
              world,
              true,
              "latency-compensated client-visible world")),
          ignored -> List.of(new Phase6Reachability.None()),
          maximumCandidates);
      simulatedTicks++;

      if (result.verdict() != Verdict.POSSIBLE
          || result.candidates().isEmpty()
          || !result.exhaustive()) {
        reasons.addAll(result.reasons());
        reasons.add("prediction step " + tick + " was not exhaustively modeled");
        return new AdvanceResult(current, false, simulatedTicks, List.copyOf(reasons));
      }

      current = Set.copyOf(result.candidates());
      if (current.size() > maximumCandidates) {
        reasons.add("prediction candidate budget exceeded");
        return new AdvanceResult(Set.of(), false, simulatedTicks, List.copyOf(reasons));
      }
    }

    reasons.add("persistent prediction advanced without replaying prior packet history");
    return new AdvanceResult(current, true, simulatedTicks, List.copyOf(reasons));
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

  private static boolean movementAllowsKinematicCertificate(Candidate candidate) {
    MovementEnvironment environment = candidate.context().movementEnvironment();
    return environment.fluid() == Fluid.NONE
        && !environment.climbable()
        && !environment.gliding()
        && candidate.context().pose() != Pose.FALL_FLYING;
  }

  private static boolean exceedsConservativeKinematicBound(
      Candidate candidate,
      Player observed,
      long ticks) {
    if (ticks < 1L || ticks > 2L) return false;
    double dx = observed.position().x() - candidate.context().player().position().x();
    double dy = observed.position().y() - candidate.context().player().position().y();
    double dz = observed.position().z() - candidate.context().player().position().z();
    double horizontalDistance = Math.hypot(dx, dz);
    double horizontalVelocity = Math.hypot(
        candidate.context().player().velocity().x(),
        candidate.context().player().velocity().z());
    double speedMultiplier = Math.max(1.0, candidate.context().effects().speedMultiplier());
    double configuredSpeed = Math.max(0.05, candidate.context().attributes().value()) * speedMultiplier;
    double horizontalPerTick = horizontalVelocity + configuredSpeed * 4.0 + 0.25;
    double verticalVelocity = Math.abs(candidate.context().player().velocity().y());
    double verticalPerTick = verticalVelocity + 1.0 + configuredSpeed + 0.25;
    double horizontalBound = horizontalPerTick * ticks;
    double verticalBound = verticalPerTick * ticks;
    return horizontalDistance > horizontalBound || Math.abs(dy) > verticalBound;
  }

  private void latestPositionAdvanceWithoutObservation(Candidate candidate, long targetTick) {
    /*
     * The certificate only establishes that the observed position is outside the
     * conservative envelope. The actual Phase 5 state remains the last prediction;
     * never mutate it toward the client observation.
     */
    prediction = Set.of(candidate);
    predictionTick = targetTick;
  }

  private long movementServerTick(Packets.RawPacket packet) {
    Long value = packet.provenance().authoritativeServerTick();
    return value == null ? 0L : value;
  }

  private EntityCollisions entityCollisions(AuthorityAnchor authority) {
    return authority == null
        ? EntityCollisions.NONE_TRACKED
        : EntityCollisions.of(authority.context().entityBoxes());
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
    Validation.SyncWindow timing = new Validation.SyncWindow(
        Math.max(0L, tick.clientTick()),
        Math.max(0L, tick.clientTick()),
        !tick.exact(),
        tick.exact()
            ? List.of("persistent client-tick clock is exact for this packet")
            : List.of(tick.source()));
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
        move.position() == null
            ? EnumSet.of(Phase6Reachability.ObservedField.ROTATION)
            : EnumSet.of(
                Phase6Reachability.ObservedField.POSITION,
                Phase6Reachability.ObservedField.ROTATION,
                Phase6Reachability.ObservedField.GROUND));
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
        relativeClientTick < 0L ? 0L : relativeClientTick,
        hasClientTickBoundary,
        hasClientTickBoundary,
        "flight-toggle-observation");
    return Phase8MovementValidation.authoritativeImpossible(
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
