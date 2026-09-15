package dev.phantom.ac.world;

import java.io.Serializable;

/**
 * The fluid information a movement simulator needs for Minecraft Java 1.21.11,
 * exactly as far as it can be established from server-observable data.
 *
 * <p>
 * Vanilla fluid height is <em>not</em> part of a clientbound block state. A
 * fluid block packet carries only the fluid tag and its {@code level} property;
 * the rendered and simulated surface height additionally depends on the fluid's
 * neighbours, which the client infers from the chunks it received.
 * {@link #height} is therefore only reported where the snapshot can derive it
 * from those neighbour states, and is otherwise {@link #HEIGHT_UNKNOWN}.</p>
 */
public record FluidState(Type type, int level, HeightSource heightSource, double height, boolean falling) implements Serializable {

    /**
     * {@code HEIGHT_UNKNOWN} means the surface height could not be established
     * from available data.
     */
    public static final double HEIGHT_UNKNOWN = Double.NaN;

    /**
     * Vanilla derives a fluid's surface from the state's {@code level} and from
     * how many of the four horizontal neighbours share the fluid. Where the
     * snapshot has all four neighbours it can reproduce that rule; where any
     * neighbour is not visible the height stays unknown.
     */
    public enum HeightSource {
        /**
         * Level and all four horizontal neighbours are visible; height is
         * derived exactly.
         */
        DERIVED,
        /**
         * The block is a fluid source ({@code level=0}) fully surrounded by
         * equal fluid.
         */
        SOURCE,
        /**
         * Height cannot be established from the available snapshot.
         */
        UNAVAILABLE
    }

    /**
     * The fluid kinds that exist in the target version's overworld/nether/end
     * block set.
     */
    public enum Type {
        NONE,
        WATER,
        LAVA;

        public boolean isFluid() {
            return this != NONE;
        }

        public boolean isLava() {
            return this == LAVA;
        }
    }

    public static final FluidState NONE = new FluidState(Type.NONE, 0, HeightSource.UNAVAILABLE, HEIGHT_UNKNOWN, false);

    public FluidState {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(heightSource, "heightSource");
        if (level < 0 || level > 7) {
            throw new IllegalArgumentException("fluid level must be 0..7 but was " + level);
        }
        if (heightSource == HeightSource.UNAVAILABLE && !Double.isNaN(height)) {
            throw new IllegalArgumentException("an unavailable fluid height must be NaN, not " + height);
        }
        if (heightSource != HeightSource.UNAVAILABLE && (Double.isNaN(height) || height < 0 || height > 1)) {
            throw new IllegalArgumentException("a derived fluid height must be within 0..1 but was " + height);
        }
    }

    public boolean isFluid() {
        return type.isFluid();
    }

    public boolean hasKnownHeight() {
        return !Double.isNaN(height);
    }
}
