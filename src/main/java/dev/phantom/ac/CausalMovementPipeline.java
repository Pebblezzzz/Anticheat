package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.ExternalTransition;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.Verdict;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;
import java.util.function.LongFunction;

import static dev.phantom.ac.Maths.Vec3;

/**
 * Canonical Phase 8 movement pipeline.
 *
 * <p>The important property of this class is provenance: every prediction starts
 * from an explicitly authoritative anchor (or is marked as unanchored), every
 * movement observation is assigned a reconstructed client-tick interval, every
 * world snapshot is selected for the tick being simulated, and server state is
 * never silently copied into the client observation.</p>
 *
 * <p>This class is deliberately independent of Paper. A Paper movement rejection
 * never enters the input, world, reachability, or verdict path.</p>
 */
public final class CausalMovementPipeline {
  private CausalMovementPipeline() {}

  public enum AuthorityQuality {
    EXACT,
    STALE,
    MISSING
  }

  public record AuthoritativeSnapshot(
      long sequence,
      long receivedNanos,
      long serverTick,
      Packets.PlayerContext context,
      Long clientTick) {
    public AuthoritativeSnapshot {
      if (sequence < 0 || receivedNanos < 0 || serverTick < 0) {
        throw new IllegalArgumentException("invalid authoritative snapshot provenance");
      }
      if (clientTick != null && clientTick < 0) {
        throw new IllegalArgumentException("authoritative client tick must be non-negative");
      }
      Objects.requireNonNull(context);
    }
    public AuthoritativeSnapshot(long sequence,long receivedNanos,long serverTick,Packets.PlayerContext context) {
      this(sequence,receivedNanos,serverTick,context,null);
    }
  }

  public record AuthorityAlignment(
      AuthorityQuality quality,
      Optional<AuthoritativeSnapshot> snapshot,
      List<String> reasons) {
    public AuthorityAlignment {
      Objects.requireNonNull(quality);
      Objects.requireNonNull(snapshot);
      reasons = List.copyOf(reasons);
      if (quality == AuthorityQuality.EXACT && snapshot.isEmpty()) {
        throw new IllegalArgumentException("exact authority alignment requires a snapshot");
      }
    }
  }

  public record Frame(
      long sequence,
      long receivedNanos,
      long serverTick,
      Phase7Timing.EventTiming timing,
      Packets.Move movement,
      Player observedBefore,
      Player observedAfter,
      AuthorityAlignment authority,
      WorldSnapshot world,
      List<String> uncertaintySources,
      List<String> trace) {
    public Frame {
      if (sequence < 0 || receivedNanos < 0 || serverTick < 0) {
        throw new IllegalArgumentException("invalid movement frame provenance");
      }
      Objects.requireNonNull(timing);
      Objects.requireNonNull(movement);
      Objects.requireNonNull(observedBefore);
      Objects.requireNonNull(observedAfter);
      Objects.requireNonNull(authority);
      Objects.requireNonNull(world);
      uncertaintySources = List.copyOf(uncertaintySources);
      trace = List.copyOf(trace);
    }
  }

  public record Report(
      List<Phase8MovementValidation.Result> results,
      List<Frame> frames,
      int movementObservations,
      int possible,
      int uncertain,
      int impossible) {
    public Report {
      results = List.copyOf(results);
      frames = List.copyOf(frames);
    }
  }

  private record MovementEvent(
      Timeline.Event event,
      Packets.Move move,
      Phase7Timing.EventTiming timing,
      State.StateFrame stateFrame,
      AuthorityAlignment authority,
      Optional<AuthoritativeSnapshot> simulationAuthority,
      WorldSnapshot world,
      boolean liveWorldUsed,
      boolean chronologyClean) {}

  /**
   * Client input is a state update, not proof that the update had already affected
   * a movement packet that arrived earlier in the same server tick. Sequence is
   * therefore retained so future input packets cannot leak backwards into an
   * earlier movement observation.
   */
  private record TimedInput(long sequence, long clientTick, InputConstraint constraint) {
    TimedInput {
      if (sequence < 0 || clientTick < 0) throw new IllegalArgumentException("invalid input provenance");
      Objects.requireNonNull(constraint);
    }
  }

  private record Frontier(Set<Candidate> candidates, long lastMovementTick, boolean anchored) {
    Frontier {
      candidates = Set.copyOf(candidates);
    }
    static Frontier empty() {
      return new Frontier(Set.of(), -1L, false);
    }
  }

  private record Advance(Set<Candidate> candidates, List<String> reasons, boolean exhaustive) {
    Advance {
      candidates = Set.copyOf(candidates);
      reasons = List.copyOf(reasons);
    }
  }

  public static Report analyze(
      String playerId,
      Timeline.Snapshot timeline,
      int maximumCandidates,
      Phase7Timing.Config timingConfig,
      WorldSnapshot liveWorld,
      Player initialAnchor,
      long initialAnchorReceivedNanos) {
    return analyzeWithWorldProvider(
        playerId, timeline, maximumCandidates, timingConfig,
        liveWorld == null ? null : ignored -> liveWorld,
        initialAnchor, initialAnchorReceivedNanos);
  }

  /**
   * Replays movement against a live-world provider whose snapshot is selected
   * independently for each movement sequence. This preserves causal ordering
   * when a validation batch contains movements both before and after a world
   * acknowledgement.
   */
  public static Report analyzeWithWorldProvider(
      String playerId,
      Timeline.Snapshot timeline,
      int maximumCandidates,
      Phase7Timing.Config timingConfig,
      LongFunction<WorldSnapshot> liveWorldProvider,
      Player initialAnchor,
      long initialAnchorReceivedNanos) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(timeline);
    Objects.requireNonNull(timingConfig);
    Contracts.requireCandidateBudget(maximumCandidates);

    Phase7Timing.Reconstruction timing = Phase7Timing.reconstruct(timeline, timingConfig);
    State.Player observedSeed = initialAnchor != null
        ? initialAnchor
        : State.Player.initial(Vec3.ZERO);
    State.Reconstruction observedReconstruction =
        reconstructClientObservations(observedSeed, timeline);
    Map<Long, State.StateFrame> observedBySequence = new HashMap<>();
    for (State.StateFrame frame : observedReconstruction.frames()) {
      observedBySequence.put(frame.event().packet().sequence(), frame);
    }

    List<AuthoritativeSnapshot> authorities = collectAuthorities(timeline);
    NavigableMap<Long, List<TimedInput>> inputByTick = collectInputs(timeline, timing);
    Set<Long> unmodeledExternalSequences = new HashSet<>();
    Map<Long, List<ExternalTransition>> externalByTick =
        collectExternalTransitions(
            timeline, timing, authorities, initialAnchor, unmodeledExternalSequences);
    World.VisibilityHistory worldHistory = World.fromTimeline(timeline, timing);

    List<MovementEvent> movements = new ArrayList<>();
    for (Timeline.Event event : timeline.events()) {
      if (!(event.packet().packet() instanceof Packets.Move move)) continue;
      Phase7Timing.EventTiming eventTiming = timing.timingFor(event.packet().sequence()).orElse(null);
      State.StateFrame stateFrame = observedBySequence.get(event.packet().sequence());
      if (eventTiming == null || stateFrame == null) continue;

      AuthorityAlignment authority = alignAuthority(
          event,
          authorities,
          initialAnchor,
          initialAnchorReceivedNanos);
      Optional<AuthoritativeSnapshot> simulationAuthority =
          selectSimulationAuthority(
              event,
              eventTiming,
              authorities,
              initialAnchor);
      /*
       * The compact live replica is safe only when its acknowledgement boundary
       * is no later than this movement and no later world mutation is retained
       * in the causal timeline. Earlier simulated ticks still use historical
       * replay rather than borrowing current world state.
       */
      long sequence=event.packet().sequence();
      WorldSnapshot movementLiveWorld = liveWorldProvider == null
          ? null
          : liveWorldProvider.apply(sequence);
      long liveWorldSequence=movementLiveWorld==null ? -1L : movementLiveWorld.causalSequence();
      boolean liveWorldCausallyAvailable=movementLiveWorld!=null
          && (liveWorldSequence<0L || liveWorldSequence<=sequence);
      boolean useLiveWorld=liveWorldCausallyAvailable;
      WorldSnapshot world = useLiveWorld
          ? movementLiveWorld
          : worldHistory.statesAt(Math.max(0L, eventTiming.simulationClientTicks().min()));

      boolean chronologyClean = !containsChronologyProblem(event.packet().flags());
      movements.add(new MovementEvent(
          event,
          move,
          eventTiming,
          stateFrame,
          authority,
          simulationAuthority,
          world,
          useLiveWorld,
          chronologyClean));
    }

