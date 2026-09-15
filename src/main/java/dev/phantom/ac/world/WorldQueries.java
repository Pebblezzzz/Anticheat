package dev.phantom.ac.world;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.geometry.Directions.Direction;
import dev.phantom.ac.geometry.VoxelShape;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;

/**
 * Deterministic world queries over a {@link WorldSnapshot}.
 *
 * <p>Every method here answers a question about the world. None of them decide
 * how a player moves: there is no gravity, drag, step height, jump height, or
 * anti-cheat threshold anywhere in this class. Phase 5 consumes these facts and
 * applies the version's movement rules to them.</p>
 *
 * <p>Every query is a pure function of the snapshot and the query arguments, so
 * repeating a query always returns an identical result. Coordinate sweeps are
 * always in ascending integer order.</p>
 */
public final class WorldQueries {
  private WorldQueries() {}

  /** A world-space collision box together with the block that produced it. */
  public record Collision(Pos position, BlockState state, BlockBox box) implements Serializable {}

  /**
   * A query result along with the coverage it was computed over. Callers must
   * check {@link #coverage} before trusting an empty collision list: an empty
   * list over {@link Coverage#UNLOADED} means "no data", not "no collision".
   */
  public record CollisionResult(List<Collision> collisions, Set<Coverage> coverage) implements Serializable {
    public CollisionResult {
      collisions = List.copyOf(collisions);
      coverage = Set.copyOf(coverage);
    }

    public boolean isEmpty() {
      return collisions.isEmpty();
    }

    /** True when the result is complete enough to be used as a definite fact. */
    public boolean isDefinite() {
      return coverage.equals(Set.of(Coverage.KNOWN));
    }

    /** True when the answer could change if the missing world data were known. */
    public boolean isUncertain() {
      return coverage.contains(Coverage.UNLOADED) || coverage.contains(Coverage.UNSUPPORTED);
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
    Set<Coverage> coverage = new LinkedHashSet<>();
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
    Set<Coverage> coverage = new LinkedHashSet<>();
    BlockBox bounds = query.bounds();
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
      coverage = Set.copyOf(coverage);
    }

    public boolean collided() {
      return clippedX != 0.0 || clippedY != 0.0 || clippedZ != 0.0;
    }
  }

  /**
   * Geometry along a movement path. The path is the box swept by the
   * displacement, which is exactly the region vanilla checks when moving an
   * entity.
   */
  public static PathCollision pathCollision(WorldSnapshot world, BlockBox box, double dx, double dy, double dz) {
    BlockBox swept = new BlockBox(
        Math.min(box.minX(), box.minX() + dx), Math.min(box.minY(), box.minY() + dy), Math.min(box.minZ(), box.minZ() + dz),
        Math.max(box.maxX(), box.maxX() + dx), Math.max(box.maxY(), box.maxY() + dy), Math.max(box.maxZ(), box.maxZ() + dz));
    CollisionResult along = collisions(world, swept);
    return new PathCollision(along.collisions(), along.coverage(),
        clip(world, Axis.X, box, dx), clip(world, Axis.Y, box, dy), clip(world, Axis.Z, box, dz));
  }

  // ------------------------------------------------------------------
  // Directional probes
  // ------------------------------------------------------------------

  /**
   * What collision geometry sits in the block cell directly beside the box in a
   * horizontal direction.
   *
   * <p>The probe is the whole adjacent block cell over the box's own vertical
   * span, not a hairline just outside the face. A hairline would miss any
   * collision whose nearest extent is further than that hairline, and geometry
   * that merely touches the box is already excluded by the strict intersection
   * test inside {@link #collisions}.</p>
   */
  public static CollisionResult beside(WorldSnapshot world, BlockBox box, Direction direction) {
    if (direction.axis() == Axis.Y) {
      throw new IllegalArgumentException("beside() requires a horizontal direction but got " + direction);
    }
    int stepX = direction.stepX();
    int stepZ = direction.stepZ();
    // Sweep from the box face through the adjacent cell. This both finds a
    // collision immediately beside a partial-cell box and preserves coverage
    // information when that adjacent cell is unloaded.
    double minX = stepX > 0 ? box.maxX() : stepX < 0 ? box.minX() - 1.0 : box.minX();
    double maxX = stepX > 0 ? box.maxX() + 1.0 : stepX < 0 ? box.minX() : box.maxX();
    double minZ = stepZ > 0 ? box.maxZ() : stepZ < 0 ? box.minZ() - 1.0 : box.minZ();
    double maxZ = stepZ > 0 ? box.maxZ() + 1.0 : stepZ < 0 ? box.minZ() : box.maxZ();
    return collisions(world, new BlockBox(minX, box.minY(), minZ, maxX, box.maxY(), maxZ));
  }

