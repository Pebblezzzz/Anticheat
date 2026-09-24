package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PhantomPlayerStateTest {

  @Test
  void lifecycleTracksResyncAndDisconnect() {
    PhantomPlayerState state = new PhantomPlayerState(UUID.randomUUID());
    State.Player anchor = State.Player.initial(new Maths.Vec3(0, 64, 0));

    assertEquals(PhantomPlayerState.Lifecycle.CONNECTING, state.lifecycle());

    state.activate(anchor, 10L);
    assertEquals(PhantomPlayerState.Lifecycle.ACTIVE, state.lifecycle());
    assertEquals(anchor, state.initialState());

    state.beginResync(anchor, 20L);
    assertEquals(PhantomPlayerState.Lifecycle.RESYNCING, state.lifecycle());
    state.completeResync();
    assertEquals(PhantomPlayerState.Lifecycle.ACTIVE, state.lifecycle());

    state.disconnect();
    assertEquals(PhantomPlayerState.Lifecycle.DISCONNECTED, state.lifecycle());
  }

  @Test
  void transactionAcknowledgementPublishesCumulativeAuthority() {
    PhantomPlayerState state = new PhantomPlayerState(UUID.randomUUID());

    Packets.PlayerContext first = context(1.0);
    Packets.PlayerContext second = context(2.0);

    state.markBarrierSent((short) -1, first.withTransactionBarrier((short) -1));
    state.markBarrierSent((short) -2, second.withTransactionBarrier((short) -2));

    assertTrue(state.latestClientVisibleContext().isEmpty());

    List<Packets.PlayerContext> released = state.acknowledgeBarrier((short) -2, 30L);

    assertEquals(2, released.size());
    assertEquals(second.withTransactionBarrier((short) -2), state.latestClientVisibleContext().orElseThrow());
    assertEquals(30L, state.latestClientVisibleSequence());
    assertTrue(state.hasAcknowledgedBarrier((short) -1));
    assertTrue(state.hasAcknowledgedBarrier((short) -2));
  }

  @Test
  void abortedBarrierDoesNotBecomeClientVisible() {
    PhantomPlayerState state = new PhantomPlayerState(UUID.randomUUID());
    Packets.PlayerContext context = context(3.0);
    state.markBarrierSent((short) -3, context.withTransactionBarrier((short) -3));

    state.abortBarrier((short) -3);

    assertEquals(List.of(), state.acknowledgeBarrier((short) -3, 40L));
    assertTrue(state.latestClientVisibleContext().isEmpty());
  }

  private static Packets.PlayerContext context(double x) {
    return new Packets.PlayerContext(
        "survival",
        new Simulation.Attributes(0.1),
        Map.of(),
        Phase5Mechanics.Pose.STANDING,
        Phase5Mechanics.MovementEnvironment.dry(true, false, false),
        new Maths.Vec3(x, 64, 0),
        Maths.Vec3.ZERO,
        false,
        false,
        false,
        List.of());
  }
  @Test
  void acknowledgedTransactionIdsCanBeReusedAfterCompletion() {
    PhantomPlayerState state = new PhantomPlayerState(UUID.randomUUID());
    Packets.PlayerContext first = context(4.0).withTransactionBarrier((short) -7);
    Packets.PlayerContext second = context(5.0).withTransactionBarrier((short) -7);

    state.markBarrierSent((short) -7, first);
    assertEquals(1, state.acknowledgeBarrier((short) -7, 50L).size());

    state.markBarrierSent((short) -7, second);

    assertEquals(
        second,
        state.acknowledgeBarrier((short) -7, 70L).getFirst());
    assertEquals(second, state.latestClientVisibleContext().orElseThrow());
  }

}
