package dev.phantom.ac.world;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.Directions.Direction;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;

/**
 * An immutable, deterministic snapshot of the world as one client could have
 * known it at one instant.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Block states are stored per {@link Chunk}, which is the unit the client
 *       actually receives and unloads. A chunk is either fully known, or not
 *       present at all. There is no half-loaded chunk.</li>
 *   <li>The block-state map inside a chunk is a {@link TreeMap} keyed by
 *       {@link Pos}. Iteration order is therefore the canonical
 *       {@code (x, y, z)} order and never depends on hash ordering, which makes
 *       every query result reproducible.</li>
 *   <li>Vertically, positions outside the target version's world bounds report
 *       {@link Coverage#UNLOADED}. The 1.21.11 overworld fills
 *       {@code y=-64..319}; the nether and end use the same height limits with
 *       different content, and the client never receives sections outside the
 *       dimension's height.</li>
 * </ul>
 *
 * <h2>What is deliberately not here</h2>
 * <p>No movement physics, no gravity, no step height, and no anti-cheat
 * heuristics. This class answers questions about the world; the movement
 * simulator decides what to do with the answers.</p>
 *
 * @param version   the model version this snapshot's states were decoded for
 * @param chunks    chunk coordinate to immutable, sorted block-state map
 * @param minY      lowest block Y the dimension exposes
 * @param maxY      highest block Y the dimension exposes
 */
public final class WorldSnapshot implements Serializable {

  /** Runtime collision resolver for exact platform-native shapes. It is intentionally
   * optional and transient: deterministic replay falls back to the version catalogue. */
  @FunctionalInterface
  public interface CollisionResolver {
    java.util.Optional<VoxelShape> resolve(WorldSnapshot snapshot, BlockState state, int x, int y, int z);
  }

  /** A read-only, query-oriented backend used by compact client-world caches. */
  public interface Backend {
    String version();
    int minY();
    int maxY();
    Set<Chunk> loadedChunks();

    /**
     * Highest capture sequence for which every state in this backend is known
     * to have been visible to the client. Negative means no causal bound was
     * supplied (legacy/test snapshot semantics).
     */
    default long causalSequence() { return -1L; }

    /** Chunks whose load is known but whose complete payload is not yet published. */
    default Set<Chunk> unknownChunks() { return Set.of(); }

    default boolean hasChunk(int chunkX, int chunkZ) {
      return loadedChunks().contains(new Chunk(chunkX, chunkZ));
    }

    Coverage coverageAt(int x, int y, int z);
    BlockState blockAtOrNull(int x, int y, int z);

    /** Optional exact runtime shape. Empty means this backend has no native resolver. */
    default java.util.Optional<VoxelShape> resolveCollisionShape(WorldSnapshot snapshot, int x, int y, int z) {
      return java.util.Optional.empty();
    }

    /** Diagnostic-only description; never used to make a verdict. */
    default String coverageDetailAt(int x, int y, int z) {
      Coverage coverage = coverageAt(x, y, z);
      BlockState state = blockAtOrNull(x, y, z);
      return state == null ? coverage.toString() : coverage + " block=" + state.blockId();
    }

    /**
     * Materializes the backend only when a caller explicitly requests the full
     * map representation (for example replay serialization or diagnostic APIs).
     * Hot-path physics queries must use coverageAt/blockAtOrNull instead.
     */
    default Map<Chunk, Map<Pos, BlockState>> materializeChunks() {
      Map<Chunk, Map<Pos, BlockState>> result = new HashMap<>();
      for (Chunk chunk : loadedChunks()) {
        Map<Pos, BlockState> states = new HashMap<>();
        int minX = chunk.x() * 16;
        int maxX = minX + 15;
        int minZ = chunk.z() * 16;
        int maxZ = minZ + 15;
        for (int x = minX; x <= maxX; x++) {
          for (int y = minY(); y <= maxY(); y++) {
            for (int z = minZ; z <= maxZ; z++) {
              if (coverageAt(x, y, z) == Coverage.UNSUPPORTED) {
                BlockState state = blockAtOrNull(x, y, z);
                states.put(new Pos(x, y, z),
                    state == null ? BlockState.unsupported("backend-unsupported") : state);
              } else if (coverageAt(x, y, z) == Coverage.KNOWN) {
                BlockState state = blockAtOrNull(x, y, z);
                if (state != null && !state.isAir()) states.put(new Pos(x, y, z), state);
              }
            }
          }
        }
        result.put(chunk, states);
      }
      return result;
    }
  }

