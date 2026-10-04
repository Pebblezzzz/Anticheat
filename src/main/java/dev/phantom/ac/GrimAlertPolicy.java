package dev.phantom.ac;

import java.io.Serializable;
import java.util.*;

public final class GrimAlertPolicy {
  private GrimAlertPolicy() {}

  public record CommandRule(double threshold, double interval) implements Serializable {
    public CommandRule {
      if (!Double.isFinite(threshold) || threshold <= 0.0
          || !Double.isFinite(interval) || interval < 0.0) {
        throw new IllegalArgumentException("invalid Grim command rule");
      }
    }

    public static CommandRule parse(String value) {
      Objects.requireNonNull(value, "value");
      String[] parts = value.trim().split(":", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("expected threshold:interval, got " + value);
      }
      return new CommandRule(Double.parseDouble(parts[0].trim()),
          Double.parseDouble(parts[1].trim()));
    }
  }

  public record Group(
      String name,
      long removeViolationsAfterMillis,
      List<String> checks,
      CommandRule alert,
      CommandRule log) implements Serializable {
    public Group {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("group name is required");
      if (removeViolationsAfterMillis <= 0L) throw new IllegalArgumentException("violation window must be positive");
      checks = List.copyOf(checks);
      if (checks.isEmpty()) throw new IllegalArgumentException("group checks must not be empty");
      Objects.requireNonNull(alert);
      Objects.requireNonNull(log);
    }

    boolean matches(String rule) {
      boolean included = false;
      for (String selector : checks) {
        if (selector == null || selector.isBlank()) continue;
        boolean excluded = selector.charAt(0) == '!';
        String target = excluded ? selector.substring(1) : selector;
        if (target.equalsIgnoreCase(rule)) {
          if (excluded) return false;
          included = true;
        }
      }
      return included;
    }
  }

  public record Decision(
      String groupName,
      long removeViolationsAfterMillis,
      CommandRule alert,
      CommandRule log) implements Serializable {}

  public record Config(
      List<Group> groups,
      CommandRule fallbackAlert,
      CommandRule fallbackLog,
      long fallbackRemoveViolationsAfterMillis) implements Serializable {
    public Config {
      groups = List.copyOf(groups);
      Objects.requireNonNull(fallbackAlert);
      Objects.requireNonNull(fallbackLog);
      if (fallbackRemoveViolationsAfterMillis <= 0L) {
        throw new IllegalArgumentException("fallback violation window must be positive");
      }
    }

    public Decision forRule(String rule) {
      Objects.requireNonNull(rule);
      for (Group group : groups) {
        if (group.matches(rule)) {
          return new Decision(group.name(), group.removeViolationsAfterMillis(), group.alert(), group.log());
        }
      }
      return new Decision("Default", fallbackRemoveViolationsAfterMillis, fallbackAlert, fallbackLog);
    }

    public static Config defaults() {
      long window = 300_000L;
      List<Group> groups = List.of(
          new Group("Simulation", window,
              List.of("MOVEMENT_REACHABILITY", "GroundSpoof", "Flight", "Jesus", "Step", "Speed",
                  "TimerBurst", "TimerLimit", "NoFall"),
              CommandRule.parse("100:40"), CommandRule.parse("1:1")),
          new Group("Knockback", window,
              List.of("Knockback", "Explosion"),
              CommandRule.parse("5:5"), CommandRule.parse("1:1")),
          new Group("Post", window,
              List.of("Post"),
              CommandRule.parse("20:20"), CommandRule.parse("1:1")),
          new Group("BadPackets", window,
              List.of("BadPackets", "PacketPosition", "PacketRotation", "EntityAction",
                  "HeldItemSlot", "InventorySlot", "InventoryClickType", "InventoryButton",
                  "BlockPlaceCursor", "BlockPlaceFace", "PacketOrder", "Crash"),
              CommandRule.parse("20:20"), CommandRule.parse("1:1")),
          new Group("Reach", window,
              List.of("Reach"),
              CommandRule.parse("1:1"), CommandRule.parse("1:1")),
          new Group("Hitboxes", window,
              List.of("Hitboxes"),
              CommandRule.parse("5:3"), CommandRule.parse("1:1")),
          new Group("Misc", window,
              List.of("Vehicle", "NoSlow", "Sprint", "MultiActions", "Place", "Baritone",
                  "Break", "TransactionOrder", "Elytra", "Chat", "Exploit",
                  "FarBreak", "FarPlace", "FastBreak"),
              CommandRule.parse("10:5"), CommandRule.parse("1:1")),
          new Group("Combat", window,
              List.of("Interact", "Aim", "AimModulo360"),
              CommandRule.parse("20:40"), CommandRule.parse("1:1")),
          new Group("Autoclicker", window,
              List.of("Autoclicker"),
              CommandRule.parse("20:40"), CommandRule.parse("1:1"))
      );
      return new Config(groups, CommandRule.parse("100:40"), CommandRule.parse("1:1"), window);
    }
  }
}
