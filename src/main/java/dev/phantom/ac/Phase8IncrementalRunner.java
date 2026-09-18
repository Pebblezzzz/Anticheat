package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;

/**
 * Persistent live movement predictor.
 *
 * <p>Offline replay remains owned by {@link Phase8LiveValidation}. This runner
 * keeps the live candidate frontier between validation cycles so a player is not
 * re-simulated from the beginning of a 6,000-packet capture every ten server ticks.</p>
 *
 * <p>Client tick-end boundaries are treated as relative chronology evidence, not
 * as an invented authoritative Move.clientTick. Any condition that cannot yet be
 * represented by the deterministic core poisons the continuation rather than
 * manufacturing a violation.</p>
 */
public final class Phase8IncrementalRunner {
  public enum Continuation { UNINITIALIZED, ACTIVE, UNCERTAIN, IMPOSSIBLE }

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
      boolean candidateFrontierRetained) {
    public Report {
      results = List.copyOf(results);
    }
  }

  private final int maximumCandidates;
  private final Phase6Reachability engine = new Phase6Reachability(new Vanilla12111RichPhysics());
  private final NavigableMap<Long, InputConstraint> inputByClientTick = new TreeMap<>();

  private Set<Candidate> candidates = Set.of();
  private Player trackedState;
  private Player anchorState;
  private EntityCollisions entityCollisions = EntityCollisions.NONE_TRACKED;

  private long epochNanos;
  private long relativeClientTick;
  private long lastMovementTick = -1;
  private long lastProcessedSequence = -1;
  private boolean sawClientTickEnd;
  private boolean waitingForTeleport;
  private boolean reanchorRequired;
  private Player pendingReanchorState;
  private boolean poisoned;
  private String poisonReason = "";
  private String reanchorReason = "";
  // Authoritative server-side ground state is kept separately from the client
  // movement packet's reported onGround bit. The latter is evidence, not truth.
  private Boolean authoritativeOnGround;
  private Maths.Vec3 authoritativeServerPosition;
  private int groundContradictionStreak;
  private long lastGroundContradictionTick = -1L;
  private int serverDivergenceStreak;
  private long lastServerDivergenceSequence = -1L;
  private static final int HARD_GROUND_CONTRADICTION_TICKS = 3;
  private static final int HARD_SERVER_DIVERGENCE_TICKS = 3;
  private static final double HARD_SERVER_DIVERGENCE_BLOCKS = 3.0;
  private Continuation continuation = Continuation.UNINITIALIZED;

  public Phase8IncrementalRunner(int maximumCandidates, long epochNanos) {
    Contracts.requireCandidateBudget(maximumCandidates);
    if (epochNanos < 0) throw new IllegalArgumentException("epochNanos must be non-negative");
    this.maximumCandidates = maximumCandidates;
    this.epochNanos = epochNanos;
  }

  public synchronized long lastProcessedSequence() {
    return lastProcessedSequence;
  }

  public synchronized Continuation continuation() {
    return continuation;
  }

  public synchronized int candidateCount() {
    return candidates.size();
  }

  public synchronized void reset(Player anchor, long epochNanos) {
    Contracts.requireCandidateBudget(maximumCandidates);
    if (epochNanos < 0) throw new IllegalArgumentException("epochNanos must be non-negative");
    this.epochNanos = epochNanos;
    this.anchorState = Objects.requireNonNull(anchor);
    this.trackedState = anchor;
    this.candidates = Set.of();
    this.inputByClientTick.clear();
    this.entityCollisions = EntityCollisions.NONE_TRACKED;
    this.relativeClientTick = 0;
    this.lastMovementTick = -1;
    this.lastProcessedSequence = -1;
    this.sawClientTickEnd = false;
    this.waitingForTeleport = false;
    this.reanchorRequired = false;
    this.pendingReanchorState = null;
    this.poisoned = false;
    this.poisonReason = "";
    this.reanchorReason = "";
    this.authoritativeOnGround = anchor.onGround();
    this.authoritativeServerPosition = null;
    this.groundContradictionStreak = 0;
    this.lastGroundContradictionTick = -1L;
    this.serverDivergenceStreak = 0;
    this.lastServerDivergenceSequence = -1L;
    this.continuation = Continuation.UNINITIALIZED;
  }

  /**
   * Process only packets whose sequence is newer than {@link #lastProcessedSequence()}.
   * A sequence discontinuity is treated as lost evidence and poisons the candidate
   * frontier instead of silently replaying across a missing interval.
   */
  public synchronized Report process(
      String playerId,
      List<Packets.RawPacket> raw,
      WorldSnapshot world,
      Player currentAnchor) {
    return process(playerId, raw, world, currentAnchor, null);
  }

  /**
   * Processes a live packet batch with an optional authoritative server position.
   * The server position is only used for a sustained contradiction check; it is
   * never promoted to the client's replay state.
   */
  public synchronized Report process(
      String playerId,
      List<Packets.RawPacket> raw,
      WorldSnapshot world,
      Player currentAnchor,
      Maths.Vec3 authoritativeServerPosition) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(raw);
    Objects.requireNonNull(world);
    this.authoritativeServerPosition = authoritativeServerPosition;

    if (currentAnchor != null && (anchorState == null || !anchorState.equals(currentAnchor))) {
      reset(currentAnchor, epochNanos);
    }

    if (raw.isEmpty()) {
      return new Report(List.of(), 0, 0, 0, 0, 0, lastProcessedSequence,
          relativeClientTick, continuation, !candidates.isEmpty());
    }

    List<Packets.RawPacket> unseen = raw.stream()
        .filter(packet -> packet.sequence() > lastProcessedSequence)
        .sorted(Comparator.comparingLong(Packets.RawPacket::receivedNanos)
            .thenComparingLong(Packets.RawPacket::sequence))
        .toList();

    if (unseen.isEmpty()) {
      return new Report(List.of(), 0, 0, 0, 0, 0, lastProcessedSequence,
          relativeClientTick, continuation, !candidates.isEmpty());
    }

    long expected = lastProcessedSequence < 0 ? 1 : lastProcessedSequence + 1;
    if (unseen.getFirst().sequence() != expected) {
      poison("capture sequence gap before incremental validation: expected " + expected
          + " but received " + unseen.getFirst().sequence());
    }

    List<Packets.NormalizedPacket> normalized = new Packets.Normalizer().normalize(unseen);
    List<Phase8MovementValidation.Result> results = new ArrayList<>();
    int movements = 0;
    int latestMovementIndex = -1;
    for (int i = normalized.size() - 1; i >= 0; i--) {
      if (normalized.get(i).packet() instanceof Packets.Move move && move.position() != null) {
        latestMovementIndex = i;
        break;
      }
    }

    for (int packetIndex = 0; packetIndex < normalized.size(); packetIndex++) {
      Packets.NormalizedPacket packet = normalized.get(packetIndex);
      Packets.Packet event = packet.packet();

      if (packet.flags().contains(Packets.PacketFlag.DUPLICATE)
          || packet.flags().contains(Packets.PacketFlag.OUT_OF_ORDER)
          || packet.flags().contains(Packets.PacketFlag.SEQUENCE_GAP)
          || packet.flags().contains(Packets.PacketFlag.BEFORE_CAPTURE_EPOCH)) {
        poison("incremental packet chronology is not exhaustive: " + packet.flags());
      }

      Player before = trackedState == null
          ? (anchorState == null ? Player.initial(Maths.Vec3.ZERO) : anchorState)
          : trackedState;
      long serverTick = serverTick(packet.receivedNanos(),
          packet.provenance().authoritativeServerTick()==null ? -1L : packet.provenance().authoritativeServerTick());
      Player after = State.apply(before, packet).withServerProvenance(serverTick, packet);

      if (event instanceof Packets.ClientTickEnd) {
        relativeClientTick++;
        sawClientTickEnd = true;
        trackedState = after;
        pruneInputHistory();
        continue;
      }

      if (event instanceof Packets.ClientInput input) {
        InputConstraint constraint = InputConstraint.fromClientInput(input);
        inputByClientTick.put(relativeClientTick, constraint);
        trackedState = after;
        pruneInputHistory();
        continue;
      }

      if (event instanceof Packets.PlayerContext context) {
        entityCollisions = EntityCollisions.of(context.entityBoxes());
        retargetCandidateEntityCollisions(entityCollisions);
        authoritativeOnGround = context.movementEnvironment().onGround();
        trackedState = after;
        continue;
      }

      if (event instanceof Packets.Teleport) {
        trackedState = after;
        candidates = Set.of();
        waitingForTeleport = true;
        reanchorRequired = false;
        resetGroundContradiction();
        poison("server correction received; awaiting correction acknowledgement and a fresh replay anchor");
        continuation = Continuation.UNCERTAIN;
        continue;
      }

      if (event instanceof Packets.TeleportConfirm confirm) {
        trackedState = after;
        if (waitingForTeleport && after.awaitingTeleport().isEmpty()) {
          waitingForTeleport = false;
          reanchorRequired = true;
          pendingReanchorState = after;
          clearPoison();
        } else {
          poison("teleport acknowledgement did not establish a confirmed correction anchor");
        }
        continue;
      }

      if (event instanceof Packets.Velocity) {
        trackedState = after;
        resetGroundContradiction();
        reanchorRequired = true;
        pendingReanchorState = authoritativeGroundState(after);
        reanchorReason = "server velocity was observed; live application timing is not yet represented by the incremental core";
        continuation = Continuation.UNCERTAIN;
        continue;
      }

      if (event.mutatesWorld()) {
        trackedState = after;
        if (worldMutationAffectsPlayer(event, before, after)) {
          resetGroundContradiction();
          reanchorRequired = true;
          reanchorReason = "relevant client-world mutation observed; historical collision state must be replayed before continuing";
          continuation = Continuation.UNCERTAIN;
        }
        continue;
      }

      if (event instanceof Packets.WorldTransactionAck) {
        trackedState = after;
        if (reanchorRequired && reanchorReason.contains("client-world mutation")) {
          // The acknowledged client-visible world is now authoritative for future
          // simulation. Keep the last legitimate candidate frontier; never promote
          // the client's current observed position to a new trusted anchor.
          reanchorRequired = false;
          reanchorReason = "";
          if (!poisoned && !candidates.isEmpty()) continuation = Continuation.ACTIVE;
        }
        continue;
      }

      if (!(event instanceof Packets.Move move)) {
        trackedState = after;
        continue;
      }

      if (move.position() != null) movements++;

      if (move.position() != null
          && futureRelevantWorldMutation(normalized, packetIndex + 1, before, after)) {
        results.add(uncertainResult(
            playerId, packet.sequence(), serverTick, before, after, world, "incremental-client-world:chunks="+world.loadedChunks().size(),
            new Validation.SyncWindow(0, 0, true, List.of("future relevant world mutation in the same capture batch")),
            "movement precedes a later world mutation that can affect its collision volume; the current snapshot is not historically valid for this movement",
            "live:incremental:"+packet.sequence()));
        trackedState = after;
        continuation = Continuation.UNCERTAIN;
        reanchorRequired = true;
        reanchorReason = "future relevant world mutation makes the current movement snapshot temporally non-causal";
        continue;
      }

      if (move.yaw() != null || move.pitch() != null) {
        float yaw = move.yaw() == null ? trackedStateYaw(after) : move.yaw();
        float pitch = move.pitch() == null ? trackedStatePitch(after) : move.pitch();
        retargetCandidateRotation(yaw, pitch);
      }

      if (move.position() == null) {
        trackedState = after;
        continue;
      }

      long movementTick;
      boolean exactTick;
      String timingReason;
      if (move.clientTick() != null) {
        movementTick = move.clientTick();
        exactTick = true;
        timingReason = "protocol-authoritative movement tick";
      } else if (sawClientTickEnd) {
        movementTick = relativeClientTick;
        exactTick = true;
        timingReason = "relative CLIENT_TICK_END boundary chronology";
      } else {
        movementTick = relativeClientTick;
        exactTick = false;
        timingReason = "no client tick-end boundary observed";
      }

      Validation.SyncWindow timing = new Validation.SyncWindow(
          Math.max(0, movementTick),
          Math.max(0, movementTick),
          !exactTick || poisoned || reanchorRequired || waitingForTeleport,
          List.of(timingReason));

      String replayReference = "live:incremental:" + packet.sequence();
      String worldReference = "incremental-client-world:chunks=" + world.loadedChunks().size();

      if (!exactTick) {
        results.add(uncertainResult(
            playerId, packet.sequence(), serverTick, before, after, world, worldReference, timing,
            "movement tick cannot be derived from a verified client boundary",
            replayReference));
        trackedState = after;
        continuation = Continuation.UNCERTAIN;
        lastMovementTick = movementTick;
        continue;
      }

      // A persistent contradiction between authoritative server collision state
      // and the client-reported ground bit is independently actionable evidence.
      // It must not be swallowed merely because world/timing replay is uncertain.
      if (packetIndex == latestMovementIndex
          && recordServerDivergence(move.position(), packet.sequence())) {
        double distance = authoritativeServerDistance(move.position());
        results.add(Phase8MovementValidation.authoritativeImpossible(
            playerId, serverTick, before, after, world, worldReference, timing,
            "AUTHORITATIVE_SERVER_POSITION_DIVERGENCE",
            "client-reported position diverges from the authoritative server position by "
                + String.format(Locale.ROOT, "%.2f", distance)
                + " blocks for " + serverDivergenceStreak
                + " consecutive 1:1 client ticks",
            List.of(
                "authoritativeServerPosition=" + authoritativeServerPosition,
                "clientReportedPosition=" + move.position(),
                "positionDistance=" + String.format(Locale.ROOT, "%.6f", distance),
                "consecutiveDivergenceTicks=" + serverDivergenceStreak,
                "thresholdBlocks=" + HARD_SERVER_DIVERGENCE_BLOCKS,
                "this signal is independent of finite candidate-search completeness"),
            replayReference));
      }

      if (recordGroundContradiction(move.onGround(), movementTick)) {
        results.add(Phase8MovementValidation.authoritativeImpossible(
            playerId, serverTick, before, after, world, worldReference, timing,
            "AUTHORITATIVE_GROUND_CONTRADICTION",
            "authoritative server ground=" + authoritativeOnGround
                + " contradicts client-reported onGround=" + move.onGround()
                + " for " + groundContradictionStreak + " consecutive 1:1 client ticks",
            List.of(
                "authoritativeServerOnGround=" + authoritativeOnGround,
                "clientReportedOnGround=" + move.onGround(),
                "consecutiveContradictionTicks=" + groundContradictionStreak,
                "movementTick=" + movementTick,
                "this signal is independent of finite candidate-search completeness"),
            replayReference));
      }

      if (lastMovementTick >= 0 && movementTick == lastMovementTick) {
        resetGroundContradiction();
        resetServerDivergence();
        results.add(uncertainResult(
            playerId, packet.sequence(), serverTick, before, after, world, worldReference,
            new Validation.SyncWindow(
                Math.max(0, movementTick),
                Math.max(0, movementTick),
                true,
                List.of(timingReason, "multiple movement packets occurred in the same client tick; sub-tick trajectory is not yet modeled")),
            "multiple movement packets occurred in the same client tick; this packet is uncertain, but later client ticks remain independently verifiable",
            replayReference));
        // Do not poison the persistent frontier. Same-tick ambiguity applies only
        // to this observation; poisoning would suppress all subsequent 1:1 ticks.
        continuation = candidates.isEmpty() ? Continuation.UNCERTAIN : Continuation.ACTIVE;
        trackedState = after;
        continue;
      }

      if (movementTick < lastMovementTick) {
        resetGroundContradiction();
        resetServerDivergence();
        results.add(uncertainResult(
            playerId, packet.sequence(), serverTick, before, after, world, worldReference, timing,
            "movement client tick regressed; chronology cannot be inverted",
            replayReference));
        poison("movement client tick regressed");
        continuation = Continuation.UNCERTAIN;
        trackedState = after;
        continue;
      }

      if (waitingForTeleport || reanchorRequired || poisoned || anchorState == null) {
        String reason = waitingForTeleport
            ? "waiting for post-correction replay anchor"
            : reanchorRequired
                ? (reanchorReason.isBlank()
                    ? "fresh movement anchor required after a synchronization-affecting transition"
                    : reanchorReason)
                : poisoned
                    ? poisonReason
                    : "no authoritative live replay anchor is available";
        results.add(uncertainResult(playerId, packet.sequence(), serverTick, before, after, world, worldReference,
            timing, reason, replayReference));
        trackedState = after;
        continuation = Continuation.UNCERTAIN;
        lastMovementTick = movementTick;
        if (pendingReanchorState != null && anchorState != null) {
          // Only an explicitly authoritative server state may establish a new
          // replay anchor. The current client-observed movement is never trusted
          // as a recovery baseline.
          Player authoritative = pendingReanchorState;
          anchorCandidates(authoritative, movementTick, world);
          pendingReanchorState = null;
          reanchorRequired = false;
          reanchorReason = "";
          clearPoison();
        }
        continue;
      }

      if (continuation == Continuation.IMPOSSIBLE) {
        results.add(terminalImpossible(playerId, packet.sequence(), serverTick, before, after, world,
            worldReference, timing, replayReference));
        trackedState = after;
        lastMovementTick = movementTick;
        continue;
      }

      if (candidates.isEmpty()) {
        anchorCandidates(anchorState, 0, world);
      }

      AdvanceResult advanced = advanceTo(movementTick, world);
      Set<Candidate> reachableCandidates = advanced.candidates();
      SearchResult reachable = new SearchResult(
          advanced.uncertain()
              ? Phase6Reachability.Verdict.UNCERTAIN
              : Phase6Reachability.Verdict.POSSIBLE,
          reachableCandidates,
          (int) Math.max(0, movementTick - currentCandidateTick()),
          reachableCandidates.size(),
          0, 0, advanced.uncertain() ? 1 : 0, 0,
          advanced.uncertain()
              ? advanced.reasons()
              : List.of("incremental deterministic candidate frontier advanced"));

      Phase8MovementValidation.Result validation = Phase8MovementValidation.validate(
          playerId, serverTick, before, after, world, worldReference, timing,
          List.of(
              "input is held until replacement",
              "movementTick=" + movementTick,
              "timingSource=" + timingReason,
              "candidateFrontierSize=" + reachableCandidates.size()),
          reachable, replayReference, exactTick && !poisoned && !reanchorRequired);

      results.add(validation);
      trackedState = after;
      lastMovementTick = movementTick;

      if (reachable.verdict() == Phase6Reachability.Verdict.UNCERTAIN) {
        poison("incremental candidate frontier became uncertain");
        continuation = Continuation.UNCERTAIN;
        continue;
      }

      Set<Candidate> matching = new LinkedHashSet<>();
      for (Candidate candidate : reachableCandidates) {
        if (matchesObserved(candidate.context().player(), after)) {
          matching.add(candidate);
        }
      }

      if (matching.isEmpty()) {
        candidates = Set.of();
        continuation = Continuation.IMPOSSIBLE;
      } else {
        candidates = Set.copyOf(matching);
        continuation = Continuation.ACTIVE;
        clearPoison();
      }
    }

    if (!normalized.isEmpty()) lastProcessedSequence = Math.max(
        lastProcessedSequence,
        normalized.stream().mapToLong(Packets.NormalizedPacket::sequence).max().orElse(lastProcessedSequence));

    int possible = (int) results.stream()
        .filter(result -> result.verdict() == Phase8MovementValidation.Verdict.POSSIBLE)
        .count();
    int uncertain = (int) results.stream()
        .filter(result -> result.verdict() == Phase8MovementValidation.Verdict.UNCERTAIN)
        .count();
    int impossible = (int) results.stream()
        .filter(result -> result.verdict() == Phase8MovementValidation.Verdict.IMPOSSIBLE)
        .count();

    return new Report(results, normalized.size(), movements, possible, uncertain, impossible,
        lastProcessedSequence, relativeClientTick, continuation, !candidates.isEmpty());
  }

  private void retargetCandidateEntityCollisions(EntityCollisions updated){
    if(candidates.isEmpty())return;
    LinkedHashSet<Candidate> remapped=new LinkedHashSet<>();
    for(Candidate candidate:candidates){
      Phase6Reachability.Context c=candidate.context();
      Phase6Reachability.Context next=new Phase6Reachability.Context(
          c.simulationTick(),c.player(),c.environment(),c.attributes(),c.effects(),c.pose(),
          c.movementEnvironment(),c.sleeping(),updated,c.uncertainty());
      remapped.add(new Candidate(candidate.id(),next,candidate.provenance()));
    }
    candidates=Set.copyOf(remapped);
  }

  private void retargetCandidateRotation(float yaw,float pitch){
    if(candidates.isEmpty())return;
    LinkedHashSet<Candidate> rotated=new LinkedHashSet<>();
    for(Candidate candidate:candidates){
      Player p=candidate.context().player();
      if(Float.compare(p.yaw(),yaw)==0&&Float.compare(p.pitch(),pitch)==0){
        rotated.add(candidate);
        continue;
      }
      Player rotatedPlayer=new Player(p.position(),p.velocity(),yaw,pitch,p.onGround(),p.gamemode(),p.effects(),
          p.awaitingTeleport(),p.uncertain(),p.input(),p.attributes(),p.pose(),p.environment(),
          p.clientTickRange(),p.provenance(),p.uncertaintyReasons());
      Phase6Reachability.Context context=new Phase6Reachability.Context(
          candidate.context().simulationTick(),rotatedPlayer,candidate.context().environment(),
          candidate.context().attributes(),candidate.context().effects(),candidate.context().pose(),
          candidate.context().movementEnvironment(),candidate.context().sleeping(),
          candidate.context().entityCollisions(),candidate.context().uncertainty());
      rotated.add(new Candidate(candidate.id(),context,candidate.provenance()));
    }
    candidates=Set.copyOf(rotated);
  }

  private float trackedStateYaw(Player player){ return player.yaw(); }
  private float trackedStatePitch(Player player){ return player.pitch(); }

  private record AdvanceResult(Set<Candidate> candidates, boolean uncertain, List<String> reasons) {}

  private AdvanceResult advanceTo(long targetTick, WorldSnapshot world) {
    Set<Candidate> nextAll = new LinkedHashSet<>();
    LinkedHashSet<String> uncertaintyReasons = new LinkedHashSet<>();
    int peak = Math.max(1, candidates.size());

    for (Candidate parent : candidates) {
      long parentTick = parent.context().simulationTick();
      if (targetTick < parentTick) {
        uncertaintyReasons.add("candidate frontier contains a future simulation tick");
        continue;
      }

      long steps = targetTick - parentTick;
      if (steps > Phase6Reachability.MAX_HORIZON_TICKS) {
        uncertaintyReasons.add("candidate branch exceeded the finite movement horizon");
        continue;
      }

      boolean parentWorldComplete = worldCoverage(world, parent);
      if (!parentWorldComplete) {
        uncertaintyReasons.add("candidate branch lacks complete collision coverage at its current position");
        continue;
      }

      List<InputConstraint> inputs = new ArrayList<>((int) steps);
      for (long tick = parentTick; tick < targetTick; tick++) {
        inputs.add(inputAtOrBefore(tick));
      }

      SearchResult result = engine.search(
          parent.context(),
          List.copyOf(inputs),
          ignored -> List.of(new WorldBranch(
              "incremental-live-world",
              world,
              parentWorldComplete,
              "persistent acknowledged client-visible world")),
          ignored -> List.of(new Phase6Reachability.None()),
          maximumCandidates);

      if (result.verdict() == Phase6Reachability.Verdict.POSSIBLE) {
        nextAll.addAll(result.candidates());
        peak = Math.max(peak, result.peakCandidates());
      } else {
        uncertaintyReasons.addAll(result.reasons());
      }
      if (nextAll.size() > maximumCandidates) {
        return new AdvanceResult(Set.of(), true, List.of("incremental candidate budget exceeded"));
      }
    }

    if (!uncertaintyReasons.isEmpty()) {
      return new AdvanceResult(Set.of(), true, List.copyOf(uncertaintyReasons));
    }
    if (nextAll.isEmpty()) {
      return new AdvanceResult(Set.of(), true, List.of("incremental candidate frontier produced no deterministic state"));
    }
    return new AdvanceResult(Set.copyOf(nextAll), false, List.of());
  }

  private Player authoritativeGroundState(Player state) {
    if (authoritativeOnGround == null || state.onGround() == authoritativeOnGround) return state;
    return new Player(state.position(), state.velocity(), state.yaw(), state.pitch(), authoritativeOnGround,
        state.gamemode(), state.effects(), state.awaitingTeleport(), state.uncertain(), state.input(),
        state.attributes(), state.pose(), state.environment(), state.clientTickRange(),
        state.provenance(), state.uncertaintyReasons());
  }

  private boolean recordGroundContradiction(Boolean clientReportedGround, long movementTick) {
    if (authoritativeOnGround == null || clientReportedGround == null) {
      resetGroundContradiction();
      return false;
    }
    if (Objects.equals(authoritativeOnGround, clientReportedGround)) {
      resetGroundContradiction();
      return false;
    }
    if (lastGroundContradictionTick == movementTick) return false;
    if (lastGroundContradictionTick < 0 || movementTick == lastGroundContradictionTick + 1L) {
      groundContradictionStreak++;
    } else {
      groundContradictionStreak = 1;
    }
    lastGroundContradictionTick = movementTick;
    return groundContradictionStreak >= HARD_GROUND_CONTRADICTION_TICKS;
  }

  private boolean recordServerDivergence(Maths.Vec3 clientPosition, long packetSequence) {
    if (authoritativeServerPosition == null || clientPosition == null) {
      resetServerDivergence();
      return false;
    }

    double distance = authoritativeServerDistance(clientPosition);
    if (!Double.isFinite(distance) || distance <= HARD_SERVER_DIVERGENCE_BLOCKS) {
      resetServerDivergence();
      return false;
    }
    if (lastServerDivergenceSequence == packetSequence) return false;
    serverDivergenceStreak++;
    lastServerDivergenceSequence = packetSequence;
    return serverDivergenceStreak >= HARD_SERVER_DIVERGENCE_TICKS;
  }

  private double authoritativeServerDistance(Maths.Vec3 clientPosition) {
    if (authoritativeServerPosition == null || clientPosition == null) return Double.NaN;
    double dx = clientPosition.x() - authoritativeServerPosition.x();
    double dy = clientPosition.y() - authoritativeServerPosition.y();
    double dz = clientPosition.z() - authoritativeServerPosition.z();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private void resetServerDivergence() {
    serverDivergenceStreak = 0;
    lastServerDivergenceSequence = -1L;
  }

  private void resetGroundContradiction() {
    groundContradictionStreak = 0;
    lastGroundContradictionTick = -1L;
  }

  private boolean futureRelevantWorldMutation(
      List<Packets.NormalizedPacket> normalized,
      int startIndex,
      Player before,
      Player after) {
    for (int i = Math.max(0, startIndex); i < normalized.size(); i++) {
      Packets.Packet future = normalized.get(i).packet();
      if (future.mutatesWorld() && worldMutationAffectsPlayer(future, before, after)) return true;
    }
    return false;
  }

  private boolean worldMutationAffectsPlayer(Packets.Packet packet, Player before, Player after) {
    double minX = Math.min(before.position().x(), after.position().x()) - 1.0;
    double maxX = Math.max(before.position().x(), after.position().x()) + 1.0;
    double minY = Math.min(before.position().y(), after.position().y()) - 1.0;
    double maxY = Math.max(before.position().y(), after.position().y()) + 2.0;
    double minZ = Math.min(before.position().z(), after.position().z()) - 1.0;
    double maxZ = Math.max(before.position().z(), after.position().z()) + 1.0;

    if (packet instanceof Packets.BlockChange block) {
      return pointInExpandedSweep(block.position().x(), block.position().y(), block.position().z(),
          minX, maxX, minY, maxY, minZ, maxZ);
    }
    if (packet instanceof Packets.BlockStateChange block) {
      return pointInExpandedSweep(block.position().x(), block.position().y(), block.position().z(),
          minX, maxX, minY, maxY, minZ, maxZ);
    }
    if (packet instanceof Packets.UnsupportedBlockStateChange block) {
      return pointInExpandedSweep(block.position().x(), block.position().y(), block.position().z(),
          minX, maxX, minY, maxY, minZ, maxZ);
    }
    if (packet instanceof Packets.ChunkData chunk) {
      return chunkIntersectsSweep(chunk.chunk(), minX, maxX, minZ, maxZ);
    }
    if (packet instanceof Packets.ChunkStates chunk) {
      return chunkIntersectsSweep(chunk.chunk(), minX, maxX, minZ, maxZ);
    }
    if (packet instanceof Packets.ChunkUnload chunk) {
      return chunkIntersectsSweep(chunk.chunk(), minX, maxX, minZ, maxZ);
    }
    return false;
  }

  private boolean pointInExpandedSweep(
      int x, int y, int z,
      double minX, double maxX, double minY, double maxY,
      double minZ, double maxZ) {
    return x + 1.0 > minX && x < maxX
        && y + 1.0 > minY && y < maxY
        && z + 1.0 > minZ && z < maxZ;
  }

  private boolean chunkIntersectsSweep(World.Chunk chunk, double minX, double maxX, double minZ, double maxZ) {
    int minChunkX = Math.floorDiv((int) Math.floor(minX), 16);
    int maxChunkX = Math.floorDiv((int) Math.floor(Math.nextDown(maxX)), 16);
    int minChunkZ = Math.floorDiv((int) Math.floor(minZ), 16);
    int maxChunkZ = Math.floorDiv((int) Math.floor(Math.nextDown(maxZ)), 16);
    return chunk.x() >= minChunkX && chunk.x() <= maxChunkX
        && chunk.z() >= minChunkZ && chunk.z() <= maxChunkZ;
  }

  private long currentCandidateTick() {
    return candidates.stream().mapToLong(candidate -> candidate.context().simulationTick())
        .max().orElse(0L);
  }

  private InputConstraint inputAtOrBefore(long tick) {
    Map.Entry<Long, InputConstraint> entry = inputByClientTick.floorEntry(tick);
    return entry == null ? InputConstraint.any() : entry.getValue();
  }

  private void anchorCandidates(Player player, long tick, WorldSnapshot world) {
    if (player == null || player.uncertain()) {
      candidates = Set.of();
      continuation = Continuation.UNCERTAIN;
      return;
    }

    if (!worldCoverage(world, new Candidate(
        0,
        anchorContext(player, world, inputAtOrBefore(tick), tick),
        new Phase6Reachability.Provenance(0, -1, tick, "ROOT", "ROOT", "None",
            List.of("incremental replay anchor"), 1, List.of())))) {
      candidates = Set.of();
      continuation = Continuation.UNCERTAIN;
      return;
    }

    candidates = Set.of(new Candidate(
        0,
        anchorContext(player, world, inputAtOrBefore(tick), tick),
        new Phase6Reachability.Provenance(0, -1, tick, "ROOT", "ROOT", "None",
            List.of("incremental replay anchor"), 1, List.of())));
    continuation = Continuation.ACTIVE;
    reanchorRequired = false;
    poisoned = false;
    poisonReason = "";
  }

  private Phase6Reachability.Context anchorContext(
      Player player,
      WorldSnapshot world,
      InputConstraint input,
      long tick) {
    MovementEnvironment env = inferEnvironment(world, player, input);
    MovementEffects effects = movementEffects(player);
    Pose pose = player.pose();
    return new Phase6Reachability.Context(
        tick,
        player,
        environmentFor(env),
        player.attributes(),
        effects,
        pose,
        env,
        pose == Pose.SLEEPING,
        entityCollisions);
  }

  private boolean worldCoverage(WorldSnapshot world, Candidate candidate) {
    Maths.Aabb box = Maths.Aabb.playerAt(
        candidate.context().player().position(),
        candidate.context().pose());
    return world.fullyKnown(new dev.phantom.ac.geometry.BlockBox(
        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
  }

  private boolean worldCoverage(WorldSnapshot world, Player player) {
    Maths.Aabb box = Maths.Aabb.playerAt(player.position(), player.pose());
    return world.fullyKnown(new dev.phantom.ac.geometry.BlockBox(
        box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
  }

  private boolean matchesObserved(Player candidate, Player observed) {
    return candidate.position().equals(observed.position())
        && Float.compare(candidate.yaw(), observed.yaw()) == 0
        && Float.compare(candidate.pitch(), observed.pitch()) == 0
        && candidate.onGround() == observed.onGround();
  }

  private Phase8MovementValidation.Result uncertainResult(
      String playerId,
      long sequence,
      long serverTick,
      Player prior,
      Player observed,
      WorldSnapshot world,
      String worldReference,
      Validation.SyncWindow timing,
      String reason,
      String replayReference) {
    SearchResult uncertain = new SearchResult(
        Phase6Reachability.Verdict.UNCERTAIN,
        candidates,
        0,
        candidates.size(),
        0, 0, 1, 0,
        List.of(reason));
    return Phase8MovementValidation.validate(
        playerId, serverTick, prior, observed, world, worldReference,
        timing, List.of(reason), uncertain, replayReference, false);
  }

  private Phase8MovementValidation.Result terminalImpossible(
      String playerId,
      long sequence,
      long serverTick,
      Player prior,
      Player observed,
      WorldSnapshot world,
      String worldReference,
      Validation.SyncWindow timing,
      String replayReference) {
    SearchResult impossible = new SearchResult(
        Phase6Reachability.Verdict.IMPOSSIBLE,
        Set.of(),
        0,
        0,
        0, 0, 0, 0,
        List.of("candidate frontier was previously exhaustively eliminated"));
    return Phase8MovementValidation.validate(
        playerId, serverTick, prior, observed, world, worldReference,
        timing, List.of("previous movement established an impossible observed state"),
        impossible, replayReference, true);
  }

  private long serverTick(long receivedNanos, long authoritative) {
    if (authoritative >= 0) return authoritative;
    if (receivedNanos <= epochNanos) return 0;
    return Math.max(0, (receivedNanos - epochNanos) / 50_000_000L);
  }

  private MovementEnvironment inferEnvironment(
      WorldSnapshot world,
      Player player,
      InputConstraint input) {
    Maths.Aabb box = Maths.Aabb.playerAt(player.position(), player.pose());
    int minX = (int) Math.floor(box.minX());
    int maxX = (int) Math.floor(Math.nextDown(box.maxX()));
    int minY = (int) Math.floor(box.minY());
    int maxY = (int) Math.floor(Math.nextDown(box.maxY()));
    int minZ = (int) Math.floor(box.minZ());
    int maxZ = (int) Math.floor(Math.nextDown(box.maxZ()));

    boolean water = false;
    boolean lava = false;
    boolean climb = false;

    for (int y = minY; y <= maxY; y++) {
      for (int x = minX; x <= maxX; x++) {
        for (int z = minZ; z <= maxZ; z++) {
          BlockState state = world.blockAtOrNull(x, y, z);
          if (state == null) continue;
          if (state.variant() == BlockState.Variant.LADDER) climb = true;
          var fluid = dev.phantom.ac.world.v12111.BlockCatalogue12111.fluid(state);
          if (fluid.type() == dev.phantom.ac.world.FluidState.Type.WATER) water = true;
          if (fluid.type() == dev.phantom.ac.world.FluidState.Type.LAVA) lava = true;
        }
      }
    }

    boolean sprint = input.sprint().orElse(false);
    boolean sneak = input.sneak().orElse(false);
    boolean swim = player.pose() == Pose.SWIMMING;
    MovementEnvironment env;
    if (water) env = MovementEnvironment.vanillaWater(player.onGround(), sprint, sneak, swim);
    else if (lava) env = MovementEnvironment.vanillaLava(player.onGround(), sprint, sneak);
    else if (climb) env = MovementEnvironment.vanillaClimbable(player.onGround(), sprint, sneak);
    else env = MovementEnvironment.dry(player.onGround(), sprint, sneak);

    return new MovementEnvironment(
        env.fluid(), env.submerged(), env.climbable(), player.onGround(),
        sprint, sneak, swim, player.pose() == Pose.FALL_FLYING,
        env.fluidSpeedMultiplier(), env.fluidDrag(), env.gravityMultiplier());
  }

  private MovementEffects movementEffects(Player player) {
    return new MovementEffects(
        amplifier(player.effects(), "speed", "minecraft:speed"),
        amplifier(player.effects(), "slowness", "minecraft:slowness"),
        amplifier(player.effects(), "jump_boost", "minecraft:jump_boost"),
        amplifier(player.effects(), "levitation", "minecraft:levitation"),
        player.effects().keySet().stream()
            .anyMatch(id -> id.equals("slow_falling") || id.equals("minecraft:slow_falling")));
  }

  private int amplifier(Map<String, Integer> effects, String... ids) {
    for (String id : ids) {
      Integer value = effects.get(id);
      if (value != null) return value;
    }
    return -1;
  }

  private Simulation.Environment environmentFor(MovementEnvironment environment) {
    if (environment.fluid() == Phase5Mechanics.Fluid.WATER) return Simulation.Environment.WATER;
    if (environment.fluid() == Phase5Mechanics.Fluid.LAVA) return Simulation.Environment.LAVA;
    if (environment.climbable()) return Simulation.Environment.CLIMBABLE;
    return Simulation.Environment.DRY;
  }

  private void pruneInputHistory() {
    long floor = Math.max(0, relativeClientTick - Phase6Reachability.MAX_HORIZON_TICKS - 4);
    inputByClientTick.headMap(floor, false).clear();
  }

  private void poison(String reason) {
    poisoned = true;
    poisonReason = reason == null || reason.isBlank() ? "incremental validation became uncertain" : reason;
  }

  private void clearPoison() {
    poisoned = false;
    poisonReason = "";
    reanchorReason = "";
    if (!waitingForTeleport && !reanchorRequired && !candidates.isEmpty()) {
      continuation = Continuation.ACTIVE;
    }
  }
}