  /** The dimension height of every 1.21.11 dimension that a client can be in. */
  public static final int OVERWORLD_MIN_Y = -64;
  public static final int OVERWORLD_MAX_Y = 319;

  private final String version;
  private volatile Map<Chunk, Map<Pos, BlockState>> chunks;
  private final int minY;
  private final int maxY;
  private final Set<Chunk> unknownChunks;
  private final transient Backend backend;

  public WorldSnapshot(String version, Map<Chunk, Map<Pos, BlockState>> chunks, int minY, int maxY) {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(chunks, "chunks");
    if (minY > maxY) throw new IllegalArgumentException("minY must not exceed maxY");
    Map<Chunk, Map<Pos, BlockState>> frozen = new HashMap<>(chunks.size());
    for (Map.Entry<Chunk, Map<Pos, BlockState>> entry : chunks.entrySet()) {
      Chunk chunk = Objects.requireNonNull(entry.getKey(), "chunk key");
      Map<Pos, BlockState> states = Objects.requireNonNull(entry.getValue(), "chunk states");
      TreeMap<Pos, BlockState> sorted = new TreeMap<>(POS_ORDER);
      for (Map.Entry<Pos, BlockState> state : states.entrySet()) {
        Pos position = Objects.requireNonNull(state.getKey(), "state position");
        BlockState value = Objects.requireNonNull(state.getValue(), "state value");
        if (!Chunk.containing(position.x(), position.z()).equals(chunk)) {
          throw new IllegalArgumentException("position " + position + " is outside chunk " + chunk);
        }
        if (position.y() < minY || position.y() > maxY) {
          throw new IllegalArgumentException("position " + position + " is outside the dimension height " + minY + ".." + maxY);
        }
        if (sorted.put(position, value) != null) {
          throw new IllegalArgumentException("duplicate state for " + position);
        }
      }
      frozen.put(chunk, Collections.unmodifiableMap(sorted));
    }
    this.chunks = Collections.unmodifiableMap(frozen);
    this.version = version;
    this.minY = minY;
    this.maxY = maxY;
    this.unknownChunks = Set.of();
    this.backend = null;
  }

  private WorldSnapshot(String version, Map<Chunk, Map<Pos, BlockState>> chunks, Set<Chunk> unknownChunks, int minY, int maxY) {
    this.version = Objects.requireNonNull(version, "version");
    this.minY = minY;
    this.maxY = maxY;
    this.unknownChunks = Set.copyOf(unknownChunks);
    this.backend = null;
    Map<Chunk, Map<Pos, BlockState>> frozen = new HashMap<>(chunks.size());
    for (Map.Entry<Chunk, Map<Pos, BlockState>> entry : chunks.entrySet()) {
      TreeMap<Pos, BlockState> sorted = new TreeMap<>(POS_ORDER); sorted.putAll(entry.getValue());
      frozen.put(entry.getKey(), Collections.unmodifiableMap(sorted));
    }
    this.chunks = Collections.unmodifiableMap(frozen);
  }

  private WorldSnapshot(String version, int minY, int maxY, Backend backend) {
    this.version = Objects.requireNonNull(version, "version");
    this.minY = minY;
    this.maxY = maxY;
    if (minY > maxY) throw new IllegalArgumentException("minY must not exceed maxY");
    this.backend = Objects.requireNonNull(backend, "backend");
    this.unknownChunks = Set.copyOf(backend.unknownChunks());
    this.chunks = null;
  }

  public static WorldSnapshot backed(String version, int minY, int maxY, Backend backend) {
    Objects.requireNonNull(backend, "backend");
    if (!version.equals(backend.version()) || minY != backend.minY() || maxY != backend.maxY()) {
      throw new IllegalArgumentException("backend dimensions/version do not match snapshot");
    }
    return new WorldSnapshot(version, minY, maxY, backend);
  }

  public String version() { return version; }

  public Map<Chunk, Map<Pos, BlockState>> chunks() {
    Map<Chunk, Map<Pos, BlockState>> current = chunks;
    if (current != null) return current;
    Map<Chunk, Map<Pos, BlockState>> materialized = backend.materializeChunks();
    Map<Chunk, Map<Pos, BlockState>> frozen = new HashMap<>(materialized.size());
    for (Map.Entry<Chunk, Map<Pos, BlockState>> entry : materialized.entrySet()) {
      TreeMap<Pos, BlockState> sorted = new TreeMap<>(POS_ORDER);
      sorted.putAll(entry.getValue());
      frozen.put(entry.getKey(), Collections.unmodifiableMap(sorted));
    }
    current = Collections.unmodifiableMap(frozen);
    chunks = current;
    return current;
  }

