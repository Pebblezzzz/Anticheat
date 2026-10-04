package dev.phantom.ac;

import dev.phantom.ac.Phase8PredictionRunner.PredictionFrame;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.Pos;

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

  private ProductionCheckEngine() {}

  public record Config(
      boolean enabled,
      int minimumObservations,
      int resetAfterTicks,
      int alertDebounceTicks,
      double attackReach,
      double blockInteractionReach) implements java.io.Serializable {
    public Config {
      if (minimumObservations < 1 || resetAfterTicks < 1 || alertDebounceTicks < 0)
        throw new IllegalArgumentException("invalid production check thresholds");
      if (!Double.isFinite(attackReach) || attackReach <= 0.0
          || !Double.isFinite(blockInteractionReach) || blockInteractionReach <= 0.0)
        throw new IllegalArgumentException("interaction ranges must be finite and positive");
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
      long lastAlertTick) implements java.io.Serializable {
    public State {
      if (supportingEvents < 0) throw new IllegalArgumentException("supportingEvents must be non-negative");
    }
    public static State empty() { return new State(0, -1L, -1L); }
  }

  public record Accumulator(Map<String, State> rules) implements java.io.Serializable {
    public Accumulator { rules = Map.copyOf(rules); }
    public static Accumulator empty() { return new Accumulator(Map.of()); }

    public Result accept(Finding finding, Config config) {
      Objects.requireNonNull(finding);
      Objects.requireNonNull(config);
      if (finding.verdict() != Verdict.IMPOSSIBLE || !config.enabled()) {
        return new Result(this, Optional.empty());
      }

      Map<String, State> updated = new LinkedHashMap<>(rules);
      State old = rules.getOrDefault(finding.rule(), State.empty());
      int nextSupporting =
          old.lastObservationTick() < 0L
              || finding.serverTick() - old.lastObservationTick() > config.resetAfterTicks()
              ? 1
              : old.supportingEvents() + 1;

      State next = new State(nextSupporting, finding.serverTick(), old.lastAlertTick());
      Optional<Finding> alert = Optional.empty();
      boolean thresholdReached = nextSupporting >= config.minimumObservations();
      boolean debounceSatisfied =
          old.lastAlertTick() < 0L
              || finding.serverTick() - old.lastAlertTick() >= config.alertDebounceTicks();
      if (thresholdReached && debounceSatisfied) {
        alert = Optional.of(finding);
        next = new State(nextSupporting, finding.serverTick(), finding.serverTick());
      }
      updated.put(finding.rule(), next);
      return new Result(new Accumulator(updated), alert);
    }
  }

  public record Result(Accumulator state, Optional<Finding> alert) {
    public Result {
      Objects.requireNonNull(state);
      Objects.requireNonNull(alert);
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

    for (Packets.RawPacket raw : ordered) {
      Packets.Packet packet = raw.packet();
      long sequence = raw.sequence();
      PredictionFrame frame = frames.floorEntry(sequence) == null
          ? null
          : frames.floorEntry(sequence).getValue();
      long serverTick = frame == null ? lastServerTick : frame.serverTick();
      lastServerTick = serverTick;

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

      if (packet instanceof Packets.Move move) {
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
              findings.add(finding(playerId, serverTick, "AimModulo360",
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
          findings.add(finding(playerId, serverTick, "Reach",
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
          findings.add(finding(playerId, serverTick, "FarBreak",
              String.format(Locale.ROOT, "block distance %.3f exceeds %.3f", distance, config.blockInteractionReach()),
              Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0),
              sequence));
        }
      }

      if (packet instanceof Packets.BlockPlace place && frame != null) {
        Pos pos = place.position();
        BlockBox block = new BlockBox(pos.x(), pos.y(), pos.z(), pos.x() + 1.0, pos.y() + 1.0, pos.z() + 1.0);
        double distance = pointAabbDistance(eyePosition(frame.observedAfter()), block);
        if (distance > config.blockInteractionReach()) {
          findings.add(finding(playerId, serverTick, "FarPlace",
              String.format(Locale.ROOT, "block distance %.3f exceeds %.3f", distance, config.blockInteractionReach()),
              Math.min(1.0, (distance - config.blockInteractionReach()) / 2.0),
              sequence));
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

    return new Report(findings);
  }

  private static Finding finding(String playerId, long serverTick, String rule,
                                 String reason, double severity, long sequence) {
    return new Finding(
        playerId, serverTick, rule, Verdict.IMPOSSIBLE, reason, severity,
        "production-check:" + playerId + ":" + rule + ":" + sequence);
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
    return new Config(true, 3, 40, 20, 4.0, 5.0);
  }
}
