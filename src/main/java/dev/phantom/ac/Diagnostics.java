package dev.phantom.ac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import static dev.phantom.ac.Packets.*;

/**
 * Produces non-enforcing diagnostic alerts from deterministic reconstruction.
 * This is intentionally distinct from a cheat judgement: an alert is evidence
 * for investigation, while movement enforcement remains disabled until the
 * relevant vanilla behaviour has independent trace validation.
 */
public final class Diagnostics {
  private Diagnostics() {}

  public enum Severity { INFO, WARNING }
  public enum Category { TIMELINE, SYNCHRONIZATION, INPUT_COVERAGE }
  public record Alert(Severity severity, Category category, long sequence, long serverTick, String message) implements Serializable {}
  public record Report(List<Alert> alerts, int movementPackets, int inputPackets, int teleportConfirms) {
    public Report { alerts = List.copyOf(alerts); }
  }

  public static Report audit(Timeline.Snapshot timeline) {
    List<Alert> alerts = new ArrayList<>();
    int moves = 0, inputs = 0, confirms = 0;
    State.Player state = State.Player.initial(Maths.Vec3.ZERO);
    boolean seenInput = false;

    for (Timeline.Event event : timeline.events()) {
      NormalizedPacket packet = event.packet();
      if (packet.flags().contains(PacketFlag.DUPLICATE)) {
        alerts.add(alert(Severity.WARNING, Category.TIMELINE, event, "duplicate source sequence retained for replay"));
      }
      if (packet.flags().contains(PacketFlag.OUT_OF_ORDER)) {
        alerts.add(alert(Severity.WARNING, Category.TIMELINE, event, "source sequence arrived out of order; reconstruction widened"));
      }
      if (packet.packet() instanceof ClientInput) {
        inputs++;
        seenInput = true;
      }
      if (packet.packet() instanceof Move) {
        moves++;
        if (!seenInput) {
          alerts.add(alert(Severity.INFO, Category.INPUT_COVERAGE, event, "movement preceded the first input packet; input is unknown for this interval"));
          seenInput = true; // one useful alert instead of a noisy alert per movement packet
        }
      }
      if (packet.packet() instanceof TeleportConfirm confirm) {
        confirms++;
        OptionalInt pending = state.awaitingTeleport();
        if (pending.isEmpty() || pending.getAsInt() != confirm.id()) {
          alerts.add(alert(Severity.WARNING, Category.SYNCHRONIZATION, event, "teleport confirmation does not match the pending teleport"));
        }
      }
      state = State.apply(state, packet);
    }
    return new Report(alerts, moves, inputs, confirms);
  }

  private static Alert alert(Severity severity, Category category, Timeline.Event event, String message) {
    return new Alert(severity, category, event.packet().sequence(), event.serverTick(), message);
  }
}
