package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.ExternalTransition;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.Simulation.Attributes;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;

/** Canonical live Phase 8 orchestration over the authoritative Phase 1-7 engines. */
public final class Phase8LiveValidation {
  private Phase8LiveValidation() {}

  public record Report(List<Phase8MovementValidation.Result> results, int movementObservations,
                       int possible, int uncertain, int impossible) {
    public Report { results = List.copyOf(results); }
  }

  record ParentAggregation(SearchResult result, Set<Candidate> candidates, boolean timingOffsetsExhaustive) {
    ParentAggregation { Objects.requireNonNull(result); candidates = Set.copyOf(candidates); }
  }

  /**
   * Aggregates timing/parent branches by their actual per-offset evidence. A
   * TimingSearchResult may use UNCERTAIN as its internal aggregate when some
   * offsets were impossible; that must not erase a proof that every offset was
   * exhaustively evaluated. Only an actually unevaluable offset makes the Phase 8
   * aggregate UNCERTAIN.
   */
  static ParentAggregation aggregateParentSearches(List<Phase6Reachability.TimingSearchResult> searches,
                                                   int maximumCandidates, long timingSpan) {
    Objects.requireNonNull(searches);
    Contracts.requireCandidateBudget(maximumCandidates);
    if (timingSpan < 1) throw new IllegalArgumentException("timing span must be positive");

    Set<Candidate> nextAll = new LinkedHashSet<>();
    boolean hasPossible = false;
    boolean hasImpossible = false;
    boolean hasUncertain = false;
    boolean timingExhaustive = true;
    int simulatedTicks = 0, peakCandidates = 0, merged = 0;
    int nonExhaustiveWorldBranches = 0, uncertainTransitions = 0, provenanceMerges = 0;
    LinkedHashSet<String> reasons = new LinkedHashSet<>();

    for (Phase6Reachability.TimingSearchResult search : searches) {
      Objects.requireNonNull(search);
      boolean offsetPossible = false;
      boolean offsetUncertain = false;
      boolean offsetImpossible = true;
      int offsetResults = 0;
      for (SearchResult perOffset : search.byFirstTick().values()) {
        offsetResults++;
        simulatedTicks = Math.max(simulatedTicks, perOffset.simulatedTicks());
        peakCandidates = Math.max(peakCandidates, perOffset.peakCandidates());
        merged += perOffset.mergedStates();
        nonExhaustiveWorldBranches += perOffset.nonExhaustiveWorldBranches();
        uncertainTransitions += perOffset.uncertainTransitions();
        provenanceMerges += perOffset.provenanceMerges();
        switch (perOffset.verdict()) {
          case POSSIBLE -> { offsetPossible = true; offsetImpossible = false; nextAll.addAll(perOffset.candidates()); }
          case IMPOSSIBLE -> { /* this offset is deterministically exhausted */ }
          case UNCERTAIN -> { offsetUncertain = true; offsetImpossible = false; nextAll.addAll(perOffset.candidates()); }
        }
      }

      boolean evaluatedAllOffsets = search.evaluatedOffsets() == timingSpan
          && search.skippedOffsets() == 0 && offsetResults == timingSpan;
      if (!evaluatedAllOffsets || offsetUncertain) timingExhaustive = false;

      if (offsetUncertain) {
        hasUncertain = true;
      } else if (offsetPossible) {
        hasPossible = true;
      } else if (evaluatedAllOffsets && offsetImpossible) {
        hasImpossible = true;
      } else {
        hasUncertain = true;
      }
      reasons.addAll(search.reasons());

      if (nextAll.size() > maximumCandidates) {
        timingExhaustive = false;
        hasUncertain = true;
        // Do not expose a partial subset as a complete legitimate state space.
        nextAll.clear();
        reasons.add("combined Phase 6 timing candidate budget exceeded; provisional candidates are not safe to continue");
        break;
      }
    }

    Phase6Reachability.Verdict verdict;
    if (hasUncertain) verdict = Phase6Reachability.Verdict.UNCERTAIN;
    else if (hasPossible && !nextAll.isEmpty()) verdict = Phase6Reachability.Verdict.POSSIBLE;
    else if (hasImpossible) verdict = Phase6Reachability.Verdict.IMPOSSIBLE;
    else {
      verdict = Phase6Reachability.Verdict.UNCERTAIN;
      timingExhaustive = false;
      reasons.add("no parent branch produced an evaluable result");
    }
    if (verdict == Phase6Reachability.Verdict.IMPOSSIBLE && reasons.isEmpty())
      reasons.add("all exhaustively modeled parent branches are impossible");

    SearchResult aggregate = new SearchResult(verdict, Set.copyOf(nextAll), simulatedTicks,
        peakCandidates, merged, nonExhaustiveWorldBranches, uncertainTransitions, provenanceMerges,
        List.copyOf(reasons));
    return new ParentAggregation(aggregate, nextAll, timingExhaustive);
  }

