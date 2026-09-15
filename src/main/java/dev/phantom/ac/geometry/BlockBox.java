package dev.phantom.ac.geometry;

import java.io.Serializable;

/**
 * A single axis-aligned box in block-local or world space, always with
 * {@code min <= max} on every axis.
 *
 * <p>
 * Vanilla's {@code AABB} permits an inverted box and therefore silently behaves
 * differently depending on how it was constructed. This type rejects that class
 * of bug at construction time, which is what makes downstream intersection/clip
 * results deterministic.</p>
 */
public record BlockBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
        implements Serializable {

    public BlockBox {
        if (Double.isNaN(minX) || Double.isNaN(minY) || Double.isNaN(minZ)
                || Double.isNaN(maxX) || Double.isNaN(maxY) || Double.isNaN(maxZ)) {
            throw new IllegalArgumentException("block box coordinates must be finite numbers");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("block box must be non-inverted: [" + minX + "," + minY + "," + minZ + "]..[" + maxX + "," + maxY + "," + maxZ + "]");
        }
    }

    /**
     * An empty box, used as the identity element of {@link #enclose}.
     */
    public static final BlockBox EMPTY = new BlockBox(0, 0, 0, 0, 0, 0);
    public static final BlockBox FULL = new BlockBox(0, 0, 0, 1, 1, 1);

    public static BlockBox of(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new BlockBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static BlockBox cube(double x, double y, double z, double size) {
        return new BlockBox(x, y, z, x + size, y + size, z + size);
    }

    /**
     * Boxes are stored as inclusive low/high integer offsets in sixteenths by
     * vanilla ({@code Block.box}), so this constructor mirrors the
     * {@code x1/16} form used by the vanilla shape definitions. Values are
     * converted with an explicit division so the result is exactly the decimal
     * vanilla evaluates.
     */
    public static BlockBox ofSixteenths(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new BlockBox(x1 / 16.0, y1 / 16.0, z1 / 16.0, x2 / 16.0, y2 / 16.0, z2 / 16.0);
    }

    public boolean isEmpty() {
        return sizeX() <= 0 || sizeY() <= 0 || sizeZ() <= 0;
    }

    public double sizeX() {
        return maxX - minX;
    }

    public double sizeY() {
        return maxY - minY;
    }

    public double sizeZ() {
        return maxZ - minZ;
    }

    public BlockBox move(double dx, double dy, double dz) {
        return new BlockBox(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /**
     * Vanilla {@code AABB.intersects}: strict on every axis, so touching faces
     * do not intersect.
     */
    public boolean intersects(BlockBox other) {
        return maxX > other.minX && minX < other.maxX
                && maxY > other.minY && minY < other.maxY
                && maxZ > other.minZ && minZ < other.maxZ;
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * Vanilla {@code AABB.contains(AABB)}: inclusive on every face.
     */
    public boolean contains(BlockBox other) {
        return other.minX >= minX && other.maxX <= maxX
                && other.minY >= minY && other.maxY <= maxY
                && other.minZ >= minZ && other.maxZ <= maxZ;
    }

    /**
     * Smallest box containing both operands. Used for swept-path coverage and
     * per-shape bounds.
     */
    public BlockBox enclose(BlockBox other) {
        return new BlockBox(
                Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public BlockBox flip(int axisIndex) {
        return switch (axisIndex) {
            case 0 ->
                new BlockBox(1 - maxX, minY, minZ, 1 - minX, maxY, maxZ);
            case 1 ->
                new BlockBox(minX, 1 - maxY, minZ, maxX, 1 - minY, maxZ);
            case 2 ->
                new BlockBox(minX, minY, 1 - maxZ, maxX, maxY, 1 - minZ);
            default ->
                throw new IllegalArgumentException("axis index must be 0, 1 or 2 but was " + axisIndex);
        };
    }
}
