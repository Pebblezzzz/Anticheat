package dev.phantom.ac;

import java.io.Serializable;

/**
 * Tracks the relative client tick interval surrounding CLIENT_TICK_END boundaries.
 *
 * <p>CLIENT_TICK_END is emitted after the client's current tick has finished.
 * Therefore it is a boundary, not the timestamp of the movement packet that
 * arrived immediately before it. A movement packet before the first boundary
 * is assigned relative tick zero; after each boundary the next movement interval
 * advances by one.</p>
 *
 * <p>The tracker also records how many movement packets arrived in the current
 * client tick interval. Multiple movement packets between two tick-end
 * boundaries are retained as an explicit integrity signal rather than silently
 * treating them as independent 1:1 simulation ticks.</p>
 */
public final class ClientTickTracker {
  public record MovementObservation(long clientTick, int packetsInTick, boolean oneToOne,
                                    long completedTickEnds, boolean hasSeenTickEnd) implements Serializable {}

  private long completedTickEnds;
  private int movementPacketsInCurrentTick;
  private boolean hasSeenTickEnd;

  /** Observes a movement packet and returns its relative client tick interval. */
  public synchronized MovementObservation onMovement() {
    movementPacketsInCurrentTick = Math.addExact(movementPacketsInCurrentTick, 1);
    return new MovementObservation(
        completedTickEnds,
        movementPacketsInCurrentTick,
        movementPacketsInCurrentTick == 1,
        completedTickEnds,
        hasSeenTickEnd);
  }

  /** Observes the end of a client tick; subsequent movement belongs to the next tick. */
  public synchronized void onClientTickEnd() {
    completedTickEnds = Math.addExact(completedTickEnds, 1L);
    movementPacketsInCurrentTick = 0;
    hasSeenTickEnd = true;
  }

  /** Compatibility helper: returns the tick interval for the next movement. */
  public synchronized Long clientTickForMovement() {
    return completedTickEnds;
  }

  public synchronized boolean hasObservedBoundary() {
    return hasSeenTickEnd;
  }

  public synchronized long endTickCount() {
    return completedTickEnds;
  }

  public synchronized int movementPacketsInCurrentTick() {
    return movementPacketsInCurrentTick;
  }
}
