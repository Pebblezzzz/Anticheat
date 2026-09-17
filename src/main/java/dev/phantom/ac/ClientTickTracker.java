package dev.phantom.ac;

/**
 * Reconstructs a relative client simulation-tick number from the 1.21.2+ CLIENT_TICK_END boundary.
 *
 * <p>The protocol packet carries no tick number. The first observed boundary therefore establishes
 * an arbitrary relative origin. Movement packets received before that first boundary are deliberately
 * left untimed rather than assigned a fabricated tick. After the first boundary, all movement packets
 * until the next boundary are assigned the same relative tick.</p>
 */
public final class ClientTickTracker {
  private boolean observedFirstBoundary;
  private long movementTick;
  private long endTickCount;

  /**
   * Observes the end of the client's current tick.
   *
   * <p>The first boundary establishes relative tick zero for the following movement interval.
   * Each later boundary advances that relative tick by one.</p>
   */
  public synchronized void onClientTickEnd() {
    if (!observedFirstBoundary) {
      observedFirstBoundary = true;
    } else {
      movementTick = Math.addExact(movementTick, 1L);
    }
    endTickCount = Math.addExact(endTickCount, 1L);
  }

  /** Returns the current relative movement tick, or {@code null} before the first boundary. */
  public synchronized Long clientTickForMovement() {
    return observedFirstBoundary ? movementTick : null;
  }

  public synchronized boolean hasObservedBoundary() {
    return observedFirstBoundary;
  }

  public synchronized long endTickCount() {
    return endTickCount;
  }
}
