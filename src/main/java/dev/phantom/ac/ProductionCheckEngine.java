package dev.phantom.ac;

import dev.phantom.ac.Phase8PredictionRunner.PredictionFrame;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.BlockState;

import java.util.*;
import static dev.phantom.ac.Maths.Vec3;

/**
 * Clean-room production check layer for non-movement packet evidence.
 *
 * <p>This layer intentionally consumes compensated/replayed state instead of
 * reading the live Bukkit world from a worker thread. Findings are evidence
 * only; punitive policy remains downstream and requires repeated evidence.</p>
 */
public final class ProductionCheckEngine {
  private static final double WORLD_BORDER = 29_999_999.0;
  /*
   * Small prediction residuals are retained as non-punitive evidence. A larger
   * deterministic residual means the best modeled vanilla explanation itself
   * could not account for the observed displacement.
   */
  private static final double PREDICTION_OFFSET_UNCERTAIN = 0.04;
  private static final double PREDICTION_OFFSET_IMPOSSIBLE = 0.08;
  private static final double PREDICTION_VERTICAL_UNCERTAIN = 0.05;
  private static final double PREDICTION_VERTICAL_IMPOSSIBLE = 0.10;
  private static final Set<String> ENTITY_ACTIONS = Set.of(
      "START_SPRINTING", "STOP_SPRINTING",
      "START_SNEAKING", "STOP_SNEAKING",
      "LEAVE_BED", "OPEN_HORSE_INVENTORY",
      "START_FLYING_WITH_ELYTRA", "START_JUMPING_WITH_HORSE",
      "STOP_JUMPING_WITH_HORSE");
  private static final Set<String> WINDOW_CLICK_TYPES = Set.of(
      "PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW", "QUICK_CRAFT", "PICKUP_ALL");

  /**
   * Persistent packet-side state. The live validator intentionally analyzes only
   * packets since the previous batch, so entity and digging state must survive
   * executor coalescing.
   */
  public static final class SessionState {
    final Map<Integer, EntityHistory> entities = new HashMap<>();
    final Map<Pos, Long> diggingStarts = new HashMap<>();
    final Map<Pos, Double> diggingStartSpeeds = new HashMap<>();
    final Map<Pos, String> diggingStartItems = new HashMap<>();
    final Map<Pos, PredictionFrame> diggingStartFrames = new HashMap<>();

    void prune(long currentSequence) {
      while (diggingStarts.size() > 64) {
        Pos oldest = diggingStarts.keySet().iterator().next();
        diggingStarts.remove(oldest);
      }
      diggingStartFrames.keySet().retainAll(diggingStarts.keySet());
      diggingStartSpeeds.keySet().retainAll(diggingStarts.keySet());
      diggingStartItems.keySet().retainAll(diggingStarts.keySet());
    }
  }

  private ProductionCheckEngine() {}

  public record Config(
      boolean enabled,
      double alertViolationThreshold,
      int resetAfterTicks,
      int alertDebounceTicks,
      double attackReach,
      double blockInteractionReach,
      boolean timerEnabled,
      int timerWindowTicks,
      long timerWindowNanos,
      double violationIncrement,
      double violationDecayPerTick,
      double maximumViolationLevel,
      double alertViolationInterval,
      GrimAlertPolicy.Config alertPolicy) implements java.io.Serializable {
    public Config {
      if (!Double.isFinite(alertViolationThreshold) || alertViolationThreshold <= 0.0
          || resetAfterTicks < 1 || alertDebounceTicks < 0)
        throw new IllegalArgumentException("invalid production check thresholds");
      if (!Double.isFinite(attackReach) || attackReach <= 0.0
          || !Double.isFinite(blockInteractionReach) || blockInteractionReach <= 0.0)
        throw new IllegalArgumentException("interaction ranges must be finite and positive");
      if (timerWindowTicks < 4 || timerWindowNanos <= 0L)
        throw new IllegalArgumentException("invalid timer window");
      if (!Double.isFinite(violationIncrement) || violationIncrement <= 0.0
          || !Double.isFinite(violationDecayPerTick) || violationDecayPerTick < 0.0
          || !Double.isFinite(maximumViolationLevel) || maximumViolationLevel <= 0.0
          || !Double.isFinite(alertViolationInterval) || alertViolationInterval <= 0.0
          || maximumViolationLevel < alertViolationThreshold)
        throw new IllegalArgumentException("invalid production violation buffer");
      Objects.requireNonNull(alertPolicy);
    }

    public Config(
        boolean enabled,
        double alertViolationThreshold,
        int resetAfterTicks,
        int alertDebounceTicks,
        double attackReach,
        double blockInteractionReach) {
      this(enabled, alertViolationThreshold, resetAfterTicks, alertDebounceTicks,
          attackReach, blockInteractionReach, true, 20, 250_000_000L,
          1.0, 0.005, 100.0, 40.0,
          new GrimAlertPolicy.Config(
              List.of(),
              new GrimAlertPolicy.CommandRule(alertViolationThreshold, 40.0),
              GrimAlertPolicy.CommandRule.parse("1:1"),
              300_000L));
    }
  }

  public enum Verdict { CLEAR, IMPOSSIBLE, UNCERTAIN }