  /**
   * The block cell a face separates the box from, retained as a small geometry
   * helper for callers that need a canonical floor-based coordinate.
   */
  private static double cellBeyond(double face, int step) {
    return step > 0 ? Math.floor(face) : Math.ceil(face) - 1.0;
  }

  /** The block cell a coordinate belongs to. */
  private static double floorCell(double coordinate) {
    return Math.floor(coordinate);
  }

  /**
   * What collision geometry is above the box: the block cell directly above the
   * box's top face.
   */
  public static CollisionResult above(WorldSnapshot world, BlockBox box) {
    double cellMinY = floorCell(box.maxY());
    return collisions(world,
        new BlockBox(box.minX(), cellMinY, box.minZ(), box.maxX(), cellMinY + 1.0, box.maxZ()));
  }

  /**
   * The support surface directly beneath a box: the highest collision top that
   * the box would land on, and the block that provides it.
   *
   * <p>This reports geometry only. Whether the entity is on the ground, and how
   * far it may fall, is a Phase 5 decision.</p>
   */
  public record Floor(BlockBox surface, Pos position, BlockState state, double topY) implements Serializable {}

  /**
   * All support surfaces overlapping the box's horizontal footprint below
   * {@code box.minY()}, ordered from highest to lowest. Only geometry within
   * {@code maximumDepth} below the box is searched, and the search stops at the
   * first unloaded section because support beyond missing data is not knowable.
   */
  public static List<Floor> floors(WorldSnapshot world, BlockBox box, double maximumDepth) {
    if (maximumDepth < 0) throw new IllegalArgumentException("maximumDepth must be non-negative");
    List<Floor> floors = new ArrayList<>();
    BlockBox region = new BlockBox(box.minX(), box.minY() - maximumDepth, box.minZ(), box.maxX(), box.minY(), box.maxZ());
    List<Pos> positions = world.positionsIntersecting(region);
    // Highest first: positionsIntersecting yields ascending y, so walk backwards.
    for (int index = positions.size() - 1; index >= 0; index--) {
      Pos position = positions.get(index);
      if (world.coverageAt(position.x(), position.y(), position.z()) != Coverage.KNOWN) continue;
      VoxelShape shape = world.collisionShapeAt(position.x(), position.y(), position.z());
      for (BlockBox candidate : shape.boxes()) {
        if (!overlapsHorizontally(candidate, box)) continue;
        if (candidate.maxY() > box.minY()) continue;
        floors.add(new Floor(candidate, position, world.blockRequireKnown(position), candidate.maxY()));
      }
    }
    floors.sort((a, b) -> Double.compare(b.topY(), a.topY()));
    return List.copyOf(floors);
  }

  /** The single highest support surface beneath the box, if any is known. */
  public static Optional<Floor> floor(WorldSnapshot world, BlockBox box, double maximumDepth) {
    List<Floor> all = floors(world, box, maximumDepth);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
  }

  /**
   * The support surfaces the box's vertical range already overlaps, which is the
   * "inside a block" case rather than the "standing on a block" case.
   */
  public static List<Floor> intersectingFloors(WorldSnapshot world, BlockBox box) {
    List<Floor> floors = new ArrayList<>();
    for (Pos position : world.positionsIntersecting(box)) {
      if (world.coverageAt(position.x(), position.y(), position.z()) != Coverage.KNOWN) continue;
      VoxelShape shape = world.collisionShapeAt(position.x(), position.y(), position.z());
      for (BlockBox candidate : shape.boxes()) {
        if (candidate.intersects(box)) floors.add(new Floor(candidate, position, world.blockRequireKnown(position), candidate.maxY()));
      }
    }
    return List.copyOf(floors);
  }