  public static Report analyze(String playerId, Timeline.Snapshot timeline,
                               int maximumCandidates, Phase7Timing.Config timingConfig) {
    Objects.requireNonNull(playerId); Objects.requireNonNull(timeline); Objects.requireNonNull(timingConfig);
    Contracts.requireCandidateBudget(maximumCandidates);
    World.VisibilityHistory history = World.fromTimeline(timeline);
    Phase7Timing.Reconstruction timing = Phase7Timing.reconstruct(timeline, timingConfig);
    Phase6Reachability engine = new Phase6Reachability(new Vanilla12111RichPhysics());
    State.Reconstruction observedStates = reconstructObservedStates(timeline);
    Map<Long, State.StateFrame> observedBySequence = observedStatesBySequence(observedStates);
    Map<Long, List<ExternalTransition>> externalByClientTick = externalTransitions(timeline, timing);

    Set<Candidate> candidates = Set.of();
    Continuation continuation = Continuation.UNANCHORED;
    Integer pendingTeleportId = null;
    InputConstraint currentInput = InputConstraint.any();
    List<Phase8MovementValidation.Result> results = new ArrayList<>();
    int movements = 0;

    for (Timeline.Event event : timeline.events()) {
      Packets.NormalizedPacket normalized = event.packet();
      Packets.Packet packet = normalized.packet();
      if (packet instanceof Packets.ClientInput input) {
        if (!normalized.flags().contains(Packets.PacketFlag.DUPLICATE)) currentInput = InputConstraint.fromClientInput(input);
        continue;
      }
      if (packet instanceof Packets.Teleport teleport) {
        pendingTeleportId = teleport.id(); candidates = Set.of(); continuation = Continuation.WAITING_FOR_TELEPORT_CONFIRM; continue;
      }
      if (packet instanceof Packets.TeleportConfirm confirm) {
        if (pendingTeleportId != null && pendingTeleportId == confirm.id()) {
          pendingTeleportId = null; candidates = Set.of(); continuation = Continuation.UNANCHORED;
        }
        continue;
      }
      if (!(packet instanceof Packets.Move move) || move.position() == null) continue;
      movements++;

      Phase7Timing.EventTiming eventTiming = timing.timingFor(normalized.sequence()).orElse(null);
      State.StateFrame stateFrame = observedBySequence.get(normalized.sequence());
      WorldSnapshot world = history.statesAt(event.serverTick());
      if (eventTiming == null || stateFrame == null) {
        results.add(anchorUncertain(playerId, event.serverTick(), stateFrame, world,
            "missing Phase 7 timing or Phase 2 state frame", "live:phase8:" + normalized.sequence()));
        continue;
      }
      Player prior = stateFrame.before(), observed = stateFrame.after();
      Validation.SyncWindow sync = Phase7Timing.toPhase6Window(eventTiming);
      String replayReference = "live:phase8:" + playerId + ":" + normalized.sequence();
      List<String> inputAssumptions = List.of("client-input=" + currentInput,
          "input-constraint-candidates=" + currentInput.enumerate().size(),
          "timing-offsets=" + sync.earliestClientTick() + ".." + sync.latestClientTick());
      String worldReference = "timeline-world:tick=" + event.serverTick() + ":chunks=" + world.loadedChunks().size();

      boolean chronologyUncertain = normalized.flags().stream().anyMatch(flag ->
          flag == Packets.PacketFlag.DUPLICATE || flag == Packets.PacketFlag.OUT_OF_ORDER
              || flag == Packets.PacketFlag.BEFORE_CAPTURE_EPOCH);
      if (chronologyUncertain) {
        SearchResult uncertain = new SearchResult(Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 0,
            candidates.size(), 0, 0, 1, 0,
            List.of("movement record chronology is not exhaustive: " + normalized.flags()));
        results.add(Phase8MovementValidation.validate(playerId, event.serverTick(), prior, observed,
            world, worldReference, sync, inputAssumptions, uncertain, replayReference));
        continue;
      }
      if (continuation == Continuation.WAITING_FOR_TELEPORT_CONFIRM) {
        SearchResult uncertain = new SearchResult(Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 0,
            candidates.size(), 0, 0, 1, 0,
            List.of("movement observed before the explicit teleport correction was confirmed"));
        results.add(Phase8MovementValidation.validate(playerId, event.serverTick(), prior, observed,
            world, worldReference, sync, inputAssumptions, uncertain, replayReference));
        continue;
      }
      if (continuation == Continuation.UNANCHORED) {
        SearchResult anchor = new SearchResult(Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 0, 0, 0, 0, 0, 0,
            List.of("first movement observation establishes the Phase 6 replay anchor"));
        results.add(Phase8MovementValidation.validate(playerId, event.serverTick(), prior, observed,
            world, worldReference, sync, inputAssumptions, anchor, replayReference));
        Player safeObserved = simulationSafe(observed);
        if (safeObserved.uncertain()) continue;
        candidates = Set.of(new Candidate(0,
            anchorContext(safeObserved, world, currentInput, Math.max(0, sync.earliestClientTick())),
            new Phase6Reachability.Provenance(0, -1, event.serverTick(), "ROOT", "ROOT", "None",
                List.of("live movement anchor"), 1, List.of())));
        continuation = Continuation.ACTIVE;
        continue;
      }
      if (continuation == Continuation.UNCERTAIN_EMPTY || continuation == Continuation.IMPOSSIBLE) {
        Phase6Reachability.Verdict terminalVerdict = continuation == Continuation.IMPOSSIBLE
            ? Phase6Reachability.Verdict.IMPOSSIBLE : Phase6Reachability.Verdict.UNCERTAIN;
        List<String> terminalReasons = continuation == Continuation.IMPOSSIBLE
            ? List.of("existing Phase 6 reachable candidate set was exhaustively eliminated by an observed movement")
            : List.of("Phase 6 candidate chain is unavailable because prior validation was uncertain");
        SearchResult terminal = new SearchResult(terminalVerdict, Set.of(), 0, 0, 0, 0,
            terminalVerdict == Phase6Reachability.Verdict.UNCERTAIN ? 1 : 0, 0, terminalReasons);
        results.add(Phase8MovementValidation.validate(playerId, event.serverTick(), prior, observed,
            world, worldReference, sync, inputAssumptions, terminal, replayReference,
            terminalVerdict != Phase6Reachability.Verdict.UNCERTAIN));
        continue;
      }

      long earliest = Math.max(0, sync.earliestClientTick());
      long latest = Math.max(earliest, sync.latestClientTick());
      long timingSpan = latest - earliest + 1;
      if (timingSpan > timingConfig.maxTimingCandidates()) {
        SearchResult uncertain = new SearchResult(Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 0, candidates.size(), 0, 0, 1, 0,
            List.of("Phase 7 timing window exceeds the configured exhaustive envelope"));
        continuation = Continuation.UNCERTAIN_EMPTY;
        results.add(Phase8MovementValidation.validate(playerId, event.serverTick(), prior, observed,
            world, worldReference, sync, inputAssumptions, uncertain, replayReference));
        continue;
      }

      List<Phase6Reachability.TimingSearchResult> parentSearches = new ArrayList<>();
      for (Candidate parent : candidates) {
        Phase6Reachability.Context prepared = withObservedEnvironment(parent.context(), world, currentInput, earliest);
        boolean worldExhaustive = worldCoverageExhaustive(world, parent);
        parentSearches.add(engine.searchWithinTimingWindow(prepared, earliest, latest, false,
            List.of(currentInput),
            tick -> List.of(new WorldBranch("client-visible-" + event.serverTick(), world, worldExhaustive,
                worldExhaustive ? "historical client-visible world covers the parent collision volume"
                    : "Phase 4 visibility does not fully cover the parent collision volume; unknown cells remain unevaluable")),
            tick -> externalByClientTick.getOrDefault(tick, List.of(new Phase6Reachability.None())), maximumCandidates));
      }
      ParentAggregation aggregated = aggregateParentSearches(parentSearches, maximumCandidates, timingSpan);
      SearchResult reachable = aggregated.result();
      results.add(Phase8MovementValidation.validate(playerId, event.serverTick(), prior, observed, world, worldReference,
          sync, inputAssumptions, reachable, replayReference, aggregated.timingOffsetsExhaustive()));

      switch (reachable.verdict()) {
        case POSSIBLE -> {
          Set<Candidate> matching = new LinkedHashSet<>();
          for (Candidate candidate : aggregated.candidates()) if (matchesObserved(candidate.context().player(), observed)) matching.add(candidate);
          candidates = Set.copyOf(matching);
          continuation = matching.isEmpty() ? Continuation.IMPOSSIBLE : Continuation.ACTIVE;
        }
        case UNCERTAIN -> {
          candidates = aggregated.candidates();
          continuation = candidates.isEmpty() ? Continuation.UNCERTAIN_EMPTY : Continuation.ACTIVE;
        }
        case IMPOSSIBLE -> { candidates = Set.of(); continuation = Continuation.IMPOSSIBLE; }
      }
    }
    int possible = (int) results.stream().filter(r -> r.verdict() == Phase8MovementValidation.Verdict.POSSIBLE).count();
    int uncertain = (int) results.stream().filter(r -> r.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN).count();
    int impossible = (int) results.stream().filter(r -> r.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE).count();
    return new Report(results, movements, possible, uncertain, impossible);
  }

