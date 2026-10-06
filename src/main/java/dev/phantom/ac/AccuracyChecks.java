package dev.phantom.ac;

import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase8PredictionRunner.PredictionFrame;
import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.EntityCollisions;

import java.util.*;

import static dev.phantom.ac.Maths.Vec3;

/**
 * Secondary accuracy layer that converts repeated, bounded observations into
 * higher-signal evidence without changing the deterministic Phase 5/6 proof.
 *
 * <p>This layer intentionally does not replace the simulator. It closes the
 * gap between a one-packet residual and a sustained behavioral violation:
 * movement advantage accumulation, continuous NoFall tracking, timer balance,
 * knockback response, rotation/interaction behavior, click timing, packet
 * ordering and transaction integrity.</p>
 */
public final class AccuracyChecks {
  private static final double MOVEMENT_ADVANTAGE_HARD = 0.20;
  private static final double MOVEMENT_ADVANTAGE_MIN_TICK = 0.018;
  private static final double MOVEMENT_ADVANTAGE_IMMEDIATE = 0.15;
  private static final double VERTICAL_ADVANTAGE_HARD = 0.22;
  private static final double VERTICAL_ADVANTAGE_MIN_TICK = 0.025;
  private static final double VERTICAL_ADVANTAGE_IMMEDIATE = 0.10;

  private static final long CLIENT_TICK_NANOS = 50_000_000L;
  private static final long TIMER_BALANCE_HARD_NANOS = 150_000_000L;
  private static final long TIMER_BALANCE_SOFT_NANOS = 90_000_000L;

  private static final double KNOCKBACK_MIN_HORIZONTAL = 0.28;
  private static final double KNOCKBACK_REQUIRED_RETAINED_FRACTION = 0.18;

  private static final int AUTOCLICK_MIN_SAMPLES = 24;
  private static final int AUTOCLICK_ADVANCED_MIN_INTERVALS = 96;
  private static final int AUTOCLICK_HISTORY_SIZE = 768;
  private static final int AUTOCLICK_TEMPLATE_MAX_PERIOD = 32;
  private static final long AUTOCLICK_MIN_INTERVAL_NANOS = 8_000_000L;
  private static final long AUTOCLICK_MAX_INTERVAL_NANOS = 300_000_000L;
  private static final long AUTOCLICK_SWING_DEDUP_NANOS = 12_000_000L;
  private static final int AUTOCLICK_FINDING_CLICK_GAP = 32;
  private static final int AUTOCLICK_INVENTORY_MIN_SAMPLES = 40;
  private static final int AUTOCLICK_STABLE_CADENCE_MIN_INTERVALS = 96;
  private static final long AUTOCLICK_STABLE_CADENCE_MAX_MAD_NANOS = 5_000_000L;
  private static final double AUTOCLICK_STABLE_CADENCE_MAX_CV = 0.120;
  private static final int AUTOCLICK_STABLE_CADENCE_MAX_BUCKETS = 24;

  /**
   * Persistent per-player evidence state. Validation is intentionally batched,
   * so temporal checks must not reset at executor batch boundaries.
   */
  public static final class State {
    long lastReceivedNanos = -1L;
    long lastTransactionPacketSequence = -1L;
    final Set<Short> openTransactions = new HashSet<>();
    final Set<Short> acknowledgedTransactions = new HashSet<>();
    final ArrayDeque<ClickSample> autoclickSamples = new ArrayDeque<>();
    long lastAutoclickPacketSequence = -1L;
    long lastAutoclickFindingSequence = -1L;
    int clicksSinceAutoclickFinding;
    final ArrayDeque<Double> knockbackResiduals = new ArrayDeque<>();
    final ArrayDeque<Long> timerBoundaries = new ArrayDeque<>();
    long timerBalanceNanos;
    int consecutiveFast;
    PendingImpulse pendingImpulse;
    final Map<Long, Integer> actionsPerTick = new HashMap<>();
    final Map<Long, Integer> placementsPerTick = new HashMap<>();
    final Map<Integer, Integer> aimMissStreaks = new HashMap<>();
    final Map<Integer, ItemSlotState> inventorySlots = new HashMap<>();
    int heldInventorySlot = -1;
    int scaffoldConsecutive;
    boolean noFallTracking;
    double fallOriginY;
    int airborneFrames;
    float previousYaw = Float.NaN;
    float previousPitch = Float.NaN;
    int snapStreak;
    double previousMovementHorizontal;
    double previousMovementVertical;
    final Map<Integer, EntityHistory> entities = new HashMap<>();
    long lastAttackNanos = -1L;
    Vec3 previousVehiclePosition;
    Vec3 vehiclePlayerOffset;
    long previousVehicleNanos = -1L;
    boolean vehicleOffsetKnown;

    void prune(long currentSequence) {
      while (autoclickSamples.size() > AUTOCLICK_HISTORY_SIZE) autoclickSamples.removeFirst();
      while (timerBoundaries.size() > 32) timerBoundaries.removeFirst();
      actionsPerTick.keySet().removeIf(tick -> tick + 64 < currentSequence);
      placementsPerTick.keySet().removeIf(tick -> tick + 64 < currentSequence);
    }

    void resetTemporalEvidence() {
      timerBalanceNanos = 0L;
      consecutiveFast = 0;
      pendingImpulse = null;
      snapStreak = 0;
      aimMissStreaks.clear();
      scaffoldConsecutive = 0;
      noFallTracking = false;
      airborneFrames = 0;
      autoclickSamples.clear();
      lastAutoclickPacketSequence = -1L;
      lastAutoclickFindingSequence = -1L;
      clicksSinceAutoclickFinding = 0;
      knockbackResiduals.clear();
      timerBoundaries.clear();
      actionsPerTick.clear();
      placementsPerTick.clear();
      previousVehiclePosition = null;
      vehiclePlayerOffset = null;
      previousVehicleNanos = -1L;
      vehicleOffsetKnown = false;
    }
  }

  private AccuracyChecks() {}

  public static List<ProductionCheckEngine.Finding> analyze(
      String playerId,
      List<Packets.RawPacket> packets,
      List<PredictionFrame> frames) {
    return analyze(playerId, packets, frames, new State());
  }

  public static List<ProductionCheckEngine.Finding> analyze(
      String playerId,
      List<Packets.RawPacket> packets,
      List<PredictionFrame> frames,
      State state) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(packets);
    Objects.requireNonNull(frames);
    Objects.requireNonNull(state);
    if (packets.isEmpty() && frames.isEmpty()) return List.of();

    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    List<Packets.RawPacket> ordered = packets.stream()
        .sorted(Comparator.comparingLong(Packets.RawPacket::sequence)
            .thenComparingLong(Packets.RawPacket::receivedNanos))
        .toList();

    Map<Long, PredictionFrame> framesBySequence = new TreeMap<>();
    for (PredictionFrame frame : frames) framesBySequence.put(frame.sequence(), frame);

