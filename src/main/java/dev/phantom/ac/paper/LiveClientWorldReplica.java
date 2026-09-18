package dev.phantom.ac.paper;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.Chunk;
import dev.phantom.ac.world.Coverage;
import dev.phantom.ac.world.Pos;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live per-player client-world replica.
 *
 * <p>This follows the same semantic model used by Grim's compensated world:
 * packet-derived chunk sections are retained rather than eagerly expanded into
 * every block state, and world mutations become visible only after a client
 * acknowledgement barrier. The deterministic core still receives only an
 * immutable {@link WorldSnapshot}.</p>
 *
 * <p>Chunk payloads are not decoded on arrival. PacketEvents' palette-backed
 * columns remain the storage representation; the backend converts individual
 * states only when a deterministic world query asks for them.</p>
 */
final class LiveClientWorldReplica {
  record ChunkKey(int x, int z) {}

  private sealed interface Mutation permits ChunkMutation, BlockMutation, UnloadMutation {}

  private record ChunkMutation(Column column, boolean fullChunk, ClientVersion clientVersion, long sequence) implements Mutation {
    ChunkMutation {
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(clientVersion, "clientVersion");
      if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
    }
  }

  private record BlockMutation(Pos position, BlockState state, long sequence) implements Mutation {
    BlockMutation {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(state, "state");
      if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
    }
  }

  private record UnloadMutation(ChunkKey chunk, long sequence) implements Mutation {
    UnloadMutation {
      Objects.requireNonNull(chunk, "chunk");
      if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
    }
  }

  private record ChunkEntry(
      Column column,
      boolean complete,
      ClientVersion clientVersion,
      long revision
  ) {}

  private final String version;
  private final int minY;
  private final int maxY;
  private final Map<ChunkKey, ChunkEntry> chunks = new HashMap<>();
  private final Map<ChunkKey, Map<Pos, BlockState>> blockOverlays = new HashMap<>();
  private final List<Mutation> unassigned = new ArrayList<>();
  private final Map<Short, List<Mutation>> pending = new LinkedHashMap<>();
  private final Deque<Short> sentOrder = new ArrayDeque<>();
  private final Map<ClientVersion, ConcurrentHashMap<Integer, BlockState>> stateCache = new ConcurrentHashMap<>();
  private long revisionCounter;
  private long lastVisibleSequence=-1L;

  LiveClientWorldReplica(String version, int minY, int maxY) {
    this.version = Objects.requireNonNull(version, "version");
    if (minY > maxY) throw new IllegalArgumentException("minY must not exceed maxY");
    this.minY = minY;
    this.maxY = maxY;
  }

  synchronized void queueChunk(long sequence, Column column, boolean fullChunk, ClientVersion clientVersion) {
    unassigned.add(new ChunkMutation(column, fullChunk, clientVersion, sequence));
  }

  synchronized void queueBlock(long sequence, Pos position, BlockState state) {
    unassigned.add(new BlockMutation(position, state, sequence));
  }