  public enum EvidenceClass {
    IMPOSSIBLE,
    HIGHLY_SUSPICIOUS,
    WEAK_HEURISTIC,
    INSUFFICIENT_INFORMATION
  }

  public record Finding(
      String playerId,
      long serverTick,
      String rule,
      Verdict verdict,
      String reason,
      double severity,
      String replayReference,
      EvidenceClass evidenceClass,
      double normalizedScore,
      String independenceKey,
      boolean causalEvidence) implements java.io.Serializable {
    public Finding {
      Objects.requireNonNull(playerId);
      Objects.requireNonNull(rule);
      Objects.requireNonNull(verdict);
      Objects.requireNonNull(reason);
      Objects.requireNonNull(replayReference);
      Objects.requireNonNull(evidenceClass);
      Objects.requireNonNull(independenceKey);
      if (serverTick < 0 || !Double.isFinite(severity) || severity < 0.0
          || !Double.isFinite(normalizedScore) || normalizedScore < 0.0
          || normalizedScore > 1.0 || independenceKey.isBlank()) {
        throw new IllegalArgumentException("invalid finding");
      }
    }

    public Finding(
        String playerId,
        long serverTick,
        String rule,
        Verdict verdict,
        String reason,
        double severity,
        String replayReference) {
      this(
          playerId,
          serverTick,
          rule,
          verdict,
          reason,
          severity,
          replayReference,
          verdict == Verdict.IMPOSSIBLE
              ? EvidenceClass.IMPOSSIBLE
              : clampScore(severity) >= 0.75
                  ? EvidenceClass.HIGHLY_SUSPICIOUS
                  : EvidenceClass.INSUFFICIENT_INFORMATION,
          clampScore(severity),
          independenceFingerprint(rule, verdict, reason),
          verdict == Verdict.IMPOSSIBLE);
    }

    public String message(String playerName) {
      String name = playerName == null || playerName.isBlank() ? playerId : playerName;
      return "[PhantomAC] " + name + " failed " + rule + " (" + reason + ")";
    }
  }

  private record EvidenceStamp(long seenMillis, double score) {
    EvidenceStamp {
      if (seenMillis < 0L || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
        throw new IllegalArgumentException("invalid evidence stamp");
      }
    }
  }

  public record State(
      int supportingEvents,
      long lastObservationTick,
      long lastAlertTick,
      double violationLevel,
      double lastAlertViolationLevel,
      List<Long> violationTimesMillis,
      Map<String, EvidenceStamp> activeEvidence) implements java.io.Serializable {
    public State {
      if (supportingEvents < 0 || !Double.isFinite(violationLevel) || violationLevel < 0.0
          || !Double.isFinite(lastAlertViolationLevel) || lastAlertViolationLevel < 0.0) {
        throw new IllegalArgumentException("invalid production violation state");
      }
      violationTimesMillis = List.copyOf(violationTimesMillis);
      activeEvidence = Map.copyOf(activeEvidence);
    }

    public State(
        int supportingEvents,
        long lastObservationTick,
        long lastAlertTick,
        double violationLevel,
        double lastAlertViolationLevel) {
      this(supportingEvents, lastObservationTick, lastAlertTick,
          violationLevel, lastAlertViolationLevel, List.of(), Map.of());
    }

    public static State empty() {
      return new State(0, -1L, -1L, 0.0, 0.0, List.of(), Map.of());
    }

    State addViolation(
        Finding finding,
        long tick,
        long nowMillis,
        Config config) {
      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(finding.rule());
      long cutoff = Math.max(0L, nowMillis - policy.removeViolationsAfterMillis());
      Map<String, EvidenceStamp> active = new LinkedHashMap<>();
      for (var entry : activeEvidence.entrySet()) {
        if (entry.getValue().seenMillis() > cutoff) active.put(entry.getKey(), entry.getValue());
      }
      boolean independent = !active.containsKey(finding.independenceKey());
      if (independent) {
        active.put(finding.independenceKey(), new EvidenceStamp(nowMillis, finding.normalizedScore()));
      }

      double rawLevel = active.values().stream()
          .mapToDouble(EvidenceStamp::score)
          .sum() * config.violationIncrement();
      long gapTicks = lastObservationTick >= 0L && tick >= lastObservationTick
          ? tick - lastObservationTick : 0L;
      double level = Math.min(
          config.maximumViolationLevel(),
          Math.max(0.0, rawLevel - gapTicks * config.violationDecayPerTick()));

      List<Long> timestamps = active.values().stream()
          .map(EvidenceStamp::seenMillis)
          .sorted()
          .toList();
      return new State(
          supportingEvents + (independent ? 1 : 0),
          tick,
          lastAlertTick,
          level,
          lastAlertViolationLevel,
          timestamps,
          active);
    }

    State retainActive(long tick, long nowMillis, Config config, String rule) {
      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(rule);
      long cutoff = Math.max(0L, nowMillis - policy.removeViolationsAfterMillis());
      Map<String, EvidenceStamp> active = new LinkedHashMap<>();
      for (var entry : activeEvidence.entrySet()) {
        if (entry.getValue().seenMillis() > cutoff) active.put(entry.getKey(), entry.getValue());
      }
      double rawLevel = active.values().stream().mapToDouble(EvidenceStamp::score).sum()
          * config.violationIncrement();
      long gapTicks = lastObservationTick >= 0L && tick >= lastObservationTick
          ? tick - lastObservationTick : 0L;
      double level = Math.min(
          config.maximumViolationLevel(),
          Math.max(0.0, rawLevel - gapTicks * config.violationDecayPerTick()));
      List<Long> timestamps = active.values().stream()
          .map(EvidenceStamp::seenMillis)
          .sorted()
          .toList();
      return new State(supportingEvents, tick, lastAlertTick, level,
          lastAlertViolationLevel, timestamps, active);
    }

    State alerted(long tick, double level) {
      return new State(supportingEvents, tick == lastObservationTick ? tick : lastObservationTick,
          tick, level, level, violationTimesMillis, activeEvidence);
    }
  }