  /** The collision geometry above the box that would block upward movement. */
  public static List<Floor> ceilings(WorldSnapshot world, BlockBox box, double maximumHeight) {
    if (maximumHeight < 0) throw new IllegalArgumentException("maximumHeight must be non-negative");
    List<Floor> ceilings = new ArrayList<>();
    BlockBox region = new BlockBox(box.minX(), box.maxY(), box.minZ(), box.maxX(), box.maxY() + maximumHeight, box.maxZ());
    List<Pos> positions = world.positionsIntersecting(region);
    for (Pos position : positions) {
      if (world.coverageAt(position.x(), position.y(), position.z()) != Coverage.KNOWN) continue;
      VoxelShape shape = world.collisionShapeAt(position.x(), position.y(), position.z());
      for (BlockBox candidate : shape.boxes()) {
        if (!overlapsHorizontally(candidate, box)) continue;
        if (candidate.minY() < box.maxY()) continue;
        ceilings.add(new Floor(candidate, position, world.blockRequireKnown(position), candidate.minY()));
      }
    }
    ceilings.sort((a, b) -> Double.compare(a.topY(), b.topY()));
    return List.copyOf(ceilings);
  }

  private static boolean overlapsHorizontally(BlockBox candidate, BlockBox box) {
    return candidate.maxX() > box.minX() && candidate.minX() < box.maxX()
        && candidate.maxZ() > box.minZ() && candidate.minZ() < box.maxZ();
  }

  // ------------------------------------------------------------------
  // Block samples and environment
  // ------------------------------------------------------------------

  /** The block states whose cells a region overlaps, in canonical order. */
  public static BlockSample blocksIn(WorldSnapshot world, BlockBox region) {
    List<Pos> positions = new ArrayList<>();
    List<BlockState> states = new ArrayList<>();
    Set<Coverage> coverage = new LinkedHashSet<>();
    for (Pos position : world.positionsIntersecting(region)) {
      Coverage cellCoverage = world.coverageAt(position.x(), position.y(), position.z());
      coverage.add(cellCoverage);
      if (cellCoverage != Coverage.KNOWN) continue;
      positions.add(position);
      states.add(world.blockRequireKnown(position));
    }
    return new BlockSample(positions, states, coverage);
  }

  /** One block cell of the environment the player's bounding box overlaps. */
  public record EnvironmentCell(Pos position, BlockState state, BlockCatalogue12111.Environment environment,
                               FluidState fluid, double slipperiness) implements Serializable {}

  /**
   * Everything movement-relevant that the given box overlaps, plus the union of
   * the environment flags. This is a report of world facts; the movement
   * response is Phase 5's job.
   */
  public record EnvironmentSample(List<EnvironmentCell> cells, Set<Coverage> coverage,
                                  boolean water, boolean lava, boolean climbable, boolean bubbleColumn,
                                  boolean slime, boolean honey, boolean soulSand, boolean cobweb,
                                  boolean powderSnow, boolean scaffolding, boolean sweetBerryBush,
                                  boolean cactus, boolean bed, boolean ice, boolean packedIce,
                                  boolean blueIce, boolean magmaBlock, boolean lilyPad,
                                  boolean containsFluid, boolean allFluidHeightsKnown) implements Serializable {
    public EnvironmentSample {
      cells = List.copyOf(cells);
      coverage = Set.copyOf(coverage);
    }

    public boolean isDefinite() {
      return coverage.equals(Set.of(Coverage.KNOWN));
    }

    /** True when the box overlaps actual fluid, as opposed to merely being near it. */
    public boolean inFluid() {
      return water || lava;
    }
  }