  synchronized void queueUnload(long sequence, Chunk chunk) {
    unassigned.add(new UnloadMutation(new ChunkKey(chunk.x(), chunk.z()), sequence));
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

  synchronized boolean acknowledge(short transactionId, long acknowledgementSequence) {
    if (acknowledgementSequence < 0) throw new IllegalArgumentException("acknowledgementSequence must be non-negative");
    if (!pending.containsKey(transactionId)) return false;

    while (!sentOrder.isEmpty()) {
      short head = sentOrder.removeFirst();
      List<Mutation> mutations = pending.remove(head);
      if (mutations != null) {
        for (Mutation mutation : mutations) apply(mutation);
      }
      if (head == transactionId) break;
    }
    lastVisibleSequence=Math.max(lastVisibleSequence,acknowledgementSequence);
    return true;
  }

  synchronized long causalSequence() {
    return lastVisibleSequence;
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

  int decodedStateCacheSize() {
    int decodedStates = 0;
    for (ConcurrentHashMap<Integer, BlockState> cache : stateCache.values()) decodedStates += cache.size();
    return decodedStates;
  }

  /**
   * Captures a read-only window over the compact packet-derived chunk cache.
   *
   * <p>No chunk is expanded here. The snapshot backend retains the same
   * palette-backed PacketEvents columns and converts only the individual block
   * states that the deterministic collision model actually queries.</p>
   */
  WorldSnapshot snapshotAround(double centerX, double centerZ, int radiusChunks) {
    if (radiusChunks < 0) throw new IllegalArgumentException("radiusChunks must be non-negative");
    int centerChunkX = Math.floorDiv((int) Math.floor(centerX), 16);
    int centerChunkZ = Math.floorDiv((int) Math.floor(centerZ), 16);

    Map<ChunkKey, ChunkEntry> local = new LinkedHashMap<>();
    Map<ChunkKey, Map<Pos, BlockState>> overlays = new LinkedHashMap<>();
    long visibleSequence;

    synchronized (this) {
      visibleSequence=lastVisibleSequence;
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

    return WorldSnapshot.backed(
        version,
        minY,
        maxY,
        new BackendView(version, minY, maxY, local, overlays, visibleSequence));
  }

  /** A full current cache view, still lazy and therefore cheap to capture. */
  WorldSnapshot snapshot() {
    Map<ChunkKey, ChunkEntry> local;
    Map<ChunkKey, Map<Pos, BlockState>> overlays = new LinkedHashMap<>();
    long visibleSequence;
    synchronized (this) {
      local = new LinkedHashMap<>(chunks);
      visibleSequence=lastVisibleSequence;
      for (Map.Entry<ChunkKey, Map<Pos, BlockState>> entry : blockOverlays.entrySet()) {
        if (!entry.getValue().isEmpty()) overlays.put(entry.getKey(), Map.copyOf(entry.getValue()));
      }
    }
    return WorldSnapshot.backed(
        version,
        minY,
        maxY,
        new BackendView(version, minY, maxY, local, overlays, visibleSequence));
  }

  private final class BackendView implements WorldSnapshot.Backend {
    private final String backendVersion;
    private final int backendMinY;
    private final int backendMaxY;
    private final Map<ChunkKey, ChunkEntry> localChunks;
    private final Map<ChunkKey, Map<Pos, BlockState>> overlays;
    private final long visibleSequence;

    private BackendView(
        String backendVersion,
        int backendMinY,
        int backendMaxY,
        Map<ChunkKey, ChunkEntry> localChunks,
        Map<ChunkKey, Map<Pos, BlockState>> overlays,
        long visibleSequence) {
      this.backendVersion = backendVersion;
      this.backendMinY = backendMinY;
      this.backendMaxY = backendMaxY;
      this.localChunks = Map.copyOf(localChunks);
      this.overlays = Map.copyOf(overlays);
      this.visibleSequence = visibleSequence;
    }

    @Override public String version() { return backendVersion; }
    @Override public int minY() { return backendMinY; }
    @Override public int maxY() { return backendMaxY; }

    @Override public long causalSequence() { return visibleSequence; }

    @Override public Set<Chunk> loadedChunks() {
      Set<Chunk> result = new LinkedHashSet<>();
      for (ChunkKey key : localChunks.keySet()) result.add(new Chunk(key.x(), key.z()));
      return Set.copyOf(result);
    }

    @Override public boolean hasChunk(int chunkX, int chunkZ) {
      return localChunks.containsKey(new ChunkKey(chunkX, chunkZ));
    }

    @Override public Coverage coverageAt(int x, int y, int z) {
      if (y < backendMinY || y > backendMaxY) return Coverage.UNLOADED;
      ChunkKey key = new ChunkKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
      ChunkEntry entry = localChunks.get(key);
      if (entry == null || !entry.complete()) return Coverage.UNLOADED;

      Pos position = new Pos(x, y, z);
      Map<Pos, BlockState> overlay = overlays.get(key);
      if (overlay != null && overlay.containsKey(position)) {
        BlockState state = overlay.get(position);
        return state.isUnsupported() ? Coverage.UNSUPPORTED : Coverage.KNOWN;
      }

      try {
        WrappedBlockState raw = rawState(entry, x, y, z);
        if (raw == null) return Coverage.KNOWN;
        BlockState state = coreState(entry.clientVersion(), raw);
        return state != null && state.isUnsupported() ? Coverage.UNSUPPORTED : Coverage.KNOWN;
      } catch (RuntimeException failure) {
        return Coverage.UNSUPPORTED;
      }
    }

    @Override public BlockState blockAtOrNull(int x, int y, int z) {
      if (y < backendMinY || y > backendMaxY) return null;
      ChunkKey key = new ChunkKey(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
      ChunkEntry entry = localChunks.get(key);
      if (entry == null || !entry.complete()) return null;

      Pos position = new Pos(x, y, z);
      Map<Pos, BlockState> overlay = overlays.get(key);
      if (overlay != null && overlay.containsKey(position)) {
        BlockState state = overlay.get(position);
        return state.isUnsupported() || state.isAir() ? null : state;
      }

      WrappedBlockState raw = rawState(entry, x, y, z);
      if (raw == null || raw.getType().isAir()) return null;
      BlockState state = coreState(entry.clientVersion(), raw);
      return state == null || state.isUnsupported() || state.isAir() ? null : state;
    }

    private WrappedBlockState rawState(ChunkEntry entry, int x, int y, int z) {
      try {
        int offsetY = y - backendMinY;
        BaseChunk[] sections = entry.column().getChunks();
        int sectionIndex = offsetY >> 4;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return null;
        BaseChunk section = sections[sectionIndex];
        if (section == null || section.isEmpty()) return null;
        return section.get(entry.clientVersion(), x & 15, offsetY & 15, z & 15);
      } catch (RuntimeException ignored) {
        return null;
      }
    }

    private BlockState coreState(ClientVersion clientVersion, WrappedBlockState raw) {
      if (raw == null || raw.getType().isAir()) return null;
      ConcurrentHashMap<Integer, BlockState> versionCache =
          stateCache.computeIfAbsent(clientVersion, ignored -> new ConcurrentHashMap<>());
      int globalId = raw.getGlobalId();
      BlockState cached = versionCache.get(globalId);
      if (cached != null) return cached;
      BlockState fresh = toCoreState(raw);
      BlockState existing = versionCache.putIfAbsent(globalId, fresh);
      return existing != null ? existing : fresh;
    }
  }

  private void apply(Mutation mutation) {
    switch (mutation) {
      case ChunkMutation chunk -> applyChunk(chunk);
      case BlockMutation block -> {
        ChunkKey key = new ChunkKey(
            Math.floorDiv(block.position().x(), 16),
            Math.floorDiv(block.position().z(), 16));
        blockOverlays.computeIfAbsent(key, ignored -> new HashMap<>())
            .put(block.position(), block.state());
      }
      case UnloadMutation unload -> {
        chunks.remove(unload.chunk());
        blockOverlays.remove(unload.chunk());
      }
    }
  }

  private void applyChunk(ChunkMutation mutation) {
    ChunkKey key = new ChunkKey(mutation.column().getX(), mutation.column().getZ());
    ChunkEntry old = chunks.get(key);
    long revision = ++revisionCounter;

    if (mutation.fullChunk() || old == null) {
      chunks.put(key, new ChunkEntry(
          mutation.column(),
          mutation.fullChunk(),
          mutation.clientVersion(),
          revision));
      blockOverlays.remove(key);
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