    Frontier frontier = Frontier.empty();
    List<Phase8MovementValidation.Result> results = new ArrayList<>();
    List<Frame> frames = new ArrayList<>();
    long previousPositionPacketTick = -1L;
    Phase7Timing.Range previousPositionPacketGenerationRange = null;
    Long previousExplicitClientTick = null;
    boolean recoveryRequired = false;
    boolean contradictionActive = false;
    long lastAmbiguitySequence = -1L;
    long lastHandledUnmodeledExternalSequence = -1L;
    boolean haveAuthoritativeSeed = initialAnchor != null && !initialAnchor.uncertain();
    for (MovementEvent movement : movements) {
      Timeline.Event event = movement.event();
      long sequence = event.packet().sequence();
      long serverTick = event.serverTick();
      Phase7Timing.EventTiming eventTiming = movement.timing();
      State.StateFrame stateFrame = movement.stateFrame();
      Player observedBefore = stateFrame.before();
      Player observedAfter = stateFrame.after();

      List<String> trace = new ArrayList<>();
      trace.add("PACKET seq=" + sequence + " receivedNanos=" + event.packet().receivedNanos());
      trace.add("CLIENT_TICK " + eventTiming.simulationClientTicks()
          + " source=" + eventTiming.source());
      trace.add("AUTHORITY " + movement.authority().quality()
          + " serverTick=" + serverTick
          + (movement.authority().snapshot().isPresent()
              ? " snapshotSeq=" + movement.authority().snapshot().get().sequence()
                + " clientTick=" + (movement.authority().snapshot().get().clientTick() == null
                    ? "unknown" : movement.authority().snapshot().get().clientTick())
              : ""));
      trace.add("WORLD source=" + (movement.liveWorldUsed() ? "acknowledged-live" : "timeline")
          + " chunks=" + movement.world().loadedChunks().size());

      movement.simulationAuthority().ifPresent(snapshot -> {
        Packets.PlayerContext context = snapshot.context();
        trace.add("SIMULATION_AUTHORITY seq=" + snapshot.sequence()
            + " serverTick=" + snapshot.serverTick()
            + " clientTick=" + (snapshot.clientTick() == null ? "unknown" : snapshot.clientTick())
            + " receivedNanos=" + snapshot.receivedNanos()
            + " pos=" + context.serverPosition()
            + " vel=" + context.serverVelocity()
            + " ground=" + context.movementEnvironment().onGround()
            + " gamemode=" + context.gamemode()
            + " canFly=" + context.canFly()
            + " flying=" + context.flying());
      });
      appendInputTrace(
          trace,
          inputByTick,
          eventTiming.simulationClientTicks(),
          sequence);

      Validation.SyncWindow sync = Phase7Timing.toPhase6Window(eventTiming);
      List<String> assumptions = new ArrayList<>();
      assumptions.add("client input constraints are packet-derived and held until replacement");
      assumptions.add("authoritative server context is kept separate from client movement claims");
      assumptions.add("world state is selected by the movement's reconstructed simulation tick");
      assumptions.addAll(movement.authority().reasons());
      assumptions.addAll(eventTiming.reasons());

      List<String> uncertainty = new ArrayList<>();
      if (!movement.chronologyClean()) {
        uncertainty.add("movement chronology contains duplicate, reorder, sequence-gap, or pre-epoch evidence");
      }
      if (eventTiming.uncertain()) {
        uncertainty.addAll(eventTiming.reasons());
      }
      if (movement.authority().quality() != AuthorityQuality.EXACT
          && initialAnchor == null) {
        uncertainty.add("no exact authoritative snapshot exists for this movement and no authoritative seed was supplied");
      }

      String replayReference = "causal:phase8:" + playerId + ":" + sequence;
      String worldReference = (movement.liveWorldUsed() ? "live-ack:" : "timeline:")
          + "serverTick=" + serverTick
          + ":chunks=" + movement.world().loadedChunks().size();

      long unmodeledExternalSequence = unmodeledExternalSequences.stream()
          .filter(transitionSequence -> transitionSequence < sequence)
          .max(Long::compareTo)
          .orElse(-1L);
      boolean hasNewUnmodeledExternal = unmodeledExternalSequence > lastHandledUnmodeledExternalSequence;
      if (hasNewUnmodeledExternal) {
        lastHandledUnmodeledExternalSequence = unmodeledExternalSequence;
        lastAmbiguitySequence = Math.max(lastAmbiguitySequence, unmodeledExternalSequence);
        frontier = Frontier.empty();
        uncertainty.add("an authoritative movement-context transition before this movement could not be assigned to an exact client simulation tick");
        recoveryRequired = true;
        SearchResult uncertain = uncertainSearch(
            frontier.candidates(),
            String.join("; ", uncertainty));
        results.add(Phase8MovementValidation.validate(
            playerId, serverTick, observedBefore, observedAfter, movement.world(),
            worldReference, sync, assumptions, uncertain, replayReference, false));
        trace.add("EVIDENCE UNCERTAIN unmodeled authoritative transition seq=" + unmodeledExternalSequence);
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }



      // Rotation-only packets are observations, not physics ticks. They may update
      // candidate rotation for the next real simulation tick, but never establish
      // a new ground/position trajectory.
      if (movement.move().position() == null) {
        if (frontier.candidates().isEmpty() || frontier.lastMovementTick() < 0) {
          SearchResult uncertain = uncertainSearch(frontier.candidates(),
              "rotation-only movement packet has no established deterministic frontier");
          results.add(Phase8MovementValidation.validate(
              playerId, serverTick, observedBefore, observedAfter, movement.world(),
              worldReference, sync, assumptions, uncertain, replayReference, false,
              EnumSet.of(Phase6Reachability.ObservedField.ROTATION)));
        } else {
          Set<Candidate> rotated = retargetRotation(
              frontier.candidates(), movement.move(), maximumCandidates);
          boolean exact = eventTiming.simulationClientTicks().isExact()
              && movement.chronologyClean();
          SearchResult rotation = new SearchResult(
              exact ? Verdict.POSSIBLE : Verdict.UNCERTAIN,
              rotated,
              0,
              rotated.size(),
              0, 0,
              exact ? 0 : 1,
              0,
              exact ? List.of("rotation observation retained without advancing a physics tick")
                  : List.of("rotation chronology is not exact"));
          results.add(Phase8MovementValidation.validate(
              playerId, serverTick, observedBefore, observedAfter, movement.world(),
              worldReference, sync, assumptions, rotation, replayReference,
              exact && !recoveryRequired,
              EnumSet.of(Phase6Reachability.ObservedField.ROTATION)));
          if (exact) frontier = new Frontier(rotated, frontier.lastMovementTick(), frontier.anchored());
        }
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      long movementTick = eventTiming.simulationClientTicks().min();
      boolean sameExplicitClientTick =
          movement.move().clientTick() != null
              && previousExplicitClientTick != null
              && movement.move().clientTick().longValue() == previousExplicitClientTick.longValue();
      boolean distinctExplicitClientTicks =
          movement.move().clientTick() != null
              && previousExplicitClientTick != null
              && movement.move().clientTick().longValue() != previousExplicitClientTick.longValue();
      boolean overlappingGenerationWindow =
          previousPositionPacketGenerationRange != null
              && !distinctExplicitClientTicks
              && rangesOverlapForSubTickDetection(
                  previousPositionPacketGenerationRange,
                  eventTiming.packetGenerationClientTicks());
      if (overlappingGenerationWindow) {
        /*
         * Overlap between bounded packet-generation windows is normal when upstream
         * latency is allowed to vary. It is timing evidence, not proof that both
         * packets occupied the same client simulation tick. The actual tick search
         * below already exhaustively evaluates the bounded window.
         */
        trace.add("TIMING_RANGE_OVERLAP observation-only previous="
            + previousPositionPacketGenerationRange
            + " current=" + eventTiming.packetGenerationClientTicks());
      }
      if (!frontier.candidates().isEmpty() && movement.authority().snapshot().isPresent()) {
        /*
         * PlayerContext carries the entity boxes observed by the server at the
         * authoritative capture point. Refresh the prediction frontier from that
         * snapshot when it is available; never replace a previously complete
         * provider with NONE_TRACKED merely because this movement lacks a new
         * authority packet.
         */
        EntityCollisions entityCollisions = entityCollisionsFor(movement);
        frontier = new Frontier(
            refreshEntityCollisions(frontier.candidates(), entityCollisions, maximumCandidates),
            frontier.lastMovementTick(),
            frontier.anchored());
        trace.add("ENTITY_COLLISION_CONTEXT authoritativeSnapshot="
            + movement.authority().snapshot().get().sequence()
            + " boxes=" + movement.authority().snapshot().get().context().entityBoxes().size());
      }

      if ((previousPositionPacketTick >= 0
              && eventTiming.simulationClientTicks().isExact()
              && movementTick == previousPositionPacketTick)
          || sameExplicitClientTick) {
        uncertainty.add("multiple position-bearing movement packets occurred in one client tick; sub-tick motion is not modeled");
        lastAmbiguitySequence = sequence;
        recoveryRequired = true;
        frontier = Frontier.empty();
        previousPositionPacketTick = -1L;
        previousPositionPacketGenerationRange = null;
        trace.add("FRONTIER_RESET reason=SUB_TICK_AMBIGUITY");
        SearchResult uncertain = uncertainSearch(
            frontier.candidates(),
            "sub-tick movement cannot be causally represented by the full-tick simulator");
        results.add(Phase8MovementValidation.validate(
            playerId, serverTick, observedBefore, observedAfter, movement.world(),
            worldReference, sync, assumptions, uncertain, replayReference, false));
        trace.add("EVIDENCE none: sub-tick trajectory is not modeled");
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      if (!movement.chronologyClean()) {
        uncertainty.add("capture chronology is incomplete; missing or reordered packets cannot be treated as inactivity");
      }

      /*
       * When the client does not provide an explicit tick, a repeated observation
       * is a safe zero-delta witness only when the local world volume is known.
       * This runs after same-tick ambiguity detection so duplicated movements
       * remain UNCERTAIN rather than being silently accepted.
       */
      if (!recoveryRequired
          && !contradictionActive
          && movement.move().clientTick() == null
          && Phase7Timing.Range.exact(Math.max(0L, movementTick)).isExact()
          && Phase6Reachability.positionMatches(observedBefore.position(), observedAfter.position())
          && Float.compare(observedBefore.yaw(), observedAfter.yaw()) == 0
          && Float.compare(observedBefore.pitch(), observedAfter.pitch()) == 0
          && observedBefore.onGround() == observedAfter.onGround()
          && movement.world().fullyKnown(playerCollisionBox(observedAfter))) {
        MovementEnvironment environment = environmentFromWorld(movement.world(), observedAfter);
        State.Environment stateEnvironment = environment.fluid() == Phase5Mechanics.Fluid.WATER
            ? State.Environment.WATER
            : environment.fluid() == Phase5Mechanics.Fluid.LAVA
                ? State.Environment.LAVA
                : environment.climbable()
                    ? State.Environment.CLIMBABLE
                    : State.Environment.DRY;
        Player witnessPlayer = new Player(
            observedAfter.position(), observedBefore.velocity(), observedAfter.yaw(), observedAfter.pitch(),
            observedAfter.onGround(), observedBefore.gamemode(), observedBefore.effects(),
            observedBefore.awaitingTeleport(), false, observedAfter.input(), observedBefore.attributes(),
            observedBefore.pose(), stateEnvironment, observedAfter.clientTickRange(),
            observedBefore.provenance(), observedBefore.uncertaintyReasons());
        long witnessTick = Math.max(0L, movementTick);
        Context context = new Context(
            witnessTick, witnessPlayer, simulationEnvironmentFor(environment), witnessPlayer.attributes(),
            movementEffects(witnessPlayer), witnessPlayer.pose(), environment,
            witnessPlayer.pose() == Pose.SLEEPING, entityCollisionsFor(movement));
        Candidate witness = new Candidate(
            0, context,
            new Phase6Reachability.Provenance(
                0, -1, witnessTick, "OBSERVED_ZERO_DELTA", "OBSERVATION", "None",
                List.of("consecutive identical client observations require no physics displacement"),
                1, List.of()));
        SearchResult witnessSearch = new SearchResult(
            Verdict.POSSIBLE, Set.of(witness), 0, 1, 0, 0, 0, 0,
            List.of("consecutive identical client observations form a deterministic zero-delta witness"));
        results.add(Phase8MovementValidation.validate(
            playerId, serverTick, observedBefore, observedAfter, movement.world(),
            worldReference, sync, assumptions, witnessSearch, replayReference, true));
        frontier = new Frontier(Set.of(witness), witnessTick, true);
        previousPositionPacketTick = witnessTick;
        previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
        trace.add("EVIDENCE POSSIBLE reason=OBSERVED_ZERO_DELTA_WITNESS");
        trace.add("MATCHING candidates=1 frontierTick=" + movementTick);
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      Optional<AuthoritativeSnapshot> freshLocalAuthority =
          causallyFreshLocalAuthority(movement, 1L, -1L);
      boolean localAuthoritativeRootAvailable = freshLocalAuthority.isPresent();
      boolean justRecovered = false;
      if (!haveAuthoritativeSeed && !localAuthoritativeRootAvailable && frontier.candidates().isEmpty()) {
        uncertainty.add("no trusted authoritative replay anchor exists");
        SearchResult uncertain = uncertainSearch(frontier.candidates(),
            String.join("; ", uncertainty));
        results.add(Phase8MovementValidation.validate(
            playerId, serverTick, observedBefore, observedAfter, movement.world(),
            worldReference, sync, assumptions, uncertain, replayReference, false));
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      boolean recoveryCanClear = recoveryRequired
          && lastAmbiguitySequence >= 0
          && sequence > lastAmbiguitySequence
          && freshLocalAuthority.isPresent()
          && freshLocalAuthority.get().sequence() > lastAmbiguitySequence
          && movement.chronologyClean()
          && !sameExplicitClientTick;
      if (recoveryCanClear) {
        recoveryRequired = false;
        justRecovered = true;
        trace.add("RECOVERY_CLEARED reason=clean causally aligned movement after fresh authority");
      }

      boolean initialAnchorWorldStale = initialAnchor != null
          && !initialAnchor.uncertain()
          && !movement.world().fullyKnown(playerCollisionBox(initialAnchor));
      boolean initialAnchorFarFromObservation = initialAnchor != null
          && distance(initialAnchor.position(), observedAfter.position()) > 32.0;
      /*
       * A fresh pre-movement authoritative snapshot is the strongest available
       * root for this validation epoch. Prefer it whenever present rather than
       * replaying from a possibly older join anchor. The populated frontier is
       * still retained on subsequent movements unless it has drifted beyond the
       * same fresh authority snapshot.
       */
      boolean preferLocalAuthoritativeRoot = localAuthoritativeRootAvailable
          || initialAnchor == null
          || justRecovered
          || initialAnchorWorldStale
          || initialAnchorFarFromObservation
          || movement.timing().simulationClientTicks().max() > Phase6Reachability.MAX_HORIZON_TICKS;
      if (preferLocalAuthoritativeRoot && initialAnchorWorldStale) {
        assumptions.add("original authoritative anchor is outside the retained client-world window; re-anchoring from the exact local server snapshot");
        trace.add("ROOT_REFRESH reason=INITIAL_ANCHOR_WORLD_STALE");
      }
      if (preferLocalAuthoritativeRoot && initialAnchorFarFromObservation) {
        trace.add("ROOT_REFRESH reason=INITIAL_ANCHOR_FAR_FROM_OBSERVED distance="
            +String.format(Locale.ROOT,"%.3f",distance(initialAnchor.position(),observedAfter.position())));
      }

      if (!frontier.candidates().isEmpty()
          && (movement.move().yaw() != null || movement.move().pitch() != null)) {
        frontier = new Frontier(
            retargetRotation(frontier.candidates(), movement.move(), maximumCandidates),
            frontier.lastMovementTick(),
            frontier.anchored());
        trace.add("ROTATION_INPUT applied packet yaw/pitch before physics simulation");
        assumptions.add("position-bearing movement rotation is applied before the physics step");
      }

      /*
       * A populated frontier can outlive the original join anchor. The previous
       * implementation only logged ROOT_REFRESH while continuing to simulate
       * from the stale frontier. Once that frontier drifts far from a fresh
       * authoritative position, continuing the old replay is no longer causal.
       * Re-anchor only when the frontier itself is far away; this avoids replacing
       * a healthy frontier on every movement after the original anchor becomes old.
       */
      boolean frontierCoverageIncomplete =
          !frontier.candidates().isEmpty()
              && frontier.candidates().stream()
                  .anyMatch(candidate ->
                      !movement.world().fullyKnown(
                          playerCollisionBox(candidate.context().player())));
      boolean frontierFarFromLocalAuthority =
          localAuthoritativeRootAvailable
              && frontierFarFromLocalAuthority(frontier, movement, 32.0);
      if (!frontier.candidates().isEmpty()
          && preferLocalAuthoritativeRoot
          && (frontierFarFromLocalAuthority || frontierCoverageIncomplete)
          && !recoveryRequired) {
        Optional<Candidate> refreshedRoot =
            rootCandidate(initialAnchor, movement, maximumCandidates, true);
        if (refreshedRoot.isPresent()) {
          Candidate root = refreshedRoot.get();
          double oldDistance = nearestFrontierAuthorityDistance(frontier, movement);
          frontier = new Frontier(Set.of(root), root.context().simulationTick(), true);
          String reason = frontierCoverageIncomplete
              ? "FRONTIER_WORLD_COVERAGE_INCOMPLETE"
              : "FRONTIER_FAR_FROM_LOCAL_AUTHORITY";
          trace.add("FRONTIER_REFRESH reason=" + reason
              + " distance="
              +String.format(Locale.ROOT, "%.3f", oldDistance));
          assumptions.add(frontierCoverageIncomplete
              ? "prediction frontier was re-anchored because its current client-world coverage was incomplete"
              : "stale prediction frontier was re-anchored from the fresh local authoritative snapshot");
        } else {
          uncertainty.add("fresh local authoritative state exists but cannot be represented inside the finite Phase 6 horizon");
          trace.add("FRONTIER_REFRESH_FAILED reason=LOCAL_AUTHORITY_ROOT_UNREPRESENTABLE");
        }
      }

      if (recoveryRequired) {
        /*
         * An authoritative server flight state is a separate evidence channel.
         * It cannot make an ambiguous packet chronology exact, so chronology
         * recovery still wins here.
         */
        uncertainty.add(
            "prediction frontier was invalidated by chronology ambiguity; waiting for a clean causally aligned movement");
        SearchResult uncertain = uncertainSearch(
            frontier.candidates(),
            String.join("; ", uncertainty));
        results.add(Phase8MovementValidation.validate(
            playerId, serverTick, observedBefore, observedAfter, movement.world(),
            worldReference, sync, assumptions, uncertain, replayReference, false));
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      Optional<AuthoritativeSnapshot> currentAuthority = movement.authority().snapshot();
      boolean authoritativeFlight =
          currentAuthority.isPresent()
              && currentAuthority.get().context().canFly()
              && currentAuthority.get().context().flying();
      boolean contradictoryFlightState =
          currentAuthority.isPresent()
              && !currentAuthority.get().context().canFly()
              && currentAuthority.get().context().flying();

      /*
       * Flight authorization is authoritative server state, not a client movement
       * claim. The movement reachability engine has no configured survival-flight
       * speed/profile, so do not invent one and then classify legitimate flight as
       * impossible. Instead retain the exact client observation as the next
       * frontier while recording the authoritative authorization explicitly.
       */
      if (authoritativeFlight) {
        assumptions.add(
            "authoritative server state reports flight is allowed and currently active; movement is validated as an authorized flight observation");
        trace.add(
            "AUTHORIZED_FLIGHT serverCanFly=true serverFlying=true serverGround="
                + currentAuthority.get().context().movementEnvironment().onGround());
        Set<Candidate> flightCandidates = Set.of(
            observedFlightCandidate(
                movement,
                observedAfter,
                currentAuthority.get(),
                movementTick,
                inputByTick));
        SearchResult flightSearch = new SearchResult(
            Verdict.POSSIBLE,
            flightCandidates,
            0,
            1,
            0,
            0,
            0,
            0,
            List.of("movement accepted under authoritative active-flight state"));
        Phase8MovementValidation.Result validation =
            Phase8MovementValidation.validate(
                playerId,
                serverTick,
                observedBefore,
                observedAfter,
                movement.world(),
                worldReference,
                sync,
                assumptions,
                flightSearch,
                replayReference,
                eventTiming.simulationClientTicks().isExact() && movement.chronologyClean(),
                EnumSet.of(
                    Phase6Reachability.ObservedField.POSITION,
                    Phase6Reachability.ObservedField.ROTATION));
        results.add(validation);
        contradictionActive = false;
        frontier = new Frontier(flightCandidates, movementTick, true);
        previousPositionPacketTick = movementTick;
        previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
        if (movement.move().clientTick() != null) {
          previousExplicitClientTick = movement.move().clientTick();
        }
        trace.add("MATCHING candidates=1 frontierTick=" + movementTick);
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      if (contradictoryFlightState) {
        uncertainty.add(
            "authoritative server state reports flying=true while canFly=false; flight authorization is contradictory");
        trace.add("AUTHORITY_INCONSISTENT_FLIGHT serverCanFly=false serverFlying=true"
            + " clientGround=" + movement.move().onGround());
      }

      boolean rootedFromLocalAuthority = false;
      boolean clientClockLocalAuthority = false;
      if (frontier.candidates().isEmpty()) {
        if (!preferLocalAuthoritativeRoot && (initialAnchor == null || initialAnchor.uncertain())) {
          uncertainty.add("first movement cannot be proven without an authoritative server anchor");
          SearchResult uncertain = uncertainSearch(Set.of(), String.join("; ", uncertainty));
          results.add(Phase8MovementValidation.validate(
              playerId, serverTick, observedBefore, observedAfter, movement.world(),
              worldReference, sync, assumptions, uncertain, replayReference, false));
          frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
              assumptions, uncertainty, trace));
          continue;
        }
        Optional<Candidate> root = rootCandidate(
            initialAnchor,
            movement,
            maximumCandidates,
            preferLocalAuthoritativeRoot);
        if (root.isEmpty() && initialAnchor != null && !initialAnchor.uncertain()) {
          Optional<Candidate> fallbackRoot =
              rootCandidate(initialAnchor, movement, maximumCandidates, false);
          if (fallbackRoot.isPresent()) {
            Candidate fallback = fallbackRoot.get();
            frontier = new Frontier(Set.of(fallback), fallback.context().simulationTick(), true);
            rootedFromLocalAuthority = false;
            clientClockLocalAuthority = false;
            trace.add("ROOT FALLBACK_EXPLICIT_ANCHOR simulationTick=" + fallback.context().simulationTick());
            root = fallbackRoot;
          }
        }
        if (root.isEmpty()) {
          uncertainty.add("authoritative local root is outside the finite causal horizon");
          SearchResult uncertain = uncertainSearch(Set.of(), String.join("; ", uncertainty));
          results.add(Phase8MovementValidation.validate(
              playerId, serverTick, observedBefore, observedAfter, movement.world(),
              worldReference, sync, assumptions, uncertain, replayReference, false));
          frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
              assumptions, uncertainty, trace));
          continue;
        }
        Candidate rootCandidate = root.orElseThrow();
        frontier = new Frontier(Set.of(rootCandidate), rootCandidate.context().simulationTick(), true);
        rootedFromLocalAuthority = preferLocalAuthoritativeRoot
            && movement.simulationAuthority().isPresent();
        clientClockLocalAuthority = rootedFromLocalAuthority
            && movement.simulationAuthority().map(AuthoritativeSnapshot::clientTick).isPresent();
        if (rootedFromLocalAuthority) {
          AuthoritativeSnapshot snapshot = movement.simulationAuthority().orElseThrow();
          trace.add("ROOT LOCAL_AUTHORITATIVE snapshotSeq=" + snapshot.sequence()
              + " serverTick=" + snapshot.serverTick()
              + " simulationTick=" + rootCandidate.context().simulationTick()
              + " position=" + rootCandidate.context().player().position());
        } else {
          trace.add("ROOT authoritative anchor=" + initialAnchor.position());
        }
      }

      /*
       * A conservative exact-tick kinematic bound is independent of block collision
       * coverage, but it must start from a known state. This is placed immediately
       * after root establishment so recovery validation can reject a deterministic
       * excessive displacement before Phase 5 encounters an incomplete sweep.
       */
      if (!recoveryRequired
          && movement.chronologyClean()
          && (eventTiming.simulationClientTicks().isExact()
              || movement.move().clientTick() != null)) {
        Optional<Candidate> kinematicReference = frontier.candidates().stream().findFirst();
        if (kinematicReference.isEmpty()) {
          kinematicReference = rootCandidate(initialAnchor, movement, maximumCandidates, true);
        }
        if (kinematicReference.isPresent()
            && movement.world().hasChunk(
                Math.floorDiv((int) Math.floor(
                    kinematicReference.get().context().player().position().x()), 16),
                Math.floorDiv((int) Math.floor(
                    kinematicReference.get().context().player().position().z()), 16))
            && movement.world().hasChunk(
                Math.floorDiv((int) Math.floor(observedAfter.position().x()), 16),
                Math.floorDiv((int) Math.floor(observedAfter.position().z()), 16))
            && exceedsConservativeKinematicBound(
                kinematicReference.get(), observedAfter, movementTick)) {
          Candidate reference = kinematicReference.get();
          SearchResult impossible = new SearchResult(
              Verdict.IMPOSSIBLE,
              Set.of(reference),
              0,
              1,
              0,
              0,
              0,
              0,
              List.of(
                  "conservative kinematic displacement bound exceeded; world collision state is not required to reject this movement",
                  "all exhaustively modeled legitimate candidates disagree with the observed movement state"));
          results.add(Phase8MovementValidation.validate(
              playerId, serverTick, observedBefore, observedAfter, movement.world(),
              worldReference, sync, assumptions, impossible, replayReference, true));
          contradictionActive = true;
          previousPositionPacketTick = movementTick;
          previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
          if (movement.move().clientTick() != null) previousExplicitClientTick = movement.move().clientTick();
          frontier = Frontier.empty();
          trace.add("EVIDENCE REACHABILITY_CONTRADICTION reason=KINEMATIC_BOUND_EXCEEDED");
          frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
              assumptions, uncertainty, trace));
          continue;
        }
      }

      /*
       * A matching authoritative/previous-frontier position is already a
       * deterministic zero-delta witness. Evaluate this before demanding a replay
       * root or world swept-volume coverage; both can be unavailable even though
       * the observation itself is causally witnessed.
       */
      Optional<Candidate> earlyAuthorityWitness =
          authorityObservationWitness(movement, movementTick);
      Optional<Candidate> earlyFrontierWitness =
          earlyAuthorityWitness.isPresent()
              ? earlyAuthorityWitness
              : frontierObservationWitness(frontier, movement, movementTick);
      if (earlyFrontierWitness.isEmpty()) {
        earlyFrontierWitness = initialAnchorObservationWitness(initialAnchor, movement, movementTick);
      }
      if (!recoveryRequired && earlyFrontierWitness.isPresent()) {
        Candidate witness = earlyFrontierWitness.get();
        SearchResult witnessSearch = new SearchResult(
            Verdict.POSSIBLE,
            Set.of(witness),
            0,
            1,
            0,
            0,
            0,
            0,
            List.of(
                "observed position matches a causally valid known state; zero-delta witness requires no physics replay"));
        results.add(Phase8MovementValidation.validate(
            playerId, serverTick, observedBefore, observedAfter, movement.world(),
            worldReference, sync, assumptions, witnessSearch, replayReference,
            true));
        frontier = new Frontier(Set.of(witness), movementTick, true);
        previousPositionPacketTick = movementTick;
        previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
        if (movement.move().clientTick() != null) previousExplicitClientTick = movement.move().clientTick();
        trace.add("CANDIDATES count=1 exhaustive=true");
        trace.add("EVIDENCE POSSIBLE reason=AUTHORITATIVE_ZERO_DELTA_WITNESS");
        trace.add("MATCHING candidates=1 frontierTick=" + movementTick);
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      /*
       * With no client-world data at all, an exact first observation can still
       * establish the causal baseline when it agrees with the explicit anchor.
       * This does not prove a physics step; it only avoids manufacturing an
       * IMPOSSIBLE verdict from an unknown world. Subsequent movements require
       * actual coverage of the causal frontier and destination.
       */
      if (previousPositionPacketTick < 0
          && !localAuthoritativeRootAvailable
          && !worldCoversFrontierMovement(movement.world(), frontier, observedAfter)) {
        Set<Candidate> baselineMatches = new LinkedHashSet<>();
        for (Candidate candidate : frontier.candidates()) {
          if (matchesObserved(candidate.context().player(), observedAfter, movement.move())) {
            baselineMatches.add(candidate);
          }
        }
        if (!baselineMatches.isEmpty()) {
          SearchResult baselineSearch = new SearchResult(
              Verdict.POSSIBLE,
              baselineMatches,
              0,
              baselineMatches.size(),
              0,
              0,
              0,
              0,
              List.of("exact initial observation established the causal baseline; world physics was not required"));
          Phase8MovementValidation.Result validation = Phase8MovementValidation.validate(
              playerId, serverTick, observedBefore, observedAfter, movement.world(),
              worldReference, sync, assumptions, baselineSearch, replayReference,
              eventTiming.simulationClientTicks().isExact() && movement.chronologyClean(),
              EnumSet.of(
                  Phase6Reachability.ObservedField.POSITION,
                  Phase6Reachability.ObservedField.ROTATION,
                  Phase6Reachability.ObservedField.GROUND));
          results.add(validation);
          contradictionActive = false;
          long baselineTick = baselineMatches.stream()
              .mapToLong(candidate -> candidate.context().simulationTick())
              .max()
              .orElse(movementTick);
          frontier = new Frontier(Set.copyOf(baselineMatches), baselineTick, true);
          previousPositionPacketTick = baselineTick;
          previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
          if (movement.move().clientTick() != null) {
            previousExplicitClientTick = movement.move().clientTick();
          }
          trace.add("EVIDENCE POSSIBLE reason=INITIAL_BASELINE_WITHOUT_WORLD");
          trace.add("MATCHING candidates=" + baselineMatches.size()
              + " frontierTick=" + baselineTick);
          frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
              assumptions, uncertainty, trace));
          continue;
        }
      }

