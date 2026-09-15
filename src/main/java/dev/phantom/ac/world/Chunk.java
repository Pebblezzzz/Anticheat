package dev.phantom.ac.world;

import java.io.Serializable;

/**
 * An immutable chunk column coordinate, which is the unit a Minecraft client
 * loads and unloads.
 *
 * <p>
 * A section is a 16x16x16 slice of a chunk. Section membership is exposed so
 * callers and tests can reason about section boundaries without a separate
 * section type, because coverage is tracked per chunk column in 1.21.11.</p>
 */
public record Chunk(int x, int z) implements Serializable {

    public static final int SIZE = 16;
    public static final int SECTION_HEIGHT = 16;
    public static final int SECTION_MASK = 15;

    /**
     * Vanilla {@code SectionPos.blockToSectionCoord}: floor division, correct
     * for negative coordinates.
     */
    public static Chunk containing(int blockX, int blockZ) {
        return new Chunk(Math.floorDiv(blockX, SIZE), Math.floorDiv(blockZ, SIZE));
    }

    public Pos origin() {
        return new Pos(x * SIZE, 0, z * SIZE);
    }

    /**
     * The section index containing a given block Y.
     */
    public static int sectionIndex(int blockY) {
        return Math.floorDiv(blockY, SECTION_HEIGHT);
    }

    /**
     * The lowest block Y of a section index.
     */
    public static int sectionMinY(int sectionIndex) {
        return sectionIndex * SECTION_HEIGHT;
    }

    public boolean contains(int blockX, int blockZ) {
        return Math.floorDiv(blockX, SIZE) == x && Math.floorDiv(blockZ, SIZE) == z;
    }
}
