package dev.phantom.ac.geometry;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.phantom.ac.geometry.Directions.Axis;
import dev.phantom.ac.geometry.Directions.Direction;

/**
 * An immutable union of axis-aligned boxes in block-local coordinates
 * ({@code [0,1]} on each axis) or in world space.
 *
 * <p>Box order is preserved exactly as declared, so iteration is deterministic
 * and two shapes built from the same definition are always {@code equals}. The
 * class deliberately mirrors vanilla's {@code VoxelShape} contract:</p>
 *
 * <ul>
 *   <li>{@link #toWorld(int, int, int)} produces world-space boxes by adding the
 *       integer block coordinates, which is exact in binary floating point.</li>
 *   <li>{@link #isFaceFull(Direction)} reports whether a face is completely
 *       covered by the union, which is the predicate vanilla uses for
 *       {@code BlockState#isSideSolid} / fence and wall connection logic.</li>
 *   <li>{@link #clip} returns the single-axis displacement after collision,
 *       using the same strict-inequality overlap test as
 *       {@code AABB.collide}.</li>
 * </ul>
 */
public final class VoxelShape implements Serializable {

  private static final VoxelShape EMPTY = new VoxelShape(List.of(), false);

  private final List<BlockBox> boxes;
  private final boolean worldSpace;

  private VoxelShape(List<BlockBox> boxes, boolean worldSpace) {
    this.boxes = List.copyOf(boxes);
    this.worldSpace = worldSpace;
  }

  /** Creates a block-local shape from one or more boxes, in the given order. */
  public static VoxelShape local(BlockBox... boxes) {
    Objects.requireNonNull(boxes, "boxes");
    for (BlockBox box : boxes) {
      Objects.requireNonNull(box, "box");
      if (box.minX() < 0 || box.minY() < 0 || box.minZ() < 0
          || box.maxX() > 1 || box.maxY() > 1 || box.maxZ() > 1) {
        throw new IllegalArgumentException("block-local shape box must lie inside the unit cube: " + box);
      }
    }
    return boxes.length == 0 ? EMPTY : new VoxelShape(List.of(boxes), false);
  }

  public static VoxelShape local(List<BlockBox> boxes) {
    Objects.requireNonNull(boxes, "boxes");
    if (boxes.isEmpty()) return EMPTY;
    return local(boxes.toArray(BlockBox[]::new));
  }

  public static VoxelShape empty() {
    return EMPTY;
  }

  /** Vanilla {@code Shapes.block()}: one full-cube box. */
  public static VoxelShape fullCube() {
    return local(BlockBox.FULL);
  }

  /** Vanilla {@code Shapes.box(x1,y1,z1,x2,y2,z2)} in world coordinates. */
  public static VoxelShape worldBoxes(List<BlockBox> boxes) {
    return boxes.isEmpty() ? EMPTY : new VoxelShape(boxes, true);
  }

  public boolean isEmpty() {
    return boxes.isEmpty();
  }

  /** True when this shape was produced by {@link #toWorld} and is therefore not in the unit cube. */
  public boolean isWorldSpace() {
    return worldSpace;
  }

  public List<BlockBox> boxes() {
    return boxes;
  }

  /** Inclusive bounds of the union, or {@code null} for the empty shape. */
  public BlockBox bounds() {
    if (boxes.isEmpty()) return null;
    BlockBox result = boxes.getFirst();
    for (int index = 1; index < boxes.size(); index++) result = result.enclose(boxes.get(index));
    return result;
  }

  /**
   * Translates every box by the given offsets, preserving this shape's space.
   *
   * <p>Translating does not by itself make a shape world-space: a shape builder
   * legitimately moves a block-local box around inside the unit cube while
   * composing a shape (a door rotating about its hinge, for example). Only
   * {@link #toWorld} promotes a shape to world space.</p>
   */
  public VoxelShape move(double dx, double dy, double dz) {
    if (boxes.isEmpty()) return this;
    List<BlockBox> moved = new ArrayList<>(boxes.size());
    for (BlockBox box : boxes) moved.add(box.move(dx, dy, dz));
    return new VoxelShape(moved, worldSpace);
  }

