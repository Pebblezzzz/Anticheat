package dev.phantom.ac;

import java.util.Objects;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
  private final ArrayDeque<Short> sentTransactions = new ArrayDeque<>();
  private final Set<Short> acknowledgedTransactions = new HashSet<>();
  private final Map<Short, Packets.PlayerContext> pendingBarrierContexts = new LinkedHashMap<>();

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
    sentTransactions.clear();
    acknowledgedTransactions.clear();
    pendingBarrierContexts.clear();
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
    sentTransactions.clear();
    acknowledgedTransactions.clear();
    pendingBarrierContexts.clear();
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
    sentTransactions.clear();
    acknowledgedTransactions.clear();
    pendingBarrierContexts.clear();
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

  public synchronized void markBarrierSent(short transactionId, Packets.PlayerContext context) {
    Objects.requireNonNull(context, "context");
    if (acknowledgedTransactions.contains(transactionId)) {
      throw new IllegalStateException("transaction barrier was already acknowledged: " + transactionId);
    }
    if (sentTransactions.contains(transactionId)) {
      throw new IllegalStateException("transaction barrier already sent: " + transactionId);
    }
    sentTransactions.addLast(transactionId);
    pendingBarrierContexts.put(transactionId, context);
  }

  public synchronized List<Packets.PlayerContext> acknowledgeBarrier(short transactionId, long acknowledgementSequence) {
    if (acknowledgementSequence < 0L) {
      throw new IllegalArgumentException("acknowledgementSequence must be non-negative");
    }
    if (acknowledgedTransactions.contains(transactionId)) return List.of();

    List<Packets.PlayerContext> released = new ArrayList<>();
    if (sentTransactions.contains(transactionId)) {
      while (!sentTransactions.isEmpty()) {
        short head = sentTransactions.removeFirst();
        acknowledgedTransactions.add(head);
        Packets.PlayerContext context = pendingBarrierContexts.remove(head);
        if (context != null) {
          latestClientVisibleContext = context;
          latestClientVisibleSequence = acknowledgementSequence;
          released.add(context);
        }
        if (head == transactionId) break;
      }
    } else {
      acknowledgedTransactions.add(transactionId);
      Packets.PlayerContext context = pendingBarrierContexts.remove(transactionId);
      if (context != null) {
        latestClientVisibleContext = context;
        latestClientVisibleSequence = acknowledgementSequence;
        released.add(context);
      }
    }
    return List.copyOf(released);
  }

  public synchronized boolean hasAcknowledgedBarrier(short transactionId) {
    return acknowledgedTransactions.contains(transactionId);
  }

  private static void requireTimestamp(long nanos) {
    if (nanos < 0L) throw new IllegalArgumentException("timestamp must be non-negative");
  }
}
