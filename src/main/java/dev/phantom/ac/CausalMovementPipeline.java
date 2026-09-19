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
      Packets.PlayerContext context) {
    public AuthoritativeSnapshot {
      if (sequence < 0 || receivedNanos < 0 || serverTick < 0) {
        throw new IllegalArgumentException("invalid authoritative snapshot provenance");
      }
      Objects.requireNonNull(context);
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
    NavigableMap<Long, InputConstraint> inputByTick = collectInputs(timeline, timing);
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
              authorities,
              initialAnchor,
              initialAnchorReceivedNanos);
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
              : ""));
      trace.add("WORLD source=" + (movement.liveWorldUsed() ? "acknowledged-live" : "timeline")
          + " chunks=" + movement.world().loadedChunks().size());

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
          || sameExplicitClientTick
          || overlappingGenerationWindow) {
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
        rootedFromLocalAuthority = preferLocalAuthoritativeRoot;
        if (preferLocalAuthoritativeRoot) {
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
       * Once a causal frontier exists, world coverage must include the simulated
       * starting positions as well as the observed destination. The very first
       * position observation is allowed to establish the baseline frontier even
       * when no client-world chunks have arrived yet; later movement cannot safely
       * be called IMPOSSIBLE from an incomplete world replica.
       */
      if (previousPositionPacketTick >= 0
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
        if (rootedFromLocalAuthority) {
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
            context));
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

  private static NavigableMap<Long, InputConstraint> collectInputs(
      Timeline.Snapshot timeline,
      Phase7Timing.Reconstruction timing) {
    NavigableMap<Long, InputConstraint> result = new TreeMap<>();
    for (Timeline.Event event : timeline.events()) {
      if (!(event.packet().packet() instanceof Packets.ClientInput input)) continue;
      Phase7Timing.EventTiming eventTiming =
          timing.timingFor(event.packet().sequence()).orElse(null);
      if (eventTiming == null || !eventTiming.inputClientTicks().isExact()
          || event.packet().flags().contains(Packets.PacketFlag.DUPLICATE)
          || event.packet().flags().contains(Packets.PacketFlag.OUT_OF_ORDER)
          || event.packet().flags().contains(Packets.PacketFlag.SEQUENCE_GAP)) {
        continue;
      }
      result.put(eventTiming.inputClientTicks().min(), InputConstraint.fromClientInput(input));
    }
    return Collections.unmodifiableNavigableMap(new TreeMap<>(result));
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
      if (eventTiming == null || !eventTiming.simulationClientTicks().isExact()) {
        unmodeledSequences.add(event.packet().sequence());
        continue;
      }

      long tick = eventTiming.simulationClientTicks().min();
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
      result.computeIfAbsent(tick, ignored -> new ArrayList<>()).add(transition);
    }
    Map<Long, List<ExternalTransition>> immutable = new HashMap<>();
    for (Map.Entry<Long, List<ExternalTransition>> entry : result.entrySet()) {
      immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
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
      NavigableMap<Long, InputConstraint> inputs,
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
      Advance one = advanceTo(
          Set.of(root.get()),
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
      NavigableMap<Long, InputConstraint> inputs,
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
      NavigableMap<Long, InputConstraint> inputs,
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
          Map.Entry<Long, InputConstraint> heldInput = inputs.floorEntry(localTick);
          InputConstraint input = heldInput == null
              ? InputConstraint.any()
              : heldInput.getValue();

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

          if (result.verdict() == Verdict.POSSIBLE) {
            next.addAll(result.candidates());
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

  private static WorldSnapshot worldForTick(
      long simulationTick,
      long targetTick,
      World.VisibilityHistory history,
      MovementEvent movement) {
    /*
     * A live acknowledged replica is safe only for the final step when no
     * world mutation was observed after this movement. Historical/intermediate
     * simulation always comes from the ticked client-visible world history.
     */
    if (movement.liveWorldUsed()
        && simulationTick == Math.max(0L, targetTick - 1L)) {
      return movement.world();
    }
    return history.statesAt(simulationTick);
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
    Player anchor = withClientRotation(authoritative, observedBefore.yaw(), observedBefore.pitch());
    long rootTick = Math.max(0L, target + (snapshot.serverTick() - movement.event().serverTick()));
    if (rootTick < 0 || target - rootTick > Phase6Reachability.MAX_HORIZON_TICKS) {
      return Optional.empty();
    }
    MovementEnvironment movementEnvironment = movementEnvironmentOf(anchor);
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
        .filter(snapshot -> movement.event().serverTick() - snapshot.serverTick() <= maxServerTickAge);
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
      List<AuthoritativeSnapshot> authorities,
      Player initialAnchor,
      long initialAnchorReceivedNanos) {
    long sequence = movement.packet().sequence();
    long received = movement.packet().receivedNanos();
    long serverTick = movement.serverTick();

    Optional<AuthoritativeSnapshot> previous = authorities.stream()
        .filter(snapshot -> snapshot.sequence() < sequence)
        .filter(snapshot -> snapshot.receivedNanos() <= received)
        .filter(snapshot -> snapshot.serverTick() < serverTick)
        .filter(snapshot -> serverTick - snapshot.serverTick() <= 1L)
        .filter(snapshot -> !isPlaceholderAuthority(snapshot, initialAnchor))
        .max(Comparator.comparingLong(AuthoritativeSnapshot::serverTick)
            .thenComparingLong(AuthoritativeSnapshot::receivedNanos)
            .thenComparingLong(AuthoritativeSnapshot::sequence));
    if (previous.isPresent()) return previous;

    /*
     * Backward compatibility for very early captures that have exactly one
     * same-tick authoritative sample and no preceding per-tick sample. This can
     * still establish a first root, but it is never preferred once a preceding
     * server-tick snapshot exists.
     */
    Optional<AuthoritativeSnapshot> sameTick = authorities.stream()
        .filter(snapshot -> snapshot.sequence() < sequence)
        .filter(snapshot -> snapshot.receivedNanos() <= received)
        .filter(snapshot -> snapshot.serverTick() == serverTick)
        .filter(snapshot -> !isPlaceholderAuthority(snapshot, initialAnchor))
        .max(Comparator.comparingLong(AuthoritativeSnapshot::receivedNanos)
            .thenComparingLong(AuthoritativeSnapshot::sequence));
    if (sameTick.isPresent()) return sameTick;

    /*
     * Very early captures may have no usable server-side context packet yet.
     * Retain the immutable join anchor as a final fallback. This also covers
     * legacy/test PlayerContext packets constructed through the compact overload
     * whose position and velocity intentionally default to zero.
     */
    if (initialAnchor != null
        && !initialAnchor.uncertain()
        && initialAnchorReceivedNanos >= 0
        && received >= initialAnchorReceivedNanos) {
      return Optional.of(new AuthoritativeSnapshot(
          0L,
          initialAnchorReceivedNanos,
          Math.max(0L, serverTick - 1L),
          contextFromAnchor(initialAnchor)));
    }

    return Optional.empty();
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
      // PlayerContext has no server yaw/pitch. Rotation is a client-controlled
      // movement input, so retain the immediately preceding client orientation
      // while taking position/velocity/ground/context exclusively from authority.
      anchor = withClientRotation(authoritative, observedBefore.yaw(), observedBefore.pitch());
      rootTick = Math.max(0L, target - 1L);
    }

    if (target < rootTick || target - rootTick > Phase6Reachability.MAX_HORIZON_TICKS) {
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
      NavigableMap<Long, InputConstraint> inputByTick) {
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
        Optional.ofNullable(inputByTick.floorEntry(Math.max(0L, simulationTick)))
            .map(Map.Entry::getValue)
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