  /**
   * Translates a block-local shape to world space for a block at the given
   * integer coordinates, exactly as vanilla {@code VoxelShape#move} does.
   * Integer block coordinates are exactly representable, so this introduces no
   * rounding error.
   */
  public VoxelShape toWorld(int blockX, int blockY, int blockZ) {
    if (boxes.isEmpty()) return this;
    // Explicitly world space even when the block is the origin, so the result is
    // never mistaken for a block-local shape by the space guards below.
    List<BlockBox> moved = new ArrayList<>(boxes.size());
    for (BlockBox box : boxes) moved.add(box.move(blockX, blockY, blockZ));
    return new VoxelShape(moved, true);
  }

  /** Mirrors vanilla {@code VoxelShape#flip} by reflecting the shape inside the unit cube. */
  public VoxelShape flip(Axis axis) {
    if (boxes.isEmpty()) return this;
    int index = axis.ordinal();
    List<BlockBox> flipped = new ArrayList<>(boxes.size());
    for (BlockBox box : boxes) flipped.add(box.flip(index));
    return new VoxelShape(flipped, worldSpace);
  }

  /**
   * Vanilla {@code Shapes.join(a, b, BooleanOp.OR)}: the union of a block-local
   * shape with one more block-local box, preserving declaration order so the
   * result is stable across runs.
   */
  public VoxelShape localUnion(BlockBox box) {
    if (worldSpace) {
      throw new IllegalStateException("union of a world-space shape with a block-local box is undefined");
    }
    Objects.requireNonNull(box, "box");
    if (box.isEmpty()) return this;
    List<BlockBox> joined = new ArrayList<>(boxes.size() + 1);
    joined.addAll(boxes);
    joined.add(box);
    return new VoxelShape(joined, false);
  }

  /** Vanilla {@code Shapes.join(a, b, BooleanOp.OR)} for two shapes in the same space. */
  public VoxelShape union(VoxelShape other) {
    if (worldSpace != other.worldSpace) {
      throw new IllegalStateException("cannot union shapes from different spaces");
    }
    if (other.boxes.isEmpty()) return this;
    if (boxes.isEmpty()) return other;
    List<BlockBox> joined = new ArrayList<>(boxes.size() + other.boxes.size());
    joined.addAll(boxes);
    joined.addAll(other.boxes);
    return new VoxelShape(joined, worldSpace);
  }

  /**
   * Vanilla door rotation: rotates a closed-door shape a quarter turn about its
   * hinge edge so that it leaves the doorway.
   *
   * <p>The hinge is the corner shared by the door's flush face and its hinged
   * side. Rotating about that corner (translate to origin, rotate, translate
   * back) keeps every box exactly axis-aligned, unlike a rotation about the
   * block centre, which would place the door in the middle of the opening.</p>
   */
  public VoxelShape rotateAboutHinge(Direction facing, boolean hingeRight) {
    double hingeX;
    double hingeZ;
    switch (facing) {
      case NORTH -> { hingeZ = 1.0; hingeX = hingeRight ? 0.0 : 1.0; }
      case SOUTH -> { hingeZ = 0.0; hingeX = hingeRight ? 0.0 : 1.0; }
      case WEST -> { hingeX = 1.0; hingeZ = hingeRight ? 0.0 : 1.0; }
      case EAST -> { hingeX = 0.0; hingeZ = hingeRight ? 0.0 : 1.0; }
      default -> throw new IllegalArgumentException("door facing must be horizontal but was " + facing);
    }
    // Translate the hinge to the origin, turn a quarter, translate back. Every
    // step stays block-local, so the result is a valid local door shape.
    return new VoxelShape(boxes, false)
        .move(-hingeX, 0, -hingeZ)
        .rotateQuarterTurnCcW()
        .move(hingeX, 0, hingeZ);
  }

  /**
   * One counter-clockwise quarter turn around the Y axis, matching vanilla's Y
   * rotation: {@code (x,z) -> (1-z, x)}. Every result is exactly one of the four
   * axis-aligned orientations, so no fractional rotation is ever produced.
   */
  public VoxelShape rotateQuarterTurnCcW() {
    if (worldSpace) {
      throw new IllegalStateException("block-local rotation is defined for block-local shapes only");
    }
    if (boxes.isEmpty()) return this;
    List<BlockBox> rotated = new ArrayList<>(boxes.size());
    for (BlockBox box : boxes) {
      rotated.add(BlockBox.of(1 - box.maxZ(), box.minY(), box.minX(), 1 - box.minZ(), box.maxY(), box.maxX()));
    }
    // A block-local rotation always yields a block-local shape, so repeated
    // rotations compose instead of failing the world-space guard.
    return new VoxelShape(rotated, false);
  }

