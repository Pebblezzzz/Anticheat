package dev.phantom.ac.geometry;

import java.io.Serializable;
import java.util.List;

/**
 * Block-local directions and axes used by block-state derived collision shapes.
 *
 * <p>These are world-axis-relative, exactly like the vanilla {@code Direction} and
 * {@code Axis} enums. Rotation of a shape is expressed by {@link Flip}, not by a
 * separate coordinate system, so there is exactly one way to construct world-space
 * geometry.</p>
 */
public final class Directions {
  private Directions() {}

  /** The six block-local directions, in vanilla's canonical declaration order. */
  public enum Direction implements Serializable {
    DOWN(0, -1, 0, Axis.Y),
    UP(0, 1, 0, Axis.Y),
    NORTH(0, 0, -1, Axis.Z),
    SOUTH(0, 0, 1, Axis.Z),
    WEST(-1, 0, 0, Axis.X),
    EAST(1, 0, 0, Axis.X);

    private final int stepX;
    private final int stepY;
    private final int stepZ;
    private final Axis axis;

    Direction(int stepX, int stepY, int stepZ, Axis axis) {
      this.stepX = stepX;
      this.stepY = stepY;
      this.stepZ = stepZ;
      this.axis = axis;
    }

    public static final List<Direction> VALUES = List.of(values());

    public int stepX() { return stepX; }
    public int stepY() { return stepY; }
    public int stepZ() { return stepZ; }
    public Axis axis() { return axis; }

    public Direction opposite() {
      return switch (this) {
        case DOWN -> UP;
        case UP -> DOWN;
        case NORTH -> SOUTH;
        case SOUTH -> NORTH;
        case WEST -> EAST;
        case EAST -> WEST;
      };
    }

    /** Counter-clockwise rotation around the Y axis, matching vanilla's Y rotation. */
    public Direction rotateY() {
      return switch (this) {
        case NORTH -> WEST;
        case WEST -> SOUTH;
        case SOUTH -> EAST;
        case EAST -> NORTH;
        case UP -> UP;
        case DOWN -> DOWN;
      };
    }
  }

  /** The three block-local axes. */
  public enum Axis implements Serializable {
    X, Y, Z
  }
}