  public int minY() { return minY; }

  public int maxY() { return maxY; }

  /** Highest capture sequence for which this snapshot's client-visible state is causally bounded. */
  public long causalSequence() {
    return backend == null ? -1L : backend.causalSequence();
  }

  /** Returns an immutable view with the supplied causal visibility boundary. */
  public WorldSnapshot withCausalSequence(long sequence) {
    if (backend == null) return this;
    WorldSnapshot base = this;
    Backend overlay = new Backend() {
      @Override public String version() { return base.version(); }
      @Override public int minY() { return base.minY(); }
      @Override public int maxY() { return base.maxY(); }
      @Override public Set<Chunk> loadedChunks() { return base.loadedChunks(); }
      @Override public long causalSequence() { return sequence; }
      @Override public Set<Chunk> unknownChunks() { return base.unknownChunkSet(); }
      @Override public boolean hasChunk(int chunkX, int chunkZ) { return base.hasChunk(chunkX, chunkZ); }
      @Override public Coverage coverageAt(int x, int y, int z) { return base.coverageAt(x, y, z); }
      @Override public BlockState blockAtOrNull(int x, int y, int z) { return base.blockAtOrNull(x, y, z); }
      @Override public java.util.Optional<VoxelShape> resolveCollisionShape(
          WorldSnapshot snapshot, int x, int y, int z) {
        return base.resolveCollisionShape(x, y, z);
      }
      @Override public String coverageDetailAt(int x, int y, int z) {
        return base.coverageDetailAt(x, y, z);
      }
    };
    return WorldSnapshot.backed(version, minY, maxY, overlay);
  }