  /**
   * Vanilla door/trapdoor/ladder rotation: rotates this block-local shape around
   * Y so that the face that pointed at {@code from} now points at {@code to}.
   * The rotation is applied as whole quarter turns, so the result is always an
   * exact axis-aligned orientation and never a fractional mirror.
   */
  public VoxelShape rotateToFace(Direction from, Direction to) {
    if (from.axis() == Axis.Y || to.axis() == Axis.Y) {
      throw new IllegalArgumentException("block-local rotation is defined for horizontal faces but got " + from + " -> " + to);
    }
    VoxelShape result = this;
    Direction current = from;
    for (int guard = 0; current != to; guard++) {
      if (guard > 4) throw new IllegalStateException("unreachable rotation state for " + from + " -> " + to);
      result = result.rotateQuarterTurnCcW();
      current = current.rotateY();
    }
    return result;
  }

  /** Sums the volumes of the boxes. Only meaningful for block-local shapes. */
  public double volume() {
    double total = 0;
    for (BlockBox box : boxes) total += box.sizeX() * box.sizeY() * box.sizeZ();
    return total;
  }

  /** True when the shape covers the entire unit cube, i.e. {@code Shapes.block()}. */
  public boolean isFullCube() {
    if (boxes.size() != 1) return false;
    BlockBox box = boxes.getFirst();
    return box.minX() == 0 && box.minY() == 0 && box.minZ() == 0
        && box.maxX() == 1 && box.maxY() == 1 && box.maxZ() == 1;
  }

  /** Vanilla {@code VoxelShape#isEmpty}; the negation of {@link #isFullCube()} for pure unions. */
  public boolean isFullFace(Direction direction) {
    return isFaceFull(direction);
  }

  /**
   * Vanilla {@code VoxelShape#isFaceFull}: the direction's face is fully covered
   * when some box spans the whole perpendicular extent and reaches the face
   * plane. Block-local coordinates only.
   */
  public boolean isFaceFull(Direction direction) {
    if (worldSpace) throw new IllegalStateException("face tests are defined for block-local shapes only");
    double limit = direction.stepX() + direction.stepY() + direction.stepZ() < 0 ? 0.0 : 1.0;
    int axisIndex = direction.axis().ordinal();
    return switch (axisIndex) {
      case 0 -> containsCovering(0, limit, direction.stepX() > 0);
      case 1 -> containsCovering(1, limit, direction.stepY() > 0);
      default -> containsCovering(2, limit, direction.stepZ() > 0);
    };
  }

  /**
   * Whether some box reaches the requested face plane and covers the whole unit
   * square of that face's two perpendicular axes. The face's own axis contributes
   * only the plane it reaches, which is what makes a bottom slab full on its
   * bottom face while a 6..10 fence post is full on no face at all.
   */
  private boolean containsCovering(int axisIndex, double limit, boolean positive) {
    for (BlockBox box : boxes) {
      double reached = switch (axisIndex) {
        case 0 -> positive ? box.maxX() : box.minX();
        case 1 -> positive ? box.maxY() : box.minY();
        default -> positive ? box.maxZ() : box.minZ();
      };
      if (reached != limit) continue;
      // The two perpendicular extents, always reported as (first, second)
      // regardless of which axis the face belongs to.
      double firstLow;
      double firstHigh;
      double secondLow;
      double secondHigh;
      switch (axisIndex) {
        case 0 -> { firstLow = box.minY(); firstHigh = box.maxY(); secondLow = box.minZ(); secondHigh = box.maxZ(); }
        case 1 -> { firstLow = box.minX(); firstHigh = box.maxX(); secondLow = box.minZ(); secondHigh = box.maxZ(); }
        default -> { firstLow = box.minX(); firstHigh = box.maxX(); secondLow = box.minY(); secondHigh = box.maxY(); }
      }
      if (firstLow == 0 && firstHigh == 1 && secondLow == 0 && secondHigh == 1) return true;
    }
    return false;
  }