  public record Accumulator(Map<String, State> rules) implements java.io.Serializable {
    public Accumulator { rules = Map.copyOf(rules); }
    public static Accumulator empty() { return new Accumulator(Map.of()); }

    public Result accept(Finding finding, Config config) {
      return accept(finding, config, Math.max(0L, finding.serverTick()) * 50L);
    }

    public Result accept(Finding finding, Config config, long nowMillis) {
      Objects.requireNonNull(finding);
      Objects.requireNonNull(config);
      if (!config.enabled()) return new Result(this, Optional.empty(), Optional.empty());

      Map<String, State> updated = new LinkedHashMap<>(rules);
      State old = rules.getOrDefault(finding.rule(), State.empty());
      if (old.lastObservationTick() >= 0L
          && finding.serverTick() >= old.lastObservationTick()
          && finding.serverTick() - old.lastObservationTick() >= config.resetAfterTicks()) {
        old = State.empty();
      }
      State next = finding.verdict() == Verdict.IMPOSSIBLE
          ? old.addViolation(finding, finding.serverTick(), nowMillis, config)
          : old.retainActive(finding.serverTick(), nowMillis, config, finding.rule());

      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(finding.rule());
      double level = Math.min(config.maximumViolationLevel(), next.violationLevel());
      next = new State(next.supportingEvents(), next.lastObservationTick(),
          next.lastAlertTick(), level, next.lastAlertViolationLevel(),
          next.violationTimesMillis(), next.activeEvidence());

      Optional<Finding> alert = Optional.empty();
      Optional<Finding> log = Optional.empty();

      double alertThreshold = Math.max(config.alertViolationThreshold(), policy.alert().threshold());
      double alertInterval = Math.max(config.alertViolationInterval(), policy.alert().interval());
      GrimAlertPolicy.CommandRule alertRule =
          new GrimAlertPolicy.CommandRule(alertThreshold, alertInterval);
      boolean thresholdReached = level + 1.0e-9 >= alertRule.threshold();
      boolean crossedNextInterval = boundaryCrossed(
          level, alertRule, next.lastAlertViolationLevel());
      boolean debounceSatisfied =
          next.lastAlertTick() < 0L
              || finding.serverTick() - next.lastAlertTick() >= config.alertDebounceTicks();

      if (finding.verdict() == Verdict.IMPOSSIBLE
          && thresholdReached && crossedNextInterval && debounceSatisfied) {
        alert = Optional.of(finding);
        next = next.alerted(finding.serverTick(), level);
      }

      if (finding.verdict() == Verdict.IMPOSSIBLE
          && thresholdReached(level, policy.log())
          && boundaryCrossed(level, policy.log(), 0.0)) {
        log = Optional.of(finding);
      }

      updated.put(finding.rule(), next);
      return new Result(new Accumulator(updated), alert, log);
    }

    private static boolean thresholdReached(double level, GrimAlertPolicy.CommandRule rule) {
      return level + 1e-9 >= rule.threshold();
    }

    private static boolean boundaryCrossed(double level, GrimAlertPolicy.CommandRule rule, double lastLevel) {
      if (rule.interval() == 0.0) {
        return level >= rule.threshold() && lastLevel < rule.threshold();
      }
      if (lastLevel <= 0.0) return level + 1e-9 >= rule.threshold();
      return level + 1e-9 >= lastLevel + rule.interval();
    }
  }

public record Result(Accumulator state, Optional<Finding> alert, Optional<Finding> log) {
    public Result {
      Objects.requireNonNull(state);
      Objects.requireNonNull(alert);
      Objects.requireNonNull(log);
    }
  }

  public record Report(List<Finding> findings) {
    public Report { findings = List.copyOf(findings); }
  }

  private static double clampScore(double score) {
    return Math.max(0.0, Math.min(1.0, score));
  }

  private static String independenceFingerprint(String rule, Verdict verdict, String reason) {
    String normalized = reason
        .replaceAll("[-+]?\\d+(?:\\.\\d+)?", "N")
        .replaceAll("\\s+", " ")
        .trim();
    if (normalized.length() > 96) normalized = normalized.substring(0, 96);
    return rule + "|" + verdict + "|" + normalized;
  }

  private static Finding typedFinding(
      String playerId,
      long serverTick,
      String rule,
      Verdict verdict,
      String reason,
      double score,
      String replayReference,
      EvidenceClass evidenceClass,
      boolean causalEvidence) {
    return new Finding(
        playerId, serverTick, rule, verdict, reason, score, replayReference,
        evidenceClass, clampScore(score),
        independenceFingerprint(rule, verdict, reason),
        causalEvidence);
  }

