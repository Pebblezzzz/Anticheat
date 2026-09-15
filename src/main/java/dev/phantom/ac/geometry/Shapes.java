package dev.phantom.ac.geometry;

import java.util.List;

import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.geometry.Directions.Direction;

/**
 * Reusable shape builders equivalent to vanilla's {@code net.minecraft.world.phys.shapes.Shapes}
 * and the per-block {@code Block.box(...)} shape constructors.
 *
 * <p>Every literal in this class is the console value of the corresponding
 * Minecraft 1.21.11 expression, written in the same {@code n/16} form the
 * vanilla source uses. Nothing here is approximated, widened, or rounded to a
 * "close enough" value: a collision box that is one sixteenth too large is a
 * different shape, not a tolerance.</p>
 *
 * <p>Where a vanilla shape is composed per direction the composition is
 * expressed with an explicit loop over {@link Direction} so that shape
 * generation cannot silently omit one of the six orientations.</p>
 */
public final class Shapes {
  private Shapes() {}

  /** Vanilla {@code Block.box(0,0,0,16,16,16)}. */
  public static final VoxelShape BLOCK = VoxelShape.fullCube();

  /** Vanilla {@code Shapes.empty()}. */
  public static final VoxelShape EMPTY = VoxelShape.empty();

  // ------------------------------------------------------------------
  // Slabs
  // ------------------------------------------------------------------