  private enum Continuation { UNANCHORED, ACTIVE, UNCERTAIN_EMPTY, IMPOSSIBLE, WAITING_FOR_TELEPORT_CONFIRM }

  private static boolean worldCoverageExhaustive(WorldSnapshot world, Candidate parent) {
    Maths.Aabb box = Maths.Aabb.playerAt(parent.context().player().position(), parent.context().pose());
    return world.fullyKnown(new dev.phantom.ac.geometry.BlockBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
  }

  private static boolean matchesObserved(Player candidate, Player observed) {
    return candidate.position().equals(observed.position())
        && Float.compare(candidate.yaw(), observed.yaw()) == 0
        && Float.compare(candidate.pitch(), observed.pitch()) == 0
        && candidate.onGround() == observed.onGround();
  }

  private static Player simulationSafe(Player player) {
    EnumSet<State.UncertaintyReason> reasons = EnumSet.noneOf(State.UncertaintyReason.class);
    reasons.addAll(player.uncertaintyReasons()); reasons.remove(State.UncertaintyReason.UNKNOWN_CLIENT_TICK);
    boolean uncertain = !reasons.isEmpty();
    if (!uncertain && !player.uncertain()) return player;
    return new Player(player.position(), player.velocity(), player.yaw(), player.pitch(), player.onGround(), player.gamemode(),
        player.effects(), player.awaitingTeleport(), uncertain, player.input(), player.attributes(), player.pose(), player.environment(),
        player.clientTickRange(), player.provenance(), reasons);
  }

  private static Phase8MovementValidation.Result anchorUncertain(String playerId, long serverTick, State.StateFrame frame,
                                                                   WorldSnapshot world, String reason, String replayReference) {
    Player state = frame == null ? Player.initial(Maths.Vec3.ZERO) : frame.after();
    Player prior = frame == null ? state : frame.before();
    Validation.SyncWindow sync = new Validation.SyncWindow(Math.max(0, serverTick), Math.max(0, serverTick), true, List.of(reason));
    SearchResult uncertain = new SearchResult(Phase6Reachability.Verdict.UNCERTAIN, Set.of(), 0, 0, 0, 0, 1, 0, List.of(reason));
    return Phase8MovementValidation.validate(playerId, serverTick, prior, state, world, "timeline-world:tick=" + serverTick,
        sync, List.of("input unavailable"), uncertain, replayReference);
  }

  private static State.Reconstruction reconstructObservedStates(Timeline.Snapshot timeline) {
    for (Timeline.Event event : timeline.events()) if (event.packet().packet() instanceof Packets.Move move && move.position() != null)
      return State.reconstruct(State.Seed.serverAnchor(Player.initial(move.position())), timeline);
    return new State.Reconstruction(List.of());
  }

  private static Map<Long, State.StateFrame> observedStatesBySequence(State.Reconstruction reconstruction) {
    Map<Long, State.StateFrame> map = new HashMap<>();
    for (State.StateFrame frame : reconstruction.frames()) map.put(frame.event().packet().sequence(), frame);
    return map;
  }

  private static Phase6Reachability.Context anchorContext(Player player, WorldSnapshot world, InputConstraint input, long tick) {
    MovementEnvironment env = inferEnvironment(world, player, input);
    return new Phase6Reachability.Context(tick, player, environmentFor(env), Attributes.DEFAULT, MovementEffects.NONE, Pose.STANDING, env, false);
  }

  private static Phase6Reachability.Context withObservedEnvironment(Phase6Reachability.Context context, WorldSnapshot world,
                                                                     InputConstraint input, long tick) {
    MovementEnvironment env = inferEnvironment(world, context.player(), input);
    return new Phase6Reachability.Context(tick, context.player(), environmentFor(env), context.attributes(), context.effects(),
        context.pose(), env, context.sleeping(), context.uncertainty());
  }

  private static Simulation.Environment environmentFor(MovementEnvironment env) {
    if (env.fluid() == Phase5Mechanics.Fluid.WATER) return Simulation.Environment.WATER;
    if (env.fluid() == Phase5Mechanics.Fluid.LAVA) return Simulation.Environment.LAVA;
    if (env.climbable()) return Simulation.Environment.CLIMBABLE;
    return Simulation.Environment.DRY;
  }

  private static MovementEnvironment inferEnvironment(WorldSnapshot world, Player player, InputConstraint input) {
    int x = (int) Math.floor(player.position().x()), y = (int) Math.floor(player.position().y()), z = (int) Math.floor(player.position().z());
    boolean water = false, lava = false, climb = false;
    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
      BlockState state = world.blockAtOrNull(x + dx, y + dy, z + dz);
      if (state == null) continue;
      if (state.variant() == BlockState.Variant.FLUID && state.blockId().equals("minecraft:water")) water = true;
      if (state.variant() == BlockState.Variant.FLUID && state.blockId().equals("minecraft:lava")) lava = true;
      if (state.variant() == BlockState.Variant.LADDER) climb = true;
    }
    boolean sprint = input.sprint().orElse(false), sneak = input.sneak().orElse(false), swim = input.jump().orElse(false);
    if (water) return MovementEnvironment.vanillaWater(player.onGround(), sprint, sneak, swim);
    if (lava) return MovementEnvironment.vanillaLava(player.onGround(), sprint, sneak);
    if (climb) return MovementEnvironment.vanillaClimbable(player.onGround(), sprint, sneak);
    return MovementEnvironment.dry(player.onGround(), sprint, sneak);
  }

