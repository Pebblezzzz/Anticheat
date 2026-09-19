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

The current 1.21.11 catalogue models full cubes, slabs, stairs, fences, walls, panes, doors, trapdoors, fence gates, thin/partial blocks, fluids, climbables, and several movement-relevant special blocks. Unknown registry/state combinations become UNSUPPORTED.

This is IMPLEMENTED and INTERNALLY TESTED, but it is not claimed to be complete vanilla parity. Independent real-client validation remains required.

## Publication

The current generation is held in an AtomicReference. Readers only see immutable snapshots. Clientbound chunk/block mutations are queued behind the same transaction barriers used by the Paper adapter and become visible to the authoritative Phase 4 generation only after acknowledgement. Packet reception and chunk decoding never wait for generation publication: chunk decode runs on a bounded worker pool, while transaction acknowledgement schedules compact publication off the Netty EventLoop.

## Entity status

Entity identity, bounding box, spawn, movement, despawn, deterministic ordering, and historical storage are IMPLEMENTED as world infrastructure. Full protocol coverage and every vanilla entity collision semantic are PARTIAL.

## Replay

Phase4WorldReplica.replay(Timeline.Snapshot) rebuilds the world from the existing canonical Phase 1–3 timeline. Equal packet histories produce equal immutable snapshots. Complete replay of dimension/world-state/entity packets is PARTIAL because the current Phase 1–3 packet schema does not capture every clientbound protocol event needed for full vanilla reconstruction.

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

- IMPLEMENTED: per-player journal, deterministic ordering, generations, explicit UNKNOWN/UNLOADED/UNSUPPORTED coverage, authoritative collision-query path, entity collision infrastructure.
- INTERNALLY TESTED: Phase 4 replication/history/uncertainty/dimension/fake-block/entity tests added in this phase.
- VANILLA VALIDATED: not claimed for the complete Phase 4 implementation.
- PARTIAL: complete protocol packet coverage, full entity protocol coverage, and exact real-client timing/reordering behavior.
- BLOCKED BY EXTERNAL DATA: independent 1.21.11 client observations for complete packet/state/collision parity.

## Completion gate

Phase 4 is not COMPLETE under the requested specification until remaining protocol coverage, replay coverage, concurrency stress measurements, performance measurements, and independent vanilla validation are supplied. The implementation intentionally reports these gaps instead of guessing world state.
