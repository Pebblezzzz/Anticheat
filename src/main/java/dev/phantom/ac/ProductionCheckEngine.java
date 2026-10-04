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
  private static final Set<String> ENTITY_ACTIONS = Set.of(
      "START_SPRINTING", "STOP_SPRINTING",
      "START_SNEAKING", "STOP_SNEAKING",
      "START_FLYING_WITH_ELYTRA", "START_JUMPING_WITH_HORSE");
  private static final Set<String> WINDOW_CLICK_TYPES = Set.of(
      "PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW", "QUICK_CRAFT", "PICKUP_ALL");

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

  public record Finding(
      String playerId,
      long serverTick,
      String rule,
      Verdict verdict,
      String reason,
      double severity,
      String replayReference) implements java.io.Serializable {
    public Finding {
      Objects.requireNonNull(playerId);
      Objects.requireNonNull(rule);
      Objects.requireNonNull(verdict);
      Objects.requireNonNull(reason);
      Objects.requireNonNull(replayReference);
      if (serverTick < 0 || !Double.isFinite(severity) || severity < 0.0) {
        throw new IllegalArgumentException("invalid finding");
      }
    }

    public String message(String playerName) {
      String name = playerName == null || playerName.isBlank() ? playerId : playerName;
      return "[PhantomAC] " + name + " failed " + rule + " (" + reason + ")";
    }
  }

  public record State(
      int supportingEvents,
      long lastObservationTick,
      long lastAlertTick,
      double violationLevel,
      double lastAlertViolationLevel,
      List<Long> violationTimesMillis) implements java.io.Serializable {
    public State {
      if (supportingEvents < 0 || !Double.isFinite(violationLevel) || violationLevel < 0.0
          || !Double.isFinite(lastAlertViolationLevel) || lastAlertViolationLevel < 0.0) {
        throw new IllegalArgumentException("invalid production violation state");
      }
      violationTimesMillis = List.copyOf(violationTimesMillis);
    }

    public State(
        int supportingEvents,
        long lastObservationTick,
        long lastAlertTick,
        double violationLevel,
        double lastAlertViolationLevel) {
      this(supportingEvents, lastObservationTick, lastAlertTick,
          violationLevel, lastAlertViolationLevel, List.of());
    }

    public static State empty() {
      return new State(0, -1L, -1L, 0.0, 0.0, List.of());
    }

    State addViolation(long tick, long nowMillis, Config config, String rule) {
      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(rule);
      long cutoff = nowMillis - policy.removeViolationsAfterMillis();
      List<Long> active = new ArrayList<>();
      for (long timestamp : violationTimesMillis) if (timestamp > cutoff) active.add(timestamp);
      active.add(nowMillis);
      double level = active.size() * config.violationIncrement();
      return new State(supportingEvents + 1, tick, lastAlertTick, level,
          lastAlertViolationLevel, active);
    }

    State retainActive(long tick, long nowMillis, Config config, String rule) {
      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(rule);
      long cutoff = Math.max(0L, nowMillis - policy.removeViolationsAfterMillis());
      List<Long> active = violationTimesMillis.stream().filter(timestamp -> timestamp > cutoff).toList();
      double level = active.size() * config.violationIncrement();
      return new State(supportingEvents, tick, lastAlertTick, level,
          lastAlertViolationLevel, active);
    }

    State alerted(long tick, double level) {
      return new State(supportingEvents, tick == lastObservationTick ? tick : lastObservationTick,
          tick, level, level, violationTimesMillis);
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
      State next = finding.verdict() == Verdict.IMPOSSIBLE
          ? old.addViolation(finding.serverTick(), nowMillis, config, finding.rule())
          : old.retainActive(finding.serverTick(), nowMillis, config, finding.rule());

      GrimAlertPolicy.Decision policy = config.alertPolicy().forRule(finding.rule());
      int activeCount = next.violationTimesMillis().size();
      double level = activeCount * config.violationIncrement();
      next = new State(next.supportingEvents(), next.lastObservationTick(),
          next.lastAlertTick(), level, next.lastAlertViolationLevel(), next.violationTimesMillis());

      Optional<Finding> alert = Optional.empty();
      Optional<Finding> log = Optional.empty();

      boolean thresholdReached = thresholdReached(level, policy.alert());
      boolean crossedNextInterval = boundaryCrossed(level, policy.alert(), next.lastAlertViolationLevel());

      if (finding.verdict() == Verdict.IMPOSSIBLE
          && thresholdReached && crossedNextInterval) {
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

  public static Report analyze(
      String playerId,
      List<Packets.RawPacket> packets,
      Phase8PredictionRunner.Report movement,
      Config config) {
    Objects.requireNonNull(playerId);
    Objects.requireNonNull(packets);
    Objects.requireNonNull(movement);
    Objects.requireNonNull(config);

    if (!config.enabled() || packets.isEmpty()) return new Report(List.of());

    NavigableMap<Long, PredictionFrame> frames = new TreeMap<>();
    for (PredictionFrame frame : movement.frames()) frames.put(frame.sequence(), frame);

    Map<Integer, BlockBox> entities = new HashMap<>();
    List<Finding> findings = new ArrayList<>();
    long lastServerTick = movement.frames().isEmpty() ? 0L : movement.frames().getLast().serverTick();

    List<Packets.RawPacket> ordered = packets.stream()
        .sorted(Comparator.comparingLong(Packets.RawPacket::sequence))
        .toList();

    float lastYaw = Float.NaN;
    long lastRotationSequence = -1L;
    int modulo360Streak = 0;
    ArrayDeque<Long> clientTickEndTimes = new ArrayDeque<>();
    Map<Pos, Long> diggingStarts = new HashMap<>();
    Map<Pos, PredictionFrame> diggingStartFrames = new HashMap<>();

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
        entities.put(spawn.entityId(), spawn.box());
        continue;
      }
      if (packet instanceof Packets.EntityMove move) {
        entities.put(move.entityId(), move.box());
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
        BlockBox target = entities.get(attack.entityId());
        if (target == null || frame == null) continue;

        Vec3 eye = eyePosition(frame.observedAfter());
        Vec3 direction = lookDirection(frame.observedAfter().yaw(), frame.observedAfter().pitch());
        double hitDistance = rayEntryDistance(eye, direction, target, config.attackReach());
        double fallbackDistance = pointAabbDistance(eye, target);

        // The packet has no hit vector for an ATTACK action. Use the compensated
        // entity box and a conservative 4-block interaction envelope.
        if (!Double.isFinite(hitDistance)
            && fallbackDistance > config.attackReach() + 0.25) {
          findings.add(uncertainFinding(playerId, serverTick, "Reach",
              "attack ray does not intersect the compensated target within the interaction envelope",
              Math.min(1.0, Math.max(0.0, (fallbackDistance - config.attackReach()) / 2.0)),
              sequence));
        }
      }

      if (packet instanceof Packets.ClientBlockBreak breakPacket && frame != null) {
        Pos pos = breakPacket.position();
        BlockBox block = new BlockBox(pos.x(), pos.y(), pos.z(), pos.x() + 1.0, pos.y() + 1.0, pos.z() + 1.0);
        double distance = pointAabbDistance(eyePosition(frame.observedAfter()), block);
        if (distance > config.blockInteractionReach()) {
          findings.add((distance > config.blockInteractionReach() + 1.0)
              ? finding(playerId, serverTick, "FarBreak",
                  String.format(Locale.ROOT, "block distance %.3f is more than 1 block beyond %.3f", distance, config.blockInteractionReach()),
                  Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0), sequence)
              : uncertainFinding(playerId, serverTick, "FarBreak",
                  String.format(Locale.ROOT, "block distance %.3f exceeds %.3f", distance, config.blockInteractionReach()),
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
          double distance = pointAabbDistance(eyePosition(frame.observedAfter()), block);
          if (distance > config.blockInteractionReach()) {
            findings.add((distance > config.blockInteractionReach() + 1.0)
                ? finding(playerId, serverTick, "FarPlace",
                    String.format(Locale.ROOT, "block distance %.3f is more than 1 block beyond %.3f", distance, config.blockInteractionReach()),
                    Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0), sequence)
                : uncertainFinding(playerId, serverTick, "FarPlace",
                    String.format(Locale.ROOT, "block distance %.3f exceeds %.3f", distance, config.blockInteractionReach()),
                    Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0),
                    sequence));
          }
        }
      }

      if (packet instanceof Packets.DigAction dig) {
        String action = dig.action();
        if (action.contains("STARTED_DIGGING")) {
          diggingStarts.put(dig.position(), raw.receivedNanos());
          if (frame != null) {
            diggingStartFrames.put(dig.position(), frame);
          } else {
            diggingStartFrames.remove(dig.position());
          }
        } else if (action.contains("FINISHED_DIGGING")) {
          Long started = diggingStarts.remove(dig.position());
          PredictionFrame startedFrame = diggingStartFrames.remove(dig.position());
          PredictionFrame worldFrame = startedFrame != null ? startedFrame : frame;
          if (started != null
              && worldFrame != null
              && frame != null
              && raw.receivedNanos() >= started
              && raw.receivedNanos() - started <= 35_000_000L
              && ("survival".equalsIgnoreCase(frame.observedAfter().gamemode())
                  || "adventure".equalsIgnoreCase(frame.observedAfter().gamemode()))) {
            var state = worldFrame.world().blockAtOrNull(
                dig.position().x(), dig.position().y(), dig.position().z());
            if (state != null
                && !state.isAir()
                && !state.isUnsupported()
                && isSlowBreakBlock(state.blockId())) {
              findings.add((raw.receivedNanos() - started) <= 10_000_000L
                  ? finding(playerId, serverTick, "FastBreak",
                      "a slow-to-break block reached FINISHED_DIGGING within 10 ms of STARTED_DIGGING",
                      1.0, sequence)
                  : uncertainFinding(playerId, serverTick, "FastBreak",
                      "a slow-to-break block reached FINISHED_DIGGING within 35 ms of STARTED_DIGGING",
                      1.0, sequence));
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

    return new Report(findings);
  }

  private static List<Finding> analyzeMovementAnomalies(
      String playerId, List<PredictionFrame> frames) {
    if (frames.isEmpty()) return List.of();

    List<Finding> findings = new ArrayList<>();
    double airborneY = Double.NaN;
    boolean airborneTracking = false;

    for (int index = 0; index < frames.size(); index++) {
      PredictionFrame frame = frames.get(index);
      Packets.Move move = frame.movement();
      if (move.position() == null) {
        airborneTracking = false;
        airborneY = Double.NaN;
        continue;
      }

      dev.phantom.ac.State.Player before = frame.observedBefore();
      dev.phantom.ac.State.Player after = frame.observedAfter();
      long sequence = frame.sequence();
      long tick = frame.serverTick();

      if (isNormalSurvivalMovement(frame)) {
        Vec3 delta = new Vec3(
            after.position().x() - before.position().x(),
            after.position().y() - before.position().y(),
            after.position().z() - before.position().z());
        double horizontal = Math.hypot(delta.x(), delta.z());
        boolean jumping = after.input().map(Simulation.AdvancedInput::jump).orElse(false);

        /*
         * Step: vanilla's normal standing step height is below one block. A
         * >0.65 block vertical rise while remaining grounded, without a jump,
         * is outside the normal collision step envelope and is a strong
         * deterministic signature of a Step-height cheat.
         */
        if (before.onGround() && after.onGround() && !jumping
            && delta.y() > 0.65 && horizontal > 0.05) {
          findings.add(finding(playerId, tick, "Step",
              String.format(Locale.ROOT,
                  "grounded movement rose %.3f blocks in one client tick without a jump",
                  delta.y()),
              1.0, sequence));
        }

        /*
         * Speed: use a deliberately high hard boundary so knockback, sprinting,
         * ice, and normal attribute effects remain below it. Blatant speed
         * clients routinely exceed this single-tick displacement.
         */
        boolean candidateExternalMotion = frame.predictedBefore().stream()
            .anyMatch(candidate -> {
              Phase5Mechanics.MovementEnvironment env = candidate.context().movementEnvironment();
              double speed = Math.hypot(
                  candidate.context().clientVelocity().x(),
                  candidate.context().clientVelocity().z());
              return env.vehicle().active() || env.gliding() || speed > 0.70;
            });
        if (!candidateExternalMotion && !jumping && horizontal > 1.0) {
          findings.add(finding(playerId, tick, "Speed",
              String.format(Locale.ROOT,
                  "survival/adventure movement displaced %.3f blocks horizontally in one client tick",
                  horizontal),
              1.0, sequence));
        }

        /*
         * Flight: upward motion with a non-jump state after the previous tick
         * has already become non-grounded is not a vanilla continuation unless
         * an external vertical effect is present.
         */
        boolean externalVertical = frame.predictedBefore().stream().anyMatch(candidate -> {
          Phase5Mechanics.MovementEffects effects = candidate.context().effects();
          Phase5Mechanics.MovementEnvironment env = candidate.context().movementEnvironment();
          return effects.levitation() || effects.slowFalling()
              || env.fluid() != Phase5Mechanics.Fluid.NONE
              || env.climbable() || env.gliding() || env.vehicle().active();
        });
        if (!externalVertical && !before.onGround() && !after.onGround() && !jumping
            && before.velocity().y() <= 0.05 && delta.y() > 0.16) {
          findings.add(finding(playerId, tick, "Flight",
              String.format(Locale.ROOT,
                  "airborne movement gained %.3f vertical blocks without jump or vertical effect",
                  delta.y()),
              1.0, sequence));
        }

        /*
         * A sustained hover/descent cancellation is also incompatible with
         * vanilla gravity. Requiring the prior observed tick prevents a normal
         * jump apex from becoming a flag.
         */
        if (!externalVertical && !before.onGround() && !after.onGround() && !jumping
            && Math.abs(delta.y()) <= 0.01
            && index > 0) {
          PredictionFrame previous = frames.get(index - 1);
          if (previous.movement().position() != null
              && isNormalSurvivalMovement(previous)
              && !previous.observedAfter().onGround()) {
            Vec3 previousDelta = new Vec3(
                previous.observedAfter().position().x() - previous.observedBefore().position().x(),
                previous.observedAfter().position().y() - previous.observedBefore().position().y(),
                previous.observedAfter().position().z() - previous.observedBefore().position().z());
            boolean currentHover = Math.abs(delta.y()) <= 0.02
                && Math.abs(after.velocity().y()) <= 0.02;
            boolean previousHover = Math.abs(previous.observedAfter().velocity().y()) <= 0.02;
            if (currentHover && previousHover
                && (horizontal > 0.05 || Math.hypot(previousDelta.x(), previousDelta.z()) > 0.05)) {
              findings.add(finding(playerId, tick, "Flight",
                  "airborne vertical velocity remained near zero across consecutive movement ticks without a vertical effect",
                  1.0, sequence));
            }
          }
        }

        /*
         * NoFall: retain the fall origin while the player is genuinely airborne.
         * A multi-block fall that lands while every modeled candidate remains
         * airborne is a deterministic no-fall contradiction.
         */
        if (!before.onGround() && !after.onGround()) {
          if (!airborneTracking) {
            airborneY = before.position().y();
            airborneTracking = true;
          }
        } else if (airborneTracking && after.onGround()) {
          double fallDistance = airborneY - after.position().y();
          boolean physicalLanding = frame.predictedAfter().stream()
              .anyMatch(candidate -> candidate.context().player().onGround());
          if (fallDistance > 3.0 && !physicalLanding) {
            findings.add(finding(playerId, tick, "NoFall",
                String.format(Locale.ROOT,
                    "landing after %.3f blocks of tracked fall has no grounded legitimate candidate",
                    fallDistance),
                1.0, sequence));
          }
          airborneTracking = false;
          airborneY = Double.NaN;
        } else if (after.onGround()) {
          airborneTracking = false;
          airborneY = Double.NaN;
        }
      } else {
        airborneTracking = false;
        airborneY = Double.NaN;
      }

      /*
       * Jesus: an on-ground claim over a fluid block with no collision support
       * below the player is not a normal vanilla standing state. Lily pads and
       * genuine solid support are excluded by the support test.
       */
      if (move.position() != null
          && ("survival".equalsIgnoreCase(after.gamemode())
              || "adventure".equalsIgnoreCase(after.gamemode()))
          && after.onGround()
          && (after.environment() == dev.phantom.ac.State.Environment.WATER
              || after.environment() == dev.phantom.ac.State.Environment.LAVA)) {
        int bx = (int) Math.floor(after.position().x());
        int by = (int) Math.floor(after.position().y());
        int bz = (int) Math.floor(after.position().z());
        if (frame.world().coverageAt(bx, by, bz) == dev.phantom.ac.world.Coverage.KNOWN
            && frame.world().coverageAt(bx, by - 1, bz) == dev.phantom.ac.world.Coverage.KNOWN) {
          BlockState fluid = frame.world().blockAtOrNull(bx, by, bz);
          BlockState below = frame.world().blockAtOrNull(bx, by - 1, bz);
          if (fluid != null && fluid.hasFluidName() && !isSolidSupport(below)) {
            findings.add(finding(playerId, tick, "Jesus",
                "player claimed on-ground while standing on liquid without collision support",
                1.0, sequence));
          }
        }
      }
    }

    return findings;
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