  /**
   * Vanilla {@code Shapes#joinIsNotEmpty}: reports whether any box of
   * {@code moving} (already in world space) intersects any box of this shape
   * (also in world space).
   */
  public boolean intersects(VoxelShape other) {
    for (BlockBox left : boxes) {
      for (BlockBox right : other.boxes) {
        if (left.intersects(right)) return true;
      }
    }
    return false;
  }

  public boolean intersects(BlockBox query) {
    for (BlockBox box : boxes) {
      if (box.intersects(query)) return true;
    }
    return false;
  }

  /**
   * Vanilla {@code VoxelShape#collide} for {@code Shapes#block}: returns the
   * corrected displacement along one axis such that the swept box does not
   * overlap this shape, or the original displacement when no collision occurs.
   *
   * <p>{@code box} and this shape must both be in world space. The overlap test
   * is deliberately strict on the non-movement axes, so a box that merely
   * touches a collision face is not pushed out. That is what makes standing on
   * a block (contact, zero overlap) distinct from intersecting it.</p>
   */
  public double clip(Axis axis, BlockBox box, double amount) {
    if (boxes.isEmpty() || amount == 0.0) return amount;
    double result = amount;
    for (BlockBox collision : boxes) {
      if (!overlapsOnOtherAxes(axis, box, collision)) continue;
      if (amount > 0.0) {
        // Distance from this box's high face to the collision's low face. It is
        // negative when the two already overlap, which is exactly the correction
        // vanilla applies to push the moving box back out.
        double gap = minOnAxis(axis, collision) - maxOnAxis(axis, box);
        if (gap < result) result = gap;
      } else {
        double gap = maxOnAxis(axis, collision) - minOnAxis(axis, box);
        if (gap > result) result = gap;
      }
    }
    return result;
  }

  private static boolean overlapsOnOtherAxes(Axis axis, BlockBox box, BlockBox collision) {
    return switch (axis) {
      case X -> box.maxY() > collision.minY() && box.minY() < collision.maxY()
          && box.maxZ() > collision.minZ() && box.minZ() < collision.maxZ();
      case Y -> box.maxX() > collision.minX() && box.minX() < collision.maxX()
          && box.maxZ() > collision.minZ() && box.minZ() < collision.maxZ();
      case Z -> box.maxX() > collision.minX() && box.minX() < collision.maxX()
          && box.maxY() > collision.minY() && box.minY() < collision.maxY();
    };
  }

  private static double minOnAxis(Axis axis, BlockBox box) {
    return switch (axis) {
      case X -> box.minX();
      case Y -> box.minY();
      case Z -> box.minZ();
    };
  }

  private static double maxOnAxis(Axis axis, BlockBox box) {
    return switch (axis) {
      case X -> box.maxX();
      case Y -> box.maxY();
      case Z -> box.maxZ();
    };
  }

  @Override public boolean equals(Object other) {
    return other instanceof VoxelShape shape && worldSpace == shape.worldSpace && boxes.equals(shape.boxes);
  }

  @Override public int hashCode() {
    return 31 * boxes.hashCode() + Boolean.hashCode(worldSpace);
  }

  @Override public String toString() {
    return (worldSpace ? "world" : "local") + boxes;
  }

  /** Deterministic box ordering used only by tests and diagnostics. */
  public VoxelShape sortedForDiagnostics() {
    List<BlockBox> sorted = new ArrayList<>(boxes);
    sorted.sort((a, b) -> {
      int c = Double.compare(a.minX(), b.minX());
      if (c != 0) return c;
      c = Double.compare(a.minY(), b.minY());
      if (c != 0) return c;
      c = Double.compare(a.minZ(), b.minZ());
      if (c != 0) return c;
      c = Double.compare(a.maxX(), b.maxX());
      if (c != 0) return c;
      c = Double.compare(a.maxY(), b.maxY());
      if (c != 0) return c;
      return Double.compare(a.maxZ(), b.maxZ());
    });
    return new VoxelShape(sorted, worldSpace);
  }
}