      /*
       * A deterministic kinematic certificate does not need swept-world coverage.
       * Check it before the conservative world-coverage gate so an obviously
       * excessive exact-tick displacement can still be proven impossible when
       * collision data is incomplete.
       */
      if (eventTiming.simulationClientTicks().isExact()) {
        Optional<Candidate> kinematicReference = frontier.candidates().stream().findFirst();
        if (kinematicReference.isEmpty()) {
          kinematicReference = rootCandidate(initialAnchor, movement, maximumCandidates, true);
        }
        if (kinematicReference.isPresent()
            && movement.world().fullyKnown(playerCollisionBox(kinematicReference.get().context().player()))
            && exceedsConservativeKinematicBound(kinematicReference.get(), observedAfter, movementTick)) {
          Candidate reference = kinematicReference.get();
          SearchResult impossible = new SearchResult(
              Verdict.IMPOSSIBLE,
              Set.of(reference),
              0,
              1,
              0,
              0,
              0,
              0,
              List.of(
                  "conservative kinematic displacement bound exceeded; world collision state is not required to reject this movement",
                  "all exhaustively modeled legitimate candidates disagree with the observed movement state"));
          results.add(Phase8MovementValidation.validate(
              playerId, serverTick, observedBefore, observedAfter, movement.world(),
              worldReference, sync, assumptions, impossible, replayReference, true));
          previousPositionPacketTick = movementTick;
          previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
          if (movement.move().clientTick() != null) previousExplicitClientTick = movement.move().clientTick();
          frontier = Frontier.empty();
          trace.add("EVIDENCE REACHABILITY_CONTRADICTION reason=KINEMATIC_BOUND_EXCEEDED");
          frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
              assumptions, uncertainty, trace));
          continue;
        }
      }

      /*
       * Once a causal frontier exists, world coverage must include the simulated
       * starting positions as well as the observed destination. A later movement
       * cannot safely be called IMPOSSIBLE from an incomplete world replica.
       */
      if ((previousPositionPacketTick >= 0 || localAuthoritativeRootAvailable)
          && !worldCoversFrontierMovement(movement.world(), frontier, observedAfter)) {
        frontier = Frontier.empty();
        uncertainty.add("client world coverage is incomplete for the causal movement frontier; unloaded blocks cannot safely be treated as air");
        recoveryRequired = true;
        SearchResult uncertain = uncertainSearch(
            frontier.candidates(),
            "movement world coverage is incomplete");
        results.add(Phase8MovementValidation.validate(
            playerId, serverTick, observedBefore, observedAfter, movement.world(),
            worldReference, sync, assumptions, uncertain, replayReference, false));
        trace.add("EVIDENCE UNCERTAIN reason=INCOMPLETE_WORLD_COVERAGE");
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      Optional<Advance> advanced;
      if (!eventTiming.simulationClientTicks().isExact()) {
        if (clientClockLocalAuthority) {
          advanced = Optional.ofNullable(advanceAcrossLocalAuthorityTimingRange(
              eventTiming.simulationClientTicks().min(),
              eventTiming.simulationClientTicks().max(),
              inputByTick,
              externalByTick,
              worldHistory,
              movement,
              maximumCandidates));
        } else {
          advanced = Optional.ofNullable(advanceAcrossTimingRange(
              frontier,
              eventTiming.simulationClientTicks().min(),
              eventTiming.simulationClientTicks().max(),
              inputByTick,
              externalByTick,
              worldHistory,
              movement,
              maximumCandidates));
        }
      } else {
        advanced = Optional.of(advanceTo(
            frontier.candidates(),
            movementTick,
            inputByTick,
            externalByTick,
            worldHistory,
            movement,
            maximumCandidates));
      }

      if (advanced.isEmpty()) {
        uncertainty.add("timing search exceeded the finite exhaustive envelope");
        SearchResult uncertain = uncertainSearch(frontier.candidates(),
            String.join("; ", uncertainty));
        results.add(Phase8MovementValidation.validate(
            playerId, serverTick, observedBefore, observedAfter, movement.world(),
            worldReference, sync, assumptions, uncertain, replayReference, false));
        frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
            assumptions, uncertainty, trace));
        continue;
      }

      Advance advance = advanced.get();
      Set<Candidate> reachable = advance.candidates();
      trace.add("INPUT_POLICY future input packets cannot affect this movement sequence");
      trace.add("CANDIDATES count=" + reachable.size()
          + " exhaustive=" + advance.exhaustive());

      SearchResult search = new SearchResult(
          advance.exhaustive() ? Verdict.POSSIBLE : Verdict.UNCERTAIN,
          reachable,
          (int) Math.min(Integer.MAX_VALUE,
              reachable.stream().mapToLong(c -> Math.max(0L,
                  c.context().simulationTick())).max().orElse(0L)),
          reachable.size(),
          0, 0,
          advance.exhaustive() ? 0 : 1,
          0,
          advance.reasons());

      boolean timingExhaustive = eventTiming.simulationClientTicks().isExact()
          || (advance.exhaustive()
              && eventTiming.simulationClientTicks().width()
                  <= timingConfig.maxTimingCandidates());
      Phase8MovementValidation.Result validation =
          Phase8MovementValidation.validate(
              playerId,
              serverTick,
              observedBefore,
              observedAfter,
              movement.world(),
              worldReference,
              sync,
              assumptions,
              search,
              replayReference,
              timingExhaustive && !recoveryRequired && movement.chronologyClean(),
              EnumSet.of(
                  Phase6Reachability.ObservedField.POSITION,
                  Phase6Reachability.ObservedField.ROTATION));
      results.add(validation);
      if (validation.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE) {
        trace.add("EVIDENCE REACHABILITY_CONTRADICTION");
      }

      // The candidate frontier advances only through a POSSIBLE observation.
      // IMPOSSIBLE/UNCERTAIN evidence never becomes a trusted baseline. UNCERTAIN
      // explicitly clears the frontier so later validation can re-root causally.
      if (validation.verdict() == Phase8MovementValidation.Verdict.POSSIBLE) {
        Set<Candidate> matching = new LinkedHashSet<>();
        for (Candidate candidate : reachable) {
          if (matchesObserved(candidate.context().player(), observedAfter, movement.move())) {
            matching.add(candidate);
          }
        }
        if (!matching.isEmpty()) {
          long resultingTick = matching.stream()
              .mapToLong(c -> c.context().simulationTick())
              .max()
              .orElse(movementTick);
          frontier = new Frontier(Set.copyOf(matching), resultingTick, true);
          previousPositionPacketTick = resultingTick;
          previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
          if (movement.move().clientTick() != null) {
            previousExplicitClientTick = movement.move().clientTick();
          }
          trace.add("MATCHING candidates=" + matching.size()
              + " frontierTick=" + resultingTick);
        }
      } else if (validation.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE) {
        contradictionActive = true;
        previousPositionPacketTick = movementTick;
        previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
        if (movement.move().clientTick() != null) {
          previousExplicitClientTick = movement.move().clientTick();
        }
        // An IMPOSSIBLE observation is evidence, never a trusted state. Discard
        // the prediction frontier so the next movement can restart from a fresh
        // causally aligned authoritative snapshot instead of replaying an
        // untrusted path all the way from join/epoch time.
        frontier = Frontier.empty();
        trace.add("EVIDENCE REACHABILITY_CONTRADICTION");
        trace.add("FRONTIER_RESET reason=IMPOSSIBLE; next movement may use exact authoritative local root");
      } else {
        recoveryRequired = true;
        lastAmbiguitySequence = sequence;
        uncertainty.addAll(advance.reasons());
        trace.add("EVIDENCE UNCERTAIN " + advance.reasons());
        /*
         * An uncertain prediction is not a trusted causal state. Retaining it
         * makes later movement inherit an old frontier even after a fresh
         * authoritative snapshot exists. Discard it and require the next clean
         * movement to re-root from current authority.
         */
        frontier = Frontier.empty();
        previousPositionPacketGenerationRange = eventTiming.packetGenerationClientTicks();
        previousExplicitClientTick = movement.move().clientTick();
        trace.add("FRONTIER_RESET reason=UNCERTAIN_REQUIRES_FRESH_AUTHORITY");
      }

      // Explicit client/server ground disagreements remain evidence, not a hard
      // verdict. A ground bit is a client claim; server collision state can be
      // sampled on a different side of the network boundary. The causal frame
      // exposes both values so an operator can see the disagreement without
      // converting it into a heuristic violation.
      movement.authority().snapshot().ifPresent(snapshot -> {
        Boolean authoritativeGround =
            snapshot.context().movementEnvironment().onGround();
        if (movement.move().onGround() != null
            && authoritativeGround != null
            && movement.move().onGround() != authoritativeGround) {
          trace.add("AUTHORITY_OBSERVATION_ONLY ground client="
              + movement.move().onGround() + " server=" + authoritativeGround);
          assumptions.add("client/server ground disagreement is corroboration only");
        }
        double distance = distance(
            movement.move().position(),
            snapshot.context().serverPosition());
        if (Double.isFinite(distance) && distance > 0.0) {
          trace.add("AUTHORITY_OBSERVATION_ONLY positionDistance="
              + String.format(Locale.ROOT, "%.6f", distance));
          assumptions.add("client/server position divergence is corroboration only; it is not a Phantom verdict");
        }
      });

      frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
          assumptions, uncertainty, trace));
    }

    if (results.size() != movements.size() || frames.size() != movements.size()) {
      throw new IllegalStateException(
          "Phase 8 movement/result cardinality invariant violated: movements="
              + movements.size() + " results=" + results.size() + " frames=" + frames.size());
    }

    int possible = (int) results.stream()
        .filter(result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE)
        .count();
    int uncertain = (int) results.stream()
        .filter(result -> result.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN)
        .count();
    int impossible = (int) results.stream()
        .filter(result -> result.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE)
        .count();
    return new Report(results, frames, movements.size(), possible, uncertain, impossible);
  }

  private static Frame frame(
      long sequence,
      Timeline.Event event,
      Phase7Timing.EventTiming timing,
      MovementEvent movement,
      Player before,
      Player after,
      List<String> assumptions,
      List<String> uncertainty,
      List<String> trace) {
    ArrayList<String> merged = new ArrayList<>(trace);
    merged.add("OBSERVED position=" + after.position()
        + " yaw=" + after.yaw()
        + " pitch=" + after.pitch()
        + " clientGround=" + movement.move().onGround());
    merged.add("UNCERTAINTY " + (uncertainty.isEmpty() ? "none" : uncertainty));
    return new Frame(
        sequence,
        event.packet().receivedNanos(),
        event.serverTick(),
        timing,
        movement.move(),
        before,
        after,
        movement.authority(),
        movement.world(),
        uncertainty,
        merged);
  }

  /**
   * Evaluates an explicitly observed server-side flight-toggle authorization
   * transition. This is an authoritative evidence channel, not movement
   * reachability and not a Paper move-rejection heuristic.
   */
  public static Optional<Phase8MovementValidation.Result> evaluateFlightToggle(
      String playerId,
      Timeline.Event event,
      Timeline.Snapshot timeline,
      Phase7Timing.Reconstruction timing,
      WorldSnapshot world,
      Player anchor) {
    if (!(event.packet().packet() instanceof Packets.FlightToggle toggle) || !toggle.flying()) {
      return Optional.empty();
    }

    List<AuthoritativeSnapshot> authorities = collectAuthorities(timeline);
    AuthoritativeSnapshot authority = authorities.stream()
        .filter(snapshot -> snapshot.serverTick() <= event.serverTick())
        .filter(snapshot -> snapshot.receivedNanos() <= event.packet().receivedNanos())
        .max(Comparator.comparingLong(AuthoritativeSnapshot::serverTick)
            .thenComparingLong(AuthoritativeSnapshot::receivedNanos)
            .thenComparingLong(AuthoritativeSnapshot::sequence))
        .orElse(null);

    if (authority == null) return Optional.empty();
    Packets.PlayerContext context = authority.context();
    String mode = context.gamemode();
    if (context.canFly() || context.flying()
        || (!"survival".equals(mode) && !"adventure".equals(mode))) {
      return Optional.empty();
    }

    State.Player seed = anchor == null ? State.Player.initial(context.serverPosition()) : anchor;
    State.Reconstruction reconstruction =
        State.reconstruct(State.Seed.serverAnchor(seed), timeline);
    State.StateFrame frame = reconstruction.frames().stream()
        .filter(value -> value.event().packet().sequence() == event.packet().sequence())
        .findFirst()
        .orElse(null);
    if (frame == null) return Optional.empty();

    Phase7Timing.EventTiming eventTiming =
        timing.timingFor(event.packet().sequence()).orElse(null);
    Validation.SyncWindow sync = eventTiming == null
        ? new Validation.SyncWindow(
            0, 0, true,
            List.of("server flight-toggle event has no client simulation tick"))
        : Phase7Timing.toPhase6Window(eventTiming);

    return Optional.of(Phase8MovementValidation.authoritativeImpossible(
        playerId,
        event.serverTick(),
        frame.before(),
        frame.after(),
        world,
        "timeline:flight-toggle:serverTick=" + event.serverTick(),
        sync,
        "UNAUTHORIZED_FLIGHT_TOGGLE_ATTEMPT",
        "authoritative server state says flight is not permitted but a flight-on transition was observed",
        List.of(
            "authoritativeCanFly=false",
            "authoritativeFlying=" + context.flying(),
            "authoritativeGamemode=" + context.gamemode(),
            "flightToggle.flying=true",
            "flightToggle.cancelled=" + toggle.cancelled(),
            "authoritySnapshotSequence=" + authority.sequence(),
            "authoritySnapshotServerTick=" + authority.serverTick(),
            "this evidence is independent of Paper movement rejection events"),
        "causal:flight:" + playerId + ":" + event.packet().sequence()));
  }

  /**
   * Reconstructs the client observation channel only. Server authority packets,
   * corrections, world packets, and Paper telemetry do not overwrite the client
   * observation state.
   */
  private static State.Reconstruction reconstructClientObservations(
      Player seed,
      Timeline.Snapshot timeline) {
    Player current = seed;
    Set<State.Fact> known = EnumSet.of(
        State.Fact.POSITION,
        State.Fact.ROTATION,
        State.Fact.VELOCITY,
        State.Fact.GROUND);
    Optional<Packets.ClientInput> currentInput = Optional.empty();
    List<State.StateFrame> frames = new ArrayList<>();
    int index = 0;

    for (Timeline.Event event : timeline.events()) {
      Player before = current;
      Packets.Packet packet = event.packet().packet();
      Set<State.Fact> refreshed = EnumSet.noneOf(State.Fact.class);

      if (!event.packet().flags().contains(Packets.PacketFlag.DUPLICATE)) {
        if (packet instanceof Packets.Move move) {
          current = State.apply(current, event.packet());
          if (move.position() != null) refreshed.add(State.Fact.POSITION);
          if (move.yaw() != null || move.pitch() != null) refreshed.add(State.Fact.ROTATION);
          if (move.onGround() != null) refreshed.add(State.Fact.GROUND);
        } else if (packet instanceof Packets.ClientInput input) {
          current = State.apply(current, event.packet());
          currentInput = Optional.of(input);
          refreshed.add(State.Fact.INPUT);
        }
      } else {
        current = current.withUncertainty(State.UncertaintyReason.DUPLICATE_PACKET);
      }

      if (!refreshed.isEmpty()) known.addAll(refreshed);
      frames.add(new State.StateFrame(
          index++,
          event,
          before,
          current,
          Set.copyOf(known),
          Set.copyOf(refreshed),
          currentInput,
          current.environment()));
    }

    return new State.Reconstruction(frames);
  }

  private static List<AuthoritativeSnapshot> collectAuthorities(Timeline.Snapshot timeline) {
    List<AuthoritativeSnapshot> snapshots = new ArrayList<>();
    for (Timeline.Event event : timeline.events()) {
      if (event.packet().packet() instanceof Packets.PlayerContext context) {
        snapshots.add(new AuthoritativeSnapshot(
            event.packet().sequence(),
            event.packet().receivedNanos(),
            event.serverTick(),
            context,
            event.packet().provenance().authoritativeClientTick()));
      }
    }
    snapshots.sort(Comparator.comparingLong(AuthoritativeSnapshot::receivedNanos)
        .thenComparingLong(AuthoritativeSnapshot::sequence));
    return List.copyOf(snapshots);
  }

  private static AuthorityAlignment alignAuthority(
      Timeline.Event movement,
      List<AuthoritativeSnapshot> authorities,
      Player initialAnchor,
      long initialAnchorReceivedNanos) {
    long sequence = movement.packet().sequence();
    long received = movement.packet().receivedNanos();
    long serverTick = movement.serverTick();

    AuthoritativeSnapshot exact = authorities.stream()
        .filter(snapshot -> snapshot.serverTick() == serverTick)
        .filter(snapshot -> snapshot.sequence() < sequence)
        .filter(snapshot -> snapshot.receivedNanos() <= received)
        .max(Comparator.comparingLong(AuthoritativeSnapshot::receivedNanos)
            .thenComparingLong(AuthoritativeSnapshot::sequence))
        .orElse(null);
    if (exact != null) {
      return new AuthorityAlignment(
          AuthorityQuality.EXACT,
          Optional.of(exact),
          List.of("authoritative snapshot is causally before the movement packet within the same server tick"));
    }

    if (initialAnchor != null && !initialAnchor.uncertain()
        && initialAnchorReceivedNanos >= 0
        && received >= initialAnchorReceivedNanos
        && authorities.isEmpty()) {
      return new AuthorityAlignment(
          AuthorityQuality.EXACT,
          Optional.of(new AuthoritativeSnapshot(
              0,
              initialAnchorReceivedNanos,
              0,
              contextFromAnchor(initialAnchor))),
          List.of("movement is evaluated from the explicitly supplied server join/world-change anchor"));
    }

    AuthoritativeSnapshot stale = authorities.stream()
        .filter(snapshot -> snapshot.serverTick() < serverTick
            || snapshot.receivedNanos() < received)
        .max(Comparator.comparingLong(AuthoritativeSnapshot::serverTick)
            .thenComparingLong(AuthoritativeSnapshot::receivedNanos))
        .orElse(null);
    if (stale != null) {
      return new AuthorityAlignment(
          AuthorityQuality.STALE,
          Optional.of(stale),
          List.of("nearest known authoritative snapshot precedes the movement but is not an exact same-tick causal snapshot"));
    }

    return new AuthorityAlignment(
        AuthorityQuality.MISSING,
        Optional.empty(),
        List.of("no causally alignable authoritative server snapshot was captured"));
  }

  private static NavigableMap<Long, List<TimedInput>> collectInputs(
      Timeline.Snapshot timeline,
      Phase7Timing.Reconstruction timing) {
    NavigableMap<Long, List<TimedInput>> result = new TreeMap<>();
    for (Timeline.Event event : timeline.events()) {
      if (!(event.packet().packet() instanceof Packets.ClientInput input)) continue;
      Phase7Timing.EventTiming eventTiming =
          timing.timingFor(event.packet().sequence()).orElse(null);
      if (eventTiming == null
          || event.packet().flags().contains(Packets.PacketFlag.DUPLICATE)
          || event.packet().flags().contains(Packets.PacketFlag.OUT_OF_ORDER)
          || event.packet().flags().contains(Packets.PacketFlag.SEQUENCE_GAP)) {
        continue;
      }
      List<Long> ticks = Phase7Timing.possibleInputTicks(eventTiming);
      if (!Phase7Timing.inputTickEnumerationComplete(eventTiming)) {
        // Preserve the timing uncertainty by leaving the input unconstrained for
        // Phase 6; a partial tick subset would fabricate precision.
        continue;
      }
      InputConstraint constraint = InputConstraint.fromClientInput(input);
      for (long clientTick : ticks) {
        result.computeIfAbsent(clientTick, ignored -> new ArrayList<>())
            .add(new TimedInput(
                event.packet().sequence(),
                clientTick,
                constraint));
      }
    }
    for (List<TimedInput> inputs : result.values()) {
      inputs.sort(Comparator.comparingLong(TimedInput::sequence));
    }
    NavigableMap<Long, List<TimedInput>> immutable = new TreeMap<>();
    for (var entry : result.entrySet()) {
      immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableNavigableMap(immutable);
  }

  /**
   * Returns the last client input that could causally have affected this
   * simulation step. In particular, a ClientInput packet received after the
   * movement packet being validated is excluded even when both events were
   * assigned to the same reconstructed client tick.
   */
  private static InputConstraint inputForSimulationTick(
      NavigableMap<Long, List<TimedInput>> inputs,
      long simulationTick,
      long movementSequence) {
    if (inputs.isEmpty() || simulationTick < 0) return InputConstraint.any();
    for (var entry : inputs.headMap(simulationTick, true).descendingMap().entrySet()) {
      TimedInput selected = null;
      for (TimedInput input : entry.getValue()) {
        if (input.sequence() > movementSequence) break;
        selected = input;
      }
      if (selected != null) return selected.constraint();
    }
    return InputConstraint.any();
  }

  private static void appendInputTrace(
      List<String> trace,
      NavigableMap<Long, List<TimedInput>> inputs,
      Phase7Timing.Range simulationTicks,
      long movementSequence) {
    List<TimedInput> recent = new ArrayList<>();
    for (var entry : inputs.subMap(
        Math.max(0L, simulationTicks.min() - 1L),
        true,
        simulationTicks.max() + 1L,
        true).entrySet()) {
      for (TimedInput input : entry.getValue()) {
        if (input.sequence() <= movementSequence) recent.add(input);
        else if (input.sequence() <= movementSequence + 8L) {
          trace.add("INPUT_FUTURE_EXCLUDED seq=" + input.sequence()
              + " tick=" + input.clientTick()
              + " constraint=" + input.constraint());
        }
      }
    }
    recent.sort(Comparator.comparingLong(TimedInput::sequence));
    int start = Math.max(0, recent.size() - 6);
    for (int i = start; i < recent.size(); i++) {
      TimedInput input = recent.get(i);
      trace.add("INPUT_CAUSAL seq=" + input.sequence()
          + " tick=" + input.clientTick()
          + " constraint=" + input.constraint());
    }
    if (recent.isEmpty()) {
      trace.add("INPUT_CAUSAL none for simulationTicks=" + simulationTicks);
    }
  }

  private static Map<Long, List<ExternalTransition>> collectExternalTransitions(
      Timeline.Snapshot timeline,
      Phase7Timing.Reconstruction timing,
      List<AuthoritativeSnapshot> authorities,
      Player initialAnchor,
      Set<Long> unmodeledSequences) {
    Map<Long, List<ExternalTransition>> result = new HashMap<>();
    for (Timeline.Event event : timeline.events()) {
      Packets.Packet packet = event.packet().packet();
      if (!(packet instanceof Packets.Velocity)
          && !(packet instanceof Packets.Teleport)
          && !(packet instanceof Packets.TeleportConfirm)) continue;
      Phase7Timing.EventTiming eventTiming =
          timing.timingFor(event.packet().sequence()).orElse(null);
      if (eventTiming == null) {
        unmodeledSequences.add(event.packet().sequence());
        continue;
      }
      if (!Phase7Timing.simulationTickEnumerationComplete(eventTiming)
          || (eventTiming.simulationClientTicks().width() > 0
              && (packet instanceof Packets.Velocity
                  || packet instanceof Packets.Teleport
                  || packet instanceof Packets.TeleportConfirm))) {
        /*
         * A non-exact external-transition timing window can place the effect on
         * either side of the movement observation. The current full-tick causal
         * replay cannot preserve every application order without fabricating
         * intermediate chronology, so keep the result uncertain.
         */
        unmodeledSequences.add(event.packet().sequence());
        continue;
      }
      List<Long> ticks = Phase7Timing.possibleSimulationTicks(eventTiming);
      if (ticks.isEmpty()) {
        unmodeledSequences.add(event.packet().sequence());
        continue;
      }

      ExternalTransition transition;
      if (packet instanceof Packets.Velocity velocity) {
        transition = new Phase6Reachability.VelocityImpulse(
            velocity.velocity(), "authoritative velocity packet seq=" + event.packet().sequence());
      } else if (packet instanceof Packets.TeleportConfirm confirm) {
        transition = new Phase6Reachability.TeleportConfirmation(confirm.id());
      } else {
        Packets.Teleport teleport = (Packets.Teleport) packet;
        if (teleport.relativeYaw() || teleport.relativePitch()) {
          unmodeledSequences.add(event.packet().sequence());
          continue;
        }
        Vec3 base = nearestAuthoritativePosition(
            authorities, initialAnchor, event.packet().receivedNanos(), event.serverTick());
        Vec3 target = new Vec3(
            teleport.relativeX() ? base.x() + teleport.position().x() : teleport.position().x(),
            teleport.relativeY() ? base.y() + teleport.position().y() : teleport.position().y(),
            teleport.relativeZ() ? base.z() + teleport.position().z() : teleport.position().z());
        float yaw = teleport.relativeYaw()
            ? nearestAuthoritativeYaw(authorities, initialAnchor, event.packet().receivedNanos()) + teleport.yaw()
            : teleport.yaw();
        float pitch = teleport.relativePitch()
            ? nearestAuthoritativePitch(authorities, initialAnchor, event.packet().receivedNanos()) + teleport.pitch()
            : teleport.pitch();
        transition = new Phase6Reachability.TeleportCorrection(
            teleport.id(), target, Vec3.ZERO, Pose.STANDING, true, yaw, pitch);
      }
      for (long tick : ticks) {
        result.computeIfAbsent(tick, ignored -> new ArrayList<>()).add(transition);
      }
    }
    Map<Long, List<ExternalTransition>> immutable = new HashMap<>();
    for (Map.Entry<Long, List<ExternalTransition>> entry : result.entrySet()) {
      List<ExternalTransition> transitions = new ArrayList<>(entry.getValue());
      transitions.sort(Comparator.comparing(Object::toString));
      immutable.put(entry.getKey(), List.copyOf(transitions));
    }
    return Map.copyOf(immutable);
  }

  /**
   * Replays each possible client simulation tick independently from the same
   * exact pre-movement authoritative snapshot. This keeps the 0..1 tick packet
   * delay envelope exhaustive without inventing a single client tick for the
   * server-side authority capture.
   */
  private static Advance advanceAcrossLocalAuthorityTimingRange(
      long earliest,
      long latest,
      NavigableMap<Long, List<TimedInput>> inputs,
      Map<Long, List<ExternalTransition>> external,
      World.VisibilityHistory worldHistory,
      MovementEvent movement,
      int maximumCandidates) {
    if (latest < earliest || earliest < 0
        || latest - earliest + 1 > Phase6Reachability.MAX_TIMING_OFFSETS) {
      return null;
    }

    Set<Candidate> union = new LinkedHashSet<>();
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    boolean exhaustive = true;

    for (long target = earliest; target <= latest; target++) {
      Optional<Candidate> root = rootCandidateForTarget(movement, target);
      if (root.isEmpty()) {
        exhaustive = false;
        reasons.add("authoritative local root could not be represented for client simulation tick " + target);
        continue;
      }
      Advance one;
      if (root.get().context().simulationTick() == target) {
        /*
         * The local authoritative snapshot is itself the deterministic state for
         * this timing candidate. A bounded packet->simulation offset can therefore
         * legitimately select the root tick and require zero physics steps.
         *
         * Treating this case as an "intermediate chronology" failure discards the
         * exact authority state and turns stationary or zero-delta movements into
         * false UNCERTAIN evidence.
         */
        one = new Advance(
            Set.of(root.get()),
            List.of("client-tick timing candidate matches the authoritative root; no physics step required"),
            true);
      } else if (root.get().context().simulationTick() + 1L == target) {
        one = advanceTo(
            Set.of(root.get()),
            target,
            inputs,
            external,
            worldHistory,
            movement,
            maximumCandidates);
      } else {
        one = new Advance(
            Set.of(),
            List.of("client-tick timing offset requires intermediate rotation chronology that is not retained"),
            false);
      }
      if (!one.exhaustive()) exhaustive = false;
      union.addAll(one.candidates());
      reasons.addAll(one.reasons());
      if (union.size() > maximumCandidates) {
        return new Advance(Set.of(),
            List.of("local-authority timing candidate budget exceeded"),
            false);
      }
    }

    return new Advance(Set.copyOf(union), List.copyOf(reasons), exhaustive);
  }

  private static Advance advanceAcrossTimingRange(
      Frontier frontier,
      long earliest,
      long latest,
      NavigableMap<Long, List<TimedInput>> inputs,
      Map<Long, List<ExternalTransition>> external,
      World.VisibilityHistory worldHistory,
      MovementEvent movement,
      int maximumCandidates) {
    if (latest < earliest || earliest < 0
        || latest - earliest + 1 > Phase6Reachability.MAX_TIMING_OFFSETS) {
      return null;
    }

    Set<Candidate> union = new LinkedHashSet<>();
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    boolean exhaustive = true;

    for (long target = earliest; target <= latest; target++) {
      Advance one = advanceTo(
          frontier.candidates(),
          target,
          inputs,
          external,
          worldHistory,
          movement,
          maximumCandidates);
      if (!one.exhaustive()) exhaustive = false;
      union.addAll(one.candidates());
      reasons.addAll(one.reasons());
      if (union.size() > maximumCandidates) {
        return new Advance(Set.of(),
            List.of("timing-range candidate budget exceeded"),
            false);
      }
    }

    return new Advance(union, List.copyOf(reasons), exhaustive);
  }

  private static Advance advanceTo(
      Set<Candidate> start,
      long targetTick,
      NavigableMap<Long, List<TimedInput>> inputs,
      Map<Long, List<ExternalTransition>> external,
      World.VisibilityHistory worldHistory,
      MovementEvent movement,
      int maximumCandidates) {
    if (targetTick < 0) {
      return new Advance(Set.of(), List.of("negative client simulation tick"), false);
    }
    if (start.isEmpty()) {
      return new Advance(Set.of(), List.of("no candidate frontier"), false);
    }

    Set<Candidate> union = new LinkedHashSet<>();
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    boolean exhaustive = true;

    /*
     * Candidates may legitimately carry different simulation ticks after a
     * bounded timing window. Never collapse them to the minimum tick: doing so
     * silently discards later-starting candidates and can create a false
     * contradiction.
     */
    for (Candidate initial : start) {
      if (initial.context().simulationTick() > targetTick) {
        reasons.add("movement tick precedes a retained candidate state");
        continue;
      }

      Set<Candidate> local = Set.of(initial);
      long localTick = initial.context().simulationTick();

      while (localTick < targetTick) {
        Set<Candidate> next = new LinkedHashSet<>();

        for (Candidate candidate : local) {
          Context context = candidate.context().withTick(localTick);
          InputConstraint input = inputForSimulationTick(
              inputs,
              localTick,
              movement.event().packet().sequence());

          long simulationTick = localTick;
          WorldSnapshot world = worldForTick(
              simulationTick, targetTick, worldHistory, movement);

          List<ExternalTransition> transitions =
              external.getOrDefault(simulationTick, List.of(new Phase6Reachability.None()));

          SearchResult result = new Phase6Reachability(
              new Vanilla12111RichPhysics()).search(
                  context,
                  List.of(input),
                  ignored -> List.of(new WorldBranch(
                      "causal-world@" + simulationTick,
                      world,
                      true,
                      "client-visible world selected for the simulated tick")),
                  ignored -> transitions,
                  maximumCandidates);

          if (result.verdict() == Verdict.POSSIBLE
              || exhaustivelyEnumeratedInputEnvelope(result, input)
              || exhaustivelyEliminatedInputEnvelope(result, input)) {
            /*
             * Phase 6's generic API preserves UNCERTAIN for a non-exact input
             * constraint even when the entire finite input envelope was actually
             * enumerated. At the pipeline boundary we can safely preserve those
             * complete candidates, or preserve an exhaustive empty result when
             * every concrete input was eliminated.
             */
            next.addAll(result.candidates());
            if (result.verdict() != Verdict.POSSIBLE) {
              reasons.addAll(result.reasons());
              reasons.add("Phase 6 input envelope was fully enumerated; no timing/world budget was spent selecting a subset");
            }
          } else {
            reasons.addAll(result.reasons());
            if (result.verdict() == Verdict.UNCERTAIN) exhaustive = false;
          }

          if (next.size() > maximumCandidates) {
            return new Advance(
                Set.of(),
                List.of("candidate budget exceeded; no provisional subset is safe to continue"),
                false);
          }
        }

        if (next.isEmpty()) {
          reasons.add("no deterministic candidate survived the simulated client tick");
          local = Set.of();
          break;
        }

        local = Set.copyOf(next);
        localTick++;
      }

      union.addAll(local);
      if (union.size() > maximumCandidates) {
        return new Advance(
            Set.of(),
            List.of("combined candidate budget exceeded; no provisional subset is safe to continue"),
            false);
      }
    }

    if (union.isEmpty()) {
      return new Advance(Set.of(), List.copyOf(reasons), exhaustive);
    }
    return new Advance(
        Set.copyOf(union),
        List.copyOf(reasons),
        exhaustive);
  }

  /**
   * Distinguishes genuine incomplete reachability from Phase 6's deliberate
   * generic UNCERTAIN label for a fully enumerated unknown-input envelope.
   *
   * The latter is exhaustive for the supplied input possibility space: there
   * is no search budget exhaustion, no non-exhaustive world branch, no
   * transition uncertainty, and every retained candidate carries only INPUT
   * uncertainty from the input envelope itself.
   */
  private static boolean exhaustivelyEliminatedInputEnvelope(
      SearchResult result,
      InputConstraint input) {
    if (result.verdict() != Verdict.UNCERTAIN
        || !result.candidates().isEmpty()
        || result.metrics().budgetReached()
        || result.nonExhaustiveWorldBranches() != 0
        || result.uncertainTransitions() != 0
        || input.enumerate().isEmpty()) {
      return false;
    }
    /*
     * Empty means every concrete input branch was eliminated. The only allowed
     * uncertainty here is the generic "input envelope" label plus the normal
     * no-survivor diagnostic. Any world/physics/budget evidence means the search
     * was not exhaustive and must remain UNCERTAIN.
     */
    return result.reasons().stream().noneMatch(reason ->
        reason.contains("world hypothesis")
            || reason.contains("world ")
            || reason.contains("Phase 5")
            || reason.contains("physics")
            || reason.contains("maximum ")
            || reason.contains("budget")
            || reason.contains("initial state carries explicit uncertainty")
            || reason.contains("external transition"));
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
            .allMatch(dimension -> dimension == Phase6Reachability.UncertainDimension.INPUT));
  }

  private static WorldSnapshot worldForTick(
      long simulationTick,
      long targetTick,
      World.VisibilityHistory history,
      MovementEvent movement) {
    /*
     * Prefer the causally selected live/client-visible snapshot when it is
     * complete enough to cover both the simulated root and observed endpoint.
     * If the compact live replica is incomplete, merge it with the historical
     * reconstruction rather than allowing an incomplete live chunk to turn
     * known historical air into UNKNOWN.
     */
    WorldSnapshot historical = history.statesAt(Math.max(0L, simulationTick));
    if (!movement.world().loadedChunks().isEmpty()) {
      Player causalRoot = movement.simulationAuthority()
          .map(snapshot -> playerFromAuthority(snapshot.context()))
          .orElse(movement.stateFrame().before());
      boolean historicalCoversRoot =
          historical.fullyKnown(playerCollisionBox(causalRoot));
      boolean historicalCoversObserved =
          historical.fullyKnown(playerCollisionBox(movement.stateFrame().after()));
      if (!historicalCoversRoot || !historicalCoversObserved
          || movement.liveWorldUsed()) {
        return WorldSnapshot.merge(historical, movement.world());
      }
    }
    return historical; 
  }

  private static Optional<Candidate> rootCandidateForTarget(
      MovementEvent movement,
      long target) {
    if (target < 0 || movement.simulationAuthority().isEmpty()) {
      return Optional.empty();
    }
    AuthoritativeSnapshot snapshot = movement.simulationAuthority().orElseThrow();
    Player authoritative = playerFromAuthority(snapshot.context());
    Player observedBefore = movement.stateFrame().before();
    float yaw = movement.move().yaw() == null ? observedBefore.yaw() : movement.move().yaw();
    float pitch = movement.move().pitch() == null ? observedBefore.pitch() : movement.move().pitch();
    Player anchor = withClientRotation(authoritative, yaw, pitch);
    long rootTick = snapshot.clientTick() != null
        ? Math.max(0L, snapshot.clientTick() - 1L)
        : Math.max(0L, target + (snapshot.serverTick() - movement.event().serverTick()));
    if (rootTick < 0 || target - rootTick > Phase6Reachability.MAX_HORIZON_TICKS) {
      return Optional.empty();
    }
    MovementEnvironment movementEnvironment;
    try {
      movementEnvironment = movementEnvironmentOf(anchor);
    } catch (IllegalStateException unknownEnvironment) {
      return Optional.empty();
    }
    Context context = new Context(
        rootTick,
        anchor,
        simulationEnvironmentFor(movementEnvironment),
        anchor.attributes(),
        movementEffects(anchor),
        anchor.pose(),
        movementEnvironment,
        anchor.pose() == Pose.SLEEPING,
        entityCollisionsFor(movement));
    return Optional.of(new Candidate(
        0,
        context,
        new Phase6Reachability.Provenance(
            0,
            snapshot.sequence(),
            rootTick,
            "LOCAL_AUTHORITATIVE_ROOT",
            "ROOT",
            "None",
            List.of(authorityRootDescription(movement)),
            1,
            List.of())));
  }

  private static String authorityRootDescription(MovementEvent movement) {
    AuthoritativeSnapshot snapshot = movement.simulationAuthority().orElseThrow();
    if (snapshot.serverTick() < movement.event().serverTick()) {
      return "causally preceding authoritative snapshot from the prior server tick";
    }
    return "early-capture same-tick authoritative snapshot fallback";
  }

  private static Optional<AuthoritativeSnapshot> causallyFreshLocalAuthority(
      MovementEvent movement,
      long maxServerTickAge,
      long minimumSequenceExclusive) {
    return movement.simulationAuthority()
        .filter(snapshot -> snapshot.sequence() > minimumSequenceExclusive)
        .filter(snapshot -> snapshot.sequence() < movement.event().packet().sequence())
        .filter(snapshot -> snapshot.receivedNanos() <= movement.event().packet().receivedNanos())
        .filter(snapshot -> snapshot.serverTick() <= movement.event().serverTick())
        .filter(snapshot -> movement.event().serverTick() - snapshot.serverTick() <= maxServerTickAge)
        .filter(snapshot -> {
          Long clientTick = movement.move().clientTick();
          if (clientTick == null || !movement.event().packet().provenance().sourceId().startsWith("paper-")) {
            return true;
          }
          return snapshot.clientTick() != null
              && snapshot.clientTick().longValue() == clientTick.longValue();
        });
  }

  /**
   * Selects the authoritative snapshot that is safe to use as the pre-movement
   * physics state. A same-tick PlayerContext may already contain the server's
   * post-movement position because the live capture runs once per server tick.
   * Therefore Phase 6 roots from the latest causally preceding server tick.
   *
   * <p>The same-tick snapshot remains in {@link AuthorityAlignment} for
   * corroboration and diagnostics; it is never silently discarded.</p>
   */
  private static Optional<AuthoritativeSnapshot> selectSimulationAuthority(
      Timeline.Event movement,
      Phase7Timing.EventTiming eventTiming,
      List<AuthoritativeSnapshot> authorities,
      Player initialAnchor) {
    long sequence = movement.packet().sequence();
    long received = movement.packet().receivedNanos();

    /*
     * Explicit client ticks are strong packet chronology, but a live Paper
     * PlayerContext is still a server-side sample. Its client-tick field is a
     * capture watermark, not proof that the sampled server position is the
     * state for that client simulation tick. For live captures, prefer the
     * latest strictly preceding server-tick authority with a client watermark
     * no later than the movement tick. This prevents a same-server-tick sample
     * taken after the server has already processed the movement from becoming
     * the pre-movement physics root.
     *
     * Historical/synthetic captures keep the exact client-tick behavior because
     * their provenance does not claim the asynchronous live Paper sampling path.
     */
    boolean explicitClientTick = movement.packet().packet() instanceof Packets.Move move && move.clientTick() != null;
    if (explicitClientTick) {
      Packets.Move move = (Packets.Move) movement.packet().packet();
      long target = move.clientTick();
      boolean liveClientTickMovement = movement.packet().provenance().sourceId().startsWith("paper-client-tick");
      if (liveClientTickMovement) {
        Optional<AuthoritativeSnapshot> precedingLive = authorities.stream()
            .filter(snapshot -> snapshot.sequence() < sequence)
            .filter(snapshot -> snapshot.receivedNanos() <= received)
            .filter(snapshot -> snapshot.serverTick() < movement.serverTick())
            .filter(snapshot -> movement.serverTick() - snapshot.serverTick() <= 1L)
            .filter(snapshot -> snapshot.clientTick() != null)
            .filter(snapshot -> snapshot.clientTick() <= target)
            .filter(snapshot -> !isPlaceholderAuthority(snapshot, initialAnchor))
            .max(Comparator.comparingLong(AuthoritativeSnapshot::serverTick)
                .thenComparingLong(AuthoritativeSnapshot::clientTick)
                .thenComparingLong(AuthoritativeSnapshot::receivedNanos)
                .thenComparingLong(AuthoritativeSnapshot::sequence));
        if (precedingLive.isPresent()) return precedingLive;

        // A same-tick live sample is safe only as corroborating authority; it is
        // never promoted to a physics root when an explicit client tick exists.
        return Optional.empty();
      }

      Optional<AuthoritativeSnapshot> exactClient = authorities.stream()
          .filter(snapshot -> snapshot.sequence() < sequence)
          .filter(snapshot -> snapshot.receivedNanos() <= received)
          .filter(snapshot -> snapshot.clientTick() != null)
          .filter(snapshot -> snapshot.clientTick() == target)
          .filter(snapshot -> !isPlaceholderAuthority(snapshot, initialAnchor))
          .max(Comparator.comparingLong(AuthoritativeSnapshot::receivedNanos)
              .thenComparingLong(AuthoritativeSnapshot::sequence));
      if (exactClient.isPresent()) return exactClient;
    }

    /*
     * For bounded timing, an annotated authority at the lower client-tick bound
     * is a sound earliest root; later offsets are replayed forward from it.
     */
    Phase7Timing.Range targetRange = eventTiming.simulationClientTicks();
    Optional<AuthoritativeSnapshot> annotated = authorities.stream()
        .filter(snapshot -> snapshot.sequence() < sequence)
        .filter(snapshot -> snapshot.receivedNanos() <= received)
        .filter(snapshot -> snapshot.clientTick() != null)
        .filter(snapshot -> snapshot.clientTick() == targetRange.min())
        .filter(snapshot -> !isPlaceholderAuthority(snapshot, initialAnchor))
        .max(Comparator.comparingLong(AuthoritativeSnapshot::receivedNanos)
            .thenComparingLong(AuthoritativeSnapshot::sequence));
    if (annotated.isPresent()) return annotated;

    /*
     * Prefer a strictly preceding server-tick snapshot when it is no more than
     * one server tick old. This is the safe live chronology root.
     */
    Optional<AuthoritativeSnapshot> previous = authorities.stream()
        .filter(snapshot -> snapshot.sequence() < sequence)
        .filter(snapshot -> snapshot.receivedNanos() <= received)
        .filter(snapshot -> snapshot.serverTick() < movement.serverTick())
        .filter(snapshot -> movement.serverTick() - snapshot.serverTick() <= 1L)
        .filter(snapshot -> !isPlaceholderAuthority(snapshot, initialAnchor))
        .filter(snapshot -> snapshot.clientTick() == null)
        .max(Comparator.comparingLong(AuthoritativeSnapshot::serverTick)
            .thenComparingLong(AuthoritativeSnapshot::receivedNanos)
            .thenComparingLong(AuthoritativeSnapshot::sequence));
    if (previous.isPresent()) return previous;

    /*
     * Synthetic/historical captures sometimes provide an authoritative
     * PlayerContext earlier in the same server tick, but without a client-tick
     * watermark. When no safe preceding-tick root exists, that earlier packet is
     * the strongest available causal anchor and is safe because sequence and
     * capture time both precede the movement. Live Paper explicit-tick captures
     * were already rejected above when their watermark is missing.
     */
    return authorities.stream()
        .filter(snapshot -> snapshot.sequence() < sequence)
        .filter(snapshot -> snapshot.receivedNanos() <= received)
        .filter(snapshot -> snapshot.serverTick() == movement.serverTick())
        .filter(snapshot -> !isPlaceholderAuthority(snapshot, initialAnchor))
        .filter(snapshot -> snapshot.clientTick() == null)
        .max(Comparator.comparingLong(AuthoritativeSnapshot::receivedNanos)
            .thenComparingLong(AuthoritativeSnapshot::sequence));
  }

  private static boolean isPlaceholderAuthority(
      AuthoritativeSnapshot snapshot,
      Player initialAnchor) {
    if (initialAnchor == null || initialAnchor.uncertain()) return false;
    Packets.PlayerContext context = snapshot.context();
    return context.serverPosition().equals(Vec3.ZERO)
        && context.serverVelocity().equals(Vec3.ZERO)
        && context.entityBoxes().isEmpty();
  }

  private static boolean frontierFarFromLocalAuthority(
      Frontier frontier,
      MovementEvent movement,
      double threshold) {
    if (frontier.candidates().isEmpty()) return false;
    Optional<AuthoritativeSnapshot> snapshot = movement.authority().snapshot();
    if (snapshot.isEmpty()) return false;
    Vec3 authorityPosition = snapshot.get().context().serverPosition();
    return frontier.candidates().stream()
        .noneMatch(candidate -> {
          double distance = distance(candidate.context().player().position(), authorityPosition);
          return Double.isFinite(distance) && distance <= threshold;
        });
  }

  private static double nearestFrontierAuthorityDistance(
      Frontier frontier,
      MovementEvent movement) {
    Optional<AuthoritativeSnapshot> snapshot = movement.authority().snapshot();
    if (snapshot.isEmpty() || frontier.candidates().isEmpty()) return Double.NaN;
    Vec3 authorityPosition = snapshot.get().context().serverPosition();
    return frontier.candidates().stream()
        .mapToDouble(candidate ->
            distance(candidate.context().player().position(), authorityPosition))
        .filter(Double::isFinite)
        .min()
        .orElse(Double.NaN);
  }

  private static dev.phantom.ac.geometry.BlockBox playerCollisionBox(Player player) {
    Maths.Aabb box=Maths.Aabb.playerAt(player.position(),player.pose());
    return new dev.phantom.ac.geometry.BlockBox(
        box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ());
  }

  /**
   * Detects a real overlap for sub-tick ambiguity.
   *
   * <p>The timing ranges are discrete client-tick sets, so an exact tick must
   * overlap any range that contains it. For two non-exact bounded ranges, however,
   * sharing only an endpoint is treated as adjacency (for example 385..386 followed
   * by 386..387), not as proof that two movements occurred in the same tick.</p>
   */
  private static boolean rangesOverlapForSubTickDetection(
      Phase7Timing.Range a,
      Phase7Timing.Range b) {
    if (a.isExact()) {
      return b.contains(a.min());
    }
    if (b.isExact()) {
      return a.contains(b.min());
    }
    return a.min() < b.max() && b.min() < a.max();
  }

  private static Optional<Candidate> rootCandidate(
      Player fallbackAnchor,
      MovementEvent movement,
      int maximumCandidates,
      boolean useLocalAuthoritativeRoot) {
    long target = movement.timing().simulationClientTicks().min();
    if (target < 0) return Optional.empty();

    Player anchor = fallbackAnchor;
    long rootTick = 0L;
    if (anchor == null && movement.simulationAuthority().isEmpty()) {
      return Optional.empty();
    }
    Optional<AuthoritativeSnapshot> simulationAuthority =
        movement.simulationAuthority();
    boolean localAuthoritativeRoot = useLocalAuthoritativeRoot
        && simulationAuthority.isPresent();

    if (localAuthoritativeRoot) {
      AuthoritativeSnapshot snapshot = simulationAuthority.orElseThrow();
      Player authoritative = playerFromAuthority(snapshot.context());
      Player observedBefore = movement.stateFrame().before();
      // PlayerContext has no server yaw/pitch. For a position-bearing movement,
      // the movement packet's rotation is the client orientation used by the tick.
      float yaw = movement.move().yaw() == null ? observedBefore.yaw() : movement.move().yaw();
      float pitch = movement.move().pitch() == null ? observedBefore.pitch() : movement.move().pitch();
      anchor = withClientRotation(authoritative, yaw, pitch);
      rootTick = snapshot.clientTick() != null
          ? Math.max(0L, snapshot.clientTick() - 1L)
          : Math.max(0L, target - 1L);
    }

    if (target < rootTick) {
      return Optional.empty();
    }
    /*
     * Non-authoritative replay from an explicit/fallback anchor can be
     * evaluated in deterministic single-tick Phase 6 calls even when the
     * absolute client tick is far away. The Phase 6 candidate budget still
     * bounds branching; an uncertain or over-budget long replay therefore
     * remains UNCERTAIN rather than fabricating an impossible result.
     *
     * Authoritative local roots retain the finite-horizon guard because there
     * is no safe intermediate chronology for a distant local snapshot.
     */
    if (localAuthoritativeRoot
        && target - rootTick > Phase6Reachability.MAX_HORIZON_TICKS) {
      return Optional.empty();
    }

    MovementEnvironment movementEnvironment =
        localAuthoritativeRoot ? movementEnvironmentOf(anchor) : MovementEnvironment.dry(anchor.onGround(), false, false);
    Context context = new Context(
        rootTick,
        anchor,
        simulationEnvironmentFor(movementEnvironment),
        anchor.attributes(),
        movementEffects(anchor),
        anchor.pose(),
        movementEnvironment,
        anchor.pose() == Pose.SLEEPING,
        entityCollisionsFor(movement));
    return Optional.of(new Candidate(
        0,
        context,
        new Phase6Reachability.Provenance(
            0,
            simulationAuthority.map(AuthoritativeSnapshot::sequence).orElse(
                movement.authority().snapshot().map(AuthoritativeSnapshot::sequence).orElse(-1L)),
            rootTick,
            localAuthoritativeRoot ? "LOCAL_AUTHORITATIVE_ROOT" : "ROOT_AUTHORITATIVE",
            "ROOT",
            "None",
            List.of(localAuthoritativeRoot
                ? authorityRootDescription(movement) + "; local root avoids long-horizon replay"
                : "explicit authoritative server anchor"),
            1,
            List.of())));
  }

  private static Player withClientRotation(Player authoritative, float yaw, float pitch) {
    return new Player(
        authoritative.position(),
        authoritative.velocity(),
        yaw,
        pitch,
        authoritative.onGround(),
        authoritative.gamemode(),
        authoritative.effects(),
        authoritative.awaitingTeleport(),
        authoritative.uncertain(),
        authoritative.input(),
        authoritative.attributes(),
        authoritative.pose(),
        authoritative.environment(),
        authoritative.clientTickRange(),
        authoritative.provenance(),
        authoritative.uncertaintyReasons());
  }

  private static MovementEnvironment movementEnvironmentOf(Player player) {
    return switch (player.environment()) {
      case WATER -> MovementEnvironment.vanillaWater(player.onGround(), false, false, player.pose() == Pose.SWIMMING);
      case LAVA -> MovementEnvironment.vanillaLava(player.onGround(), false, false);
      case CLIMBABLE -> MovementEnvironment.vanillaClimbable(player.onGround(), false, false);
      case DRY -> MovementEnvironment.dry(player.onGround(), false, false);
      case UNKNOWN -> throw new IllegalStateException("authoritative local root has unknown environment");
    };
  }

  private static EntityCollisions entityCollisionsFor(MovementEvent movement) {
    return movement.authority().snapshot()
        .map(snapshot -> EntityCollisions.of(snapshot.context().entityBoxes()))
        .orElse(EntityCollisions.NONE_TRACKED);
  }

  private static Set<Candidate> refreshEntityCollisions(
      Set<Candidate> candidates,
      EntityCollisions entityCollisions,
      int maximumCandidates) {
    if (candidates.isEmpty() || candidates.size() > maximumCandidates) return Set.of();
    LinkedHashSet<Candidate> refreshed = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      Context old = candidate.context();
      Context updated = new Context(
          old.simulationTick(),
          old.player(),
          old.environment(),
          old.attributes(),
          old.effects(),
          old.pose(),
          old.movementEnvironment(),
          old.sleeping(),
          entityCollisions,
          old.uncertainty());
      refreshed.add(new Candidate(candidate.id(), updated, candidate.provenance()));
    }
    return Set.copyOf(refreshed);
  }

  private static boolean worldCoversFrontierMovement(
      WorldSnapshot world,
      Frontier frontier,
      Player observedAfter) {
    if (world == null || frontier.candidates().isEmpty()) return false;
    int afterChunkX = Math.floorDiv((int) Math.floor(observedAfter.position().x()), 16);
    int afterChunkZ = Math.floorDiv((int) Math.floor(observedAfter.position().z()), 16);
    if (!world.hasChunk(afterChunkX, afterChunkZ)) return false;
    for (Candidate candidate : frontier.candidates()) {
      int chunkX = Math.floorDiv((int) Math.floor(candidate.context().player().position().x()), 16);
      int chunkZ = Math.floorDiv((int) Math.floor(candidate.context().player().position().z()), 16);
      if (!world.hasChunk(chunkX, chunkZ)) return false;
    }
    return true;
  }

  private static boolean containsChronologyProblem(
      EnumSet<Packets.PacketFlag> flags) {
    return flags.contains(Packets.PacketFlag.DUPLICATE)
        || flags.contains(Packets.PacketFlag.OUT_OF_ORDER)
        || flags.contains(Packets.PacketFlag.SEQUENCE_GAP)
        || flags.contains(Packets.PacketFlag.BEFORE_CAPTURE_EPOCH);
  }

  private static boolean hasWorldMutationAfter(
      Timeline.Snapshot timeline,
      long sequence) {
    int movementIndex = -1;
    for (int i = 0; i < timeline.events().size(); i++) {
      if (timeline.events().get(i).packet().sequence() == sequence) {
        movementIndex = i;
        break;
      }
    }
    if (movementIndex < 0) return true;
    for (int i = movementIndex + 1; i < timeline.events().size(); i++) {
      if (timeline.events().get(i).packet().packet().mutatesWorld()) return true;
    }
    return false;
  }

  private static Optional<Candidate> initialAnchorObservationWitness(
      Player initialAnchor,
      MovementEvent movement,
      long simulationTick) {
    if (initialAnchor == null || initialAnchor.uncertain()
        || initialAnchor.environment() == State.Environment.UNKNOWN
        || simulationTick < 0) return Optional.empty();
    if (!movement.world().fullyKnown(playerCollisionBox(initialAnchor))) return Optional.empty();
    Player observed = movement.stateFrame().after();
    if (!Phase6Reachability.positionMatches(initialAnchor.position(), observed.position())) return Optional.empty();
    if (initialAnchor.onGround() != observed.onGround()) return Optional.empty();
    if (movement.move().onGround() != null && initialAnchor.onGround() != movement.move().onGround()) return Optional.empty();
    if (movement.move().yaw() != null
        && Float.compare(initialAnchor.yaw(), movement.move().yaw()) != 0) return Optional.empty();
    if (movement.move().pitch() != null
        && Float.compare(initialAnchor.pitch(), movement.move().pitch()) != 0) return Optional.empty();
    float yaw = movement.move().yaw() == null ? observed.yaw() : movement.move().yaw();
    float pitch = movement.move().pitch() == null ? observed.pitch() : movement.move().pitch();
    Player witnessPlayer = new Player(
        observed.position(),
        initialAnchor.velocity(),
        yaw,
        pitch,
        observed.onGround(),
        initialAnchor.gamemode(),
        initialAnchor.effects(),
        initialAnchor.awaitingTeleport(),
        false,
        observed.input(),
        initialAnchor.attributes(),
        initialAnchor.pose(),
        initialAnchor.environment(),
        observed.clientTickRange(),
        initialAnchor.provenance(),
        initialAnchor.uncertaintyReasons());
    MovementEnvironment environment = movementEnvironmentOf(witnessPlayer);
    Context context = new Context(
        simulationTick,
        witnessPlayer,
        simulationEnvironmentFor(environment),
        witnessPlayer.attributes(),
        movementEffects(witnessPlayer),
        witnessPlayer.pose(),
        environment,
        witnessPlayer.pose() == Pose.SLEEPING,
        entityCollisionsFor(movement));
    return Optional.of(new Candidate(
        0,
        context,
        new Phase6Reachability.Provenance(
            0,
            0,
            simulationTick,
            "INITIAL_ANCHOR_ZERO_DELTA",
            "ANCHOR",
            "None",
            List.of("observed position matches the explicit initial authoritative anchor; no physics replay required"),
            1,
            List.of(),
            List.of())));
  }

  private static Optional<Candidate> frontierObservationWitness(
      Frontier frontier,
      MovementEvent movement,
      long simulationTick) {
    if (frontier.candidates().isEmpty() || simulationTick < 0) return Optional.empty();
    Player observed = movement.stateFrame().after();
    if (!movement.world().fullyKnown(playerCollisionBox(observed))) return Optional.empty();
    for (Candidate candidate : frontier.candidates()) {
      if (candidate.context().simulationTick() > simulationTick) continue;
      Player player = candidate.context().player();
      if (!matchesObserved(player, observed, movement.move())) continue;
      Context context = candidate.context().withTick(simulationTick);
      return Optional.of(new Candidate(
          candidate.id(),
          context,
          new Phase6Reachability.Provenance(
              candidate.id(),
              candidate.provenance().parentId(),
              simulationTick,
              "FRONTIER_ZERO_DELTA",
              candidate.provenance().worldBranch(),
              candidate.provenance().externalTransition(),
              List.of("trusted frontier already matches the observed state; no physics replay required"),
              candidate.provenance().mergedPathCount(),
              candidate.provenance().mergedParentIds(),
              candidate.provenance().assumptions())));
    }
    return Optional.empty();
  }

  private static Optional<Candidate> authorityObservationWitness(
      MovementEvent movement,
      long simulationTick) {
    Optional<AuthoritativeSnapshot> authority = movement.authority().snapshot();
    if (authority.isEmpty() || simulationTick < 0) return Optional.empty();
    AuthoritativeSnapshot snapshot = authority.get();
    if (snapshot.sequence() == 0L) return Optional.empty();
    long age = movement.event().serverTick() - snapshot.serverTick();
    if (age < 0 || age > 1L) return Optional.empty();
    Player authoritative = playerFromAuthority(snapshot.context());
    Player observed = movement.stateFrame().after();
    if (!Phase6Reachability.positionMatches(authoritative.position(), observed.position())) return Optional.empty();
    if (!movement.world().fullyKnown(playerCollisionBox(observed))) return Optional.empty();
    if (authoritative.onGround() != observed.onGround()) return Optional.empty();
    if (movement.move().onGround() != null && authoritative.onGround() != movement.move().onGround()) return Optional.empty();
    /*
     * PlayerContext intentionally has no server yaw/pitch. The movement packet's
     * orientation is therefore the observed client orientation and must not be
     * compared against the synthetic 0/0 values used by playerFromAuthority().
     * Likewise, the authority's client-tick watermark identifies its capture
     * boundary; it need not equal the later movement's reconstructed client tick.
     */
    float yaw = movement.move().yaw() == null ? observed.yaw() : movement.move().yaw();
    float pitch = movement.move().pitch() == null ? observed.pitch() : movement.move().pitch();
    Player witnessPlayer = new Player(
        observed.position(),
        authoritative.velocity(),
        yaw,
        pitch,
        movement.move().onGround() == null ? authoritative.onGround() : movement.move().onGround(),
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
        simulationTick,
        witnessPlayer,
        simulationEnvironmentFor(environment),
        witnessPlayer.attributes(),
        movementEffects(witnessPlayer),
        witnessPlayer.pose(),
        environment,
        witnessPlayer.pose() == Pose.SLEEPING,
        entityCollisionsFor(movement));
    return Optional.of(new Candidate(
        0,
        context,
        new Phase6Reachability.Provenance(
            0,
            snapshot.sequence(),
            simulationTick,
            "AUTHORITATIVE_ZERO_DELTA",
            "AUTHORITY",
            "None",
            List.of("observed position matches authoritative snapshot; no client physics step required"),
            1,
            List.of())));
  }

  private static boolean exceedsConservativeKinematicBound(
      Candidate candidate,
      Player observed,
      long targetTick) {
    long startTick = candidate.context().simulationTick();
    long ticks = targetTick - startTick;
    if (ticks < 0 || ticks > 2L) return false;
    double dx = observed.position().x() - candidate.context().player().position().x();
    double dy = observed.position().y() - candidate.context().player().position().y();
    double dz = observed.position().z() - candidate.context().player().position().z();
    double horizontalDistance = Math.hypot(dx, dz);
    double horizontalVelocity = Math.hypot(
        candidate.context().player().velocity().x(), candidate.context().player().velocity().z());
    double speedMultiplier = Math.max(1.0, candidate.context().effects().speedMultiplier());
    double configuredSpeed = Math.max(0.05, candidate.context().attributes().value()) * speedMultiplier;
    double horizontalPerTick = horizontalVelocity + configuredSpeed * 4.0 + 0.25;
    double verticalVelocity = Math.abs(candidate.context().player().velocity().y());
    double verticalPerTick = verticalVelocity + 1.0 + configuredSpeed + 0.25;
    double horizontalBound = horizontalPerTick * Math.max(1L, ticks);
    double verticalBound = verticalPerTick * Math.max(1L, ticks);
    return horizontalDistance > horizontalBound || Math.abs(dy) > verticalBound;
  }

  private static Set<Candidate> retargetRotation(
      Set<Candidate> candidates,
      Packets.Move move,
      int maximumCandidates) {
    if (candidates.isEmpty()) return Set.of();
    if (candidates.size() > maximumCandidates) return Set.of();
    LinkedHashSet<Candidate> result = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      Player player = candidate.context().player();
      float yaw = move.yaw() == null ? player.yaw() : move.yaw();
      float pitch = move.pitch() == null ? player.pitch() : move.pitch();
      Player rotated = new Player(
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
      Context context = new Context(
          candidate.context().simulationTick(),
          rotated,
          candidate.context().environment(),
          candidate.context().attributes(),
          candidate.context().effects(),
          candidate.context().pose(),
          candidate.context().movementEnvironment(),
          candidate.context().sleeping(),
          candidate.context().entityCollisions(),
          candidate.context().uncertainty());
      result.add(new Candidate(candidate.id(), context, candidate.provenance()));
    }
    return Set.copyOf(result);
  }

  private static boolean matchesObserved(Player candidate, Player observed, Packets.Move movement) {
    if (!Phase6Reachability.positionMatches(candidate.position(), observed.position())) return false;
    if (Float.compare(candidate.yaw(), observed.yaw()) != 0) return false;
    if (Float.compare(candidate.pitch(), observed.pitch()) != 0) return false;
    return movement.onGround() == null || candidate.onGround() == movement.onGround();
  }

  private static Candidate observedFlightCandidate(
      MovementEvent movement,
      Player observed,
      AuthoritativeSnapshot authority,
      long simulationTick,
      NavigableMap<Long, List<TimedInput>> inputByTick) {
    Packets.PlayerContext context = authority.context();
    Player authoritative = playerFromAuthority(context);
    Player baseline = new Player(
        observed.position(),
        authoritative.velocity(),
        observed.yaw(),
        observed.pitch(),
        observed.onGround(),
        authoritative.gamemode(),
        authoritative.effects(),
        authority.context().serverPosition().equals(observed.position())
            ? authoritative.awaitingTeleport()
            : OptionalInt.empty(),
        false,
        Optional.ofNullable(inputForSimulationTick(
            inputByTick,
            Math.max(0L, simulationTick),
            movement.event().packet().sequence()))
            .flatMap(CausalMovementPipeline::inputConstraintToAdvancedInput),
        authoritative.attributes(),
        authoritative.pose(),
        authoritative.environment(),
        State.TickRange.exact(Math.max(0L, simulationTick)),
        new State.Provenance(
            movement.event().packet().sequence(),
            movement.event().serverTick(),
            "AUTHORIZED_FLIGHT"),
        Set.of());
    MovementEnvironment movementEnvironment = movementEnvironmentOf(authoritative);
    Context contextValue = new Context(
        Math.max(0L, simulationTick),
        baseline,
        simulationEnvironmentFor(movementEnvironment),
        authoritative.attributes(),
        movementEffects(authoritative),
        authoritative.pose(),
        movementEnvironment,
        authoritative.pose() == Pose.SLEEPING,
        entityCollisionsFor(movement),
        Set.of());
    return new Candidate(
        0,
        contextValue,
        new Phase6Reachability.Provenance(
            0,
            authority.sequence(),
            Math.max(0L, simulationTick),
            "AUTHORIZED_FLIGHT",
            "AUTHORIZED",
            "None",
            List.of("authoritative server reports canFly=true and flying=true"),
            1,
            List.of()));
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

  private static SearchResult uncertainSearch(
      Set<Candidate> candidates,
      String reason) {
    return new SearchResult(
        Verdict.UNCERTAIN,
        candidates,
        0,
        Math.max(1, candidates.size()),
        0,
        0,
        1,
        0,
        List.of(reason));
  }

  private static Vec3 nearestAuthoritativePosition(
      List<AuthoritativeSnapshot> authorities,
      Player initialAnchor,
      long receivedNanos,
      long serverTick) {
    AuthoritativeSnapshot snapshot = authorities.stream()
        .filter(value -> value.receivedNanos() <= receivedNanos
            && value.serverTick() <= serverTick)
        .max(Comparator.comparingLong(AuthoritativeSnapshot::serverTick)
            .thenComparingLong(AuthoritativeSnapshot::receivedNanos))
        .orElse(null);
    if (snapshot != null) return snapshot.context().serverPosition();
    return initialAnchor == null ? Vec3.ZERO : initialAnchor.position();
  }

  private static float nearestAuthoritativeYaw(
      List<AuthoritativeSnapshot> authorities,
      Player initialAnchor,
      long receivedNanos) {
    /*
     * PlayerContext intentionally carries authoritative position/velocity and
     * movement context, but not a server yaw/pitch. A relative teleport rotation
     * therefore cannot be reconstructed exactly from server state. Use the
     * explicit anchor only; otherwise retain the prior client orientation and
     * let the surrounding timing/recovery logic mark the transition uncertain.
     */
    return initialAnchor == null ? 0.0f : initialAnchor.yaw();
  }

  private static float nearestAuthoritativePitch(
      List<AuthoritativeSnapshot> authorities,
      Player initialAnchor,
      long receivedNanos) {
    return initialAnchor == null ? 0.0f : initialAnchor.pitch();
  }

  private static Packets.PlayerContext contextFromAnchor(Player anchor) {
    MovementEnvironment environment = MovementEnvironment.dry(
        anchor.onGround(), false, false);
    return new Packets.PlayerContext(
        anchor.gamemode(),
        anchor.attributes(),
        anchor.effects(),
        anchor.pose(),
        environment,
        anchor.position(),
        anchor.velocity(),
        "creative".equals(anchor.gamemode())
            || "spectator".equals(anchor.gamemode()),
        false,
        anchor.pose() == Pose.SLEEPING,
        List.of());
  }

  private static Player playerFromAuthority(Packets.PlayerContext context) {
    State.Environment environment = context.movementEnvironment().fluid()
        == Phase5Mechanics.Fluid.WATER
        ? State.Environment.WATER
        : context.movementEnvironment().fluid() == Phase5Mechanics.Fluid.LAVA
            ? State.Environment.LAVA
            : context.movementEnvironment().climbable()
                ? State.Environment.CLIMBABLE
                : State.Environment.DRY;
    return new Player(
        context.serverPosition(),
        context.serverVelocity(),
        0.0f,
        0.0f,
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

  private static MovementEffects movementEffects(Player player) {
    return new MovementEffects(
        amplifier(player.effects(), "speed", "minecraft:speed"),
        amplifier(player.effects(), "slowness", "minecraft:slowness"),
        amplifier(player.effects(), "jump_boost", "minecraft:jump_boost"),
        amplifier(player.effects(), "levitation", "minecraft:levitation"),
        player.effects().keySet().stream().anyMatch(id ->
            id.equals("slow_falling") || id.equals("minecraft:slow_falling")));
  }

  private static int amplifier(Map<String, Integer> effects, String... ids) {
    for (String id : ids) {
      Integer value = effects.get(id);
      if (value != null) return value;
    }
    return -1;
  }

  private static MovementEnvironment environmentFromWorld(
      WorldSnapshot world,
      Player player) {
    Maths.Aabb box = Maths.Aabb.playerAt(player.position(), player.pose());
    boolean water = false;
    boolean lava = false;
    boolean climb = false;
    int minX = (int) Math.floor(box.minX());
    int maxX = (int) Math.floor(Math.nextDown(box.maxX()));
    int minY = (int) Math.floor(box.minY());
    int maxY = (int) Math.floor(Math.nextDown(box.maxY()));
    int minZ = (int) Math.floor(box.minZ());
    int maxZ = (int) Math.floor(Math.nextDown(box.maxZ()));
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          var state = world.blockAtOrNull(x, y, z);
          if (state == null) continue;
          if (state.variant() == dev.phantom.ac.world.BlockState.Variant.LADDER) climb = true;
          var fluid = dev.phantom.ac.world.v12111.BlockCatalogue12111.fluid(state);
          if (fluid.type() == dev.phantom.ac.world.FluidState.Type.WATER) water = true;
          if (fluid.type() == dev.phantom.ac.world.FluidState.Type.LAVA) lava = true;
        }
      }
    }
    if (water) return MovementEnvironment.vanillaWater(
        player.onGround(), false, false, player.pose() == Pose.SWIMMING);
    if (lava) return MovementEnvironment.vanillaLava(
        player.onGround(), false, false);
    if (climb) return MovementEnvironment.vanillaClimbable(
        player.onGround(), false, false);
    return MovementEnvironment.dry(player.onGround(), false, false);
  }

  private static Simulation.Environment simulationEnvironmentFor(MovementEnvironment env) {
    return switch (env.fluid()) {
      case WATER -> Simulation.Environment.WATER;
      case LAVA -> Simulation.Environment.LAVA;
      case NONE -> env.climbable()
          ? Simulation.Environment.CLIMBABLE
          : Simulation.Environment.DRY;
    };
  }

  private static double distance(Vec3 a, Vec3 b) {
    if (a == null || b == null) return Double.NaN;
    double dx = a.x() - b.x();
    double dy = a.y() - b.y();
    double dz = a.z() - b.z();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }
}