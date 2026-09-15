package dev.phantom.ac.world;

import java.io.Serializable;

/**
 * An immutable block position.
 */
public record Pos(int x, int y, int z) implements Serializable {

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public Chunk chunk() {
        return Chunk.containing(x, z);
    }

    /**
     * The chunk-local x coordinate, always {@code 0..15}. Uses
     * {@link Math#floorMod} so negative world coordinates map onto the correct
     * in-chunk column, exactly as vanilla's {@code SectionPos.sectionRelative}
     * does for the client's chunk storage.
     */
    public int localX() {
        return Math.floorMod(x, Chunk.SIZE);
    }

    public int localZ() {
        return Math.floorMod(z, Chunk.SIZE);
    }
}
