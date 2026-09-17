package dev.phantom.ac;

import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.*;

/**
 * Per-player compensated client world.
 *
 * <p>World packets are not made visible to prediction merely because the
 * server emitted them. They are queued behind a per-player transaction
 * barrier and become part of the client-visible world only after the
 * matching client acknowledgement. The mutable journal is exposed to the
 * live adapter; the simulator receives only immutable {@link WorldSnapshot}
 * values.</p>
 */
public final class CompensatedClientWorld implements Serializable {
  public sealed interface Mutation extends Serializable
      permits Mutation.ChunkSnapshot, Mutation.ChunkUnload, Mutation.Block {
    record ChunkSnapshot(Chunk chunk, Map<Pos, BlockState> states) implements Mutation {
      public ChunkSnapshot {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(states, "states");
        states = Map.copyOf(states);
      }
    }

    record ChunkUnload(Chunk chunk) implements Mutation {
      public ChunkUnload { Objects.requireNonNull(chunk, "chunk"); }
    }

    record Block(Pos position, BlockState state) implements Mutation {
      public Block {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(state, "state");
      }
    }
  }

  private final String version;
  private final int minY;
  private final int maxY;

  /** Current compensated client-visible state. */
  private final Map<Chunk, Map<Pos, BlockState>> chunks = new HashMap<>();

  /** World packets waiting to be associated with the next transaction barrier. */
  private final List<Mutation> unassigned = new ArrayList<>();

  /** Mutations associated with a sent-but-not-yet-acknowledged transaction. */
  private final Map<Short, List<Mutation>> pending = new LinkedHashMap<>();

  /** Ordered transaction send history; ACKs retire entries from the front. */
  private final Deque<Short> sentOrder = new ArrayDeque<>();
  private final Set<Short> acknowledged = new HashSet<>();

  public CompensatedClientWorld(String version, int minY, int maxY) {
    this.version = Objects.requireNonNull(version, "version");
    if (minY > maxY) throw new IllegalArgumentException("minY must not exceed maxY");
    this.minY = minY;
    this.maxY = maxY;
  }

  public synchronized void queue(Mutation mutation) {
    unassigned.add(Objects.requireNonNull(mutation, "mutation"));
  }

  /**
   * Associates all mutations observed before this transaction send with this
   * barrier. Mutations explicitly queued for this id by an asynchronous
   * decoder are also supported through {@link #queueForBarrier(short, Mutation)}.
   */
  public synchronized void openBarrier(short transactionId) {
    if (acknowledged.contains(transactionId)) return;
    if (!pending.containsKey(transactionId)) {
      pending.put(transactionId, new ArrayList<>());
      sentOrder.addLast(transactionId);
    }
    if (!unassigned.isEmpty()) {
      pending.get(transactionId).addAll(unassigned);
      unassigned.clear();
    }
  }

  /**
   * Queues a mutation for a known barrier. This is used when an asynchronously
   * decoded chunk finishes after the outbound chunk packet has already caused
   * its barrier to be scheduled.
   */
  public synchronized void queueForBarrier(short transactionId, Mutation mutation) {
    Objects.requireNonNull(mutation, "mutation");
    if (acknowledged.contains(transactionId)) {
      apply(mutation);
      return;
    }
    pending.computeIfAbsent(transactionId, ignored -> new ArrayList<>()).add(mutation);
  }

  /**
   * Marks a transaction as acknowledged and applies every earlier acknowledged
   * barrier in send order. This preserves the same ordering guarantee used by
   * Grim's transaction-backed compensated state.
   */
  public synchronized boolean acknowledge(short transactionId) {
    if (!pending.containsKey(transactionId)) return false;

    while (!sentOrder.isEmpty()) {
      short head = sentOrder.removeFirst();
      List<Mutation> mutations = pending.remove(head);
      if (mutations != null) {
        for (Mutation mutation : mutations) apply(mutation);
      }
      acknowledged.add(head);
      if (head == transactionId) break;
    }
    return true;
  }

  /** Requeues a barrier's mutations when the synthetic transaction could not be sent. */
  public synchronized void abortBarrier(short transactionId) {
    List<Mutation> mutations = pending.remove(transactionId);
    sentOrder.remove(transactionId);
    if (mutations != null) unassigned.addAll(mutations);
  }

  public synchronized boolean hasPendingTransaction(short transactionId) {
    return pending.containsKey(transactionId);
  }

  public synchronized int pendingBarrierCount() {
    return pending.size();
  }

  public synchronized int visibleChunkCount() {
    return chunks.size();
  }

  /** Returns an immutable client-visible world suitable for deterministic simulation. */
  public synchronized WorldSnapshot snapshot() {
    WorldSnapshot.Builder builder = WorldSnapshot.builder(version, minY, maxY);
    for (Chunk chunk : sortedChunks()) {
      builder.loadChunk(chunk.x(), chunk.z());
      Map<Pos, BlockState> states = chunks.get(chunk);
      if (states == null) continue;
      List<Map.Entry<Pos, BlockState>> ordered = new ArrayList<>(states.entrySet());
      ordered.sort(Map.Entry.comparingByKey(WorldSnapshot.POS_ORDER));
      for (Map.Entry<Pos, BlockState> entry : ordered) {
        Pos pos = entry.getKey();
        BlockState state = entry.getValue();
        if (state.isUnsupported()) builder.setUnsupportedBlock(pos.x(), pos.y(), pos.z(), state.blockId());
        else builder.setBlock(pos.x(), pos.y(), pos.z(), state);
      }
    }
    return builder.build();
  }

  private List<Chunk> sortedChunks() {
    List<Chunk> result = new ArrayList<>(chunks.keySet());
    result.sort(Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));
    return result;
  }

  private void apply(Mutation mutation) {
    switch (mutation) {
      case Mutation.ChunkSnapshot snapshot -> {
        Map<Pos, BlockState> copy = new HashMap<>();
        for (Map.Entry<Pos, BlockState> entry : snapshot.states().entrySet()) {
          Pos pos = entry.getKey();
          if (!Chunk.containing(pos.x(), pos.z()).equals(snapshot.chunk())) {
            throw new IllegalArgumentException("chunk state outside chunk " + snapshot.chunk());
          }
          if (pos.y() < minY || pos.y() > maxY) {
            throw new IllegalArgumentException("chunk state outside world height: " + pos);
          }
          copy.put(pos, entry.getValue());
        }
        chunks.put(snapshot.chunk(), copy);
      }
      case Mutation.ChunkUnload unload -> chunks.remove(unload.chunk());
      case Mutation.Block block -> {
        Chunk chunk = Chunk.containing(block.position().x(), block.position().z());
        if (!chunks.containsKey(chunk)) return;
        if (block.state().isAir()) {
          chunks.get(chunk).remove(block.position());
        } else {
          chunks.get(chunk).put(block.position(), block.state());
        }
      }
    }
  }
}
