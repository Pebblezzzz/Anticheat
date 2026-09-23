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