  /** The movement-relevant environment overlapping a box, with coverage. */
  public static EnvironmentSample environment(WorldSnapshot world, BlockBox box) {
    List<EnvironmentCell> cells = new ArrayList<>();
    Set<Coverage> coverage = new LinkedHashSet<>();
    boolean water = false;
    boolean lava = false;
    boolean climbable = false;
    boolean bubbleColumn = false;
    boolean slime = false;
    boolean honey = false;
    boolean soulSand = false;
    boolean cobweb = false;
    boolean powderSnow = false;
    boolean scaffolding = false;
    boolean sweetBerryBush = false;
    boolean cactus = false;
    boolean bed = false;
    boolean ice = false;
    boolean packedIce = false;
    boolean blueIce = false;
    boolean magmaBlock = false;
    boolean lilyPad = false;
    boolean containsFluid = false;
    boolean allFluidHeightsKnown = true;
    for (Pos position : world.positionsIntersecting(box)) {
      Coverage cellCoverage = world.coverageAt(position.x(), position.y(), position.z());
      coverage.add(cellCoverage);
      if (cellCoverage != Coverage.KNOWN) continue;
      BlockState state = world.blockRequireKnown(position);
      if (state.isAir()) continue;
      BlockCatalogue12111.Environment flags = BlockCatalogue12111.environment(state);
      FluidState fluid = fluidAt(world, position, state);
      if (fluid.isFluid()) {
        containsFluid = true;
        if (!fluid.hasKnownHeight()) allFluidHeightsKnown = false;
      }
      water |= flags.water();
      lava |= flags.lava();
      climbable |= flags.climbable();
      bubbleColumn |= flags.bubbleColumn();
      slime |= flags.slime();
      honey |= flags.honey();
      soulSand |= flags.soulSand();
      cobweb |= flags.cobweb();
      powderSnow |= flags.powderSnow();
      scaffolding |= flags.scaffold();
      sweetBerryBush |= flags.sweetBerryBush();
      cactus |= flags.cactus();
      bed |= flags.bed();
      ice |= flags.ice();
      packedIce |= flags.packedIce();
      blueIce |= flags.blueIce();
      magmaBlock |= flags.magmaBlock();
      lilyPad |= flags.lilyPad();
      cells.add(new EnvironmentCell(position, state, flags, fluid, BlockCatalogue12111.slipperiness(state)));
    }
    return new EnvironmentSample(cells, coverage, water, lava, climbable, bubbleColumn, slime, honey,
        soulSand, cobweb, powderSnow, scaffolding, sweetBerryBush, cactus, bed, ice, packedIce, blueIce,
        magmaBlock, lilyPad, containsFluid, allFluidHeightsKnown);
  }

  // ------------------------------------------------------------------
  // Fluids
  // ------------------------------------------------------------------

  /**
   * The fluid state of a single position, including a surface height derived
   * from the position's own level and its four horizontal neighbours.
   *
   * <p>Vanilla's fluid height rule needs all four neighbours. When any of them
   * is not visible to this client the height is reported as
   * {@link FluidState.HeightSource#UNAVAILABLE} with a NaN height rather than a
   * plausible-looking number.</p>
   */
  public static FluidState fluidAt(WorldSnapshot world, Pos position, BlockState state) {
    FluidState base = BlockCatalogue12111.fluid(state);
    if (!base.isFluid()) return FluidState.NONE;
    if (base.type() == FluidState.Type.WATER && base.level() == 0 && state.waterlogged()) {
      // A waterlogged block holds a full source-height water fluid.
      return new FluidState(FluidState.Type.WATER, 0, FluidState.HeightSource.SOURCE, 1.0, false);
    }
    int sameHeight = 0;
    boolean neighboursVisible = true;
    for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
      int nx = position.x() + direction.stepX();
      int nz = position.z() + direction.stepZ();
      if (world.coverageAt(nx, position.y(), nz) != Coverage.KNOWN) {
        neighboursVisible = false;
        break;
      }
      BlockState neighbour = world.blockAtOrNull(nx, position.y(), nz);
      FluidState neighbourFluid = neighbour == null ? FluidState.NONE : BlockCatalogue12111.fluid(neighbour);
      if (neighbourFluid.type() == base.type() && neighbourFluid.level() == base.level()) sameHeight++;
    }
    if (!neighboursVisible) {
      return new FluidState(base.type(), base.level(), FluidState.HeightSource.UNAVAILABLE,
          FluidState.HEIGHT_UNKNOWN, base.falling());
    }
    double height = BlockCatalogue12111.flowingHeight(base.level(), sameHeight);
    FluidState.HeightSource source = base.level() == 0 && sameHeight == 4
        ? FluidState.HeightSource.SOURCE : FluidState.HeightSource.DERIVED;
    return new FluidState(base.type(), base.level(), source, height, base.falling());
  }

  /** Convenience overload that looks the state up itself. */
  public static FluidState fluidAt(WorldSnapshot world, int x, int y, int z) {
    BlockState state = world.blockAtOrNull(x, y, z);
    if (state == null) return FluidState.NONE;
    return fluidAt(world, new Pos(x, y, z), state);
  }

  /** Vanilla {@code Level#getFluidState} for a whole region: the fluids overlapping a box. */
  public static List<Pos> fluidPositions(WorldSnapshot world, BlockBox box) {
    List<Pos> fluid = new ArrayList<>();
    for (Pos position : world.positionsIntersecting(box)) {
      if (world.coverageAt(position.x(), position.y(), position.z()) != Coverage.KNOWN) continue;
      BlockState state = world.blockAtOrNull(position.x(), position.y(), position.z());
      if (state != null && BlockCatalogue12111.fluid(state).isFluid()) fluid.add(position);
    }
    return List.copyOf(fluid);
  }
}
