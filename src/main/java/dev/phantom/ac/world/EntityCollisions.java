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

    /**
     * A single entity's bounding box, with a stable identity for
     * reproducibility.
     */
    record EntityBox(int entityId, BlockBox box) implements Serializable {

        public EntityBox {
            Objects.requireNonNull(box, "box");
        }
    }

    /**
     * The result of an entity query. {@code complete} is false when the
     * provider cannot enumerate every entity in the region, which is the normal
     * case until full entity tracking exists. Phase 5 must treat an incomplete
     * list as uncertainty rather than as an empty world.
     */
    record EntityCollisionResult(List<EntityBox> boxes, boolean complete) implements Serializable {

        public EntityCollisionResult {
            boxes = List.copyOf(boxes);
        }

        public boolean isEmpty() {
            return boxes.isEmpty();
        }

        public boolean isDefinite() {
            return complete;
        }
    }

    /**
     * Boxes overlapping the query, in ascending {@code entityId} order.
     */
    EntityCollisionResult boxesIn(BlockBox query);

    /** True only when the provider can account for every client-visible entity relevant to the query. */
    default boolean complete() { return true; }

    /**
     * The provider used by default: it reports no entities and states plainly
     * that its answer is not complete. It never pretends an empty list means
     * "no entities exist".
     */
    EntityCollisions NONE_TRACKED = new EntityCollisions() {
        @Override public EntityCollisionResult boxesIn(BlockBox query) { return new EntityCollisionResult(List.of(), false); }
        @Override public boolean complete() { return false; }
    };

    /**
     * A fully-known provider backed by a fixed list, used by replays that
     * recorded entity boxes and by tests. The list is sorted so the result is
     * deterministic.
     */
    static EntityCollisions of(List<EntityBox> boxes) {
        List<EntityBox> sorted = boxes.stream()
                .sorted(java.util.Comparator.comparingInt(EntityBox::entityId))
                .toList();
        return new EntityCollisions() {
            @Override
            public EntityCollisionResult boxesIn(BlockBox query) {
                List<EntityBox> overlapping = sorted.stream().filter(entity -> entity.box().intersects(query)).toList();
                return new EntityCollisionResult(overlapping, true);
            }
        };
    }
}
