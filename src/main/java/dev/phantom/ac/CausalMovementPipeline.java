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
    Map<Long, InputConstraint> inputByTick = collectInputs(timeline, timing);
    Set<Long> unmodeledExternalSequences = new HashSet<>();
    Map<Long, List<ExternalTransition>> externalByTick =
        collectExternalTransitions(
            timeline, timing, authorities, initialAnchor, unmodeledExternalSequences);
    World.VisibilityHistory worldHistory = World.fromTimeline(timeline);

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
      boolean futureWorldMutation = hasWorldMutationAfter(timeline, event.packet().sequence());
      boolean useLiveWorld = liveWorld != null
          && move.position() != null
          && !futureWorldMutation;
      WorldSnapshot world = useLiveWorld
          ? liveWorld
          : worldHistory.statesAt(event.serverTick());

      boolean chronologyClean = !containsChronologyProblem(event.packet().flags());
      movements.add(new MovementEvent(
          event,
          move,
          eventTiming,
          stateFrame,
          authority,
          world,
          useLiveWorld,
          chronologyClean));
    }

    Frontier frontier = Frontier.empty();
    List<Phase8MovementValidation.Result> results = new ArrayList<>();
    List<Frame> frames = new ArrayList<>();
    long previousPositionPacketTick = -1L;
    Long previousExplicitClientTick = null;
    boolean recoveryRequired = false;
    long lastAmbiguitySequence = -1L;
    boolean haveAuthoritativeSeed = initialAnchor != null && !initialAnchor.uncertain();
    Map<Long, Phase7Timing.EventTiming> timingsBySequence = new HashMap<>();
    for (Phase7Timing.Frame frame : timing.frames()) {
      timingsBySequence.put(frame.timing().sequence(), frame.timing());
    }

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

      boolean hasUnmodeledExternalBefore = unmodeledExternalSequences.stream()
          .anyMatch(transitionSequence -> transitionSequence < sequence);
      if (hasUnmodeledExternalBefore) {
        uncertainty.add("an authoritative velocity/teleport transition before this movement could not be assigned to an exact client simulation tick");
        recoveryRequired = true;
      }

      String replayReference = "causal:phase8:" + playerId + ":" + sequence;
      String worldReference = (movement.liveWorldUsed() ? "live-ack:" : "timeline:")
          + "serverTick=" + serverTick
          + ":chunks=" + movement.world().loadedChunks().size();

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
      if ((previousPositionPacketTick >= 0
              && eventTiming.simulationClientTicks().isExact()
              && movementTick == previousPositionPacketTick)
          || sameExplicitClientTick) {
        uncertainty.add("multiple position-bearing movement packets occurred in one client tick; sub-tick motion is not modeled");
        lastAmbiguitySequence = sequence;
        recoveryRequired = true;
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

      if (!haveAuthoritativeSeed && frontier.candidates().isEmpty()) {
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

      if (recoveryRequired) {
        Optional<Frontier> reanchored = tryAuthoritativeReanchor(
            movement,
            timingsBySequence,
            maximumCandidates);
        if (reanchored.isPresent()) {
          frontier = reanchored.get();
          recoveryRequired = false;
          uncertainty.add("prediction re-anchored only from a timestamped authoritative server snapshot");
          trace.add("RECOVERY authoritative re-anchor accepted");
        } else {
          uncertainty.add("prediction frontier was invalidated by ambiguous chronology and no exact authoritative re-anchor is available");
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
      }

      if (frontier.candidates().isEmpty()) {
        if (initialAnchor == null || initialAnchor.uncertain()) {
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
            inputByTick,
            movement,
            maximumCandidates);
        if (root.isEmpty()) {
          uncertainty.add("authoritative anchor is outside known world coverage");
          SearchResult uncertain = uncertainSearch(Set.of(), String.join("; ", uncertainty));
          results.add(Phase8MovementValidation.validate(
              playerId, serverTick, observedBefore, observedAfter, movement.world(),
              worldReference, sync, assumptions, uncertain, replayReference, false));
          frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
              assumptions, uncertainty, trace));
          continue;
        }
        frontier = new Frontier(Set.of(root.get()), -1L, true);
        trace.add("ROOT authoritative anchor=" + initialAnchor.position());
      }

      Optional<Advance> advanced;
      if (!eventTiming.simulationClientTicks().isExact()) {
        advanced = Optional.ofNullable(advanceAcrossTimingRange(
            frontier,
            eventTiming.simulationClientTicks().min(),
            eventTiming.simulationClientTicks().max(),
            inputByTick,
            externalByTick,
            worldHistory,
            movement,
            maximumCandidates));
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

      // The candidate frontier advances only through a POSSIBLE observation.
      // IMPOSSIBLE/UNCERTAIN evidence does not turn the client's observed state
      // into a new trusted baseline, and therefore cannot permanently poison
      // later validation.
      if (validation.verdict() == Phase8MovementValidation.Verdict.POSSIBLE) {
        Set<Candidate> matching = new LinkedHashSet<>();
        for (Candidate candidate : reachable) {
          if (matchesObserved(candidate.context().player(), observedAfter)) {
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
          if (movement.move().clientTick() != null) {
            previousExplicitClientTick = movement.move().clientTick();
          }
          trace.add("MATCHING candidates=" + matching.size()
              + " frontierTick=" + resultingTick);
        }
      } else if (validation.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE) {
        previousPositionPacketTick = movementTick;
        if (movement.move().clientTick() != null) {
          previousExplicitClientTick = movement.move().clientTick();
        }
        trace.add("EVIDENCE REACHABILITY_CONTRADICTION");
      } else {
        recoveryRequired = recoveryRequired || eventTiming.uncertain();
        trace.add("EVIDENCE UNCERTAIN " + advance.reasons());
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

      if (lastAmbiguitySequence >= 0 && sequence > lastAmbiguitySequence
          && movement.authority().quality() == AuthorityQuality.EXACT
          && eventTiming.simulationClientTicks().isExact()) {
        recoveryRequired = false;
      }

      frames.add(frame(sequence, event, eventTiming, movement, observedBefore, observedAfter,
          assumptions, uncertainty, trace));
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
        + " ground=" + movement.move().onGround());
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

  private static Map<Long, InputConstraint> collectInputs(
      Timeline.Snapshot timeline,
      Phase7Timing.Reconstruction timing) {
    Map<Long, InputConstraint> result = new HashMap<>();
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
    return Map.copyOf(result);
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

  private static Advance advanceAcrossTimingRange(
      Frontier frontier,
      long earliest,
      long latest,
      Map<Long, InputConstraint> inputs,
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

    if (!reasons.isEmpty()) exhaustive = false;
    return new Advance(union, List.copyOf(reasons), exhaustive);
  }

  private static Advance advanceTo(
      Set<Candidate> start,
      long targetTick,
      Map<Long, InputConstraint> inputs,
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
          InputConstraint input = inputs.getOrDefault(
              localTick, InputConstraint.any());

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
      return new Advance(Set.of(), List.copyOf(reasons), false);
    }
    return new Advance(
        Set.copyOf(union),
        List.copyOf(reasons),
        reasons.isEmpty());
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

  private static Optional<Candidate> rootCandidate(
      Player anchor,
      Map<Long, InputConstraint> inputs,
      MovementEvent movement,
      int maximumCandidates) {
    long target = movement.timing().simulationClientTicks().min();
    if (target < 0 || target > Phase6Reachability.MAX_HORIZON_TICKS) return Optional.empty();

    World.VisibilityHistory history = World.fromTimeline(
        new Timeline.Snapshot(List.of(movement.event())));
    WorldSnapshot world = history.statesAt(Math.max(0, target));
    Context context = new Context(
        0,
        anchor,
        Simulation.Environment.DRY,
        anchor.attributes(),
        movementEffects(anchor),
        anchor.pose(),
        MovementEnvironment.dry(anchor.onGround(), false, false),
        anchor.pose() == Pose.SLEEPING,
        EntityCollisions.NONE_TRACKED);
    return Optional.of(new Candidate(
        0,
        context,
        new Phase6Reachability.Provenance(
            0,
            -1,
            0,
            "ROOT_AUTHORITATIVE",
            "ROOT",
            "None",
            List.of("explicit authoritative server anchor"),
            1,
            List.of())));
  }

  private static Optional<Frontier> tryAuthoritativeReanchor(
      MovementEvent movement,
      Map<Long, Phase7Timing.EventTiming> timingsBySequence,
      int maximumCandidates) {
    if (movement.authority().quality() != AuthorityQuality.EXACT
        || movement.authority().snapshot().isEmpty()) {
      return Optional.empty();
    }
    Phase7Timing.EventTiming authorityTiming =
        timingsBySequence.get(movement.authority().snapshot().get().sequence());
    if (authorityTiming == null
        || !authorityTiming.simulationClientTicks().isExact()) {
      return Optional.empty();
    }

    Packets.PlayerContext context = movement.authority().snapshot().get().context();
    Player player = playerFromAuthority(context);
    long tick = authorityTiming.simulationClientTicks().min();
    WorldSnapshot world = movement.world();
    Maths.Aabb box = Maths.Aabb.playerAt(player.position(), context.pose());
    if (!world.fullyKnown(new dev.phantom.ac.geometry.BlockBox(
        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()))) {
      return Optional.empty();
    }

    InputConstraint input = InputConstraint.any();
    Candidate candidate = new Candidate(
        0,
        new Context(
            tick,
            player,
            simulationEnvironmentFor(context.movementEnvironment()),
            context.attributes(),
            movementEffects(player),
            context.pose(),
            context.movementEnvironment(),
            context.sleeping(),
            EntityCollisions.of(context.entityBoxes())),
        new Phase6Reachability.Provenance(
            0,
            -1,
            tick,
            "ROOT_AUTHORITATIVE_REANCHOR",
            "AUTHORITY",
            "None",
            List.of("timestamped authoritative server snapshot after chronology ambiguity"),
            1,
            List.of()));
    return Optional.of(new Frontier(Set.of(candidate), tick - 1L, true));
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
    return timeline.events().stream()
        .anyMatch(event -> event.packet().sequence() > sequence
            && event.packet().packet().mutatesWorld());
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

  private static boolean matchesObserved(Player candidate, Player observed) {
    return candidate.position().equals(observed.position())
        && Float.compare(candidate.yaw(), observed.yaw()) == 0
        && Float.compare(candidate.pitch(), observed.pitch()) == 0;
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
