# Phase 4 — Per-player client-visible world

## Definition

Phase 4 is the authoritative world/collision layer between packet/timeline capture and Phase 5. It reconstructs the world a particular client could have known, rather than reading the current server world.

Model: client-bound packet/timeline history -> per-player replica -> ordered journal -> compact packet-style world state -> immutable generations -> WorldSnapshot/WorldQueries -> Phase 5.

There is no global reconstructed world shared by players.

## Implemented

- The former `LiveClientWorldReplica` Paper cache is removed. It is not a second world model anymore.

- Phase4WorldRegistry owns one Phase4WorldReplica per tracked player.
- Each replica owns its own ordered event journal and immutable generation history.
- Two players can receive different block histories and therefore have different snapshots.
- The replica adapts existing Phase 1–3 timeline packet types for chunk data, chunk unloads, block-state deltas, unsupported states, and legacy chunk/block packets.
- Live CHUNK_DATA is kept palette/bit-packed at section level; individual client block changes use small immutable overlays instead of expanding a chunk into one Java object per block.
- Direct Phase 4 events represent dimension changes, world metadata, multi-block changes, and entity spawn/move/despawn.
- Every event retains source, packet type, sequence, server tick, client tick when available, and provenance.

## Ordering and history

Events are canonically ordered by server tick, capture receive time, sequence, then deterministic local ordinal. Duplicate capture sequences are ignored. The live path applies append-ordered events incrementally; a full journal replay is retained only as the deterministic fallback when an out-of-order event is accepted.

Every visible mutation publishes a new immutable Generation. Generations share unchanged compact chunk data through copy-on-write, so history does not duplicate every block. Historical generations can be selected by capture sequence or canonical event order. This is a primitive for later Phase 7 temporal selection; Phase 7 synchronization policy is not implemented here.

## Coverage semantics

Coverage is explicit:

- KNOWN: client-visible block state established.
- UNKNOWN: chunk load is known/expected but a complete decoded payload has not been published.
- UNLOADED: no client-visible chunk exists.
- UNSUPPORTED: payload is known but the version adapter cannot verify its state/shape.

None is silently converted to air. Collision queries carry coverage so uncertainty propagates to callers.

## Collision and environment

WorldSnapshot and WorldQueries are the single collision representation. They expose block states, collision boxes, fluids, environment facts, and coverage. EntityCollisions provides deterministic entity bounding-box infrastructure with an explicit completeness flag.

The deterministic 1.21.11 replay catalogue now contains every published block id and every published block-state combination from the pinned minecraft-data source: 1,166 block ids, 29,671 published block-state combinations, and 5,128 deduplicated collision shapes (with constant-shape blocks represented once). Collision boxes are preserved at the source's 1/32-block precision, including shapes that extend beyond the nominal block cube.

The live Paper adapter additionally warms native Paper BlockData collision shapes on the main thread and serves those immutable results to validation threads without Bukkit access. The deterministic catalogue is the fallback for replay/offline validation. Unknown registry/state combinations remain UNSUPPORTED rather than becoming air or a guessed cube.

This is IMPLEMENTED and INTERNALLY TESTED for the checked-in data and live/replay wiring. It is not marked VANILLA VALIDATED until an independent 1.21.11 client corpus verifies packet visibility, state ordering, collision geometry, and update timing against actual clients.

## Publication

The current generation is held in an AtomicReference. Readers only see immutable snapshots. Clientbound chunk/block mutations are queued behind the same transaction barriers used by the Paper adapter and become visible to the authoritative Phase 4 generation only after acknowledgement. Packet reception and chunk decoding never wait for generation publication: chunk decode runs on a bounded worker pool, while transaction acknowledgement schedules compact publication off the Netty EventLoop.

## Entity status

Client-visible entity spawn, relative movement, teleport, metadata-driven bounding-box refresh, despawn, and passenger/attachment invalidation are now captured into Phase 4 history. Entity completeness is explicit: any unmodelled lifecycle/relationship transition marks the entity view incomplete rather than silently dropping it.

The live adapter aligns server-native bounding boxes to the packet-visible entity position, while replay stores the resulting immutable boxes. Full vanilla entity semantics beyond bounding-box collision, especially riding/passenger transforms and every metadata-dependent size transition, still require independent validation.

## Replay

Phase4WorldReplica.replay(Timeline.Snapshot) rebuilds the world from the canonical packet history. Timeline format v10 persists lossless block-state properties and client-visible entity lifecycle state. Equal packet histories produce equal immutable snapshots, and merged snapshots retain the exact collision resolver when one is installed.

Replay remains PARTIAL for protocol events that are intentionally outside the Phase 4 collision contract or are conservatively invalidated rather than reconstructed, such as passenger transform semantics.

## Phase boundary

Phase 4 owns replication, history, block states, collision geometry, environment facts, entity collision state, and uncertainty.

Phase 4 does not own movement physics, reachability, anti-cheat policy, thresholds, flags, or punishment.

Phase 5 should consume WorldSnapshot/WorldView/WorldQueries rather than implementing another block geometry table.

## Grim comparison

| Grim concept | Phantom equivalent | Need | Location |
|---|---|---|---|
| Per-player CompensatedWorld | Phase4WorldReplica + Phase4WorldRegistry | Yes | Phase 4 |
| Packet-driven chunk/block replication | Timeline adapter + Phase 4 events | Yes | Phase 1–4 boundary |
| Transaction/latency-gated visibility | Existing CompensatedClientWorld barrier model plus Phase 4 provenance/generation seam | Yes | Adapter/Phase 4 |
| Live packet world cache | Phase4WorldReplica compact packet-style sections + transaction-gated immutable generations | Yes | Phase 4 + Paper adapter |
| Historical/latency world state | Immutable Generation history | Yes | Phase 4; temporal selection belongs to Phase 7 |
| Version-specific collision shapes | BlockCatalogue12111 + WorldSnapshot | Yes | Phase 4 |
| Compensated entity state | EntityCollisions generation state | Yes | Phase 4 |
| Async-safe publication | Immutable generations + AtomicReference | Yes | Phase 4 |

This is a clean-room architectural comparison. No Grim source code is copied. Grim publicly describes per-player world replication, packet-driven chunk/block updates, client-specific fake blocks, and queued world changes for latency compensation; those concepts informed the boundary design. See the cited public Grim sources in the repository review.

## Evidence labels

- IMPLEMENTED: per-player journal, deterministic ordering, immutable generations, explicit coverage, lossless block properties, native Paper collision integration, deterministic generated 1.21.11 collision data, replay persistence, and client-visible entity lifecycle capture.
- INTERNALLY TESTED: state-property round trips, exact collision resolver wiring/merge preservation, generated stair/slab shape reconstruction, entity completeness, unknown/unloaded resolver suppression, and deterministic replay cases.
- VANILLA VALIDATED: not claimed for the complete Phase 4 implementation.
- PARTIAL: full entity movement semantics, passenger/riding transforms, and independent verification of client packet visibility/timing.
- BLOCKED BY EXTERNAL DATA: an independent 1.21.11 real-client corpus sufficient to compare the reconstructed world byte-for-byte/shape-for-shape and validate capture ordering/timing.

## Completion gate

The code path is now complete for the declared Phase 4 collision/world-state contract: live client-visible block replication, deterministic 1.21.11 block-state collision reconstruction, immutable generation/history, and conservative entity lifecycle tracking.

The acceptance label remains below COMPLETE until the remaining empirical gates are supplied: concurrency stress measurements, scale/performance measurements, and an independent real 1.21.11 client corpus validating packet visibility, state ordering, collision geometry, and entity semantics.