  public static Report analyze(
      String playerId,
      List<Packets.RawPacket> packets,
      Phase8PredictionRunner.Report movement,
      Config config) {
    return analyze(playerId, packets, movement, config, new SessionState(), new AccuracyChecks.State());
  }

  public static Report analyze(
      String playerId,
      List<Packets.RawPacket> packets,
      Phase8PredictionRunner.Report movement,
      Config config,
      SessionState state) {
    return analyze(playerId, packets, movement, config, state, new AccuracyChecks.State());
  }

  public static Report analyze(
      String playerId,
      List<Packets.RawPacket> packets,
      Phase8PredictionRunner.Report movement,
      Config config,
      SessionState state,
      AccuracyChecks.State accuracyState) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(packets);
    Objects.requireNonNull(movement);
    Objects.requireNonNull(config);
    Objects.requireNonNull(state);
    Objects.requireNonNull(accuracyState);

    if (!config.enabled() || packets.isEmpty()) return new Report(List.of());

    NavigableMap<Long, PredictionFrame> frames = new TreeMap<>();
    for (PredictionFrame frame : movement.frames()) frames.put(frame.sequence(), frame);

    Map<Integer, EntityHistory> entities = state.entities;
    List<Finding> findings = new ArrayList<>();
    long lastServerTick = movement.frames().isEmpty() ? 0L : movement.frames().getLast().serverTick();

    List<Packets.RawPacket> ordered = packets.stream()
        .sorted(Comparator.comparingLong(Packets.RawPacket::sequence))
        .toList();

    float lastYaw = Float.NaN;
    long lastRotationSequence = -1L;
    int modulo360Streak = 0;
    ArrayDeque<Long> clientTickEndTimes = new ArrayDeque<>();