  /** Canonical position order: x, then y, then z. */
  public static final java.util.Comparator<Pos> POS_ORDER =
      java.util.Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z);

  /** An empty snapshot for a dimension, i.e. the client has received no chunks. */
  /**
   * Merges snapshots that came from the same client-world replica at the same
   * validation instant. Overlapping chunks must agree; disjoint coverage is
   * combined. This is used to cover both the current observation and an
   * authoritative replay anchor without inventing world data.
   */
  public static WorldSnapshot merge(WorldSnapshot first,WorldSnapshot second){
    Objects.requireNonNull(first,"first");
    Objects.requireNonNull(second,"second");
    if(!first.version().equals(second.version())||first.minY()!=second.minY()||first.maxY()!=second.maxY())
      throw new IllegalArgumentException("world snapshots have incompatible dimensions or model versions");

    if (first.backend != null || second.backend != null) {
      Backend mergedBackend = new Backend() {
        @Override public String version() { return first.version(); }
        @Override public int minY() { return first.minY(); }
        @Override public int maxY() { return first.maxY(); }
        @Override public Set<Chunk> loadedChunks() {
          Set<Chunk> result = new LinkedHashSet<>(first.loadedChunks());
          result.addAll(second.loadedChunks());
          return Set.copyOf(result);
        }
        @Override public long causalSequence() {
          long a=first.causalSequence(), b=second.causalSequence();
          return a<0 || b<0 ? -1L : Math.max(a,b);
        }
        @Override public boolean hasChunk(int chunkX,int chunkZ) {
          return first.hasChunk(chunkX,chunkZ) || second.hasChunk(chunkX,chunkZ);
        }
        @Override public Coverage coverageAt(int x,int y,int z) {
          Chunk sought=Chunk.containing(x,z);
          boolean a=first.hasChunk(sought), b=second.hasChunk(sought);
          if (a && b) {
            Coverage ca=first.coverageAt(x,y,z), cb=second.coverageAt(x,y,z);
            if (ca!=cb) {
              throw new IllegalArgumentException("overlapping world snapshots disagree in coverage at ("+x+","+y+","+z+")");
            }
            if (ca!=Coverage.UNLOADED) {
              BlockState sa=first.blockAtOrNull(x,y,z), sb=second.blockAtOrNull(x,y,z);
              if (!Objects.equals(sa,sb) && ca==Coverage.KNOWN) {
                throw new IllegalArgumentException("overlapping world snapshots disagree at ("+x+","+y+","+z+")");
              }
            }
            return ca;
          }
          return a ? first.coverageAt(x,y,z) : second.coverageAt(x,y,z);
        }
        @Override public BlockState blockAtOrNull(int x,int y,int z) {
          Chunk sought=Chunk.containing(x,z);
          if (first.hasChunk(sought)) return first.blockAtOrNull(x,y,z);
          return second.blockAtOrNull(x,y,z);
        }
        @Override public java.util.Optional<VoxelShape> resolveCollisionShape(WorldSnapshot snapshot,int x,int y,int z) {
          Chunk sought=Chunk.containing(x,z);
          if(first.hasChunk(sought)){
            java.util.Optional<VoxelShape> a=first.resolveCollisionShape(x,y,z);
            if(a.isPresent())return a;
          }
          if(second.hasChunk(sought))return second.resolveCollisionShape(x,y,z);
          return java.util.Optional.empty();
        }
      };
      return WorldSnapshot.backed(first.version(),first.minY(),first.maxY(),mergedBackend);
    }

    Map<Chunk,Map<Pos,BlockState>> merged=new HashMap<>(first.chunks());
    for(var entry:second.chunks().entrySet()){
      Map<Pos,BlockState> existing=merged.get(entry.getKey());
      if(existing==null){
        merged.put(entry.getKey(),new HashMap<>(entry.getValue()));
        continue;
      }
      Map<Pos,BlockState> combined=new HashMap<>(existing);
      for(var block:entry.getValue().entrySet()){
        BlockState prior=combined.putIfAbsent(block.getKey(),block.getValue());
        if(prior!=null&&!prior.equals(block.getValue()))
          throw new IllegalArgumentException("overlapping world snapshots disagree at "+block.getKey());
      }
      merged.put(entry.getKey(),combined);
    }
    return new WorldSnapshot(first.version(),merged,first.minY(),first.maxY());
  }

  public static WorldSnapshot empty(String version, int minY, int maxY) {
    return new WorldSnapshot(version, Map.of(), minY, maxY);
  }

  /** The 1.21.11 overworld empty snapshot. */
  public static WorldSnapshot emptyOverworld12111() {
    return empty(dev.phantom.ac.Contracts.TARGET_VERSION, OVERWORLD_MIN_Y, OVERWORLD_MAX_Y);
  }

  /** A builder so callers never have to mutate a snapshot after construction. */
  public static Builder builder(String version) {
    return new Builder(version, OVERWORLD_MIN_Y, OVERWORLD_MAX_Y);
  }

  public static Builder builder(String version, int minY, int maxY) {
    return new Builder(version, minY, maxY);
  }

  // ------------------------------------------------------------------
  // Coverage and direct state access
  // ------------------------------------------------------------------

  /** Whether the client's world data covers this position at all. */
  public Coverage coverageAt(int x, int y, int z) {
    if (backend != null) return backend.coverageAt(x, y, z);
    if (y < minY || y > maxY) return Coverage.UNLOADED;
    Chunk chunk = Chunk.containing(x, z);
    if (unknownChunks.contains(chunk)) return Coverage.UNKNOWN;
    Map<Pos, BlockState> states = chunks.get(chunk);
    if (states == null) return Coverage.UNLOADED;
    BlockState state = states.get(new Pos(x, y, z));
    if (state == null) return Coverage.KNOWN;
    return state.isUnsupported() ? Coverage.UNSUPPORTED : Coverage.KNOWN;
  }

  public boolean isKnown(int x, int y, int z) {
    return coverageAt(x, y, z) == Coverage.KNOWN;
  }

  public boolean hasChunk(int chunkX, int chunkZ) {
    return backend != null
        ? backend.hasChunk(chunkX, chunkZ)
        : chunks.containsKey(new Chunk(chunkX, chunkZ));
  }

  public boolean hasChunk(Chunk chunk) {
    return hasChunk(chunk.x(), chunk.z());
  }

  public Set<Chunk> loadedChunks() {
    return backend != null ? Set.copyOf(backend.loadedChunks()) : chunks.keySet();
  }

  /** All block states in a chunk, in canonical order, or an empty map if unloaded. */
  public Map<Pos, BlockState> chunkStates(Chunk chunk) {
    if (backend != null) return chunks().getOrDefault(chunk, Map.of());
    return chunks.getOrDefault(chunk, Map.of());
  }

  /**
   * The block state at a position.
   *
   * @return the state when covered, otherwise {@code null}. Callers must consult
   *         {@link #coverageAt} rather than substituting air.
   */
  /** Diagnostic-only coverage/state description for debug traces. */
  public String coverageDetailAt(int x, int y, int z) {
    if (backend != null) return backend.coverageDetailAt(x, y, z);
    Coverage coverage = coverageAt(x, y, z);
    BlockState state = chunks == null ? null : chunks.getOrDefault(Chunk.containing(x, z), Map.of())
        .get(new Pos(x, y, z));
    return state == null
        ? coverage.toString()
        : coverage + " block=" + state.blockId() + " properties=" + state.properties();
  }

  /**
   * Returns a causally identical snapshot with one already-known block overridden.
   *
   * <p>This is used only for short-lived client-side prediction, such as the
   * one-tick window after a 1.21+ client finishes digging a block but before
   * the authoritative block update reaches the client. Unknown/unloaded chunks
   * are never turned into known air by this helper.</p>
   */
  public WorldSnapshot withBlockOverride(int x, int y, int z, BlockState state) {
    Objects.requireNonNull(state, "state");
    if (coverageAt(x, y, z) != Coverage.KNOWN) return this;
    final WorldSnapshot base = this;
    final Pos target = new Pos(x, y, z);
    Backend overlay = new Backend() {
      @Override public String version() { return base.version(); }
      @Override public int minY() { return base.minY(); }
      @Override public int maxY() { return base.maxY(); }
      @Override public Set<Chunk> loadedChunks() { return base.loadedChunks(); }
      @Override public long causalSequence() { return base.causalSequence(); }
      @Override public Set<Chunk> unknownChunks() { return base.unknownChunkSet(); }
      @Override public boolean hasChunk(int chunkX, int chunkZ) { return base.hasChunk(chunkX, chunkZ); }
      @Override public Coverage coverageAt(int bx, int by, int bz) {
        return base.coverageAt(bx, by, bz);
      }
      @Override public BlockState blockAtOrNull(int bx, int by, int bz) {
        if (bx == target.x() && by == target.y() && bz == target.z()) {
          return state.isAir() || state.isUnsupported() ? null : state;
        }
        return base.blockAtOrNull(bx, by, bz);
      }
      @Override public java.util.Optional<VoxelShape> resolveCollisionShape(
          WorldSnapshot snapshot, int bx, int by, int bz) {
        if (bx == target.x() && by == target.y() && bz == target.z()) {
          if (state.isAir()) return java.util.Optional.of(VoxelShape.empty());
          if (state.isUnsupported()) return java.util.Optional.empty();
        }
        return base.resolveCollisionShape(bx, by, bz);
      }
    };
    return WorldSnapshot.backed(version, minY, maxY, overlay);
  }

  public BlockState blockAtOrNull(int x, int y, int z) {
    if (backend != null) return backend.blockAtOrNull(x, y, z);
    if (y < minY || y > maxY) return null;
    Map<Pos, BlockState> states = chunks.get(Chunk.containing(x, z));
    if (states == null) return null;
    BlockState state = states.get(new Pos(x, y, z));
    return state != null && !state.isUnsupported() ? state : null;
  }

  /**
   * The block state at a position, defaulting to air only when the position is
   * genuinely {@link Coverage#UNKNOWN}. An unloaded position throws, because
   * treating it as air is exactly the bug this layer exists to prevent.
   */
  public BlockState requireBlockAt(int x, int y, int z) {
    Coverage coverage = coverageAt(x, y, z);
    return switch (coverage) {
      case KNOWN -> {
        BlockState state = blockAtOrNull(x, y, z);
        yield state == null ? BlockState.air() : state;
      }
      case UNSUPPORTED -> throw new UnsupportedStateException(x, y, z);
      case UNKNOWN -> throw new UnknownRegionException(x, y, z);
      case UNLOADED -> throw new UnloadedRegionException(x, y, z);
    };
  }

  /**
   * The state at a known position, asserting that the caller has already
   * established coverage. Used by query code that filters coverage first, so the
   * assertion is a guard against a refactoring mistake rather than a fallback.
   */
  public BlockState blockRequireKnown(Pos position) {
    Coverage coverage = coverageAt(position.x(), position.y(), position.z());
    if (coverage == Coverage.UNLOADED) throw new UnloadedRegionException(position.x(), position.y(), position.z());
    if (coverage == Coverage.UNKNOWN) throw new UnknownRegionException(position.x(), position.y(), position.z());
    if (coverage == Coverage.UNSUPPORTED) throw new UnsupportedStateException(position.x(), position.y(), position.z());
    // Covered and empty is genuine air, not missing data.
    BlockState state = blockAtOrNull(position.x(), position.y(), position.z());
    return state == null ? BlockState.air() : state;
  }

  /** Raised when a caller asks for a guaranteed state in an unloaded region. */
  public static final class UnknownRegionException extends IllegalStateException {
    public UnknownRegionException(int x, int y, int z) { super("client world data for (" + x + "," + y + "," + z + ") is not yet known"); }
  }

  public static final class UnloadedRegionException extends IllegalStateException {
    public UnloadedRegionException(int x, int y, int z) {
      super("no client world data for (" + x + "," + y + "," + z + "): the chunk is unloaded");
    }
  }

  /** Raised when a caller asks for a guaranteed shape at a state this build cannot verify. */
  public static final class UnsupportedStateException extends IllegalStateException {
    public UnsupportedStateException(int x, int y, int z) {
      super("block state at (" + x + "," + y + "," + z + ") has no verified shape in this build");
    }
  }

  // ------------------------------------------------------------------
  // Coverage over regions
  // ------------------------------------------------------------------

  /** Vanilla {@code AABB} integer sweep bounds, clamped to the dimension height. */
  public List<Pos> positionsIntersecting(BlockBox query) {
    // Vanilla's block sweep is floor(min) .. floor(max) on every axis, clamped at
    // the top by the dimension height. A query that spans a whole block therefore
    // includes both cells, and a query ending on a fractional bound excludes the
    // cell above it.
    int minX = (int) Math.floor(query.minX());
    int maxX = (int) Math.floor(query.maxX());
    int minZ = (int) Math.floor(query.minZ());
    int maxZ = (int) Math.floor(query.maxZ());
    int lowY = Math.max(this.minY, (int) Math.floor(query.minY()));
    int highY = Math.min(this.maxY, (int) Math.floor(query.maxY()));
    List<Pos> positions = new ArrayList<>();
    for (int x = minX; x <= maxX; x++) {
      for (int y = lowY; y <= highY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          positions.add(new Pos(x, y, z));
        }
      }
    }
    return List.copyOf(positions);
  }

  /** One concrete block cell whose coverage cannot be fully verified by this snapshot. */
  public record CoverageProblem(Pos position, Coverage coverage, String detail) implements Serializable {
    public CoverageProblem {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(coverage, "coverage");
      Objects.requireNonNull(detail, "detail");
    }
  }

  /**
   * Returns every block cell in the query whose coverage is UNLOADED, UNKNOWN,
   * or UNSUPPORTED, in the snapshot's canonical XYZ order. Diagnostic-only.
   */
  public List<CoverageProblem> coverageProblemsIn(BlockBox query) {
    List<CoverageProblem> result = new ArrayList<>();
    for (Pos position : positionsIntersecting(query)) {
      Coverage coverage = coverageAt(position.x(), position.y(), position.z());
      if (coverage == Coverage.UNLOADED
          || coverage == Coverage.UNKNOWN
          || coverage == Coverage.UNSUPPORTED) {
        result.add(new CoverageProblem(position, coverage, coverageDetailAt(position.x(), position.y(), position.z())));
      }
    }
    return List.copyOf(result);
  }

  /**
   * The distinct coverage values present in the block cells a query box
   * overlaps. This is how the reachability layer learns that its answer must be
   * uncertain rather than wrong.
   */
  public Set<Coverage> coverageIn(BlockBox query) {
    Set<Coverage> result = new LinkedHashSet<>();
    for (Pos position : positionsIntersecting(query)) {
      result.add(coverageAt(position.x(), position.y(), position.z()));
    }
    if (result.isEmpty()) {
      // A query entirely outside the world height has no known data at all.
      result.add(Coverage.UNLOADED);
    }
    return result;
  }

  /** True when every block cell the query overlaps is fully known. */
  public boolean fullyKnown(BlockBox query) {
    return coverageIn(query).equals(Set.of(Coverage.KNOWN));
  }

  /** True when the query overlaps information this build cannot verify. */
  public boolean hasUnknownOrUnsupported(BlockBox query) {
    Set<Coverage> coverage = coverageIn(query);
    return coverage.contains(Coverage.UNLOADED) || coverage.contains(Coverage.UNKNOWN) || coverage.contains(Coverage.UNSUPPORTED);
  }

  // ------------------------------------------------------------------
  // Shapes
  // ------------------------------------------------------------------

  /**
   * The world-space collision shape of the block at a position.
   *
   * <p>Neighbour-dependent blocks (fences, walls, panes, gates) are resolved
   * here, because neighbour state is a world fact and not a property of the
   * block itself. A block whose neighbours are not fully known produces an
   * empty shape only when the block's own shape is empty; otherwise the missing
   * neighbour is reported through {@link #coverageIn} on the connection check.</p>
   */
  /** Returns only the platform-native collision answer, without invoking deterministic fallbacks. */
  private java.util.Optional<VoxelShape> resolveCollisionShape(int x,int y,int z) {
    return backend == null
        ? java.util.Optional.empty()
        : backend.resolveCollisionShape(this,x,y,z);
  }

  public VoxelShape collisionShapeAt(int x, int y, int z) {
    Coverage coverage = coverageAt(x, y, z);
    if (coverage != Coverage.KNOWN) return VoxelShape.empty();
    if (backend != null) {
      java.util.Optional<VoxelShape> exact = backend.resolveCollisionShape(this, x, y, z);
      if (exact.isPresent()) return exact.get();
    }
    // A known position with no stored state is genuine air, which has no
    // collision geometry. Only UNLOADED and UNSUPPORTED mean "no data".
    BlockState state = blockAtOrNull(x, y, z);
    if (state == null) return VoxelShape.empty();
    if (state.variant() == BlockState.Variant.CATALOGUE) {
      java.util.Optional<VoxelShape> deterministic =
          dev.phantom.ac.world.v12111.BlockCollisionCatalogue12111.shapeFor(state);
      if (deterministic.isPresent()) return deterministic.get().toWorld(x, y, z);
    }
    VoxelShape local = BlockCatalogue12111.collisionShape(state, neighboursFor(x, y, z, state));
    return local.isEmpty() ? VoxelShape.empty() : local.toWorld(x, y, z);
  }

  private BlockCatalogue12111.NeighbourLookup neighboursFor(int x, int y, int z, BlockState self) {
    return new BlockCatalogue12111.NeighbourLookup() {
      @Override public boolean connects(Direction direction) {
        return WorldSnapshot.this.connectsFor(x, y, z, self, direction);
      }

      @Override public boolean tallNeighbour(Direction direction) {
        BlockState neighbour = blockAtOrNull(x + direction.stepX(), y + direction.stepY(), z + direction.stepZ());
        return neighbour != null && neighbour.variant() == BlockState.Variant.WALL;
      }
    };
  }

  /**
   * Vanilla connection test used by fences, walls, panes and gates.
   *
   * <p>A neighbour connects when it is the same kind of connectable block, when
   * it is a full cube that presents a solid face to this block, or when it is a
   * block that vanilla allows to merge with the shape. Perpendicular-only
   * connections for vertical offsets are not modelled because 1.21.11 does not
   * connect them.</p>
   */
  private boolean connectsFor(int x, int y, int z, BlockState self, Direction direction) {
    BlockState neighbour = blockAtOrNull(x + direction.stepX(), y + direction.stepY(), z + direction.stepZ());
    if (neighbour == null) return false;
    if (neighbour.isAir()) return false;
    if (neighbour.variant() == self.variant()) return true;
    if (neighbour.variant() == BlockState.Variant.UNSUPPORTED) return false;
    VoxelShape neighbourShape = BlockCatalogue12111.baseCollisionShape(neighbour);
    if (neighbourShape.isEmpty()) return false;
    // Vanilla's solid-face test: the neighbour must present a full face towards
    // this block. Direction here points from this block to the neighbour, so
    // the face we need is the neighbour's opposite face.
    return neighbourShape.isFaceFull(direction.opposite());
  }

  // ------------------------------------------------------------------
  // Identity
  // ------------------------------------------------------------------

  /** The world's loaded chunks as a stable ordered set, for diagnostics and tests. */
  public List<Chunk> loadedChunkList() {
    List<Chunk> ordered = new ArrayList<>(loadedChunks());
    ordered.sort(java.util.Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));
    return List.copyOf(ordered);
  }

  public Set<Chunk> unknownChunkSet() { return backend == null ? unknownChunks : Set.copyOf(backend.unknownChunks()); }

  @Override public String toString() {
    int chunkCount = backend != null ? backend.loadedChunks().size() : chunks.size();
    return "WorldSnapshot[" + version + " chunks=" + chunkCount + " y=" + minY + ".." + maxY + "]";
  }

  /** Materializes a compact backend only at explicit serialization/replay boundaries. */
  private Object writeReplace() {
    return backend == null ? this : new WorldSnapshot(version, chunks(), unknownChunkSet(), minY, maxY);
  }

  @Override public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof WorldSnapshot that)) return false;
    return minY == that.minY && maxY == that.maxY
        && version.equals(that.version) && chunks().equals(that.chunks());
  }

  @Override public int hashCode() {
    return Objects.hash(version, chunks(), unknownChunkSet(), minY, maxY);
  }

  // ------------------------------------------------------------------
  // Builder
  // ------------------------------------------------------------------

  /** Mutable builder that produces one immutable snapshot. Not thread-safe by design. */
  public static final class Builder {
    private final String version;
    private final int minY;
    private final int maxY;
    private final Set<Chunk> unknownChunks = new TreeSet<>(java.util.Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));
    private final Map<Chunk, Map<Pos, BlockState>> chunks = new TreeMap<>(
        java.util.Comparator.comparingInt(Chunk::x).thenComparingInt(Chunk::z));

    private Builder(String version, int minY, int maxY) {
      this.version = Objects.requireNonNull(version, "version");
      if (minY > maxY) throw new IllegalArgumentException("minY must not exceed maxY");
      this.minY = minY;
      this.maxY = maxY;
    }

    /** Declares a chunk as fully decoded/known to the client. */
    public Builder loadChunk(int chunkX, int chunkZ) {
      Chunk chunk = new Chunk(chunkX, chunkZ); unknownChunks.remove(chunk);
      chunks.computeIfAbsent(new Chunk(chunkX, chunkZ), ignored -> new HashMap<>());
      return this;
    }

    /** Declares a chunk as delivered to the client, without any block states. */
    public Builder loadUnknownChunk(int chunkX, int chunkZ) {
      Chunk chunk = new Chunk(chunkX, chunkZ); unknownChunks.add(chunk); chunks.remove(chunk); return this;
    }

    public Builder loadUnknownChunk(Chunk chunk) { return loadUnknownChunk(chunk.x(), chunk.z()); }

    public Builder loadChunk(Chunk chunk) {
      return loadChunk(chunk.x(), chunk.z());
    }

    /** Unloads a chunk, discarding its states, mirroring a client chunk-unload packet. */
    public Builder unloadChunk(int chunkX, int chunkZ) {
      Chunk chunk = new Chunk(chunkX, chunkZ); chunks.remove(chunk); unknownChunks.remove(chunk);
      return this;
    }

    public Builder setBlock(int x, int y, int z, BlockState state) {
      Objects.requireNonNull(state, "state");
      if (y < minY || y > maxY) {
        throw new IllegalArgumentException("y " + y + " is outside the dimension height " + minY + ".." + maxY);
      }
      if (state.isUnsupported()) {
        throw new IllegalArgumentException("unsupported states must be stored with setUnsupportedBlock");
      }
      Chunk chunk = Chunk.containing(x, z);
      chunks.computeIfAbsent(chunk, ignored -> new HashMap<>()).put(new Pos(x, y, z), state);
      return this;
    }

    /**
     * Stores a block state that this build cannot verify. The snapshot reports
     * it as {@link Coverage#UNSUPPORTED} rather than as a guessed shape.
     */
    public Builder setUnsupportedBlock(int x, int y, int z, String blockId) {
      if (y < minY || y > maxY) {
        throw new IllegalArgumentException("y " + y + " is outside the dimension height " + minY + ".." + maxY);
      }
      // Stored directly rather than through setBlock, which deliberately rejects
      // unsupported states so ordinary writes cannot hide missing shape coverage.
      Chunk chunk = Chunk.containing(x, z);
      chunks.computeIfAbsent(chunk, ignored -> new HashMap<>())
          .put(new Pos(x, y, z), BlockState.unsupported(blockId));
      return this;
    }

    public Builder setBlocks(Map<Pos, BlockState> states) {
      for (Map.Entry<Pos, BlockState> entry : states.entrySet()) {
        Pos position = entry.getKey();
        setBlock(position.x(), position.y(), position.z(), entry.getValue());
      }
      return this;
    }

    public WorldSnapshot build() {
      Map<Chunk, Map<Pos, BlockState>> snapshot = new HashMap<>(chunks.size());
      for (Map.Entry<Chunk, Map<Pos, BlockState>> entry : chunks.entrySet()) {
        // Materialize every declared chunk so removing its last block does not
        // silently unload the chunk.
        snapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
      }
      return new WorldSnapshot(version, snapshot, unknownChunks, minY, maxY);
    }
  }
}