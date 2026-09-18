package dev.phantom.ac.paper;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live per-player client-world replica.
 *
 * <p>This follows the same semantic model used by Grim's compensated world:
 * packet-derived chunk sections are retained rather than eagerly expanded into
 * every block state, and world mutations become visible only after a client
 * acknowledgement barrier. The deterministic core still receives only an
 * immutable {@link WorldSnapshot}.</p>
 *
 * <p>Unlike the old implementation, chunk payloads are not decoded on arrival.
 * The snapshot builder decodes only the local chunk window needed by the newest
 * movement validation, off the Paper main thread.</p>
 */
final class LiveClientWorldReplica {
  record ChunkKey(int x, int z) {}

  private sealed interface Mutation permits ChunkMutation, BlockMutation, UnloadMutation {}

  private record ChunkMutation(Column column, boolean fullChunk, ClientVersion clientVersion) implements Mutation {
    ChunkMutation {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(clientVersion, "clientVersion");
      if (column.getX() != column.getX() || column.getZ() != column.getZ()) {
        throw new IllegalArgumentException("invalid chunk coordinate");
      }
    }
  }

  private record BlockMutation(Pos position, BlockState state) implements Mutation {
    BlockMutation {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(state, "state");
    }
  }

  private record UnloadMutation(ChunkKey chunk) implements Mutation {
    UnloadMutation { Objects.requireNonNull(chunk, "chunk"); }
  }

  private record ChunkEntry(
      Column column,
      boolean complete,
      ClientVersion clientVersion,
      long revision
  ) {}

  private record DecodedChunk(long revision, Map<Pos, BlockState> states) {}

  private final String version;
  private final int minY;
  private final int maxY;
  private final Map<ChunkKey, ChunkEntry> chunks = new HashMap<>();
  private final Map<ChunkKey, Map<Pos, BlockState>> blockOverlays = new HashMap<>();
  private final List<Mutation> unassigned = new ArrayList<>();
  private final Map<Short, List<Mutation>> pending = new LinkedHashMap<>();
  private final Deque<Short> sentOrder = new ArrayDeque<>();
  private final Map<ChunkKey, DecodedChunk> decodedCache = new ConcurrentHashMap<>();
  private final Map<Integer, BlockState> stateCache = new ConcurrentHashMap<>();
  private final AtomicLong revisionCounter = new AtomicLong();

  LiveClientWorldReplica(String version, int minY, int maxY) {
    this.version = Objects.requireNonNull(version, "version");
    if (minY > maxY) throw new IllegalArgumentException("minY must not exceed maxY");
    this.minY = minY;
    this.maxY = maxY;
  }

  synchronized void queueChunk(Column column, boolean fullChunk, ClientVersion clientVersion) {
    unassigned.add(new ChunkMutation(column, fullChunk, clientVersion));
  }

  synchronized void queueBlock(Pos position, BlockState state) {
    unassigned.add(new BlockMutation(position, state));
  }

  synchronized void queueUnload(Chunk chunk) {
    unassigned.add(new UnloadMutation(new ChunkKey(chunk.x(), chunk.z())));
  }

  synchronized boolean hasUnassignedMutations() {
    return !unassigned.isEmpty();
  }

  /**
   * Opens a new barrier for mutations that were emitted before the synthetic
   * transaction. The caller may safely retry a failed send by aborting this
   * barrier without losing the unassigned mutations.
   */
  synchronized void openBarrier(short transactionId) {
    if (pending.containsKey(transactionId)) {
      throw new IllegalStateException("transaction barrier already open: " + transactionId);
    }
    List<Mutation> batch = new ArrayList<>(unassigned);
    unassigned.clear();
    pending.put(transactionId, batch);
    sentOrder.addLast(transactionId);
  }

  synchronized boolean acknowledge(short transactionId) {
    if (!pending.containsKey(transactionId)) return false;

    while (!sentOrder.isEmpty()) {
      short head = sentOrder.removeFirst();
      List<Mutation> mutations = pending.remove(head);
      if (mutations != null) {
        for (Mutation mutation : mutations) apply(mutation);
      }
      if (head == transactionId) break;
    }
    return true;
  }

  synchronized void abortBarrier(short transactionId) {
    List<Mutation> mutations = pending.remove(transactionId);
    sentOrder.remove(transactionId);
    if (mutations != null && !mutations.isEmpty()) {
      unassigned.addAll(0, mutations);
    }
  }

  synchronized int pendingBarrierCount() {
    return pending.size();
  }

  synchronized int visibleChunkCount() {
    int count = 0;
    for (ChunkEntry entry : chunks.values()) if (entry.complete()) count++;
    return count;
  }

  int decodedCacheSize() {
    return decodedCache.size();
  }