    for (Packets.RawPacket raw : ordered) {
      Packets.Packet packet = raw.packet();
      long sequence = raw.sequence();
      PredictionFrame frame = frames.floorEntry(sequence) == null
          ? null
          : frames.floorEntry(sequence).getValue();
      long serverTick = frame == null ? lastServerTick : frame.serverTick();
      lastServerTick = serverTick;

      if (packet instanceof Packets.ClientTickEnd && config.timerEnabled()) {
        clientTickEndTimes.addLast(raw.receivedNanos());
        while (clientTickEndTimes.size() > config.timerWindowTicks()) {
          clientTickEndTimes.removeFirst();
        }
        if (clientTickEndTimes.size() == config.timerWindowTicks()) {
          long elapsed = raw.receivedNanos() - clientTickEndTimes.getFirst();
          if (elapsed >= 0L && elapsed <= config.timerWindowNanos()) {
            double severity = Math.min(1.0,
                (double) config.timerWindowNanos() / Math.max(1L, elapsed) / 4.0);
            findings.add(uncertainFinding(playerId, serverTick, "TimerBurst",
                "client tick-boundary packets arrived materially faster than 20 TPS",
                severity, sequence));
          }
        }
      }

      if (packet instanceof Packets.EntitySpawn spawn) {
        entities.put(spawn.entityId(), new EntityHistory(raw.receivedNanos(), spawn.box()));
        continue;
      }
      if (packet instanceof Packets.EntityMove move) {
        entities.computeIfAbsent(
                move.entityId(), ignored -> new EntityHistory(raw.receivedNanos(), move.box()))
            .add(raw.receivedNanos(), move.box());
        continue;
      }
      if (packet instanceof Packets.EntityDespawn despawn) {
        entities.remove(despawn.entityId());
        continue;
      }

      if (packet instanceof Packets.EntityAction entityAction) {
        if (!ENTITY_ACTIONS.contains(entityAction.action())) {
          findings.add(finding(playerId, serverTick, "EntityAction",
              "entity-action opcode is outside the 1.21.11 client action set",
              1.0, sequence));
        } else if (entityAction.jumpBoost() != 0
            && (!"START_JUMPING_WITH_HORSE".equals(entityAction.action())
                || Math.abs(entityAction.jumpBoost()) > 100)) {
          findings.add(finding(playerId, serverTick, "EntityAction",
              "jump boost is non-zero for a non-horse action or exceeds the protocol safety envelope",
              1.0, sequence));
        }
      }

      if (packet instanceof Packets.HeldItemChange heldItem) {
        if (heldItem.slot() < 0 || heldItem.slot() > 8) {
          findings.add(finding(playerId, serverTick, "HeldItemSlot",
              "held-item slot is outside the legal hotbar range 0..8",
              1.0, sequence));
        }
      }

      if (packet instanceof Packets.Move move) {
        /*
         * GroundSpoof is a prediction check, not a packet-shape check. Match Grim's
         * conservative boundary: only evaluate position-bearing, non-stationary
         * movement after prediction completed, and only when every retained candidate
         * agrees on the physical ground state. Mixed candidates or uncertainty mean
         * the ground contradiction is not exhaustively proven.
         */
        boolean positionBearingMovement = move.position() != null
            && frame != null
            && !positionExactlyMatches(frame.observedBefore().position(), frame.observedAfter().position());
        boolean predictionGroundDeterministic = frame != null
            && frame.predictedAfter() != null
            && !frame.predictedAfter().isEmpty()
            && frame.predictedAfter().stream().map(candidate -> candidate.context().player().onGround())
                .distinct().count() == 1L
            && frame.uncertaintySources().isEmpty();
        if (positionBearingMovement && predictionGroundDeterministic && move.onGround() != null) {
          boolean predictedGround = frame.predictedAfter().iterator().next().context().player().onGround();
          if (move.onGround() != predictedGround) {
            findings.add(finding(playerId, serverTick, "GroundSpoof",
                "the claimed ground state contradicts the deterministic predicted physical ground state",
                1.0, sequence));
          }
        }

        if (move.position() != null) {
          Vec3 p = move.position();
          if (!finite(p) || Math.abs(p.x()) > WORLD_BORDER || Math.abs(p.z()) > WORLD_BORDER
              || Math.abs(p.y()) > Integer.MAX_VALUE) {
            findings.add(finding(playerId, serverTick, "PacketPosition",
                "position outside the valid 1.21 world coordinate envelope", 1.0, sequence));
          }
        }

        if (move.pitch() != null) {
          float pitch = move.pitch();
          if (!Float.isFinite(pitch) || pitch < -90.0f - 1.0e-3f || pitch > 90.0f + 1.0e-3f) {
            findings.add(finding(playerId, serverTick, "PacketRotation",
                "pitch outside the legal player rotation interval", 1.0, sequence));
          }
        }

        if (move.yaw() != null && Float.isFinite(move.yaw())) {
          if (Float.isFinite(lastYaw)) {
            float delta = move.yaw() - lastYaw;
            double wrapped = Math.IEEEremainder(delta, 360.0);
            if (Math.abs(delta) > 359.5f && Math.abs(wrapped) < 0.5) {
              modulo360Streak++;
            } else {
              modulo360Streak = 0;
            }
            if (modulo360Streak >= 3) {
              findings.add(uncertainFinding(playerId, serverTick, "AimModulo360",
                  "repeated yaw changes collapse modulo 360", Math.min(1.0, modulo360Streak / 10.0), sequence));
            }
          }
          lastYaw = move.yaw();
          lastRotationSequence = sequence;
        } else if (move.yaw() != null && !Float.isFinite(move.yaw())) {
          findings.add(finding(playerId, serverTick, "PacketRotation",
              "non-finite yaw", 1.0, sequence));
        }
      }

      if (packet instanceof Packets.InventoryClick click) {
        if (click.slot() < -999 || click.slot() > 127) {
          findings.add(finding(playerId, serverTick, "InventorySlot",
              "container click slot is outside the protocol slot envelope",
              1.0, sequence));
        }
        if (!WINDOW_CLICK_TYPES.contains(click.clickType())) {
          findings.add(finding(playerId, serverTick, "InventoryClickType",
              "container click type is not a legal 1.21.11 protocol value",
              1.0, sequence));
        } else {
          boolean invalidButton = switch (click.clickType()) {
            case "PICKUP", "QUICK_MOVE", "CLONE" -> click.button() < 0 || click.button() > 2;
            case "SWAP" -> (click.button() < 0 || click.button() > 8) && click.button() != 40;
            case "THROW" -> click.button() != 0 && click.button() != 1;
            case "QUICK_CRAFT" -> click.button() < 0 || click.button() == 3 || click.button() == 7 || click.button() > 10;
            case "PICKUP_ALL" -> click.button() != 0;
            default -> false;
          };
          if (invalidButton) {
            findings.add(finding(playerId, serverTick, "InventoryButton",
                "container click button is invalid for its click type",
                1.0, sequence));
          }
        }
      }

      if (packet instanceof Packets.InteractEntity attack
          && attack.action() == Packets.InteractAction.ATTACK) {
        EntityHistory targetHistory = entities.get(attack.entityId());
        BlockBox target = targetHistory == null
            ? null
            : targetHistory.compensated(raw.receivedNanos());
        if (frame == null) continue;

        if (target == null) {
          findings.add(uncertainFinding(playerId, serverTick, "Hitbox",
              "attack target has no causally reconstructed client-visible hitbox",
              1.0, sequence));
          continue;
        }

        Vec3 eye = eyePosition(frame.observedAfter());
        Vec3 direction = lookDirection(frame.observedAfter().yaw(), frame.observedAfter().pitch());
        double rayDistance = rayEntryDistance(eye, direction, target, config.attackReach());
        double pointDistance = pointAabbDistance(eye, target);

        if (!Double.isFinite(rayDistance)) {
          if (pointDistance > config.attackReach() + 1.0e-6) {
            findings.add(finding(playerId, serverTick, "Reach",
                String.format(Locale.ROOT,
                    "attack target hitbox was %.3f blocks from the eye, beyond configured %.3f block reach",
                    pointDistance, config.attackReach()),
                Math.min(1.0,
                    (pointDistance - config.attackReach())
                        / Math.max(0.5, config.attackReach() * 0.5)),
                sequence));
          } else if (frame.uncertaintySources().isEmpty()) {
            findings.add(finding(playerId, serverTick, "Hitbox",
                String.format(Locale.ROOT,
                    "target was within %.3f blocks but the observed client view ray never entered the reconstructed hitbox",
                    config.attackReach()),
                Math.min(1.0, Math.max(0.0,
                    (config.attackReach() - pointDistance)
                        / Math.max(0.5, config.attackReach()))),
                sequence));
          } else {
            findings.add(uncertainFinding(playerId, serverTick, "Hitbox",
                "target geometry is within interaction range, but movement/timing uncertainty prevents a hard ray contradiction",
                0.75, sequence));
          }
        }
      }

      if (packet instanceof Packets.ClientBlockBreak breakPacket && frame != null) {
        Pos pos = breakPacket.position();
        BlockBox block = new BlockBox(pos.x(), pos.y(), pos.z(), pos.x() + 1.0, pos.y() + 1.0, pos.z() + 1.0);
        Vec3 eye = eyePosition(frame.observedAfter());
        double distance = pointAabbDistance(eye, block);
        double rayDistance = rayEntryDistance(
            eye,
            lookDirection(frame.observedAfter().yaw(), frame.observedAfter().pitch()),
            block,
            config.blockInteractionReach());
        if (!Double.isFinite(rayDistance) && distance > config.blockInteractionReach()) {
          findings.add((distance > config.blockInteractionReach() + 1.0)
              ? finding(playerId, serverTick, "FarBreak",
                  String.format(Locale.ROOT,
                      "look ray misses the target block within %.3f blocks and the target is %.3f blocks away",
                      config.blockInteractionReach(), distance),
                  Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0), sequence)
              : uncertainFinding(playerId, serverTick, "FarBreak",
                  String.format(Locale.ROOT,
                      "look ray misses the target block within %.3f blocks",
                      config.blockInteractionReach()),
                  Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0),
                  sequence));
        }
      }

      if (packet instanceof Packets.BlockPlace place) {
        if (place.cursorPresent()) {
          Vec3 cursor = place.cursor();
          if (cursor.x() < -1.0e-4 || cursor.x() > 1.0001
              || cursor.y() < -1.0e-4 || cursor.y() > 1.0001
              || cursor.z() < -1.0e-4 || cursor.z() > 1.0001) {
            findings.add(finding(playerId, serverTick, "BlockPlaceCursor",
                "block-use cursor coordinates are outside the legal 0..1 hitbox envelope",
                1.0, sequence));
          }
        }
        if (place.faceId() < 0 || place.faceId() > 5) {
          findings.add(finding(playerId, serverTick, "BlockPlaceFace",
              "block-use face id is outside the legal 0..5 range",
              1.0, sequence));
        }

        if (frame != null) {
          Pos pos = place.position();
          BlockBox block = new BlockBox(pos.x(), pos.y(), pos.z(), pos.x() + 1.0, pos.y() + 1.0, pos.z() + 1.0);
          Vec3 eye = eyePosition(frame.observedAfter());
          double distance = pointAabbDistance(eye, block);
          double rayDistance = rayEntryDistance(
              eye,
              lookDirection(frame.observedAfter().yaw(), frame.observedAfter().pitch()),
              block,
              config.blockInteractionReach());
          if (!Double.isFinite(rayDistance) && distance > config.blockInteractionReach()) {
            findings.add((distance > config.blockInteractionReach() + 1.0)
                ? finding(playerId, serverTick, "FarPlace",
                    String.format(Locale.ROOT,
                        "look ray misses the target block within %.3f blocks and the target is %.3f blocks away",
                        config.blockInteractionReach(), distance),
                    Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0), sequence)
                : uncertainFinding(playerId, serverTick, "FarPlace",
                    String.format(Locale.ROOT,
                        "look ray misses the target block within %.3f blocks",
                        config.blockInteractionReach()),
                    Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0),
                    sequence));
          }
        }
      }

      if (packet instanceof Packets.DigAction dig) {
        String action = dig.action();
        if (action.contains("STARTED_DIGGING")) {
          state.diggingStarts.put(dig.position(), raw.receivedNanos());
          if (frame != null) {
            state.diggingStartFrames.put(dig.position(), frame);
          } else {
            state.diggingStartFrames.remove(dig.position());
          }
        } else if (action.contains("FINISHED_DIGGING")) {
          Long started = state.diggingStarts.remove(dig.position());
          Double startSpeed = state.diggingStartSpeeds.remove(dig.position());
          String startItem = state.diggingStartItems.remove(dig.position());
          PredictionFrame startedFrame = state.diggingStartFrames.remove(dig.position());
          PredictionFrame worldFrame = startedFrame != null ? startedFrame : frame;
          if (started != null
              && worldFrame != null
              && frame != null
              && raw.receivedNanos() >= started
              && raw.receivedNanos() - started <= 35_000_000L
              && ("survival".equalsIgnoreCase(frame.observedAfter().gamemode())
                  || "adventure".equalsIgnoreCase(frame.observedAfter().gamemode()))) {
            var blockState = worldFrame.world().blockAtOrNull(
                dig.position().x(), dig.position().y(), dig.position().z());
            if (blockState != null && !blockState.isAir() && !blockState.isUnsupported()) {
              long startClientTick = startedFrame == null ? -1L : startedFrame.clientTick();
              long finishClientTick = frame.clientTick();
              long clientTickDelta = startClientTick >= 0L && finishClientTick >= startClientTick
                  ? finishClientTick - startClientTick
                  : Long.MAX_VALUE;
              Double finishSpeed = breakPacket.breakSpeedPerTick();
              double conservativeMaxSpeed = Math.max(
                  startSpeed == null ? 0.0 : startSpeed,
                  finishSpeed == null ? 0.0 : finishSpeed);
              boolean speedKnown = conservativeMaxSpeed > 0.0
                  && Double.isFinite(conservativeMaxSpeed)
                  && startSpeed != null
                  && finishSpeed != null;
              if (speedKnown && clientTickDelta != Long.MAX_VALUE) {
                double maximumVanillaProgress =
                    Math.max(0.0, clientTickDelta) * conservativeMaxSpeed;
                if (maximumVanillaProgress + 1.0e-6 < 1.0) {
                  findings.add(finding(playerId, serverTick, "FastBreak",
                      String.format(Locale.ROOT,
                          "finished digging after %d client ticks, but the captured server break speed permits at most %.3f progress",
                          clientTickDelta, maximumVanillaProgress),
                      Math.min(1.0, 1.0 - maximumVanillaProgress), sequence));
                }
              } else if (clientTickDelta != Long.MAX_VALUE
                  && raw.receivedNanos() - started <= 35_000_000L) {
                findings.add(uncertainFinding(playerId, serverTick, "FastBreak",
                    "finished digging unusually quickly, but complete server break-speed state was not captured across the interval",
                    0.85, sequence));
              }
              if (startItem != null && !startItem.equals(breakPacket.heldItemType())) {
                findings.add(uncertainFinding(playerId, serverTick, "InventoryState",
                    "held tool changed during a single block-break interval; break-speed causality was segmented",
                    0.65, sequence));
              }
            }
          }
        }
      }

      // VehicleMove is captured separately so future vehicle prediction can consume
      // exact client vehicle claims. It is not independently punished here because
      // passenger offsets, vehicle interpolation, and causal server vehicle state
      // must be modeled before a spoof verdict is sound.
    }

    if (lastRotationSequence < 0) {
      // No rotation packet means there is no aim evidence; intentionally silent.
    }

    findings.addAll(analyzeMovementAnomalies(playerId, movement.frames()));
    findings.addAll(AccuracyChecks.analyze(playerId, ordered, movement.frames(), accuracyState));

    state.prune(ordered.isEmpty() ? 0L : ordered.getLast().sequence());
    return new Report(findings);
  }

  private static List<Finding> analyzeMovementAnomalies(
      String playerId, List<PredictionFrame> frames) {
    if (frames.isEmpty()) return List.of();

    List<Finding> findings = new ArrayList<>();
    for (PredictionFrame frame : frames) {
      Packets.Move move = frame.movement();
      if (move.position() == null) continue;

      State.Player before = frame.observedBefore();
      State.Player after = frame.observedAfter();
      if (!isNormalSurvivalMovement(frame)) continue;

      Vec3 delta = new Vec3(
          after.position().x() - before.position().x(),
          after.position().y() - before.position().y(),
          after.position().z() - before.position().z());
      double horizontal = Math.hypot(delta.x(), delta.z());
      boolean jumping = after.input().map(Simulation.AdvancedInput::jump).orElse(false);

      // Step remains a distinct collision-envelope contradiction; Speed/Flight/NoFall
      // are owned by the prediction-derived movement evidence layer in AccuracyChecks.
      if (before.onGround() && after.onGround() && !jumping
          && delta.y() > 0.65 && horizontal > 0.05
          && predictionContradictsObservedPosition(frame, 0.08)) {
        findings.add(finding(playerId, frame.serverTick(), "Step",
            String.format(Locale.ROOT,
                "grounded movement rose %.3f blocks in one client tick without a jump",
                delta.y()),
            1.0, frame.sequence()));
      }

      if (move.position() != null
          && ("survival".equalsIgnoreCase(after.gamemode())
              || "adventure".equalsIgnoreCase(after.gamemode()))
          && after.onGround()
          && (after.environment() == State.Environment.WATER
              || after.environment() == State.Environment.LAVA)) {
        int bx = (int) Math.floor(after.position().x());
        int by = (int) Math.floor(after.position().y());
        int bz = (int) Math.floor(after.position().z());
        if (frame.world().coverageAt(bx, by, bz) == dev.phantom.ac.world.Coverage.KNOWN
            && frame.world().coverageAt(bx, by - 1, bz) == dev.phantom.ac.world.Coverage.KNOWN) {
          BlockState fluid = frame.world().blockAtOrNull(bx, by, bz);
          BlockState below = frame.world().blockAtOrNull(bx, by - 1, bz);
          if (fluid != null && fluid.hasFluidName() && !isSolidSupport(below)) {
            findings.add(finding(playerId, frame.serverTick(), "Jesus",
                "player claimed on-ground while standing on liquid without collision support",
                1.0, frame.sequence()));
          }
        }
      }
    }
    return List.copyOf(findings);
  }

  private static boolean isNormalSurvivalMovement(PredictionFrame frame) {
    dev.phantom.ac.State.Player player = frame.observedAfter();
    if (!"survival".equalsIgnoreCase(player.gamemode())
        && !"adventure".equalsIgnoreCase(player.gamemode())) {
      return false;
    }
    if (player.environment() != dev.phantom.ac.State.Environment.DRY) return false;
    for (Phase6Reachability.Candidate candidate : frame.predictedBefore()) {
      Phase5Mechanics.MovementEnvironment env = candidate.context().movementEnvironment();
      if (env.fluid() != Phase5Mechanics.Fluid.NONE
          || env.climbable() || env.gliding() || env.vehicle().active()) {
        return false;
      }
    }
    return true;
  }

  private static boolean predictionContradictsObservedPosition(
      PredictionFrame frame, double minimumResidual) {
    if (frame == null
        || !frame.uncertaintySources().isEmpty()
        || frame.predictedAfter().isEmpty()
        || frame.observedAfter().position() == null) {
      return false;
    }
    Vec3 observed = frame.observedAfter().position();
    return frame.predictedAfter().stream()
        .allMatch(candidate ->
            distanceSquared(candidate.context().player().position(), observed)
                > minimumResidual * minimumResidual);
  }

  private static boolean isSolidSupport(BlockState state) {
    if (state == null || state.isAir() || state.isUnsupported() || state.hasFluidName()) return false;
    return switch (state.variant()) {
      case NO_COLLISION_SPECIAL -> false;
      default -> true;
    };
  }

  private static Finding finding(String playerId, long serverTick, String rule,
                                 String reason, double severity, long sequence) {
    return new Finding(
        playerId, serverTick, rule, Verdict.IMPOSSIBLE, reason, severity,
        "production-check:" + playerId + ":" + rule + ":" + sequence);
  }

  private static Finding uncertainFinding(String playerId, long serverTick, String rule,
                                          String reason, double severity, long sequence) {
    return new Finding(
        playerId, serverTick, rule, Verdict.UNCERTAIN, reason, severity,
        "production-check:" + playerId + ":" + rule + ":" + sequence);
  }

  private static boolean isSlowBreakBlock(String blockId) {
    return switch (blockId) {
      case "minecraft:obsidian",
          "minecraft:crying_obsidian",
          "minecraft:respawn_anchor",
          "minecraft:netherite_block",
          "minecraft:ancient_debris",
          "minecraft:deepslate",
          "minecraft:reinforced_deepslate" -> true;
      default -> blockId.endsWith("_ore")
          || blockId.equals("minecraft:stone")
          || blockId.equals("minecraft:deepslate_bricks");
    };
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

    BlockBox compensated(long interactionNanos) {
      if (samples.isEmpty()) return null;
      EntitySample before = null;
      EntitySample after = null;
      for (EntitySample sample : samples) {
        if (sample.receivedNanos() <= interactionNanos) {
          before = sample;
        } else {
          after = sample;
          break;
        }
      }
      if (before == null) return samples.getFirst().box();
      if (after == null) return samples.getLast().box();
      long span = after.receivedNanos() - before.receivedNanos();
      if (span <= 0L) return before.box();
      double alpha = Math.max(0.0, Math.min(1.0,
          (interactionNanos - before.receivedNanos()) / (double) span));
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


  private static boolean positionExactlyMatches(Vec3 a, Vec3 b) {
    return a != null && b != null
        && Double.doubleToLongBits(a.x()) == Double.doubleToLongBits(b.x())
        && Double.doubleToLongBits(a.y()) == Double.doubleToLongBits(b.y())
        && Double.doubleToLongBits(a.z()) == Double.doubleToLongBits(b.z());
  }

  private static boolean finite(Vec3 v) {
    return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
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

  private static double pointAabbDistance(Vec3 point, BlockBox box) {
    double dx = point.x() < box.minX() ? box.minX() - point.x()
        : point.x() > box.maxX() ? point.x() - box.maxX() : 0.0;
    double dy = point.y() < box.minY() ? box.minY() - point.y()
        : point.y() > box.maxY() ? point.y() - box.maxY() : 0.0;
    double dz = point.z() < box.minZ() ? box.minZ() - point.z()
        : point.z() > box.maxZ() ? point.z() - box.maxZ() : 0.0;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /**
   * Returns the first non-negative ray parameter at which the ray enters the
   * AABB, or POSITIVE_INFINITY when no hit occurs before maxDistance.
   */
  private static double rayEntryDistance(Vec3 origin, Vec3 direction, BlockBox box, double maxDistance) {
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
      if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
      tMin = Math.max(tMin, t1);
      tMax = Math.min(tMax, t2);
      if (tMin > tMax) return Double.POSITIVE_INFINITY;
    }
    return tMin >= 0.0 && tMin <= maxDistance ? tMin : Double.POSITIVE_INFINITY;
  }

  private static double distanceSquared(Vec3 a, Vec3 b) {
    double dx = a.x() - b.x();
    double dy = a.y() - b.y();
    double dz = a.z() - b.z();
    return dx * dx + dy * dy + dz * dz;
  }

  public static Config defaultConfig() {
    return new Config(
        true, 100.0, 40, 20, 4.0, 5.0,
        true, 20, 250_000_000L,
        1.0, 0.005, 100.0, 40.0, GrimAlertPolicy.Config.defaults());
  }
}