  private static Map<Long, List<ExternalTransition>> externalTransitions(Timeline.Snapshot timeline, Phase7Timing.Reconstruction timing) {
    Map<Long, List<ExternalTransition>> out = new HashMap<>();
    Set<Long> applied = new HashSet<>(); Player state = Player.initial(Maths.Vec3.ZERO);
    for (Timeline.Event event : timeline.events()) {
      if (event.packet().flags().contains(Packets.PacketFlag.DUPLICATE) || !applied.add(event.packet().sequence())) continue;
      Packets.Packet packet = event.packet().packet();
      Phase7Timing.EventTiming eventTiming = timing.timingFor(event.packet().sequence()).orElse(null);
      if (eventTiming == null) continue;
      Phase7Timing.Range range = eventTiming.simulationClientTicks();
      long span = Math.max(0, range.max() - Math.max(0, range.min())) + 1;
      if (span > timing.config().maxTimingCandidates()) continue;
      ExternalTransition transition = null;
      if (packet instanceof Packets.Velocity velocity) transition = new Phase6Reachability.VelocityImpulse(velocity.velocity(), "server velocity packet");
      else if (packet instanceof Packets.Teleport teleport) {
        Maths.Vec3 target = new Maths.Vec3(teleport.relativeX() ? state.position().x() + teleport.position().x() : teleport.position().x(),
            teleport.relativeY() ? state.position().y() + teleport.position().y() : teleport.position().y(),
            teleport.relativeZ() ? state.position().z() + teleport.position().z() : teleport.position().z());
        transition = new Phase6Reachability.TeleportCorrection(teleport.id(), target, Maths.Vec3.ZERO, Pose.STANDING, true);
      } else if (packet instanceof Packets.TeleportConfirm confirm) transition = new Phase6Reachability.TeleportConfirmation(confirm.id());
      if (transition != null) for (long tick = Math.max(0, range.min()); tick <= Math.max(0, range.max()); tick++)
        out.computeIfAbsent(tick, ignored -> new ArrayList<>()).add(transition);
      try { state = State.apply(state, event.packet()); } catch (RuntimeException ignored) { }
    }
    Map<Long, List<ExternalTransition>> frozen = new HashMap<>();
    for (Map.Entry<Long, List<ExternalTransition>> entry : out.entrySet()) frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    return Map.copyOf(frozen);
  }
}