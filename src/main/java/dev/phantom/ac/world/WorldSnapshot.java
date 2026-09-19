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
  /** Coverage required to resolve collision shapes, including one-cell contextual neighbours. */
  public Set<Coverage> collisionCoverageIn(BlockBox query) {
    Set<Coverage> result = new LinkedHashSet<>(coverageIn(query));
    /*
     * Fences, walls and panes can change shape based on adjacent blocks. Resolve
     * their context conservatively by including a one-block horizontal halo. This
     * never invents a connection: a missing neighbour now makes the collision
     * result non-definite instead of silently behaving like air.
     */
    BlockBox halo = new BlockBox(
        query.minX() - 1.0, query.minY(), query.minZ() - 1.0,
        query.maxX() + 1.0, query.maxY(), query.maxZ() + 1.0);
    result.addAll(coverageIn(halo));
    return Set.copyOf(result);
  }

  /** True when collision geometry for the query and all contextual neighbours is known. */
  public boolean collisionFullyKnown(BlockBox query) {
    return collisionCoverageIn(query).equals(Set.of(Coverage.KNOWN));
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
  public VoxelShape collisionShapeAt(int x, int y, int z) {
    Coverage coverage = coverageAt(x, y, z);
    if (coverage != Coverage.KNOWN) return VoxelShape.empty();
    // A known position with no stored state is genuine air, which has no