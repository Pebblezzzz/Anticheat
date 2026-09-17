package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ClientTickTrackerTest {
  @Test
  void movementBeforeFirstTickEndUsesRelativeZero() {
    var tracker=new ClientTickTracker();
    var first=tracker.onMovement();
    assertEquals(0,first.clientTick());
    assertFalse(first.hasSeenTickEnd());
    assertTrue(first.oneToOne());
  }

  @Test
  void tickEndAdvancesTheFollowingMovementInterval() {
    var tracker=new ClientTickTracker();
    assertEquals(0,tracker.onMovement().clientTick());
    tracker.onClientTickEnd();
    var next=tracker.onMovement();
    assertEquals(1,next.clientTick());
    assertTrue(next.hasSeenTickEnd());
  }

  @Test
  void multipleMovementsInOneClientTickAreExplicitlyMarked() {
    var tracker=new ClientTickTracker();
    tracker.onMovement();
    var second=tracker.onMovement();
    assertEquals(2,second.packetsInTick());
    assertFalse(second.oneToOne());
    assertEquals(0,second.clientTick());
    tracker.onClientTickEnd();
    assertTrue(tracker.onMovement().oneToOne());
    assertEquals(1,tracker.onMovement().clientTick());
  }

  @Test
  void idleClientTicksStillAdvanceTheRelativeClock() {
    var tracker=new ClientTickTracker();
    tracker.onClientTickEnd();
    tracker.onClientTickEnd();
    assertEquals(2,tracker.clientTickForMovement());
    assertTrue(tracker.hasObservedBoundary());
  }
}
