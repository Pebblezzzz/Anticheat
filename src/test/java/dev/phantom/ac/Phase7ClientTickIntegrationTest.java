package dev.phantom.ac;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static dev.phantom.ac.Maths.Vec3;
import static dev.phantom.ac.Packets.*;
import static dev.phantom.ac.Phase7Timing.*;
import static org.junit.jupiter.api.Assertions.*;

class Phase7ClientTickIntegrationTest {
  @Test void movementIsUntimedBeforeFirstTickEnd() {
    ClientTickTracker tracker = new ClientTickTracker();
    assertNull(tracker.clientTickForMovement());
    assertFalse(tracker.hasObservedBoundary());
    assertEquals(0, tracker.endTickCount());
  }

  @Test void firstTickEndStartsRelativeZeroAndMultipleMovesShareThatTick() {
    ClientTickTracker tracker = new ClientTickTracker();
    tracker.onClientTickEnd();

    assertEquals(0L, tracker.clientTickForMovement());
    assertEquals(0L, tracker.clientTickForMovement());
    assertTrue(tracker.hasObservedBoundary());
    assertEquals(1L, tracker.endTickCount());
  }

  @Test void subsequentTickEndsAdvanceOnlyTheNextMovementInterval() {
    ClientTickTracker tracker = new ClientTickTracker();
    tracker.onClientTickEnd();
    assertEquals(0L, tracker.clientTickForMovement());

    tracker.onClientTickEnd();
    assertEquals(1L, tracker.clientTickForMovement());

    tracker.onClientTickEnd();
    assertEquals(2L, tracker.clientTickForMovement());
    assertEquals(3L, tracker.endTickCount());
  }

  @Test void explicitMovementTickIsExactEvenWithBoundedUpstreamLatency() {
    Timeline.Snapshot timeline = Timeline.assign(
        new Normalizer().normalize(List.of(
            new RawPacket(1, 0L, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
            new RawPacket(2, 50_000_000L, new Move(new Vec3(0.1, 0, 0), 0f, 0f, true, 1L))
        )),
        0L,
        50_000_000L
    );

    Phase7Timing.Reconstruction reconstruction =
        Phase7Timing.reconstruct(timeline, Phase7Timing.Config.defaultConfig());

    assertEquals(Range.exact(0L), reconstruction.frames().get(0).timing().packetGenerationClientTicks());
    assertEquals(Range.exact(1L), reconstruction.frames().get(1).timing().packetGenerationClientTicks());
    assertFalse(reconstruction.frames().get(0).timing().uncertain());
    assertFalse(reconstruction.frames().get(1).timing().uncertain());
    assertEquals(Consistency.CONSISTENT, reconstruction.consistency());
  }

  @Test void exactMoveTickStopsStateFromInventingUnknownClientTick() {
    NormalizedPacket event = new NormalizedPacket(
        1L,
        0L,
        new Move(Vec3.ZERO, 0f, 0f, true, 7L),
        EnumSet.of(PacketFlag.NORMAL)
    );

    State.Player state = State.apply(State.Player.initial(Vec3.ZERO), event);

    assertFalse(state.uncertaintyReasons().contains(State.UncertaintyReason.UNKNOWN_CLIENT_TICK));
    assertEquals(State.TickRange.exact(7L), state.clientTickRange());
  }

  @Test void exactClientTickStillBecomesUncertainForCaptureChronologyProblems() {
    Timeline.Snapshot timeline = Timeline.assign(
        new Normalizer().normalize(List.of(
            new RawPacket(1, 0L, new Move(Vec3.ZERO, 0f, 0f, true, 0L)),
            new RawPacket(3, 50_000_000L, new Move(new Vec3(0.1, 0, 0), 0f, 0f, true, 1L))
        )),
        0L,
        50_000_000L
    );

    Phase7Timing.Reconstruction reconstruction =
        Phase7Timing.reconstruct(timeline, Phase7Timing.Config.defaultConfig());

    assertTrue(reconstruction.frames().get(1).timing().uncertain());
    assertTrue(reconstruction.frames().get(1).timing().reasons().stream()
        .anyMatch(reason -> reason.contains("sequence gap")));
  }
}
