    public boolean isUncertain() {
      return coverage.contains(Coverage.UNLOADED) || coverage.contains(Coverage.UNKNOWN) || coverage.contains(Coverage.UNSUPPORTED);
    }

    public boolean collides() {
      return !collisions.isEmpty();
    }
  }

  /** The blocks overlapping a region, with coverage. */
  public record BlockSample(List<Pos> positions, List<BlockState> states, Set<Coverage> coverage) implements Serializable {
    public BlockSample {
      positions = List.copyOf(positions);
      states = List.copyOf(states);
      coverage = Set.copyOf(coverage);
    }

    public boolean isDefinite() {
      return coverage.equals(Set.of(Coverage.KNOWN));
    }
  }

  // ------------------------------------------------------------------
  // Collision queries
  // ------------------------------------------------------------------

  /**
   * All world-space collision boxes overlapping {@code query}, in canonical
   * order. Boxes that merely touch the query are excluded, matching vanilla's
   * strict {@code AABB.intersects} test.
   */
  public static CollisionResult collisions(WorldSnapshot world, BlockBox query) {
    List<Collision> collisions = new ArrayList<>();
    Set<Coverage> coverage = new LinkedHashSet<>(world.collisionCoverageIn(query));
    for (Pos position : world.positionsIntersecting(query)) {
      Coverage cellCoverage = world.coverageAt(position.x(), position.y(), position.z());
      coverage.add(cellCoverage);
      if (cellCoverage != Coverage.KNOWN) continue;
      BlockState state = world.blockAtOrNull(position.x(), position.y(), position.z());
      if (state == null || state.isAir()) continue;
      VoxelShape shape = world.collisionShapeAt(position.x(), position.y(), position.z());
      for (BlockBox box : shape.boxes()) {
        if (box.intersects(query)) collisions.add(new Collision(position, state, box));
      }
    }
    return new CollisionResult(collisions, coverage);
  }

  /** All collision boxes overlapping a world-space {@link VoxelShape}, used for swept paths. */
  public static CollisionResult collisions(WorldSnapshot world, VoxelShape query) {
    List<Collision> collisions = new ArrayList<>();
    BlockBox bounds = query.bounds();
    Set<Coverage> coverage = new LinkedHashSet<>(world.collisionCoverageIn(bounds));
    if (bounds == null) return new CollisionResult(List.of(), Set.of(Coverage.KNOWN));
    for (Pos position : world.positionsIntersecting(bounds)) {
      Coverage cellCoverage = world.coverageAt(position.x(), position.y(), position.z());
      coverage.add(cellCoverage);
      if (cellCoverage != Coverage.KNOWN) continue;
      VoxelShape shape = world.collisionShapeAt(position.x(), position.y(), position.z());
      for (BlockBox box : shape.boxes()) {
        if (box.intersects(bounds)) collisions.add(new Collision(position, world.requireBlockAt(position.x(), position.y(), position.z()), box));
      }
    }
    return new CollisionResult(collisions, coverage);
  }

  /**
   * Vanilla {@code Level.collisions} per axis: the displacement a box may travel
   * along one axis before hitting something. Contact (a box exactly touching a
   * collision face) does not count as a collision, which is what makes standing
   * on the ground distinct from being inside a block.
   */
  public static double clip(WorldSnapshot world, Axis axis, BlockBox box, double amount) {
    if (amount == 0.0) return 0.0;
    BlockBox swept = box.move(
        axis == Axis.X ? amount : 0, axis == Axis.Y ? amount : 0, axis == Axis.Z ? amount : 0);
    double result = amount;
    for (Pos position : world.positionsIntersecting(swept)) {
      if (world.coverageAt(position.x(), position.y(), position.z()) != Coverage.KNOWN) continue;
      VoxelShape shape = world.collisionShapeAt(position.x(), position.y(), position.z());
      if (shape.isEmpty()) continue;
      result = shape.clip(axis, box, result);
      if (result == 0.0) return 0.0;
    }
    return result;
  }

  /**
   * The full swept-path collision response presented as a single result: which
   * collision boxes exist along the path, and the clipped displacement per axis.
   * Physics stays in Phase 5; this only reports geometry and clip distances.
   */
  public record PathCollision(List<Collision> collisions, Set<Coverage> coverage,
                              double clippedX, double clippedY, double clippedZ) implements Serializable {
    public PathCollision {
      collisions = List.copyOf(collisions);