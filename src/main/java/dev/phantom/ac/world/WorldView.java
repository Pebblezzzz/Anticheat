package dev.phantom.ac.world;

import java.util.List;

import dev.phantom.ac.geometry.BlockBox;
import dev.phantom.ac.world.WorldQueries.CollisionResult;
import dev.phantom.ac.world.WorldQueries.EnvironmentSample;

/**
 * The single access point Phase 5 uses to obtain world facts.
 *
 * <p>
 * This exists so the future movement simulator never has to reach for a second
 * collision system. A caller gets one object per tick and asks it everything:
 * block states, collision geometry, fluids, environment, entities, and —
 * critically — whether the answers are complete.</p>
 *
 * <p>
 * All methods are pure functions of the underlying {@link WorldSnapshot} and
 * their arguments. Repeated calls always return equal results.</p>
 */
public interface WorldView {

    /**
     * The immutable snapshot this view answers from.
     */
    WorldSnapshot snapshot();

    /**
     * Entity collision boxes overlapping a query, with an explicit completeness
     * flag.
     */
    EntityCollisions entityCollisions();

    /**
     * The state at a position, or {@code null} when the client's data does not
     * cover it.
     */
    default BlockState blockAtOrNull(int x, int y, int z) {
        return snapshot().blockAtOrNull(x, y, z);
    }

    default Coverage coverageAt(int x, int y, int z) {
        return snapshot().coverageAt(x, y, z);
    }

    /**
     * The block state at a known position. Throws when the position is unloaded
     * or unsupported, so a caller cannot accidentally read air out of missing
     * data.
     */
    default BlockState requireBlockAt(int x, int y, int z) {
        return snapshot().requireBlockAt(x, y, z);
    }

    default boolean fullyKnown(BlockBox query) {
        return snapshot().fullyKnown(query);
    }

    default CollisionResult collisions(BlockBox query) {
        return WorldQueries.collisions(snapshot(), query);
    }

    default EnvironmentSample environment(BlockBox query) {
        return WorldQueries.environment(snapshot(), query);
    }

    default FluidState fluidAt(int x, int y, int z) {
        return WorldQueries.fluidAt(snapshot(), x, y, z);
    }

    default WorldQueries.PathCollision pathCollision(BlockBox box, double dx, double dy, double dz) {
        return WorldQueries.pathCollision(snapshot(), box, dx, dy, dz);
    }

    default List<WorldQueries.Floor> floors(BlockBox box, double maximumDepth) {
        return WorldQueries.floors(snapshot(), box, maximumDepth);
    }

    /**
     * The production view: a snapshot plus a (possibly incomplete) entity
     * provider.
     */
    static WorldView of(WorldSnapshot snapshot, EntityCollisions entities) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        java.util.Objects.requireNonNull(entities, "entities");
        return new WorldView() {
            @Override
            public WorldSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public EntityCollisions entityCollisions() {
                return entities;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof WorldView view
                        && snapshot.equals(view.snapshot())
                        && entities.equals(view.entityCollisions());
            }

            @Override
            public int hashCode() {
                return 31 * snapshot.hashCode() + entities.hashCode();
            }

            @Override
            public String toString() {
                return "WorldView[" + snapshot + "]";
            }
        };
    }

    /**
     * The view used when no entities are tracked yet: world facts only,
     * explicitly incomplete entities.
     */
    static WorldView of(WorldSnapshot snapshot) {
        return of(snapshot, EntityCollisions.NONE_TRACKED);
    }
}