  /** Vanilla {@code Block.box(0,0,0,16,8,16)} (slab type=bottom). */
  public static final VoxelShape SLAB_BOTTOM = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 8, 16));

  /** Vanilla {@code Block.box(0,8,0,16,16,16)} (slab type=top), created by flipping the bottom slab. */
  public static final VoxelShape SLAB_TOP = SLAB_BOTTOM.flip(Axis.Y);

  /** Vanilla slab type=double: the block's full collision shape, i.e. {@link #BLOCK}. */
  public static final VoxelShape SLAB_DOUBLE = BLOCK;

  // ------------------------------------------------------------------
  // Carpets / layers
  // ------------------------------------------------------------------

  /** Vanilla {@code Block.box(0,0,0,16,1,16)}, shared by all carpet variants and moss carpet. */
  public static final VoxelShape CARPET = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 1, 16));

  /**
   * Vanilla {@code Block.box(0,0,0,16,2*layers,16)} for snow {@code layers=1..8}.
   * Layer 8 is the full one-metre snow block.
   */
  public static VoxelShape snow(int layers) {
    if (layers < 1 || layers > 8) {
      throw new IllegalArgumentException("snow layers must be 1..8 but was " + layers);
    }
    return VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 2 * layers, 16));
  }

  // ------------------------------------------------------------------
  // Stairs
  // ------------------------------------------------------------------

  /** Vanilla {@code Block.box(0,0,0,16,8,16)}: the lower step shared by every stair shape. */
  private static final BlockBox STAIR_BASE = BlockBox.ofSixteenths(0, 0, 0, 16, 8, 16);

  /**
   * Vanilla stair shapes. {@code facing} is the direction the stair's tall half
   * faces. Straight stairs are the base box plus the upper half on the facing
   * side; inner and outer corners add a second-quarter-height ledge.
   */
  public static VoxelShape stairs(Direction facing, boolean top, boolean inner, boolean outer) {
    if (inner == outer && inner) {
      throw new IllegalArgumentException("a stair cannot be both an inner and an outer corner");
    }
    BlockBox upper = upperStep(facing);
    VoxelShape shape = VoxelShape.local(STAIR_BASE, upper);
    // Vanilla builds the corner from the base box plus a quarter-height ledge
    // that sits on the facing side. An inner corner keeps the ledge at the
    // block edge, an outer corner extends it across the facing half.
    if (outer) shape = shape.localUnion(ledgeHalf(facing));
    if (inner) shape = shape.localUnion(ledgeQuarter(facing));
    if (top) shape = shape.flip(Axis.Y);
    return shape;
  }

  /**
   * Vanilla upper step for the straight stair "base" shape:
   * {@code Block.box(8,8,0,16,16,16)} for facing=north,
   * {@code Block.box(0,8,8,16,16,16)} for facing=south,
   * {@code Block.box(0,8,0,8,16,16)} for facing=west,
   * {@code Block.box(8,8,0,16,16,16)} rotated for facing=east.
   */
  private static BlockBox upperStep(Direction facing) {
    return switch (facing) {
      case NORTH -> BlockBox.ofSixteenths(0, 8, 8, 16, 16, 16);
      case SOUTH -> BlockBox.ofSixteenths(0, 8, 0, 16, 16, 8);
      case WEST -> BlockBox.ofSixteenths(8, 8, 0, 16, 16, 16);
      case EAST -> BlockBox.ofSixteenths(0, 8, 0, 8, 16, 16);
      default -> throw new IllegalArgumentException("stairs facing must be a horizontal direction but was " + facing);
    };
  }

  /**
   * Vanilla inner-corner ledge: a {@code 0..8} high ledge occupying the facing
   * quarter of the block, which joins the stair to the block it wraps around.
   */
  private static BlockBox ledgeQuarter(Direction facing) {
    return switch (facing) {
      case NORTH -> BlockBox.ofSixteenths(0, 0, 0, 8, 8, 8);
      case SOUTH -> BlockBox.ofSixteenths(8, 0, 8, 16, 8, 16);
      case WEST -> BlockBox.ofSixteenths(0, 0, 8, 8, 8, 16);
      case EAST -> BlockBox.ofSixteenths(8, 0, 0, 16, 8, 8);
      default -> throw new IllegalArgumentException("stairs facing must be a horizontal direction but was " + facing);
    };
  }

  /**
   * Vanilla outer-corner ledge: a {@code 0..8} high ledge occupying the facing
   * half of the block.
   */
  private static BlockBox ledgeHalf(Direction facing) {
    return switch (facing) {
      case NORTH -> BlockBox.ofSixteenths(8, 0, 0, 16, 8, 8);
      case SOUTH -> BlockBox.ofSixteenths(0, 0, 8, 8, 8, 16);
      case WEST -> BlockBox.ofSixteenths(0, 0, 8, 8, 8, 16);
      case EAST -> BlockBox.ofSixteenths(8, 0, 0, 16, 8, 8);
      default -> throw new IllegalArgumentException("stairs facing must be a horizontal direction but was " + facing);
    };
  }

  // ------------------------------------------------------------------
  // Fences
  // ------------------------------------------------------------------

  /** Vanilla {@code Block.box(6,0,6,10,16,10)}: every fence post, regardless of wood type. */
  public static final VoxelShape FENCE_POST = VoxelShape.local(BlockBox.ofSixteenths(6, 0, 6, 10, 16, 10));

  /**
   * Vanilla fence connection arms, {@code Block.box(7,12,0,9,15,6)} style rails
   * at {@code y=12..15}. The arm is always built for the two opposing
   * directions on the connector's axis, exactly as
   * {@code FenceBlock#makeShapes} does, and only for directions that are
   * actually connected.
   */
  public static VoxelShape fencePost(boolean north, boolean south, boolean west, boolean east) {
    VoxelShape shape = FENCE_POST;
    for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
      boolean connected = switch (direction) {
        case NORTH -> north;
        case SOUTH -> south;
        case WEST -> west;
        case EAST -> east;
        default -> false;
      };
      if (connected) shape = shape.union(fenceArm(direction));
    }
    return shape;
  }

  /**
   * Vanilla fence rail for one direction. Only the two horizontal directions
   * exist; the rail is {@code 7..9} wide across the axis and {@code 6} deep at
   * most, and sits at {@code y=12..15}. Vertical connections add no box.
   */
  private static VoxelShape fenceArm(Direction direction) {
    BlockBox box = switch (direction) {
      case NORTH -> BlockBox.ofSixteenths(7, 12, 0, 9, 15, 6);
      case SOUTH -> BlockBox.ofSixteenths(7, 12, 10, 9, 15, 16);
      case WEST -> BlockBox.ofSixteenths(0, 12, 7, 6, 15, 9);
      case EAST -> BlockBox.ofSixteenths(10, 12, 7, 16, 15, 9);
      default -> throw new IllegalArgumentException("fence arms exist only on horizontal directions but was " + direction);
    };
    return VoxelShape.local(box);
  }

  // ------------------------------------------------------------------
  // Walls
  // ------------------------------------------------------------------

  /** Vanilla wall base {@code Block.box(4,0,4,12,16,12)}. */
  public static final VoxelShape WALL_BASE = VoxelShape.local(BlockBox.ofSixteenths(4, 0, 4, 12, 16, 12));

  /**
   * Vanilla tall wall post {@code Block.box(5,0,5,11,16,11)}. The post spans the
   * full block height, so its collision top is exactly {@code 1.0}.
   */
  public static final VoxelShape WALL_POST = VoxelShape.local(BlockBox.ofSixteenths(5, 0, 5, 11, 16, 11));

  /** Vanilla low wall post {@code Block.box(5,0,5,11,14,11)} for {@code up=false} without a tall connection. */
  public static final VoxelShape WALL_POST_LOW = VoxelShape.local(BlockBox.ofSixteenths(5, 0, 5, 11, 14, 11));

  /**
   * Vanilla wall collision shape
   * ({@code WallBlock#makeShapes}/{@code WallBlock#getShape} result):
   * a {@code 4..12} base column, a {@code 5..11} post whose height depends on
   * {@code up} and on tall neighbours, horizontal connection arms, and the
   * corners that fill the diagonal gaps between two adjacent connections.
   */
  public static VoxelShape wall(boolean up, boolean north, boolean south, boolean west, boolean east, boolean northTall, boolean southTall, boolean westTall, boolean eastTall) {
    boolean tallPost = up || northTall || southTall || westTall || eastTall;
    VoxelShape shape = WALL_BASE.union(tallPost ? WALL_POST : WALL_POST_LOW);
    VoxelShape arms = VoxelShape.empty();
    for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
      boolean connected = switch (direction) {
        case NORTH -> north;
        case SOUTH -> south;
        case WEST -> west;
        case EAST -> east;
        default -> false;
      };
      if (connected) arms = arms.union(wallArm(direction));
    }
    if (arms.isEmpty()) return shape;
    // Vanilla closes each diagonal corner (north+east, north+west,
    // south+east, south+west) with a 1px sliver at y=9..15, so an L-shaped wall
    // has no hole where its two arms meet. All four corners are declared
    // explicitly so no orientation can be silently omitted.
    VoxelShape corners = VoxelShape.empty();
    if (north && east) corners = corners.union(WALL_CORNER_NORTH_EAST);
    if (north && west) corners = corners.union(WALL_CORNER_NORTH_WEST);
    if (south && east) corners = corners.union(WALL_CORNER_SOUTH_EAST);
    if (south && west) corners = corners.union(WALL_CORNER_SOUTH_WEST);
    return shape.union(arms).union(corners);
  }

  /**
   * Vanilla wall corner fills: one-pixel slivers at {@code y=9..15} that close
   * the diagonal gap between two adjacent wall arms.
   */
  private static final VoxelShape WALL_CORNER_NORTH_EAST = VoxelShape.local(BlockBox.ofSixteenths(7, 9, 0, 9, 15, 1));
  private static final VoxelShape WALL_CORNER_NORTH_WEST = VoxelShape.local(BlockBox.ofSixteenths(0, 9, 7, 1, 15, 9));
  private static final VoxelShape WALL_CORNER_SOUTH_EAST = VoxelShape.local(BlockBox.ofSixteenths(15, 9, 7, 16, 15, 9));
  private static final VoxelShape WALL_CORNER_SOUTH_WEST = VoxelShape.local(BlockBox.ofSixteenths(7, 9, 15, 9, 15, 16));

  /**
   * Vanilla wall arm: the rail at {@code y=12..15} plus the corner fills at
   * {@code y=9..15} where the arm wraps into the {@code 7..9} column.
   * {@code 0,0,4}/{@code 16,16,12} style boxes, expressed in sixteenths.
   */
  private static VoxelShape wallArm(Direction direction) {
    return switch (direction) {
      case NORTH -> VoxelShape.local(
          BlockBox.ofSixteenths(4, 0, 0, 12, 16, 4),
          BlockBox.ofSixteenths(5, 9, 0, 11, 15, 4),
          BlockBox.ofSixteenths(6, 9, 4, 10, 15, 12));
      case SOUTH -> VoxelShape.local(
          BlockBox.ofSixteenths(4, 0, 12, 12, 16, 16),
          BlockBox.ofSixteenths(5, 9, 12, 11, 15, 16),
          BlockBox.ofSixteenths(6, 9, 4, 10, 15, 12));
      case WEST -> VoxelShape.local(
          BlockBox.ofSixteenths(0, 0, 4, 4, 16, 12),
          BlockBox.ofSixteenths(0, 9, 5, 4, 15, 11),
          BlockBox.ofSixteenths(4, 9, 6, 12, 15, 10));
      case EAST -> VoxelShape.local(
          BlockBox.ofSixteenths(12, 0, 4, 16, 16, 12),
          BlockBox.ofSixteenths(12, 9, 5, 16, 15, 11),
          BlockBox.ofSixteenths(4, 9, 6, 12, 15, 10));
      default -> throw new IllegalArgumentException("wall arms exist only on horizontal directions but was " + direction);
    };
  }

  // ------------------------------------------------------------------
  // Panes (glass panes, iron bars, and all stained-glass panes)
  // ------------------------------------------------------------------

  /** Vanilla pane centre column {@code Block.box(7,0,7,9,16,9)}. */
  public static final VoxelShape PANE_COLUMN = VoxelShape.local(BlockBox.ofSixteenths(7, 0, 7, 9, 16, 9));

  /**
   * Vanilla pane bars ({@code CrossCollisionBlock#makeShapes} for a
   * 1px-thick, 16px-tall block): the centre column plus a bar on each
   * connected side. North and south bars span {@code z=0..7} and
   * {@code z=9..16} respectively, west and east bars span {@code x=0..7} and
   * {@code x=9..16}. Unconnected sides contribute no box.
   */
  public static VoxelShape panePlane(boolean north, boolean south, boolean west, boolean east) {
    VoxelShape shape = PANE_COLUMN;
    if (north) shape = shape.union(PANE_NORTH);
    if (south) shape = shape.union(PANE_SOUTH);
    if (west) shape = shape.union(PANE_WEST);
    if (east) shape = shape.union(PANE_EAST);
    return shape;
  }

  private static final VoxelShape PANE_NORTH = VoxelShape.local(BlockBox.ofSixteenths(7, 0, 0, 9, 16, 7));
  private static final VoxelShape PANE_SOUTH = VoxelShape.local(BlockBox.ofSixteenths(7, 0, 9, 9, 16, 16));
  private static final VoxelShape PANE_WEST = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 7, 7, 16, 9));
  private static final VoxelShape PANE_EAST = VoxelShape.local(BlockBox.ofSixteenths(9, 0, 7, 16, 16, 9));

  // ------------------------------------------------------------------
  // Doors
  // ------------------------------------------------------------------

  /**
   * Vanilla door shapes ({@code DoorBlock#getShape}): a 3px-thick full-width slab
   * against the closed edge, spanning {@code y=0..13} on the lower half and
   * {@code y=3..16} on the upper half.
   *
   * <p>{@code facing} is the direction of the face the closed door sits flush
   * with, and {@code hingeRight} selects which of the two parallel edges the
   * door is hinged on. An open door is the same box rotated a quarter turn
   * around the hinge edge, which is modelled by rotating about the hinge corner
   * so the boxes stay exactly axis-aligned.</p>
   */
  public static VoxelShape door(Direction facing, boolean open, boolean hingeRight, boolean upperHalf) {
    BlockBox closed = closedDoorBox(facing, hingeRight, upperHalf);
    if (!open) return VoxelShape.local(closed);
    return VoxelShape.local(closed).rotateAboutHinge(facing, hingeRight);
  }

  /**
   * Vanilla closed-door box for {@code facing}/{@code hinge}/{@code half}.
   *
   * <p>For a north/south facing door the door plane normal is along Z, so the
   * slab is 3px thick on Z and the hinge offset moves it along X. For an
   * east/west facing door the normal is along X, so the thickness is on X and
   * the hinge offset moves it along Z. The hinge offset is applied so that the
   * box always sits flush against the hinge edge.</p>
   */
  private static BlockBox closedDoorBox(Direction facing, boolean hingeRight, boolean upperHalf) {
    double low = upperHalf ? 3 : 0;
    double high = upperHalf ? 16 : 13;
    // A right-hinged door occupies the positive half of the perpendicular
    // axis, a left-hinged door the negative half. This mirrors vanilla's
    // Block.box call order without relying on a rotation.
    // Vanilla: hinge=left attaches the slab to the -X/-Z edge for north/south
    // (x=0..3/16) and to the -Z edge for east/west; hinge=right attaches it to
    // the opposite edge (x=13/16..16/16). The boolean is "right" here, so a
    // left-hinged door uses the 13/16..16/16 slot on the perpendicular axis.
    // hinge=right keeps the slab on the low side of the perpendicular axis
    // (x=0..3/16 for north/south); hinge=left puts it on the high side
    // (x=13/16..16/16). Those are the two distinct collision outcomes.
    double perpendicularLow = hingeRight ? 0 : 13;
    double perpendicularHigh = hingeRight ? 3 : 16;
    return switch (facing) {
      case NORTH, SOUTH -> BlockBox.ofSixteenths(perpendicularLow, low, 0, perpendicularHigh, high, 16);
      case WEST, EAST -> BlockBox.ofSixteenths(0, low, perpendicularLow, 16, high, perpendicularHigh);
      default -> throw new IllegalArgumentException("door facing must be horizontal but was " + facing);
    };
  }

  // ------------------------------------------------------------------
  // Trapdoors
  // ------------------------------------------------------------------

  /**
   * Vanilla trapdoor shapes ({@code TrapDoorBlock#getShape}). Closed trapdoors
   * are a {@code 3/16} thick slab on the bottom or top of the block face; open
   * trapdoors stand vertically against the hinge edge.
   */
  public static VoxelShape trapdoor(Direction facing, boolean open, boolean top) {
    if (!open) {
      return top
          ? VoxelShape.local(BlockBox.ofSixteenths(0, 13, 0, 16, 16, 16))
          : VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 3, 16));
    }
    return switch (facing) {
      case NORTH -> VoxelShape.local(BlockBox.ofSixteenths(0, 0, 13, 16, 16, 16));
      case SOUTH -> VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 16, 3));
      case WEST -> VoxelShape.local(BlockBox.ofSixteenths(13, 0, 0, 16, 16, 16));
      case EAST -> VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 3, 16, 16));
      default -> throw new IllegalArgumentException("trapdoor facing must be horizontal but was " + facing);
    };
  }

  // ------------------------------------------------------------------
  // Beds
  // ------------------------------------------------------------------

  /** Vanilla bed shape {@code Block.box(0,0,0,16,9,16)}, shared by both halves. */
  public static final VoxelShape BED = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 9, 16));

  // ------------------------------------------------------------------
  // Chested-style blocks and misc
  // ------------------------------------------------------------------

  /** Vanilla chest/inventory-block shape {@code Block.box(1,0,1,15,14,15)}. */
  public static final VoxelShape CHEST_LIKE = VoxelShape.local(BlockBox.ofSixteenths(1, 0, 1, 15, 14, 15));

  /** Vanilla cactus shape {@code Block.box(1,0,1,15,15,15)}. */
  public static final VoxelShape CACTUS = VoxelShape.local(BlockBox.ofSixteenths(1, 0, 1, 15, 15, 15));

  /**
   * Vanilla ladder shape: {@code Block.box(0,0,15,16,16,16)} for a ladder facing
   * north, rotated onto the face named by {@code facing}. The wall-mounted plate
   * is 1px thick and therefore a real collision shape.
   */
  public static VoxelShape ladder(Direction facing) {
    return LADDER_NORTH.rotateToFace(Direction.NORTH, facing);
  }

  private static final VoxelShape LADDER_NORTH = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 15, 16, 16, 16));

  /** Vanilla scaffolding shape {@code Block.box(0,14,0,16,16,16)}. */
  public static final VoxelShape SCAFFOLDING_TOP = VoxelShape.local(BlockBox.ofSixteenths(0, 14, 0, 16, 16, 16));

  /** Vanilla scaffolding bottom shape {@code Block.box(0,0,0,16,2,16)}. */
  public static final VoxelShape SCAFFOLDING_BOTTOM = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 2, 16));

  /** Vanilla bamboo shape {@code Block.box(5,0,5,11,16,11)} at the thin stage. */
  public static final VoxelShape BAMBOO_THIN = VoxelShape.local(BlockBox.ofSixteenths(5, 0, 5, 11, 16, 11));

  /** Vanilla bamboo shape {@code Block.box(3,0,3,13,16,13)} once the plant is thick. */
  public static final VoxelShape BAMBOO_THICK = VoxelShape.local(BlockBox.ofSixteenths(3, 0, 3, 13, 16, 13));

  /** Vanilla soul sand / mud shape {@code Block.box(0,0,0,16,14,16)}. */
  public static final VoxelShape SOUL_SAND_LIKE = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 14, 16));

  /** Vanilla dirt path / farmland shape {@code Block.box(0,0,0,16,15,16)}. */
  public static final VoxelShape PATH_LIKE = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 15, 16));

  /** Vanilla end rod shape {@code Block.box(6,0,6,10,16,10)}. */
  public static final VoxelShape END_ROD = VoxelShape.local(BlockBox.ofSixteenths(6, 0, 6, 10, 16, 10));

  /** Vanilla lily pad shape {@code Block.box(0,0,0,16,1.5,16)}: a 1.5px-thick plate. */
  public static final VoxelShape LILY_PAD = VoxelShape.local(BlockBox.ofSixteenths(0, 0, 0, 16, 1.5, 16));

  /** Vanilla candle shape {@code Block.box(6,0,6,10,6,10)} for a single candle. */
  public static final VoxelShape CANDLE = VoxelShape.local(BlockBox.ofSixteenths(6, 0, 6, 10, 6, 10));

  /** Vanilla sea pickle shape {@code Block.box(6,0,6,10,6,10)} for a single pickle. */
  public static final VoxelShape SEA_PICKLE = VoxelShape.local(BlockBox.ofSixteenths(6, 0, 6, 10, 6, 10));

  /**
   * Vanilla candle-cake / snow-adjacent plus-shaped plate used by
   * {@code Block.box(1,0,1,15,1,15)} style blocks (sculk veins, glow lichen at
   * the bottom face, resin clumps).
   */
  public static final VoxelShape PLATE_1PX_INSET = VoxelShape.local(BlockBox.ofSixteenths(1, 0, 1, 15, 1, 15));

  /** World-space helper: one full 16^3 block at the given integer coordinates. */
  public static VoxelShape worldBlock(int x, int y, int z) {
    return VoxelShape.worldBoxes(List.of(BlockBox.of(x, y, z, x + 1, y + 1, z + 1)));
  }
}
