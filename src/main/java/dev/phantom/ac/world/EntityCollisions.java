package dev.phantom.ac.world;

import dev.phantom.ac.geometry.BlockBox;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic entity collision boxes for the movement simulator.
 *
 * <p>
 * Vanilla moves the player against every {@code Entity} in the level, so a
 * correct movement model cannot ignore entity collision. Modelling every entity
 * type is not Phase 4's job: what Phase 4 owes Phase 5 is a deterministic,
 * version-neutral way to obtain the boxes that overlap a query, together with
 * explicit knowledge of whether that list is complete.</p>
 *
 * <p>
 * This interface is the seam. Phase 5 asks a provider for boxes; a later phase
 * supplies a real provider driven by tracked entities, and nothing in the world
 * or geometry layers has to change.</p>
 */
public interface EntityCollisions {

    record EntityBox(int entityId, BlockBox box) implements Serializable {
        public EntityBox {
            Objects.requireNonNull(box, "box");
        }
    }

    record EntityCollisionResult(List<EntityBox> boxes, boolean complete) implements Serializable {
        public EntityCollisionResult {
            boxes = List.copyOf(boxes);
        }
        public boolean isEmpty() { return boxes.isEmpty(); }
        public boolean isDefinite() { return complete; }
    }

    EntityCollisionResult boxesIn(BlockBox query);

    /** True only when the provider can account for every client-visible entity relevant to the query. */
    default boolean complete() { return true; }

    /**
     * The provider used by default: it reports no entities and states plainly
     * that its answer is not complete.
     */
    EntityCollisions NONE_TRACKED = new EntityCollisions() {
        @Override public EntityCollisionResult boxesIn(BlockBox query) {
            return new EntityCollisionResult(List.of(), false);
        }
        @Override public boolean complete() { return false; }
    };

    /** Fully-known fixed-list provider used by replays/tests. */
    static EntityCollisions of(List<EntityBox> boxes) {
        return of(boxes, true);
    }

    /**
     * Fixed-list provider with explicit completeness. Live adapters must only
     * pass true when they have actually enumerated every relevant entity.
     */
    static EntityCollisions of(List<EntityBox> boxes, boolean complete) {
        List<EntityBox> sorted = boxes.stream()
                .sorted(java.util.Comparator.comparingInt(EntityBox::entityId))
                .toList();
        return new EntityCollisions() {
            @Override
            public EntityCollisionResult boxesIn(BlockBox query) {
                List<EntityBox> overlapping = sorted.stream()
                        .filter(entity -> entity.box().intersects(query))
                        .toList();
                return new EntityCollisionResult(overlapping, complete);
            }
            @Override public boolean complete() { return complete; }
        };
    }
}
