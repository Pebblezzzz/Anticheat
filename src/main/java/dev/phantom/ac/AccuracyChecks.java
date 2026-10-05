package dev.phantom.ac;

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
    final ArrayDeque<Long> timerBoundaries = new ArrayDeque<>();
    long timerBalanceNanos;
    int consecutiveFast;
    PendingImpulse pendingImpulse;
    final Map<Long, Integer> actionsPerTick = new HashMap<>();
    final Map<Long, Integer> placementsPerTick = new HashMap<>();
    int scaffoldConsecutive;
    boolean noFallTracking;
    double fallOriginY;
    int airborneFrames;
    float previousYaw = Float.NaN;
    float previousPitch = Float.NaN;
    int snapStreak;
    double previousMovementHorizontal;
    double previousMovementVertical;
    final Map<Integer, BlockBox> entities = new HashMap<>();
    long lastAttackNanos = -1L;
    Vec3 previousVehiclePosition;
    long previousVehicleNanos = -1L;

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
      scaffoldConsecutive = 0;
      noFallTracking = false;
      airborneFrames = 0;
      attackTimes.clear();
      timerBoundaries.clear();
      actionsPerTick.clear();
      placementsPerTick.clear();
      previousVehiclePosition = null;
      previousVehicleNanos = -1L;
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

      if (state.timerBalanceNanos >= 300_000_000L && state.consecutiveFast >= 12) {
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
          state.pendingImpulse = new PendingImpulse(packet.sequence(), packet.receivedNanos(), velocity.velocity(), 0);
        }
        continue;
      }

      if (!(packet.packet() instanceof Packets.Move move) || move.position() == null || state.pendingImpulse == null) continue;
      PendingImpulse pending = state.pendingImpulse;
      if (packet.sequence() <= pending.sequence()) continue;

      PredictionFrame frame = frameAt(frames, packet.sequence());
      if (frame == null || !frame.uncertaintySources().isEmpty()) {
        if (packet.sequence() - pending.sequence() > 12L) state.pendingImpulse = null;
        continue;
      }

      Vec3 delta = new Vec3(
          frame.observedAfter().position().x() - frame.observedBefore().position().x(),
          frame.observedAfter().position().y() - frame.observedBefore().position().y(),
          frame.observedAfter().position().z() - frame.observedBefore().position().z());
      double expectedHorizontal = Math.hypot(pending.velocity().x(), pending.velocity().z());
      double observedAlongImpulse = delta.x() * pending.velocity().x()
          + delta.z() * pending.velocity().z();
      double impulseSquared = expectedHorizontal * expectedHorizontal;

      double retained = impulseSquared <= 1.0e-9
          ? 1.0
          : observedAlongImpulse / impulseSquared;
      boolean noReachableExplanation = frame.predictedAfter().stream()
          .noneMatch(c -> horizontalDistance(c.context().player().position(), frame.observedAfter().position()) <= 0.06);
      if (impulseSquared > 1.0e-9
          && retained < KNOCKBACK_REQUIRED_RETAINED_FRACTION
          && noReachableExplanation) {
        int badMoves = pending.badMoves() + 1;
        state.pendingImpulse = new PendingImpulse(
            pending.sequence(), pending.receivedNanos(), pending.velocity(), badMoves);
        if (badMoves >= 2) {
          findings.add(hard(playerId, frame, "Knockback",
              String.format(Locale.ROOT,
                  "two consecutive post-velocity observations remained outside the predicted knockback envelope; retained projection was %.1f%%",
                  Math.max(0.0, retained * 100.0)),
              Math.min(1.0, 1.0 - Math.max(0.0, retained))));
          state.pendingImpulse = null;
        }
      } else if (packet.sequence() - pending.sequence() > 12L || !noReachableExplanation) {
        state.pendingImpulse = null;
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
    Map<Integer, BlockBox> entities = state.entities;

    for (Packets.RawPacket packet : packets) {
      if (packet.packet() instanceof Packets.EntitySpawn spawn) entities.put(spawn.entityId(), spawn.box());
      else if (packet.packet() instanceof Packets.EntityMove move) entities.put(move.entityId(), move.box());
      else if (packet.packet() instanceof Packets.EntityDespawn despawn) entities.remove(despawn.entityId());

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

        BlockBox target = entities.get(attack.entityId());
        if (target == null) {
          findings.add(uncertain(playerId, frame, "Interact",
              "attack referenced an entity whose compensated hitbox was not reconstructed",
              1.0));
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
    for (long interval : intervals) {
      double d = interval - mean;
      variance += d * d;
    }
    double cv = Math.sqrt(variance / intervals.size()) / mean;
    if (cv < 0.02) {
      PredictionFrame frame = frameAt(frames,
          packets.stream().filter(p -> p.packet() instanceof Packets.InteractEntity)
              .mapToLong(Packets.RawPacket::sequence).max().orElse(0L));
      return List.of(hard(playerId, frame, "Autoclicker",
          String.format(Locale.ROOT,
              "attack intervals were unusually periodic (n=%d, mean=%.1fms, cv=%.4f)",
              intervals.size(), mean / 1_000_000.0, cv),
          Math.min(1.0, (0.02 - cv) / 0.02)));
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
            "multiple block-placement actions landed on the same client tick",
            Math.min(1.0, count / 4.0)));
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
            "sustained near-downward placement pattern accompanied forward movement",
            Math.min(1.0, state.scaffoldConsecutive / 10.0)));
        state.scaffoldConsecutive = 0;
      }

      if (place.cursorPresent()
          && (place.cursor().x() < 0.0 || place.cursor().x() > 1.0
              || place.cursor().y() < 0.0 || place.cursor().y() > 1.0
              || place.cursor().z() < 0.0 || place.cursor().z() > 1.0)) {
        findings.add(hard(playerId, frame, "Place",
            "placement cursor escaped the legal block-face interval",
            1.0));
      }
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> vehicleSafety(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames,
      State state) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    for (Packets.RawPacket packet : packets) {
      if (!(packet.packet() instanceof Packets.VehicleMove move)) continue;
      if (state.previousVehiclePosition != null) {
        Vec3 previous = state.previousVehiclePosition;
        Vec3 delta = new Vec3(
            move.position().x() - previous.x(),
            move.position().y() - previous.y(),
            move.position().z() - previous.z());
        double horizontal = Math.hypot(delta.x(), delta.z());
        if (packet.receivedNanos() >= state.previousVehicleNanos
            && packet.receivedNanos() - state.previousVehicleNanos <= 100_000_000L
            && (horizontal > 3.0 || Math.abs(delta.y()) > 2.5)) {
          PredictionFrame frame = frameAt(frames, packet.sequence());
          findings.add(hard(playerId, frame, "Vehicle",
              String.format(Locale.ROOT,
                  "vehicle movement displaced %.3f horizontal / %.3f vertical blocks in one packet",
                  horizontal, Math.abs(delta.y())),
              1.0));
        }
      }
      state.previousVehiclePosition = move.position();
      state.previousVehicleNanos = packet.receivedNanos();
    }
    return List.copyOf(findings);
  }

  private record PendingImpulse(long sequence, long receivedNanos, Vec3 velocity, int badMoves) {}

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
