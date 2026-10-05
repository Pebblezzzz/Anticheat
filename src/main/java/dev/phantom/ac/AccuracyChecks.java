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
  private static final double VERTICAL_ADVANTAGE_IMMEDIATE = 0.10;

  private static final long CLIENT_TICK_NANOS = 50_000_000L;
  private static final long TIMER_BALANCE_HARD_NANOS = 150_000_000L;
  private static final long TIMER_BALANCE_SOFT_NANOS = 90_000_000L;

  private static final double KNOCKBACK_MIN_HORIZONTAL = 0.28;
  private static final double KNOCKBACK_REQUIRED_RETAINED_FRACTION = 0.18;

  private static final int AUTOCLICK_MIN_SAMPLES = 20;
  private static final double AUTOCLICK_MAX_CV = 0.045;
  private static final long AUTOCLICK_MAX_INTERVAL_NANOS = 150_000_000L;

  private AccuracyChecks() {}

  public static List<ProductionCheckEngine.Finding> analyze(
      String playerId,
      List<Packets.RawPacket> packets,
      List<PredictionFrame> frames) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(packets);
    Objects.requireNonNull(frames);
    if (packets.isEmpty() && frames.isEmpty()) return List.of();

    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    List<Packets.RawPacket> ordered = packets.stream()
        .sorted(Comparator.comparingLong(Packets.RawPacket::sequence)
            .thenComparingLong(Packets.RawPacket::receivedNanos))
        .toList();

    Map<Long, PredictionFrame> framesBySequence = new TreeMap<>();
    for (PredictionFrame frame : frames) framesBySequence.put(frame.sequence(), frame);

    findings.addAll(movementAdvantage(playerId, frames));
    findings.addAll(noFallContinuity(playerId, frames));
    findings.addAll(timerBalance(playerId, ordered, framesBySequence));
    findings.addAll(knockbackResponse(playerId, ordered, framesBySequence));
    findings.addAll(rotationAndCombat(playerId, ordered, framesBySequence));
    findings.addAll(autoClicker(playerId, ordered, framesBySequence));
    findings.addAll(packetIntegrity(playerId, ordered, framesBySequence));
    findings.addAll(scaffoldAndPlacement(playerId, ordered, framesBySequence));
    findings.addAll(vehicleSafety(playerId, ordered, framesBySequence));
    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> movementAdvantage(
      String playerId, List<PredictionFrame> frames) {
    if (frames.isEmpty()) return List.of();

    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    double previousHorizontal = 0.0;
    double previousVertical = 0.0;

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
              && previousHorizontal < MOVEMENT_ADVANTAGE_HARD;
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
              && previousVertical < VERTICAL_ADVANTAGE_HARD;
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

      previousHorizontal = horizontal;
      previousVertical = vertical;
    }

    return List.copyOf(findings);
  }

  /**
   * Fall tracking is based on movement frames, not raw packet adjacency.
   * Rotation/status packets therefore cannot erase a legitimate fall origin.
   */
  private static List<ProductionCheckEngine.Finding> noFallContinuity(
      String playerId, List<PredictionFrame> frames) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    boolean tracking = false;
    double fallOriginY = 0.0;
    int airborneFrames = 0;

    for (PredictionFrame frame : frames) {
      if (frame.movement().position() == null) continue;

      var before = frame.observedBefore();
      var after = frame.observedAfter();

      if (!before.onGround() && !after.onGround()) {
        if (!tracking) {
          tracking = true;
          fallOriginY = before.position().y();
          airborneFrames = 0;
        }
        airborneFrames++;
        continue;
      }

      if (tracking && after.onGround()) {
        double fallDistance = fallOriginY - after.position().y();
        boolean predictedLanding = frame.predictedAfter().stream()
            .anyMatch(candidate -> candidate.context().player().onGround());
        if (fallDistance > 2.75
            && !predictedLanding
            && frame.uncertaintySources().isEmpty()
            && airborneFrames >= 3) {
          findings.add(hard(playerId, frame, "NoFall",
              String.format(Locale.ROOT,
                  "landing after %.3f blocks of tracked airborne descent had no grounded prediction",
                  fallDistance),
              Math.min(1.0, fallDistance / 6.0)));
        }
        tracking = false;
        airborneFrames = 0;
      } else if (after.onGround()) {
        tracking = false;
        airborneFrames = 0;
      }
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> timerBalance(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    ArrayDeque<Long> boundaries = new ArrayDeque<>();
    long balance = 0L;
    int consecutiveFast = 0;

    for (Packets.RawPacket packet : packets) {
      if (!(packet.packet() instanceof Packets.ClientTickEnd)) continue;
      boundaries.addLast(packet.receivedNanos());
      while (boundaries.size() > 24) boundaries.removeFirst();
      if (boundaries.size() < 8) continue;

      Long first = boundaries.peekFirst();
      long elapsed = packet.receivedNanos() - first;
      if (elapsed <= 0L) continue;

      int intervals = boundaries.size() - 1;
      long expected = CLIENT_TICK_NANOS * intervals;
      long delta = expected - elapsed;
      balance = Math.max(-2_000_000_000L, Math.min(2_000_000_000L, balance + delta));

      long mean = elapsed / intervals;
      if (mean < 47_000_000L) consecutiveFast++;
      else consecutiveFast = Math.max(0, consecutiveFast - 2);

      if (balance >= TIMER_BALANCE_HARD_NANOS && consecutiveFast >= 8) {
        PredictionFrame frame = frameAt(frames, packet.sequence());
        findings.add(hard(playerId, frame, "TimerBurst",
            String.format(Locale.ROOT,
                "client tick timing accumulated %d ms of positive timer balance over a sustained fast clock",
                balance / 1_000_000L),
            Math.min(1.0, balance / 600_000_000.0)));
        balance /= 2L;
        consecutiveFast = 0;
      } else if (balance >= TIMER_BALANCE_SOFT_NANOS && consecutiveFast >= 6) {
        PredictionFrame frame = frameAt(frames, packet.sequence());
        findings.add(uncertain(playerId, frame, "TimerLimit",
            String.format(Locale.ROOT,
                "client tick timing accumulated %d ms of positive balance; network timing still warrants caution",
                balance / 1_000_000L),
            Math.min(1.0, balance / 400_000_000.0)));
      }
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> knockbackResponse(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    PendingImpulse pending = null;

    for (Packets.RawPacket packet : packets) {
      if (packet.packet() instanceof Packets.Velocity velocity) {
        double horizontal = Math.hypot(velocity.velocity().x(), velocity.velocity().z());
        if (horizontal >= KNOCKBACK_MIN_HORIZONTAL) {
          pending = new PendingImpulse(packet.sequence(), packet.receivedNanos(), velocity.velocity());
        }
        continue;
      }

      if (!(packet.packet() instanceof Packets.Move move) || move.position() == null || pending == null) continue;
      if (packet.sequence() <= pending.sequence()) continue;

      PredictionFrame frame = frameAt(frames, packet.sequence());
      if (frame == null || !frame.uncertaintySources().isEmpty()) {
        if (packet.sequence() - pending.sequence() > 8L) pending = null;
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

      if (impulseSquared > 1.0e-9
          && observedAlongImpulse / impulseSquared < KNOCKBACK_REQUIRED_RETAINED_FRACTION
          && frame.predictedAfter().stream().noneMatch(c ->
              horizontalDistance(c.context().player().position(), frame.observedAfter().position()) <= 0.06)) {
        findings.add(hard(playerId, frame, "Knockback",
            String.format(Locale.ROOT,
                "observed movement retained only %.1f%% of server velocity projection",
                Math.max(0.0, observedAlongImpulse / impulseSquared * 100.0)),
            1.0 - Math.max(0.0, observedAlongImpulse / impulseSquared)));
        pending = null;
      } else if (packet.sequence() - pending.sequence() > 8L) {
        pending = null;
      }
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> rotationAndCombat(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    Map<Integer, BlockBox> entities = new HashMap<>();
    float previousYaw = Float.NaN;
    float previousPitch = Float.NaN;
    int snapStreak = 0;
    Packets.RawPacket previousAttack = null;

    for (Packets.RawPacket packet : packets) {
      if (packet.packet() instanceof Packets.EntitySpawn spawn) entities.put(spawn.entityId(), spawn.box());
      else if (packet.packet() instanceof Packets.EntityMove move) entities.put(move.entityId(), move.box());
      else if (packet.packet() instanceof Packets.EntityDespawn despawn) entities.remove(despawn.entityId());

      if (packet.packet() instanceof Packets.Move move) {
        if (move.yaw() != null && Float.isFinite(move.yaw())) {
          if (Float.isFinite(previousYaw)) {
            double delta = Math.abs(Math.IEEEremainder(move.yaw() - previousYaw, 360.0));
            double pitchDelta = move.pitch() == null || !Float.isFinite(previousPitch)
                ? 0.0
                : Math.abs(move.pitch() - previousPitch);
            if (delta >= 75.0 || pitchDelta >= 55.0) snapStreak++;
            else snapStreak = Math.max(0, snapStreak - 1);
          }
          previousYaw = move.yaw();
          if (move.pitch() != null && Float.isFinite(move.pitch())) previousPitch = move.pitch();
        }
      }

      if (packet.packet() instanceof Packets.InteractEntity attack
          && attack.action() == Packets.InteractAction.ATTACK) {
        PredictionFrame frame = frameAt(frames, packet.sequence());
        if (frame == null) continue;

        if (snapStreak >= 3) {
          findings.add(uncertain(playerId, frame, "Aim",
              "attack occurred immediately after a repeated large rotation snap sequence",
              Math.min(1.0, snapStreak / 8.0)));
        }

        BlockBox target = entities.get(attack.entityId());
        if (target == null) {
          findings.add(uncertain(playerId, frame, "Interact",
              "attack referenced an entity whose compensated hitbox was not reconstructed",
              1.0));
        }

        if (previousAttack != null) {
          long delta = packet.receivedNanos() - previousAttack.receivedNanos();
          if (delta >= 0L && delta <= 30_000_000L) {
            findings.add(uncertain(playerId, frame, "MultiActions",
                "multiple attack actions arrived within one client-tick-sized window",
                0.5));
          }
        }
        previousAttack = packet;
      }
    }

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> autoClicker(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames) {
    List<Long> attacks = new ArrayList<>();
    for (Packets.RawPacket packet : packets) {
      if (packet.packet() instanceof Packets.InteractEntity attack
          && attack.action() == Packets.InteractAction.ATTACK) {
        attacks.add(packet.receivedNanos());
      }
    }
    if (attacks.size() < AUTOCLICK_MIN_SAMPLES) return List.of();

    List<Long> intervals = new ArrayList<>();
    for (int i = Math.max(1, attacks.size() - 40); i < attacks.size(); i++) {
      long delta = attacks.get(i) - attacks.get(i - 1);
      if (delta > 0L && delta <= AUTOCLICK_MAX_INTERVAL_NANOS) intervals.add(delta);
    }
    if (intervals.size() < AUTOCLICK_MIN_SAMPLES) return List.of();

    double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0.0);
    if (mean <= 0.0) return List.of();
    double variance = 0.0;
    for (long interval : intervals) {
      double d = interval - mean;
      variance += d * d;
    }
    double cv = Math.sqrt(variance / intervals.size()) / mean;
    if (cv < AUTOCLICK_MAX_CV) {
      PredictionFrame frame = frameAt(frames,
          packets.stream().filter(p -> p.packet() instanceof Packets.InteractEntity)
              .mapToLong(Packets.RawPacket::sequence).max().orElse(0L));
      return List.of(hard(playerId, frame, "Autoclicker",
          String.format(Locale.ROOT,
              "attack intervals were unusually periodic (n=%d, mean=%.1fms, cv=%.4f)",
              intervals.size(), mean / 1_000_000.0, cv),
          Math.min(1.0, (AUTOCLICK_MAX_CV - cv) / AUTOCLICK_MAX_CV)));
    }
    return List.of();
  }

  private static List<ProductionCheckEngine.Finding> packetIntegrity(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    long lastReceived = -1L;
    Set<Short> openTransactions = new HashSet<>();
    Set<Short> acknowledged = new HashSet<>();
    Map<Long, Integer> actionsPerTick = new HashMap<>();

    for (Packets.RawPacket packet : packets) {
      if (lastReceived >= 0L && packet.receivedNanos() < lastReceived) {
        PredictionFrame frame = frameAt(frames, packet.sequence());
        findings.add(hard(playerId, frame, "PacketOrder",
            "packet sequence increased while capture receive time moved backwards",
            1.0));
      }
      lastReceived = Math.max(lastReceived, packet.receivedNanos());

      if (packet.packet() instanceof Packets.WorldTransactionSend send) {
        openTransactions.add(send.id());
      } else if (packet.packet() instanceof Packets.WorldTransactionAck ack) {
        if (!openTransactions.remove(ack.id()) || !acknowledged.add(ack.id())) {
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

    return List.copyOf(findings);
  }

  private static List<ProductionCheckEngine.Finding> scaffoldAndPlacement(
      String playerId,
      List<Packets.RawPacket> packets,
      Map<Long, PredictionFrame> frames) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    Map<Long, Integer> placementsPerTick = new HashMap<>();
    int consecutive = 0;

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
        consecutive++;
      } else {
        consecutive = Math.max(0, consecutive - 1);
      }

      if (consecutive >= 6) {
        findings.add(uncertain(playerId, frame, "Scaffold",
            "sustained near-downward placement pattern accompanied forward movement",
            Math.min(1.0, consecutive / 10.0)));
        consecutive = 0;
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
      Map<Long, PredictionFrame> frames) {
    List<ProductionCheckEngine.Finding> findings = new ArrayList<>();
    Vec3 previous = null;
    for (Packets.RawPacket packet : packets) {
      if (!(packet.packet() instanceof Packets.VehicleMove move)) continue;
      if (previous != null) {
        Vec3 delta = new Vec3(
            move.position().x() - previous.x(),
            move.position().y() - previous.y(),
            move.position().z() - previous.z());
        double horizontal = Math.hypot(delta.x(), delta.z());
        if (horizontal > 3.0 || Math.abs(delta.y()) > 2.5) {
          PredictionFrame frame = frameAt(frames, packet.sequence());
          findings.add(hard(playerId, frame, "Vehicle",
              String.format(Locale.ROOT,
                  "vehicle movement displaced %.3f horizontal / %.3f vertical blocks in one packet",
                  horizontal, Math.abs(delta.y())),
              1.0));
        }
      }
      previous = move.position();
    }
    return List.copyOf(findings);
  }

  private record PendingImpulse(long sequence, long receivedNanos, Vec3 velocity) {}

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
