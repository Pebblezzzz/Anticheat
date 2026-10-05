package dev.phantom.ac;

import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase8PredictionRunner.PredictionFrame;
import dev.phantom.ac.geometry.BlockBox;
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
  private static final double MOVEMENT_ADVANTAGE_IMMEDIATE = 0.10;
  private static final double VERTICAL_ADVANTAGE_HARD = 0.22;
  private static final double VERTICAL_ADVANTAGE_MIN_TICK = 0.025;
  private static final double VERTICAL_ADVANTAGE_IMMEDIATE = 0.10;

  private static final long CLIENT_TICK_NANOS = 50_000_000L;
  private static final long TIMER_BALANCE_HARD_NANOS = 150_000_000L;
  private static final long TIMER_BALANCE_SOFT_NANOS = 90_000_000L;

  private static final double KNOCKBACK_MIN_HORIZONTAL = 0.28;
  private static final double KNOCKBACK_REQUIRED_RETAINED_FRACTION = 0.18;

  private static final int AUTOCLICK_MIN_SAMPLES = 20;
  private static final double AUTOCLICK_MAX_CV = 0.045;
  private static final long AUTOCLICK_MAX_INTERVAL_NANOS = 150_000_000L;

  /**
   * Persistent per-player evidence state. Validation is intentionally batched,
   * so temporal checks must not reset at executor batch boundaries.
   */
  public static final class State {
    long lastReceivedNanos = -1L;
    final Set<Short> openTransactions = new HashSet<>();
    final Set<Short> acknowledgedTransactions = new HashSet<>();
    final ArrayDeque<Long> attackTimes = new ArrayDeque<>();
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
      while (attackTimes.size() > 64) attackTimes.removeFirst();
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
      attackTimes.clear();
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

    for (PredictionFrame frame : frames) {
      MovementAdvantageTracker.Snapshot advantage = frame.movementAdvantage();
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

  private static List<ProductionCheckEngine.Finding> timerBalance(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    ArrayDeque<Long> boundaries = state.timerBoundaries;

    for (Packets.RawPacket packet : packets) {
      if (!(packet.packet() instanceof Packets.ClientTickEnd)) continue;
      boundaries.addLast(packet.receivedNanos());
      while (boundaries.size() > 32) boundaries.removeFirst();
      if (boundaries.size() < 8) continue;

      Long first = boundaries.peekFirst();
      long elapsed = packet.receivedNanos() - first;
      if (elapsed <= 0L) continue;

      int intervals = boundaries.size() - 1;
      long expected = CLIENT_TICK_NANOS * intervals;
      long delta = expected - elapsed;
      state.timerBalanceNanos = Math.max(-2_000_000_000L, Math.min(2_000_000_000L, state.timerBalanceNanos + delta));

      long mean = elapsed / intervals;
      if (mean < 46_000_000L) state.consecutiveFast++;
      else state.consecutiveFast = Math.max(0, state.consecutiveFast - 2);

      List<Long> recentIntervals = new ArrayList<>();
      List<Long> boundaryList = List.copyOf(boundaries);
      for (int i = 1; i < boundaryList.size(); i++) {
        long interval = boundaryList.get(i) - boundaryList.get(i - 1);
        if (interval > 0L) recentIntervals.add(interval);
      }
      recentIntervals.sort(Long::compare);
      long median = recentIntervals.isEmpty()
          ? Long.MAX_VALUE
          : recentIntervals.get(recentIntervals.size() / 2);
      int p90Index = recentIntervals.isEmpty()
          ? -1
          : Math.min(recentIntervals.size() - 1,
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
          state.pendingImpulse =
              new PendingImpulse(packet.sequence(), packet.receivedNanos(), velocity.velocity(), 0, "KNOCKBACK");
          state.knockbackResiduals.clear();
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
    for (Packets.RawPacket packet : packets) {
      if (packet.packet() instanceof Packets.InteractEntity attack
          && attack.action() == Packets.InteractAction.ATTACK) {
        state.attackTimes.addLast(packet.receivedNanos());
      }
    }
    while (state.attackTimes.size() > 128) state.attackTimes.removeFirst();
    if (state.attackTimes.size() < 49) return List.of();

    List<Long> attacks = List.copyOf(state.attackTimes);
    List<Long> intervals = new ArrayList<>();
    for (int i = Math.max(1, attacks.size() - 48); i < attacks.size(); i++) {
      long delta = attacks.get(i) - attacks.get(i - 1);
      if (delta > 0L && delta <= AUTOCLICK_MAX_INTERVAL_NANOS) intervals.add(delta);
    }
    if (intervals.size() < 40) return List.of();

    double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0.0);
    if (mean <= 0.0) return List.of();

    double variance = 0.0;
    double meanAbsStep = 0.0;
    for (int i = 0; i < intervals.size(); i++) {
      double d = intervals.get(i) - mean;
      variance += d * d;
      if (i > 0) meanAbsStep += Math.abs(intervals.get(i) - intervals.get(i - 1));
    }
    double cv = Math.sqrt(variance / intervals.size()) / mean;
    double normalizedMeanAbsStep =
        intervals.size() <= 1 ? Double.POSITIVE_INFINITY
            : (meanAbsStep / (intervals.size() - 1)) / mean;

    long[] buckets = intervals.stream()
        .mapToLong(v -> v / 5_000_000L)
        .distinct()
        .sorted()
        .toArray();

    /*
     * CV alone is easy to defeat with a little random jitter. Combine three
     * independent signals: low coefficient of variation, tiny interval-to-
     * interval movement, and low timing alphabet. Constant/near-constant
     * automation therefore remains a hard signal while human-ish timing stays
     * outside the hard threshold.
     */
    boolean mechanical =
        cv < 0.030
            && normalizedMeanAbsStep < 0.045
            && buckets.length <= 8;
    boolean heavilyHumanizedButPeriodic =
        cv < AUTOCLICK_MAX_CV
            && normalizedMeanAbsStep < 0.060
            && buckets.length <= 5;

    if (mechanical || heavilyHumanizedButPeriodic) {
      PredictionFrame frame = frameAt(frames,
          packets.stream().filter(p -> p.packet() instanceof Packets.InteractEntity)
              .mapToLong(Packets.RawPacket::sequence).max().orElse(0L));
      double severity = mechanical ? 1.0 : 0.75;
      return List.of(hard(playerId, frame, "Autoclicker",
          String.format(Locale.ROOT,
              "attack timing was highly periodic (n=%d, mean=%.1fms, cv=%.4f, step=%.4f, timingBuckets=%d)",
              intervals.size(), mean / 1_000_000.0, cv, normalizedMeanAbsStep, buckets.length),
          severity));
    }
    return List.of();
  }

  private static List<ProductionCheckEngine.Finding> packetIntegrity(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    Map<Long, Integer> actionsPerTick = state.actionsPerTick;

    for (Packets.RawPacket packet : packets) {
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
        findings.add(finding(playerId, frame.serverTick(), "Place",
            "placement packet selected a known clicked block that the reconstructed view ray never entered",
            1.0, frame.sequence()));
      }

      if (place.faceId() < 0 || place.faceId() > 5) {
        findings.add(finding(playerId, frame.serverTick(), "Place",
            "placement packet used an invalid clicked-face id",
            1.0, frame.sequence()));
        continue;
      }

      if (place.cursorPresent() && !cursorMatchesFace(place.cursor(), place.faceId())) {
        findings.add(finding(playerId, frame.serverTick(), "Place",
            "placement cursor does not lie on the selected block face",
            1.0, frame.sequence()));
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
      if (frame == null || frame.uncertaintySources().isEmpty() == false
          || frame.predictedAfter().isEmpty()) {
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