    findings.addAll(inventoryState(playerId, ordered, framesBySequence, state));
    findings.addAll(movementAdvantage(playerId, frames, state));
    findings.addAll(noFallContinuity(playerId, frames, state));
    findings.addAll(timerBalance(playerId, ordered, framesBySequence, state));
    findings.addAll(knockbackResponse(playerId, ordered, framesBySequence, state));
    findings.addAll(rotationAndCombat(playerId, ordered, framesBySequence, state));
    findings.addAll(autoClicker(playerId, ordered, framesBySequence, state));
    findings.addAll(packetIntegrity(playerId, ordered, framesBySequence, state));
    findings.addAll(scaffoldAndPlacement(playerId, ordered, framesBySequence, state));
    findings.addAll(vehicleSafety(playerId, ordered, framesBySequence, state));
    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> inventoryState(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();

    for (Packets.RawPacket packet : packets) {
      if (packet.packet() instanceof Packets.InventorySlotState slot) {
        state.inventorySlots.put(
            slot.slot(),
            new ItemSlotState(slot.itemType(), slot.itemAmount(), slot.stateId()));
        if (slot.slot() >= 0 && slot.slot() < 9 && state.heldInventorySlot < 0) {
          state.heldInventorySlot = slot.slot();
        }
        continue;
      }

      if (packet.packet() instanceof Packets.HeldItemChange held) {
        state.heldInventorySlot = held.slot();
        continue;
      }

      String observedItem = null;
      if (packet.packet() instanceof Packets.BlockPlace place) {
        observedItem = place.heldItemType();
      } else if (packet.packet() instanceof Packets.DigAction dig) {
        observedItem = dig.heldItemType();
      } else if (packet.packet() instanceof Packets.ClientBlockBreak breakPacket) {
        observedItem = breakPacket.heldItemType();
      }
      if (observedItem == null || state.heldInventorySlot < 0) continue;

      ItemSlotState known = state.inventorySlots.get(state.heldInventorySlot);
      if (known == null || observedItem.equals(known.itemType())) continue;

      PredictionFrame frame = frameAt(frames, packet.sequence());
      findings.add(uncertain(playerId, frame, "InventoryState",
          String.format(Locale.ROOT,
              "action reported held item %s but the latest causally visible slot state is %s",
              observedItem, known.itemType()),
          0.55));
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> movementAdvantage(
      String playerId, List<PredictionFrame> frames, State state) {
    if (frames.isEmpty()) return List.of();

    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    MovementAdvantageTracker fallbackTracker = new MovementAdvantageTracker();

    for (PredictionFrame frame : frames) {
      MovementAdvantageTracker.Snapshot advantage = frame.movementAdvantage();
      if (!advantage.evaluated()
          && frame.uncertaintySources().isEmpty()
          && !frame.predictedAfter().isEmpty()
          && frame.observedBefore().position() != null
          && frame.observedAfter().position() != null) {
        advantage = fallbackTracker.observe(
            frame.predictedAfter().stream()
                .map(candidate -> candidate.context().player().position())
                .toList(),
            frame.observedBefore().position(),
            frame.observedAfter().position(),
            true);
      }
      if (!advantage.evaluated()) continue;

      double horizontal = advantage.accumulatedHorizontal();
      double vertical = advantage.accumulatedVertical();
      double signedHorizontal = advantage.signedHorizontal();
      double signedVertical = advantage.signedVertical();

      boolean horizontalImmediate =
          signedHorizontal >= MOVEMENT_ADVANTAGE_IMMEDIATE;
      boolean horizontalAccumulated =
          signedHorizontal >= MOVEMENT_ADVANTAGE_MIN_TICK
              && horizontal >= MOVEMENT_ADVANTAGE_HARD
              && state.previousMovementHorizontal < MOVEMENT_ADVANTAGE_HARD;
      if (horizontalImmediate || horizontalAccumulated) {
        findings.add(hard(playerId, frame, "Speed",
            String.format(Locale.ROOT,
                "observed movement exceeded the reachable horizontal envelope by %.3f blocks; "
                    + "decaying signed advantage is %.3f blocks",
                signedHorizontal, horizontal),
            Math.min(1.0, Math.max(signedHorizontal, horizontal) / 0.60)));
      }

      boolean verticalImmediate =
          signedVertical >= VERTICAL_ADVANTAGE_IMMEDIATE;
      boolean verticalAccumulated =
          signedVertical >= VERTICAL_ADVANTAGE_MIN_TICK
              && vertical >= VERTICAL_ADVANTAGE_HARD
              && state.previousMovementVertical < VERTICAL_ADVANTAGE_HARD;
      if ((verticalImmediate || verticalAccumulated)
          && !frame.observedAfter().onGround()
          && !frame.observedAfter().input().map(Simulation.AdvancedInput::jump).orElse(false)) {
        findings.add(hard(playerId, frame, "Flight",
            String.format(Locale.ROOT,
                "observed movement exceeded the reachable vertical envelope by %.3f blocks; "
                    + "decaying signed advantage is %.3f blocks",
                signedVertical, vertical),
            Math.min(1.0, Math.max(signedVertical, vertical) / 0.60)));
      }

      state.previousMovementHorizontal = horizontal;
      state.previousMovementVertical = vertical;
    }

    return List.copyOf(findings);
  }

  /**
   * Fall tracking is based on movement frames, not raw packet adjacency.
   * Rotation/status packets therefore cannot erase a legitimate fall origin.
   */
  private static List<ProductionCheckEngine.Finding> noFallContinuity(
      String playerId, List<PredictionFrame> frames, State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();

    for (PredictionFrame frame : frames) {
      if (frame.movement().position() == null) continue;

      var before = frame.observedBefore();
      var after = frame.observedAfter();

      if (!before.onGround() && !after.onGround()) {
        if (!state.noFallTracking) {
          state.noFallTracking = true;
          state.fallOriginY = before.position().y();
          state.airborneFrames = 0;
        }
        state.airborneFrames++;
        continue;
      }

      if (state.noFallTracking && after.onGround()) {
        double fallDistance = state.fallOriginY - after.position().y();
        boolean predictedLanding = frame.predictedAfter().stream()
            .anyMatch(candidate -> candidate.context().player().onGround());
        if (fallDistance > 2.75
            && !predictedLanding
            && frame.uncertaintySources().isEmpty()
            && state.airborneFrames >= 3) {
          findings.add(hard(playerId, frame, "NoFall",
              String.format(Locale.ROOT,
                  "landing after %.3f blocks of tracked airborne descent had no grounded prediction",
                  fallDistance),
              Math.min(1.0, fallDistance / 6.0)));
        }
        state.noFallTracking = false;
        state.airborneFrames = 0;
      } else if (after.onGround()) {
        state.noFallTracking = false;
        state.airborneFrames = 0;
      }
    }

    return List.copyOf(findings);
  }

  /**
   * Tracks timer balance incrementally from each client-tick interval.
   *
   * <p>The previous implementation added the full rolling-window drift on every
   * boundary. Once the window reached 32 samples, the same elapsed time was
   * counted repeatedly, making Timer evidence depend on window size instead of
   * actual client clock drift. Grim-style timer accounting advances the balance
   * from each real interval, while the rolling median/p90 are retained only as
   * jitter guards.</p>
   */
  private static List<ProductionCheckEngine.Finding> timerBalance(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    ArrayDeque<Long> boundaries = state.timerBoundaries;

    for (Packets.RawPacket packet : packets) {
      if (!(packet.packet() instanceof Packets.ClientTickEnd)) continue;

      Long previous = boundaries.peekLast();
      boundaries.addLast(packet.receivedNanos());
      while (boundaries.size() > 32) boundaries.removeFirst();

      if (previous == null) {
        continue;
      }

      long interval = packet.receivedNanos() - previous;
      if (interval <= 0L) {
        continue;
      }

      long delta = CLIENT_TICK_NANOS - interval;
      state.timerBalanceNanos = Math.max(
          -2_000_000_000L,
          Math.min(2_000_000_000L, state.timerBalanceNanos + delta));

      if (interval < 46_000_000L) {
        state.consecutiveFast++;
      } else if (interval > 50_000_000L) {
        state.consecutiveFast = Math.max(0, state.consecutiveFast - 2);
      } else {
        state.consecutiveFast = Math.max(0, state.consecutiveFast - 1);
      }

      List<Long> recentIntervals = new ArrayList<>();
      List<Long> boundaryList = List.copyOf(boundaries);
      for (int i = 1; i < boundaryList.size(); i++) {
        long recent = boundaryList.get(i) - boundaryList.get(i - 1);
        if (recent > 0L) recentIntervals.add(recent);
      }
      recentIntervals.sort(Long::compare);
      long median = recentIntervals.isEmpty()
          ? Long.MAX_VALUE
          : recentIntervals.get(recentIntervals.size() / 2);
      int p90Index = recentIntervals.isEmpty()
          ? -1
          : Math.min(
              recentIntervals.size() - 1,
              (int) Math.floor((recentIntervals.size() - 1) * 0.90));
      long p90 = p90Index < 0 ? Long.MAX_VALUE : recentIntervals.get(p90Index);

      if (state.timerBalanceNanos >= 300_000_000L
          && state.consecutiveFast >= 12
          && median < 45_000_000L
          && p90 < 49_000_000L) {
        PredictionFrame frame = frameAt(frames, packet.sequence());
        findings.add(hard(playerId, frame, "TimerBurst",
            String.format(Locale.ROOT,
                "client tick timing accumulated %d ms of positive timer balance over a sustained fast clock",
                state.timerBalanceNanos / 1_000_000L),
            Math.min(1.0, state.timerBalanceNanos / 800_000_000.0)));
        state.timerBalanceNanos /= 2L;
        state.consecutiveFast = 0;
      } else if (state.timerBalanceNanos >= 180_000_000L && state.consecutiveFast >= 8) {
        PredictionFrame frame = frameAt(frames, packet.sequence());
        findings.add(uncertain(playerId, frame, "TimerLimit",
            String.format(Locale.ROOT,
                "client tick timing accumulated %d ms of positive balance; network timing still warrants caution",
                state.timerBalanceNanos / 1_000_000L),
            Math.min(1.0, state.timerBalanceNanos / 500_000_000.0)));
      }
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> knockbackResponse(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();

    for (Packets.RawPacket packet : packets) {
      if (packet.packet() instanceof Packets.Velocity velocity) {
        double horizontal = Math.hypot(velocity.velocity().x(), velocity.velocity().z());
        if (horizontal >= KNOCKBACK_MIN_HORIZONTAL) {
          PendingImpulse existing = state.pendingImpulse;
          boolean joinsExplosion = existing != null
              && "EXPLOSION".equals(existing.source())
              && packet.sequence() >= existing.sequence()
              && packet.sequence() - existing.sequence() <= 3L
              && vectorDistance(existing.velocity(), velocity.velocity()) <= 0.08;
          state.pendingImpulse = joinsExplosion
              ? new PendingImpulse(
                  existing.sequence(),
                  existing.receivedNanos(),
                  velocity.velocity(),
                  existing.badMoves(),
                  existing.source())
              : new PendingImpulse(
                  packet.sequence(), packet.receivedNanos(), velocity.velocity(), 0, "KNOCKBACK");
          if (!joinsExplosion) state.knockbackResiduals.clear();
        }
        continue;
      }

      if (packet.packet() instanceof Packets.ExplosionImpulse impulse) {
        double horizontal = Math.hypot(impulse.velocity().x(), impulse.velocity().z());
        if (horizontal >= KNOCKBACK_MIN_HORIZONTAL) {
          state.pendingImpulse = new PendingImpulse(
              packet.sequence(),
              packet.receivedNanos(),
              impulse.velocity(),
              0,
              "EXPLOSION".equalsIgnoreCase(impulse.cause()) ? "EXPLOSION" : "KNOCKBACK");
          state.knockbackResiduals.clear();
        }
        continue;
      }

      if (!(packet.packet() instanceof Packets.Move move)
          || move.position() == null
          || state.pendingImpulse == null) {
        continue;
      }

      PendingImpulse pending = state.pendingImpulse;
      if (packet.sequence() <= pending.sequence()) continue;

      PredictionFrame frame = frameAt(frames, packet.sequence());
      if (frame == null || frame.uncertaintySources().isEmpty() == false
          || frame.predictedAfter().isEmpty()) {
        if (packet.sequence() - pending.sequence() > 12L) state.pendingImpulse = null;
        continue;
      }

      /*
       * The normal Phase 6 frontier has already incorporated the velocity
       * transition. Measure the actual observation against every surviving
       * candidate rather than projecting movement onto the raw velocity vector.
       * This catches velocity cancellation while allowing walls, friction,
       * input, and subsequent impulses to be explained by vanilla simulation.
       */
      double minHorizontalResidual = Double.POSITIVE_INFINITY;
      double minTotalResidual = Double.POSITIVE_INFINITY;
      for (Candidate candidate : frame.predictedAfter()) {
        Vec3 expected = candidate.context().player().position();
        Vec3 observed = frame.observedAfter().position();
        double horizontalResidual =
            Math.hypot(expected.x() - observed.x(), expected.z() - observed.z());
        double totalResidual =
            Math.sqrt(
                Math.pow(expected.x() - observed.x(), 2)
                    + Math.pow(expected.y() - observed.y(), 2)
                    + Math.pow(expected.z() - observed.z(), 2));
        minHorizontalResidual = Math.min(minHorizontalResidual, horizontalResidual);
        minTotalResidual = Math.min(minTotalResidual, totalResidual);
      }

      boolean outsidePredictionEnvelope =
          Double.isFinite(minHorizontalResidual)
              && minHorizontalResidual >= 0.08
              && Double.isFinite(minTotalResidual)
              && minTotalResidual >= 0.10;

      if (outsidePredictionEnvelope) {
        int badMoves = pending.badMoves() + 1;
        state.pendingImpulse = new PendingImpulse(
            pending.sequence(), pending.receivedNanos(), pending.velocity(), badMoves, pending.source());
        state.knockbackResiduals.addLast(minTotalResidual);
        while (state.knockbackResiduals.size() > 8) state.knockbackResiduals.removeFirst();

        if (badMoves >= 2) {
          double evidence = state.knockbackResiduals.stream()
              .mapToDouble(Double::doubleValue)
              .average()
              .orElse(minTotalResidual);
          String rule = "EXPLOSION".equals(pending.source()) ? "Explosion" : "Knockback";
          findings.add(hard(playerId, frame, rule,
              String.format(Locale.ROOT,
                  "post-%s movement stayed outside every causally predicted response; residual=%.3f blocks",
                  pending.source().toLowerCase(Locale.ROOT), evidence),
              Math.min(1.0, evidence / 0.50)));
          state.pendingImpulse = null;
          state.knockbackResiduals.clear();
        }
      } else if (packet.sequence() - pending.sequence() > 12L) {
        state.pendingImpulse = null;
        state.knockbackResiduals.clear();
      }
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> rotationAndCombat(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    Map<Integer, EntityHistory> entities = state.entities;

    for (Packets.RawPacket packet : packets) {
      if (packet.packet() instanceof Packets.EntitySpawn spawn) {
        entities.put(spawn.entityId(), new EntityHistory(packet.receivedNanos(), spawn.box()));
      } else if (packet.packet() instanceof Packets.EntityMove move) {
        entities.computeIfAbsent(
                move.entityId(), ignored -> new EntityHistory(packet.receivedNanos(), move.box()))
            .add(packet.receivedNanos(), move.box());
      } else if (packet.packet() instanceof Packets.EntityDespawn despawn) {
        entities.remove(despawn.entityId());
      }

      if (packet.packet() instanceof Packets.Move move) {
        if (move.yaw() != null && Float.isFinite(move.yaw())) {
          if (Float.isFinite(state.previousYaw)) {
            double delta = Math.abs(Math.IEEEremainder(move.yaw() - state.previousYaw, 360.0));
            double pitchDelta = move.pitch() == null || !Float.isFinite(state.previousPitch)
                ? 0.0
                : Math.abs(move.pitch() - state.previousPitch);
            if (delta >= 75.0 || pitchDelta >= 55.0) state.snapStreak++;
            else state.snapStreak = Math.max(0, state.snapStreak - 1);
          }
          state.previousYaw = move.yaw();
          if (move.pitch() != null && Float.isFinite(move.pitch())) state.previousPitch = move.pitch();
        }
      }

      if (packet.packet() instanceof Packets.InteractEntity attack
          && attack.action() == Packets.InteractAction.ATTACK) {
        PredictionFrame frame = frameAt(frames, packet.sequence());
        if (frame == null) continue;

        if (state.snapStreak >= 3) {
          findings.add(uncertain(playerId, frame, "Aim",
              "attack occurred immediately after a repeated large rotation snap sequence",
              Math.min(1.0, state.snapStreak / 8.0)));
        }

        EntityHistory history = entities.get(attack.entityId());
        BlockBox target = history == null
            ? null
            : history.compensated(packet.receivedNanos());
        if (target == null) {
          findings.add(uncertain(playerId, frame, "Interact",
              "attack referenced an entity whose compensated/interpolated hitbox was not reconstructed",
              1.0));
        }

        if (target != null) {
          Vec3 eye = eyePosition(frame.observedAfter());
          Vec3 direction = lookDirection(frame.observedAfter().yaw(), frame.observedAfter().pitch());
          Vec3 center = new Vec3(
              (target.minX() + target.maxX()) * 0.5,
              (target.minY() + target.maxY()) * 0.5,
              (target.minZ() + target.maxZ()) * 0.5);
          double targetDistance = pointAabbDistance(eye, target);
          double centerDistance = Math.sqrt(
              Math.pow(center.x() - eye.x(), 2)
                  + Math.pow(center.y() - eye.y(), 2)
                  + Math.pow(center.z() - eye.z(), 2));
          double angle = angleBetween(direction, new Vec3(
              center.x() - eye.x(), center.y() - eye.y(), center.z() - eye.z()));

          if (Double.isFinite(angle) && targetDistance <= 4.25 && angle >= 25.0) {
            int streak = state.aimMissStreaks.merge(attack.entityId(), 1, Integer::sum);
            if (streak >= 4) {
              findings.add(uncertain(playerId, frame, "Aim",
                  String.format(Locale.ROOT,
                      "reconstructed target center remained %.1f degrees from the observed view across %d attacks",
                      angle, streak),
                  Math.min(1.0, (angle / 90.0) * (streak / 8.0))));
              state.aimMissStreaks.put(attack.entityId(), 0);
            }
          } else {
            state.aimMissStreaks.remove(attack.entityId());
          }

          if (centerDistance <= 0.0) {
            findings.add(uncertain(playerId, frame, "Aim",
                "target center collapsed onto the eye reconstruction; aim angle is undefined",
                0.1));
          }
        }

        if (state.lastAttackNanos >= 0L) {
          long delta = packet.receivedNanos() - state.lastAttackNanos;
          if (delta >= 0L && delta <= 30_000_000L) {
            findings.add(uncertain(playerId, frame, "MultiActions",
                "multiple attack actions arrived within one client-tick-sized window",
                0.5));
          }
        }
        state.lastAttackNanos = packet.receivedNanos();
      }
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> autoClicker(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ClickSample> newSamples = new ArrayList<>();

    for (Packets.RawPacket packet : packets) {
      if (packet.sequence() <= state.lastAutoclickPacketSequence) continue;
      ClickSample sample = autoclickSample(packet);
      if (sample == null) continue;

      state.autoclickSamples.addLast(sample);
      state.lastAutoclickPacketSequence = packet.sequence();
      state.clicksSinceAutoclickFinding++;
      newSamples.add(sample);
    }

    while (state.autoclickSamples.size() > AUTOCLICK_HISTORY_SIZE) {
      state.autoclickSamples.removeFirst();
    }

    if (newSamples.isEmpty()) return List.of();

    List<ClickSample> all = List.copyOf(state.autoclickSamples);
    List<ClickSample> left = canonicalLeftClicks(all);
    List<ClickSample> right = canonicalRightClicks(all);
    List<ClickSample> inventory = canonicalInventoryClicks(all);

    ClickEvidence leftEvidence = analyzeClickStream(left, AUTOCLICK_MIN_SAMPLES);
    ClickEvidence rightEvidence = analyzeClickStream(right, AUTOCLICK_MIN_SAMPLES);
    ClickEvidence inventoryEvidence =
        analyzeClickStream(inventory, AUTOCLICK_INVENTORY_MIN_SAMPLES);

    ClickEvidence best = strongestEvidence(leftEvidence, rightEvidence, inventoryEvidence);
    if (!best.detected() || best.latestSequence() < 0L) return List.of();

    boolean initialFinding = state.lastAutoclickFindingSequence < 0L;
    boolean cooldownSatisfied =
        initialFinding || state.clicksSinceAutoclickFinding >= AUTOCLICK_FINDING_CLICK_GAP;
    if (!cooldownSatisfied) return List.of();

    PredictionFrame frame = frameAt(frames, best.latestSequence());
    state.lastAutoclickFindingSequence = best.latestSequence();
    state.clicksSinceAutoclickFinding = 0;

    String reason = String.format(Locale.ROOT,
        "%s click stream matches an automated timing fingerprint "
            + "(n=%d, rate=%.2f cps, mean=%.1fms, cv=%.4f, mad=%.4f, "
            + "grid=%d, transitionEntropy=%.3f, template=%d/%.4f, windows=%d)",
        best.kind(),
        best.sampleCount(),
        best.cps(),
        best.meanNanos() / 1_000_000.0,
        best.cv(),
        best.madRatio(),
        best.timingBuckets(),
        best.transitionEntropy(),
        best.templatePeriod(),
        best.templateError(),
        best.windowAgreement());

    return List.of(hard(
        playerId, frame, "Autoclicker", reason, best.severity()));
  }

  private static ClickSample autoclickSample(Packets.RawPacket packet) {
    Packets.Packet value = packet.packet();

    if (value instanceof Packets.InteractEntity interaction) {
      return new ClickSample(
          packet.sequence(),
          packet.receivedNanos(),
          interaction.action() == Packets.InteractAction.ATTACK
              ? ClickKind.LEFT_COMBAT
              : ClickKind.RIGHT_ENTITY);
    }

    if (value instanceof Packets.ArmAnimation) {
      return new ClickSample(packet.sequence(), packet.receivedNanos(), ClickKind.SWING);
    }

    if (value instanceof Packets.BlockPlace) {
      return new ClickSample(packet.sequence(), packet.receivedNanos(), ClickKind.RIGHT_BLOCK);
    }

    if (value instanceof Packets.UseItem) {
      return new ClickSample(packet.sequence(), packet.receivedNanos(), ClickKind.RIGHT_USE);
    }

    if (value instanceof Packets.DigAction dig
        && "STARTED_DIGGING".equals(dig.action())) {
      return new ClickSample(packet.sequence(), packet.receivedNanos(), ClickKind.LEFT_BLOCK);
    }

    if (value instanceof Packets.InventoryClick) {
      return new ClickSample(packet.sequence(), packet.receivedNanos(), ClickKind.INVENTORY);
    }

    return null;
  }

  private static List<ClickSample> canonicalLeftClicks(List<ClickSample> all) {
    List<ClickSample> candidates = all.stream()
        .filter(sample -> sample.kind() == ClickKind.LEFT_COMBAT
            || sample.kind() == ClickKind.LEFT_BLOCK
            || sample.kind() == ClickKind.SWING)
        .sorted(Comparator.comparingLong(ClickSample::sequence))
        .toList();

    List<ClickSample> result = new ArrayList<>();
    for (ClickSample sample : candidates) {
      if (sample.kind() == ClickKind.SWING) {
        if (hasNearbyRightClick(all, sample, AUTOCLICK_SWING_DEDUP_NANOS)
            || hasNearbyLeftAction(candidates, sample, AUTOCLICK_SWING_DEDUP_NANOS)) {
          continue;
        }
      }
      if (!result.isEmpty()
          && sample.nanos() - result.get(result.size() - 1).nanos()
              <= AUTOCLICK_MIN_INTERVAL_NANOS
          && sample.kind() == ClickKind.SWING) {
        continue;
      }
      result.add(new ClickSample(sample.sequence(), sample.nanos(), ClickKind.LEFT));
    }
    return List.copyOf(result);
  }

  private static List<ClickSample> canonicalRightClicks(List<ClickSample> all) {
    List<ClickSample> candidates = all.stream()
        .filter(sample -> sample.kind() == ClickKind.RIGHT_ENTITY
            || sample.kind() == ClickKind.RIGHT_BLOCK
            || sample.kind() == ClickKind.RIGHT_USE)
        .sorted(Comparator.comparingLong(ClickSample::sequence))
        .toList();

    List<ClickSample> result = new ArrayList<>();
    for (ClickSample sample : candidates) {
      if (!result.isEmpty()
          && sample.nanos() - result.get(result.size() - 1).nanos()
              <= AUTOCLICK_SWING_DEDUP_NANOS) {
        continue;
      }
      result.add(new ClickSample(sample.sequence(), sample.nanos(), ClickKind.RIGHT));
    }
    return List.copyOf(result);
  }

  private static List<ClickSample> canonicalInventoryClicks(List<ClickSample> all) {
    return all.stream()
        .filter(sample -> sample.kind() == ClickKind.INVENTORY)
        .sorted(Comparator.comparingLong(ClickSample::sequence))
        .toList();
  }

  private static boolean hasNearbyRightClick(
      List<ClickSample> all, ClickSample target, long windowNanos) {
    for (ClickSample sample : all) {
      if (!sample.kind().rightSource()) continue;
      long delta = Math.abs(sample.nanos() - target.nanos());
      if (delta <= windowNanos) return true;
      if (sample.nanos() > target.nanos() + windowNanos) break;
    }
    return false;
  }

  private static boolean hasNearbyLeftAction(
      List<ClickSample> leftCandidates, ClickSample target, long windowNanos) {
    for (ClickSample sample : leftCandidates) {
      if (sample.kind() == ClickKind.SWING) continue;
      long delta = Math.abs(sample.nanos() - target.nanos());
      if (delta <= windowNanos) return true;
      if (sample.nanos() > target.nanos() + windowNanos) break;
    }
    return false;
  }

  private static ClickEvidence strongestEvidence(ClickEvidence... evidences) {
    return Arrays.stream(evidences)
        .filter(ClickEvidence::detected)
        .max(Comparator.comparingDouble(ClickEvidence::severity))
        .orElse(ClickEvidence.none());
  }

  private static ClickEvidence analyzeClickStream(
      List<ClickSample> samples, int minimumSamples) {
    if (samples.size() < minimumSamples + 1) return ClickEvidence.none();

    List<Long> intervals = new ArrayList<>();
    long latestSequence = samples.get(samples.size() - 1).sequence();
    for (int i = 1; i < samples.size(); i++) {
      long delta = samples.get(i).nanos() - samples.get(i - 1).nanos();
      if (delta >= AUTOCLICK_MIN_INTERVAL_NANOS
          && delta <= AUTOCLICK_MAX_INTERVAL_NANOS) {
        intervals.add(delta);
      }
    }

    if (intervals.size() < minimumSamples) return ClickEvidence.none();

    double mean = intervals.stream()
        .mapToLong(Long::longValue)
        .average()
        .orElse(0.0);
    if (!Double.isFinite(mean) || mean <= 0.0) return ClickEvidence.none();

    long[] sorted = intervals.stream().mapToLong(Long::longValue).sorted().toArray();
    double median = percentile(sorted, 0.50);
    double madNanos = medianAbsoluteDeviation(sorted, median);
    double madRatio = madNanos / Math.max(1.0, median);

    double variance = 0.0;
    double meanAbsStep = 0.0;
    for (int i = 0; i < intervals.size(); i++) {
      double d = intervals.get(i) - mean;
      variance += d * d;
      if (i > 0) {
        meanAbsStep += Math.abs(intervals.get(i) - intervals.get(i - 1));
      }
    }

    double cv = Math.sqrt(variance / intervals.size()) / mean;
    double normalizedMeanAbsStep = intervals.size() <= 1
        ? Double.POSITIVE_INFINITY
        : (meanAbsStep / (intervals.size() - 1)) / mean;

    int timingBuckets = timingBucketCount(intervals);
    double transitionEntropy = transitionEntropy(intervals);
    TemplateFingerprint template = bestTemplateFingerprint(intervals);

    boolean mechanical =
        cv < 0.030
            && madRatio < 0.020
            && normalizedMeanAbsStep < 0.045
            && timingBuckets <= 8;

    boolean quantizedDeterministic =
        cv < 0.065
            && madRatio < 0.045
            && timingBuckets <= 14
            && transitionEntropy < 0.42
            && template.period() >= 2
            && template.error() < 0.045
            && template.windowAgreement() >= 3;

    boolean humanizedTemplate =
        intervals.size() >= AUTOCLICK_ADVANCED_MIN_INTERVALS
            && cv < 0.100
            && madRatio < 0.065
            && transitionEntropy < 0.58
            && template.period() >= 2
            && template.error() < 0.035
            && template.windowAgreement() == 3;

    boolean repeatedPhase =
        intervals.size() >= AUTOCLICK_ADVANCED_MIN_INTERVALS
            && cv < 0.120
            && transitionEntropy < 0.68
            && template.period() >= 2
            && template.error() < 0.050
            && template.windowAgreement() == 3
            && timingBuckets <= 32;

    /*
     * Live packet arrival is not a perfect clock: Netty scheduling, batching,
     * OS wakeups and server load can add a few milliseconds of noise to an
     * otherwise fixed client cadence. A long, high-rate stream with a tiny
     * absolute MAD is therefore a stronger signal than the old raw-CV gate.
     * Requiring 96 intervals keeps this relaxed path firmly in sustained-
     * automation territory rather than normal short human click bursts.
     */
    boolean stableCadence =
        intervals.size() >= AUTOCLICK_STABLE_CADENCE_MIN_INTERVALS
            && mean <= 125_000_000.0
            && madNanos <= AUTOCLICK_STABLE_CADENCE_MAX_MAD_NANOS
            && cv < AUTOCLICK_STABLE_CADENCE_MAX_CV
            && timingBuckets <= AUTOCLICK_STABLE_CADENCE_MAX_BUCKETS;

    boolean inventoryStrong =
        samples.get(0).kind() == ClickKind.INVENTORY
            && intervals.size() >= AUTOCLICK_INVENTORY_MIN_SAMPLES
            && (mechanical || quantizedDeterministic || humanizedTemplate);

    boolean detected =
        mechanical
            || quantizedDeterministic
            || humanizedTemplate
            || repeatedPhase
            || stableCadence
            || inventoryStrong;

    if (!detected) return ClickEvidence.none();

    double severity;
    if (mechanical) severity = 1.0;
    else if (humanizedTemplate) severity = 0.97;
    else if (quantizedDeterministic) severity = 0.95;
    else if (repeatedPhase) severity = 0.93;
    else if (stableCadence) severity = 0.96;
    else severity = 0.95;

    return new ClickEvidence(
        true,
        canonicalKindLabel(samples),
        samples.size(),
        latestSequence,
        1_000_000_000.0 / mean,
        mean,
        cv,
        madRatio,
        timingBuckets,
        transitionEntropy,
        template.period(),
        template.error(),
        template.windowAgreement(),
        severity);
  }

  private static String canonicalKindLabel(List<ClickSample> samples) {
    return switch (samples.get(0).kind()) {
      case LEFT, LEFT_COMBAT, LEFT_BLOCK, SWING -> "left";
      case RIGHT, RIGHT_ENTITY, RIGHT_BLOCK, RIGHT_USE -> "right";
      case INVENTORY -> "inventory";
    };
  }

  private static int timingBucketCount(List<Long> intervals) {
    return (int) intervals.stream()
        .mapToLong(value -> Math.round(value / 2_000_000.0))
        .distinct()
        .count();
  }

  /**
   * Measures conditional entropy of the next timing bucket given the previous bucket.
   * A human click stream can revisit the same interval while producing many different
   * successors; an automated template tends to have a low-entropy successor mapping.
   */
  private static double transitionEntropy(List<Long> intervals) {
    if (intervals.size() < 3) return Double.POSITIVE_INFINITY;

    Map<Long, Map<Long, Integer>> nextByPrevious = new HashMap<>();
    for (int i = 1; i < intervals.size(); i++) {
      long previous = Math.round(intervals.get(i - 1) / 2_000_000.0);
      long next = Math.round(intervals.get(i) / 2_000_000.0);
      nextByPrevious
          .computeIfAbsent(previous, ignored -> new HashMap<>())
          .merge(next, 1, Integer::sum);
    }

    double weightedEntropy = 0.0;
    double totalTransitions = intervals.size() - 1.0;

    for (Map<Long, Integer> successors : nextByPrevious.values()) {
      int bucketCount = successors.values().stream().mapToInt(Integer::intValue).sum();
      if (bucketCount <= 0) continue;

      double localEntropy = 0.0;
      for (int count : successors.values()) {
        double probability = count / (double) bucketCount;
        localEntropy -= probability * Math.log(probability);
      }

      double weight = bucketCount / totalTransitions;
      weightedEntropy += weight * localEntropy;
    }

    int observedBuckets = (int) intervals.stream()
        .mapToLong(value -> Math.round(value / 2_000_000.0))
        .distinct()
        .count();
    double maxEntropy = Math.log(Math.max(2, observedBuckets));
    return maxEntropy <= 0.0 ? 0.0 : weightedEntropy / maxEntropy;
  }

  private static TemplateFingerprint bestTemplateFingerprint(List<Long> intervals) {
    int bestPeriod = -1;
    double bestError = Double.POSITIVE_INFINITY;

    for (int period = 2; period <= AUTOCLICK_TEMPLATE_MAX_PERIOD; period++) {
      if (intervals.size() <= period * 2) break;
      double error = templateError(intervals, period);
      if (error < bestError) {
        bestError = error;
        bestPeriod = period;
      }
    }

    if (bestPeriod < 0) return TemplateFingerprint.none();

    int windowSize = intervals.size() / 3;
    int agreement = 0;
    for (int window = 0; window < 3; window++) {
      int from = window * windowSize;
      int to = window == 2 ? intervals.size() : from + windowSize;
      List<Long> slice = intervals.subList(from, to);
      int period = bestTemplatePeriod(slice);
      if (period == bestPeriod && templateError(slice, period) < 0.055) {
        agreement++;
      }
    }

    return new TemplateFingerprint(bestPeriod, bestError, agreement);
  }

  private static int bestTemplatePeriod(List<Long> intervals) {
    int bestPeriod = -1;
    double bestError = Double.POSITIVE_INFINITY;
    for (int period = 2; period <= AUTOCLICK_TEMPLATE_MAX_PERIOD; period++) {
      if (intervals.size() <= period * 2) break;
      double error = templateError(intervals, period);
      if (error < bestError) {
        bestError = error;
        bestPeriod = period;
      }
    }
    return bestPeriod;
  }

  private static double templateError(List<Long> intervals, int period) {
    if (period < 2 || intervals.size() <= period) return Double.POSITIVE_INFINITY;

    double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(1.0);
    if (mean <= 0.0) return Double.POSITIVE_INFINITY;

    double absoluteError = 0.0;
    int count = 0;
    for (int i = period; i < intervals.size(); i++) {
      absoluteError += Math.abs(intervals.get(i) - intervals.get(i - period));
      count++;
    }
    return count == 0 ? Double.POSITIVE_INFINITY : (absoluteError / count) / mean;
  }

  private static double medianAbsoluteDeviation(long[] sorted, double median) {
    double[] deviations = new double[sorted.length];
    for (int i = 0; i < sorted.length; i++) {
      deviations[i] = Math.abs(sorted[i] - median);
    }
    Arrays.sort(deviations);
    return percentile(deviations, 0.50);
  }

  private static double percentile(long[] sorted, double quantile) {
    if (sorted.length == 0) return Double.NaN;
    double position = Math.max(0.0, Math.min(1.0, quantile)) * (sorted.length - 1);
    int lower = (int) Math.floor(position);
    int upper = (int) Math.ceil(position);
    if (lower == upper) return sorted[lower];
    double weight = position - lower;
    return sorted[lower] + (sorted[upper] - sorted[lower]) * weight;
  }

  private static double percentile(double[] sorted, double quantile) {
    if (sorted.length == 0) return Double.NaN;
    double position = Math.max(0.0, Math.min(1.0, quantile)) * (sorted.length - 1);
    int lower = (int) Math.floor(position);
    int upper = (int) Math.ceil(position);
    if (lower == upper) return sorted[lower];
    double weight = position - lower;
    return sorted[lower] + (sorted[upper] - sorted[lower]) * weight;
  }

  private enum ClickKind {
    LEFT(true, false, "left"),
    RIGHT(false, true, "right"),
    INVENTORY(false, false, "inventory"),
    LEFT_COMBAT(true, false, "left-combat"),
    LEFT_BLOCK(true, false, "left-block"),
    SWING(true, false, "swing"),
    RIGHT_ENTITY(false, true, "right-entity"),
    RIGHT_BLOCK(false, true, "right-block"),
    RIGHT_USE(false, true, "right-use");

    private final boolean leftSource;
    private final boolean rightSource;
    private final String label;

    ClickKind(boolean leftSource, boolean rightSource, String label) {
      this.leftSource = leftSource;
      this.rightSource = rightSource;
      this.label = label;
    }

    boolean rightSource() {
      return rightSource;
    }

    String label() {
      return label;
    }
  }

  private record ClickSample(long sequence, long nanos, ClickKind kind) {}

  private record TemplateFingerprint(int period, double error, int windowAgreement) {
    static TemplateFingerprint none() {
      return new TemplateFingerprint(-1, Double.POSITIVE_INFINITY, 0);
    }
  }

  private record ClickEvidence(
      boolean detected,
      String kind,
      int sampleCount,
      long latestSequence,
      double cps,
      double meanNanos,
      double cv,
      double madRatio,
      int timingBuckets,
      double transitionEntropy,
      int templatePeriod,
      double templateError,
      int windowAgreement,
      double severity) {

    static ClickEvidence none() {
      return new ClickEvidence(
          false, "unknown", 0, -1L, 0.0, 0.0,
          Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
          Integer.MAX_VALUE, Double.POSITIVE_INFINITY,
          -1, Double.POSITIVE_INFINITY, 0, 0.0);
    }
  }

  private static List<ProductionCheckEngine.Finding> packetIntegrity(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    Map<Long, Integer> actionsPerTick = state.actionsPerTick;

    for (Packets.RawPacket packet : packets) {
      if (packet.sequence() <= state.lastTransactionPacketSequence) {
        continue;
      }
      state.lastTransactionPacketSequence = packet.sequence();

      if (state.lastReceivedNanos >= 0L && packet.receivedNanos() < state.lastReceivedNanos) {
        PredictionFrame frame = frameAt(frames, packet.sequence());
        findings.add(uncertain(playerId, frame, "PacketOrder",
            "packet sequence increased while capture receive time moved backwards",
            1.0));
      }
      state.lastReceivedNanos = Math.max(state.lastReceivedNanos, packet.receivedNanos());

      if (packet.packet() instanceof Packets.WorldTransactionSend send) {
        state.openTransactions.add(send.id());
      } else if (packet.packet() instanceof Packets.WorldTransactionAck ack) {
        if (!state.openTransactions.remove(ack.id()) || !state.acknowledgedTransactions.add(ack.id())) {
          PredictionFrame frame = frameAt(frames, packet.sequence());
          findings.add(hard(playerId, frame, "TransactionOrder",
              "transaction acknowledgement was duplicated or referenced an unknown barrier",
              1.0));
        }
      }

      PredictionFrame frame = frameAt(frames, packet.sequence());
      if (frame != null && frame.clientTick() >= 0L) {
        if (packet.packet() instanceof Packets.InteractEntity
            || packet.packet() instanceof Packets.BlockPlace
            || packet.packet() instanceof Packets.ClientBlockBreak
            || packet.packet() instanceof Packets.DigAction) {
          actionsPerTick.merge(frame.clientTick(), 1, Integer::sum);
          if (actionsPerTick.get(frame.clientTick()) > 4) {
            findings.add(uncertain(playerId, frame, "MultiActions",
                "more than four interaction actions were observed in one client tick",
                Math.min(1.0, actionsPerTick.get(frame.clientTick()) / 8.0)));
          }
        }
      }
    }

    state.prune(packets.isEmpty() ? 0L : packets.getLast().sequence());
    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> scaffoldAndPlacement(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    Map<Long, Integer> placementsPerTick = state.placementsPerTick;

    for (Packets.RawPacket packet : packets) {
      if (!(packet.packet() instanceof Packets.BlockPlace place)) continue;
      PredictionFrame frame = frameAt(frames, packet.sequence());
      if (frame == null) continue;

      long tick = frame.clientTick();
      placementsPerTick.merge(tick, 1, Integer::sum);
      int count = placementsPerTick.get(tick);
      if (count > 2) {
        findings.add(uncertain(playerId, frame, "Scaffold",
            "multiple block-placement actions landed on the same reconstructed client tick",
            Math.min(1.0, count / 4.0)));
      }

      int bx = place.position().x();
      int by = place.position().y();
      int bz = place.position().z();
      if (frame.world().coverageAt(bx, by, bz) != dev.phantom.ac.world.Coverage.KNOWN) {
        findings.add(uncertain(playerId, frame, "Scaffold",
            "clicked placement surface is outside the client-visible world snapshot",
            0.9));
        continue;
      }

      Vec3 eye = eyePosition(frame.observedAfter());
      Vec3 direction = lookDirection(frame.observedAfter().yaw(), frame.observedAfter().pitch());
      BlockBox clicked = new BlockBox(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0);
      double clickRay = rayEntryDistance(
          eye, direction, clicked, 5.0);
      if (!Double.isFinite(clickRay)) {
        findings.add(hard(playerId, frame, "Place",
            "placement packet selected a known clicked block that the reconstructed view ray never entered",
            1.0));
      }

      if (place.faceId() < 0 || place.faceId() > 5) {
        findings.add(hard(playerId, frame, "Place",
            "placement packet used an invalid clicked-face id",
            1.0));
        continue;
      }

      if (place.cursorPresent() && !cursorMatchesFace(place.cursor(), place.faceId())) {
        findings.add(hard(playerId, frame, "Place",
            "placement cursor does not lie on the selected block face",
            1.0));
      }

      int tx = bx;
      int ty = by;
      int tz = bz;
      switch (place.faceId()) {
        case 0 -> ty--;
        case 1 -> ty++;
        case 2 -> tz--;
        case 3 -> tz++;
        case 4 -> tx--;
        case 5 -> tx++;
        default -> {}
      }

      var targetCoverage = frame.world().coverageAt(tx, ty, tz);
      if (targetCoverage == dev.phantom.ac.world.Coverage.KNOWN) {
        BlockState target = frame.world().blockAtOrNull(tx, ty, tz);
        if (target != null && !target.isAir() && target.variant() != BlockState.Variant.FLUID) {
          findings.add(uncertain(playerId, frame, "Scaffold",
              "placement target is already occupied in the client-visible world; item-specific replaceability was not asserted",
              0.7));
        } else if (target == null) {
          findings.add(uncertain(playerId, frame, "Scaffold",
              "placement target state was unavailable despite known coverage",
              0.8));
        }
      } else {
        findings.add(uncertain(playerId, frame, "Scaffold",
            "placement target is outside the client-visible world snapshot",
            0.8));
      }

      float pitch = frame.observedAfter().pitch();
      if (Float.isFinite(pitch) && pitch > 78.0f
          && frame.movement().position() != null) {
        state.scaffoldConsecutive++;
      } else {
        state.scaffoldConsecutive = Math.max(0, state.scaffoldConsecutive - 1);
      }

      if (state.scaffoldConsecutive >= 6) {
        findings.add(uncertain(playerId, frame, "Scaffold",
            String.format(Locale.ROOT,
                "six or more causally reconstructed placements occurred with sustained near-downward view (heldItem=%s)",
                place.heldItemType()),
            Math.min(1.0, state.scaffoldConsecutive / 10.0)));
        state.scaffoldConsecutive = 0;
      }
    }

    return List.copyOf(findings);
  }

  private static boolean cursorMatchesFace(Vec3 cursor, int faceId) {
    double epsilon = 0.075;
    return switch (faceId) {
      case 0 -> Math.abs(cursor.y()) <= epsilon;
      case 1 -> Math.abs(cursor.y() - 1.0) <= epsilon;
      case 2 -> Math.abs(cursor.z()) <= epsilon;
      case 3 -> Math.abs(cursor.z() - 1.0) <= epsilon;
      case 4 -> Math.abs(cursor.x()) <= epsilon;
      case 5 -> Math.abs(cursor.x() - 1.0) <= epsilon;
      default -> false;
    };
  }

  private static List<ProductionCheckEngine.Finding> vehicleSafety(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();

    for (Packets.RawPacket packet : packets) {
      if (!(packet.packet() instanceof Packets.VehicleMove vehicle)) continue;
      PredictionFrame frame = frameAt(frames, packet.sequence());
      if (frame == null
          || !frame.uncertaintySources().isEmpty()
          || frame.predictedAfter().isEmpty()) {
        if (state.previousVehiclePosition != null) {
          double displacement = horizontalDistance(state.previousVehiclePosition, vehicle.position());
          if (displacement >= 1.0) {
            findings.add(uncertain(playerId, frame, "Vehicle",
                String.format(Locale.ROOT,
                    "vehicle claim moved %.3f blocks without a causally complete vehicle prediction frame",
                    displacement),
                Math.min(1.0, displacement / 4.0)));
          }
        }
        state.previousVehiclePosition = vehicle.position();
        state.previousVehicleNanos = packet.receivedNanos();
        continue;
      }

      if (!state.vehicleOffsetKnown) {
        state.vehiclePlayerOffset = new Vec3(
            frame.observedAfter().position().x() - vehicle.position().x(),
            frame.observedAfter().position().y() - vehicle.position().y(),
            frame.observedAfter().position().z() - vehicle.position().z());
        state.vehicleOffsetKnown = true;
      }

      Vec3 expectedPlayer = new Vec3(
          vehicle.position().x() + state.vehiclePlayerOffset.x(),
          vehicle.position().y() + state.vehiclePlayerOffset.y(),
          vehicle.position().z() + state.vehiclePlayerOffset.z());

      double minResidual = Double.POSITIVE_INFINITY;
      boolean vehicleCandidateSeen = false;
      for (Candidate candidate : frame.predictedAfter()) {
        boolean vehicleMode = candidate.movementMode() == Phase6Reachability.MovementMode.VEHICLE
            || candidate.context().movementEnvironment().vehicle().active();
        if (!vehicleMode) continue;
        vehicleCandidateSeen = true;
        Vec3 position = candidate.context().player().position();
        minResidual = Math.min(minResidual, Math.sqrt(
            Math.pow(position.x() - expectedPlayer.x(), 2)
                + Math.pow(position.y() - expectedPlayer.y(), 2)
                + Math.pow(position.z() - expectedPlayer.z(), 2)));
      }

      if (!vehicleCandidateSeen) {
        findings.add(uncertain(playerId, frame, "Vehicle",
            "vehicle movement arrived without a causally reconstructed vehicle-mode prediction candidate",
            0.8));
      } else if (minResidual >= 0.08) {
        findings.add(hard(playerId, frame, "Vehicle",
            String.format(Locale.ROOT,
                "vehicle claim remained %.3f blocks outside every causally predicted vehicle-mode rider state",
                minResidual),
            Math.min(1.0, minResidual / 0.50)));
      }

      state.previousVehiclePosition = vehicle.position();
      state.previousVehicleNanos = packet.receivedNanos();
    }

    return List.copyOf(findings);
  }

  private static Vec3 eyePosition(dev.phantom.ac.State.Player player) {
    double eye = switch (player.pose()) {
      case CROUCHING -> 1.27;
      case SWIMMING, FALL_FLYING, SLEEPING -> 0.4;
      default -> 1.62;
    };
    return player.position().add(new Vec3(0.0, eye, 0.0));
  }

  private static Vec3 lookDirection(float yaw, float pitch) {
    double yawRadians = Math.toRadians(yaw);
    double pitchRadians = Math.toRadians(pitch);
    double cosPitch = Math.cos(pitchRadians);
    Vec3 direction = new Vec3(
        -Math.sin(yawRadians) * cosPitch,
        -Math.sin(pitchRadians),
        Math.cos(yawRadians) * cosPitch);
    double length = Math.sqrt(
        direction.x() * direction.x()
        + direction.y() * direction.y()
        + direction.z() * direction.z());
    return length <= 1.0e-12 ? new Vec3(0.0, 0.0, 0.0)
        : new Vec3(direction.x() / length, direction.y() / length, direction.z() / length);
  }

  private static final class EntityHistory {
    private final ArrayDeque<EntitySample> samples = new ArrayDeque<>();

    EntityHistory(long receivedNanos, BlockBox box) {
      add(receivedNanos, box);
    }

    void add(long receivedNanos, BlockBox box) {
      samples.addLast(new EntitySample(receivedNanos, box));
      while (samples.size() > 4) samples.removeFirst();
    }

    BlockBox compensated(long attackNanos) {
      if (samples.isEmpty()) return null;
      EntitySample before = null;
      EntitySample after = null;
      for (EntitySample sample : samples) {
        if (sample.receivedNanos() <= attackNanos) before = sample;
        else { after = sample; break; }
      }
      if (before == null) return samples.getFirst().box();
      if (after == null) return samples.getLast().box();
      long span = after.receivedNanos() - before.receivedNanos();
      if (span <= 0L) return before.box();
      double alpha = Math.max(0.0, Math.min(1.0,
          (attackNanos - before.receivedNanos()) / (double) span));
      return interpolateBox(before.box(), after.box(), alpha);
    }
  }

  private record EntitySample(long receivedNanos, BlockBox box) {}

  private static BlockBox interpolateBox(BlockBox a, BlockBox b, double alpha) {
    return new BlockBox(
        lerp(a.minX(), b.minX(), alpha),
        lerp(a.minY(), b.minY(), alpha),
        lerp(a.minZ(), b.minZ(), alpha),
        lerp(a.maxX(), b.maxX(), alpha),
        lerp(a.maxY(), b.maxY(), alpha),
        lerp(a.maxZ(), b.maxZ(), alpha));
  }

  private static double lerp(double a, double b, double alpha) {
    return a + (b - a) * alpha;
  }


  private record ItemSlotState(String itemType, int amount, int stateId) {}

  private record PendingImpulse(
      long sequence, long receivedNanos, Vec3 velocity, int badMoves, String source) {}

  private static PredictionFrame frameAt(Map<Long, PredictionFrame> frames, long sequence) {
    if (frames instanceof NavigableMap<?, ?> rawNavigable) {
      @SuppressWarnings("unchecked")
      NavigableMap<Long, PredictionFrame> navigable =
          (NavigableMap<Long, PredictionFrame>) rawNavigable;
      Map.Entry<Long, PredictionFrame> entry = navigable.floorEntry(sequence);
      return entry == null ? null : entry.getValue();
    }
    return frames.entrySet().stream()
        .filter(e -> e.getKey() <= sequence)
        .max(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .orElse(null);
  }

  private static double rayEntryDistance(
      Vec3 origin, Vec3 direction, BlockBox box, double maxDistance) {
    double tMin = 0.0;
    double tMax = maxDistance;
    double[] o = {origin.x(), origin.y(), origin.z()};
    double[] d = {direction.x(), direction.y(), direction.z()};
    double[] min = {box.minX(), box.minY(), box.minZ()};
    double[] max = {box.maxX(), box.maxY(), box.maxZ()};

    for (int axis = 0; axis < 3; axis++) {
      if (Math.abs(d[axis]) < 1.0e-12) {
        if (o[axis] < min[axis] || o[axis] > max[axis]) return Double.POSITIVE_INFINITY;
        continue;
      }
      double inv = 1.0 / d[axis];
      double t1 = (min[axis] - o[axis]) * inv;
      double t2 = (max[axis] - o[axis]) * inv;
      if (t1 > t2) {
        double tmp = t1;
        t1 = t2;
        t2 = tmp;
      }
      tMin = Math.max(tMin, t1);
      tMax = Math.min(tMax, t2);
      if (tMin > tMax) return Double.POSITIVE_INFINITY;
    }
    return tMin >= 0.0 && tMin <= maxDistance ? tMin : Double.POSITIVE_INFINITY;
  }

  private static double vectorDistance(Vec3 a, Vec3 b) {
    return Math.sqrt(
        Math.pow(a.x() - b.x(), 2)
            + Math.pow(a.y() - b.y(), 2)
            + Math.pow(a.z() - b.z(), 2));
  }

  private static double angleBetween(Vec3 left, Vec3 right) {
    double leftLength = Math.sqrt(
        left.x() * left.x() + left.y() * left.y() + left.z() * left.z());
    double rightLength = Math.sqrt(
        right.x() * right.x() + right.y() * right.y() + right.z() * right.z());
    if (!Double.isFinite(leftLength) || !Double.isFinite(rightLength)
        || leftLength <= 1.0e-12 || rightLength <= 1.0e-12) {
      return Double.NaN;
    }
    double cosine = (
        left.x() * right.x() + left.y() * right.y() + left.z() * right.z())
        / (leftLength * rightLength);
    return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosine))));
  }

  private static double pointAabbDistance(Vec3 point, BlockBox box) {
    double dx = point.x() < box.minX() ? box.minX() - point.x()
        : point.x() > box.maxX() ? point.x() - box.maxX() : 0.0;
    double dy = point.y() < box.minY() ? box.minY() - point.y()
        : point.y() > box.maxY() ? point.y() - box.maxY() : 0.0;
    double dz = point.z() < box.minZ() ? box.minZ() - point.z()
        : point.z() > box.maxZ() ? point.z() - box.maxZ() : 0.0;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private static ProductionCheckEngine.Finding hard(
      String playerId,
      PredictionFrame frame,
      String rule,
      String reason,
      double severity) {
    long tick = frame == null ? 0L : frame.serverTick();
    long sequence = frame == null ? 0L : frame.sequence();
    return new ProductionCheckEngine.Finding(
        playerId, tick, rule, ProductionCheckEngine.Verdict.IMPOSSIBLE,
        reason, Math.max(0.0, Math.min(1.0, severity)),
        "accuracy:" + playerId + ":" + rule + ":" + sequence);
  }

  private static ProductionCheckEngine.Finding uncertain(
      String playerId,
      PredictionFrame frame,
      String rule,
      String reason,
      double severity) {
    long tick = frame == null ? 0L : frame.serverTick();
    long sequence = frame == null ? 0L : frame.sequence();
    return new ProductionCheckEngine.Finding(
        playerId, tick, rule, ProductionCheckEngine.Verdict.UNCERTAIN,
        reason, Math.max(0.0, Math.min(1.0, severity)),
        "accuracy:" + playerId + ":" + rule + ":" + sequence);
  }

  private static double horizontalDistance(Vec3 a, Vec3 b) {
    return Math.hypot(a.x() - b.x(), a.z() - b.z());
  }
}
