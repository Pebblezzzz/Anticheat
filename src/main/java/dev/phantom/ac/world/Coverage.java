package dev.phantom.ac.world;

import java.io.Serializable;

/**
 * Whether a position's block state is actually known to the client.
 *
 * <p>
 * This is the single most important correctness rule of the world layer: an
 * unloaded chunk must never be reported as air. {@code UNLOADED} is a claim
 * about missing information, not a claim about the world.</p>
 */
public enum Coverage implements Serializable {
    /**
     * The containing chunk was delivered to the client and the position has a
     * state.
     */
    KNOWN,
    /** The chunk is expected/declared loaded but its complete payload is not yet published. */
    UNKNOWN,
    /**
     * The containing chunk is not (or no longer) loaded on the client. No state
     * exists.
     */
    UNLOADED,
    /**
     * The chunk is loaded, but the state that was delivered is outside the
     * shapes this build has verified. The position is neither air nor a cube.
     */
    UNSUPPORTED
}
