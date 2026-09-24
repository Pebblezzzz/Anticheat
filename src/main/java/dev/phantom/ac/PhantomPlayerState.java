package dev.phantom.ac;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-player causal state machine owned by the live adapter.
 *
 * <p>The adapter may receive packets, Paper observations, and transaction
 * acknowledgements concurrently. This class is the single owner for the
 * lifecycle/authority portion of that state instead of allowing those facts to
 * be scattered across unrelated callbacks.</p>
 */
public final class PhantomPlayerState {
  public enum Lifecycle {
    CONNECTING,
    ACTIVE,
    RESYNCING,
    DISCONNECTED
  }

  private final UUID playerId;
  private Lifecycle lifecycle = Lifecycle.CONNECTING;
  private long lifecycleEpoch;
  private State.Player initialState;
  private long initialStateReceivedNanos = -1L;
  private Packets.PlayerContext latestAuthoritativeContext;
  private Packets.PlayerContext pendingAuthoritativeContext;
  private Packets.PlayerContext latestClientVisibleContext;
  private long latestClientVisibleSequence = -1L;

  public PhantomPlayerState(UUID playerId) {
    this.playerId = Objects.requireNonNull(playerId, "playerId");
  }

  public synchronized UUID playerId() {
    return playerId;
  }

  public synchronized Lifecycle lifecycle() {
    return lifecycle;
  }

  public synchronized long lifecycleEpoch() {
    return lifecycleEpoch;
  }

  public synchronized void activate(State.Player authoritativeAnchor, long receivedNanos) {
    Objects.requireNonNull(authoritativeAnchor, "authoritativeAnchor");
    requireTimestamp(receivedNanos);
    initialState = authoritativeAnchor;
    initialStateReceivedNanos = receivedNanos;
    latestAuthoritativeContext = null;
    pendingAuthoritativeContext = null;
    latestClientVisibleContext = null;
    latestClientVisibleSequence = -1L;
    lifecycle = Lifecycle.ACTIVE;
    lifecycleEpoch++;
  }

  public synchronized void beginResync(State.Player authoritativeAnchor, long receivedNanos) {
    Objects.requireNonNull(authoritativeAnchor, "authoritativeAnchor");
    requireTimestamp(receivedNanos);
    initialState = authoritativeAnchor;
    initialStateReceivedNanos = receivedNanos;
    latestAuthoritativeContext = null;
    pendingAuthoritativeContext = null;
    latestClientVisibleContext = null;
    latestClientVisibleSequence = -1L;
    lifecycle = Lifecycle.RESYNCING;
    lifecycleEpoch++;
  }

  public synchronized void completeResync() {
    if (lifecycle == Lifecycle.DISCONNECTED) return;
    lifecycle = Lifecycle.ACTIVE;
  }

  public synchronized void disconnect() {
    lifecycle = Lifecycle.DISCONNECTED;
    lifecycleEpoch++;
    pendingAuthoritativeContext = null;
    latestAuthoritativeContext = null;
  }

  public synchronized State.Player initialState() {
    return initialState;
  }

  public synchronized long initialStateReceivedNanos() {
    return initialStateReceivedNanos;
  }

  public synchronized void observeAuthoritativeContext(Packets.PlayerContext context) {
    latestAuthoritativeContext = Objects.requireNonNull(context, "context");
    pendingAuthoritativeContext = context;
    if (lifecycle == Lifecycle.CONNECTING) lifecycle = Lifecycle.ACTIVE;
  }

  public synchronized Packets.PlayerContext pendingAuthoritativeContext() {
    return pendingAuthoritativeContext;
  }

  public synchronized void markContextPublished() {
    pendingAuthoritativeContext = null;
  }

  public synchronized Optional<Packets.PlayerContext> latestAuthoritativeContext() {
    return Optional.ofNullable(latestAuthoritativeContext);
  }

  public synchronized void markClientVisible(
      Packets.PlayerContext context,
      long acknowledgementSequence) {
    Objects.requireNonNull(context, "context");
    if (acknowledgementSequence < 0L) {
      throw new IllegalArgumentException("acknowledgementSequence must be non-negative");
    }
    latestClientVisibleContext = context;
    latestClientVisibleSequence = acknowledgementSequence;
  }

  public synchronized Optional<Packets.PlayerContext> latestClientVisibleContext() {
    return Optional.ofNullable(latestClientVisibleContext);
  }

  public synchronized long latestClientVisibleSequence() {
    return latestClientVisibleSequence;
  }

  private static void requireTimestamp(long nanos) {
    if (nanos < 0L) throw new IllegalArgumentException("timestamp must be non-negative");
  }
}