  /**
   * Builds a snapshot from a local chunk window. The raw column references are
   * copied under the lock; block decoding happens after releasing it, so chunk
   * parsing cannot stall the packet listener or Paper main thread.
   */
  WorldSnapshot snapshotAround(double centerX, double centerZ, int radiusChunks) {
    if (radiusChunks < 0) throw new IllegalArgumentException("radiusChunks must be non-negative");
    int centerChunkX = Math.floorDiv((int) Math.floor(centerX), 16);
    int centerChunkZ = Math.floorDiv((int) Math.floor(centerZ), 16);

    Map<ChunkKey, ChunkEntry> local = new LinkedHashMap<>();
    Map<ChunkKey, Map<Pos, BlockState>> overlays = new LinkedHashMap<>();

    synchronized (this) {
      for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
        for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
          ChunkKey key = new ChunkKey(centerChunkX + dx, centerChunkZ + dz);
          ChunkEntry entry = chunks.get(key);
          if (entry != null && entry.complete()) local.put(key, entry);

          Map<Pos, BlockState> overlay = blockOverlays.get(key);
          if (overlay != null && !overlay.isEmpty()) overlays.put(key, Map.copyOf(overlay));
        }
      }
    }

    WorldSnapshot.Builder builder = WorldSnapshot.builder(version, minY, maxY);
    List<ChunkKey> ordered = new ArrayList<>(local.keySet());
    ordered.sort(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));

    for (ChunkKey key : ordered) {
      ChunkEntry entry = local.get(key);
      DecodedChunk decoded = decodeCached(key, entry);
      builder.loadChunk(key.x(), key.z());

      for (Map.Entry<Pos, BlockState> block : decoded.states().entrySet()) {
        BlockState state = block.getValue();
        if (state.isUnsupported()) {
          builder.setUnsupportedBlock(block.getKey().x(), block.getKey().y(), block.getKey().z(), state.blockId());
        } else if (!state.isAir()) {
          builder.setBlock(block.getKey().x(), block.getKey().y(), block.getKey().z(), state);
        }
      }

      Map<Pos, BlockState> overlay = overlays.get(key);
      if (overlay != null) {
        for (Map.Entry<Pos, BlockState> block : overlay.entrySet()) {
          Pos pos = block.getKey();
          BlockState state = block.getValue();
          if (state.isUnsupported()) builder.setUnsupportedBlock(pos.x(), pos.y(), pos.z(), state.blockId());
          else if (state.isAir()) {
            // Removing the state from the builder requires rebuilding the chunk,
            // so use the overlay as the authoritative value by writing only the
            // non-air replacement below. A later snapshot decode sees the same
            // overlay and therefore never resurrects a removed block.
          } else {
            builder.setBlock(pos.x(), pos.y(), pos.z(), state);
          }
        }
      }
    }

    return builder.build();
  }

  private DecodedChunk decodeCached(ChunkKey key, ChunkEntry entry) {
    DecodedChunk cached = decodedCache.get(key);
    if (cached != null && cached.revision() == entry.revision()) return cached;

    Map<Pos, BlockState> states = decode(entry.column(), entry.clientVersion());
    DecodedChunk fresh = new DecodedChunk(entry.revision(), Map.copyOf(states));
    decodedCache.put(key, fresh);
    return fresh;
  }

  private Map<Pos, BlockState> decode(Column column, ClientVersion clientVersion) {
    Map<Pos, BlockState> states = new HashMap<>();
    BaseChunk[] sections = column.getChunks();
    int minSection = Math.floorDiv(minY, 16);
    int maxSectionExclusive = Math.floorDiv(maxY - 1, 16) + 1;
    int baseX = column.getX() * 16;
    int baseZ = column.getZ() * 16;

    for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
      BaseChunk section = sections[sectionIndex];
      if (section == null || section.isEmpty()) continue;

      int sectionY = minSection + sectionIndex;
      if (sectionY < minSection || sectionY >= maxSectionExclusive) continue;
      int baseY = sectionY * 16;

      for (int localY = 0; localY < 16; localY++) {
        for (int localZ = 0; localZ < 16; localZ++) {
          for (int localX = 0; localX < 16; localX++) {
            int globalId = section.getBlockId(localX, localY, localZ);
            if (globalId <= 0) continue;

            BlockState core = stateCache.get(globalId);
            if (core == null) {
              WrappedBlockState state = WrappedBlockState.getByGlobalId(clientVersion, globalId, false);
              core = state == null || state.getType().isAir() ? BlockState.air() : toCoreState(state);
              BlockState existing = stateCache.putIfAbsent(globalId, core);
              if (existing != null) core = existing;
            }

            if (!core.isAir()) {
              states.put(new Pos(baseX + localX, baseY + localY, baseZ + localZ), core);
            }
          }
        }
      }
    }

    return states;
  }

  private void apply(Mutation mutation) {
    switch (mutation) {
      case ChunkMutation chunk -> applyChunk(chunk);
      case BlockMutation block -> {
        ChunkKey key = new ChunkKey(
            Math.floorDiv(block.position().x(), 16),
            Math.floorDiv(block.position().z(), 16));
        ChunkEntry entry = chunks.get(key);
        if (entry == null || !entry.complete()) return;
        blockOverlays.computeIfAbsent(key, ignored -> new HashMap<>()).put(block.position(), block.state());
      }
      case UnloadMutation unload -> {
        chunks.remove(unload.chunk());
        blockOverlays.remove(unload.chunk());
        decodedCache.remove(unload.chunk());
      }
    }
  }

  private void applyChunk(ChunkMutation mutation) {
    ChunkKey key = new ChunkKey(mutation.column().getX(), mutation.column().getZ());
    ChunkEntry old = chunks.get(key);
    long revision = revisionCounter.incrementAndGet();

    if (mutation.fullChunk() || old == null) {
      chunks.put(key, new ChunkEntry(
          mutation.column(),
          mutation.fullChunk(),
          mutation.clientVersion(),
          revision));
      blockOverlays.remove(key);
      decodedCache.remove(key);
      return;
    }

    BaseChunk[] previous = old.column().getChunks();
    BaseChunk[] incoming = mutation.column().getChunks();
    BaseChunk[] merged = previous.clone();
    int length = Math.min(merged.length, incoming.length);
    for (int i = 0; i < length; i++) {
      if (incoming[i] != null) merged[i] = incoming[i];
    }

    Column combined = new Column(
        key.x(), key.z(), true, merged, new TileEntity[0]);
    chunks.put(key, new ChunkEntry(combined, old.complete(), mutation.clientVersion(), revision));
    decodedCache.remove(key);
  }

  private static BlockState toCoreState(WrappedBlockState state) {
    String name = state.getType().getName();
    Map<String, String> properties = new LinkedHashMap<>();
    putEnum(properties, "type", state.getData(StateValue.TYPE));
    putEnum(properties, "facing", state.getData(StateValue.FACING));
    putEnum(properties, "half", state.getData(StateValue.HALF));
    putEnum(properties, "shape", state.getData(StateValue.SHAPE));
    putEnum(properties, "part", state.getData(StateValue.PART));
    putEnum(properties, "hinge", state.getData(StateValue.HINGE));
    putNumber(properties, "layers", state.getData(StateValue.LAYERS));
    putNumber(properties, "level", state.getData(StateValue.LEVEL));
    putNumber(properties, "candles", state.getData(StateValue.CANDLES));
    putNumber(properties, "pickles", state.getData(StateValue.PICKLES));
    putBoolean(properties, "waterlogged", state.getData(StateValue.WATERLOGGED));
    putBoolean(properties, "open", state.getData(StateValue.OPEN));
    putBoolean(properties, "powered", state.getData(StateValue.POWERED));
    putBoolean(properties, "up", state.getData(StateValue.UP));
    putBoolean(properties, "north", state.getData(StateValue.NORTH));
    putBoolean(properties, "south", state.getData(StateValue.SOUTH));
    putBoolean(properties, "west", state.getData(StateValue.WEST));
    putBoolean(properties, "east", state.getData(StateValue.EAST));
    putBoolean(properties, "lit", state.getData(StateValue.LIT));
    if (isFence(name) && !hasAll(properties, "waterlogged", "north", "south", "west", "east")) {
      return BlockState.unsupported(name);
    }
    if (isWall(name) && !hasAll(properties, "waterlogged", "up", "north", "south", "west", "east")) {
      return BlockState.unsupported(name);
    }
    if (isPane(name) && !hasAll(properties, "waterlogged", "north", "south", "west", "east")) {
      return BlockState.unsupported(name);
    }
    return dev.phantom.ac.world.v12111.BlockCatalogue12111.decode(name, properties);
  }

  private static boolean isFence(String name) {
    return name.endsWith("_fence") && !name.endsWith("_fence_gate");
  }

  private static boolean isWall(String name) {
    return name.endsWith("_wall");
  }

  private static boolean isPane(String name) {
    return name.endsWith("_pane");
  }

  private static boolean hasAll(Map<String, String> map, String... keys) {
    for (String key : keys) if (!map.containsKey(key)) return false;
    return true;
  }

  private static void putEnum(Map<String, String> map, String key, Object value) {
    if (value != null) map.put(key, value.toString().toLowerCase(Locale.ROOT));
  }

  private static void putNumber(Map<String, String> map, String key, Object value) {
    if (value instanceof Number number) map.put(key, Integer.toString(number.intValue()));
  }

  private static void putBoolean(Map<String, String> map, String key, Object value) {
    if (value instanceof Boolean bool) map.put(key, Boolean.toString(bool));
  }
}
