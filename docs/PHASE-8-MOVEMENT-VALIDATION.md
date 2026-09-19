# Phase 8 — Movement Validation

Phase 8 consumes normalized packet observations, client state, the per-player latency-compensated packet world, deterministic physics, and reachability. The hardened live path is persistent rather than replay-based: each packet advances an in-memory prediction frontier and the frontier is retained between validation calls.

## Responsibilities

```text
packet stream -> client state / prediction state
             -> latency-compensated packet world
             -> predict forward with Phase 5/6
             -> compare observed packet
             -> retain prediction state
             -> Phase 8 evidence
```

`Phase8MovementValidation` is a pure comparison/evidence layer. It does not contain movement physics or threshold-based movement checks. `Phase8PredictionRunner` is the hardened live prediction engine. `CausalMovementPipeline` remains for deterministic offline/replay analysis.

## Verdict semantics

- **POSSIBLE** — at least one complete candidate matches every field actually declared by the movement observation.
- **IMPOSSIBLE** — every relevant branch was deterministically and exhaustively evaluated and no candidate matches.
- **UNCERTAIN** — required information was missing, incomplete, ambiguous, non-exhaustive, or chronologically unreliable, so impossibility cannot be proved.

Missing information is never treated as an impossible transition. In particular, unloaded/unsupported world regions, incomplete visibility history, unresolved packet chronology, skipped timing offsets, candidate-budget exhaustion, and unconfirmed correction state are uncertainty sources.

Timing deserves one special distinction: a bounded Phase 7 timing range can be exhaustively searched. If every offset is evaluated and all offsets are impossible, Phase 8 may still return `IMPOSSIBLE`; if at least one offset is possible, the result is `POSSIBLE`; if any relevant offset is unevaluable, the aggregate is `UNCERTAIN`. An internal conservative timing aggregate must not erase the per-offset proof.

## Candidate handling

Candidate sets are deterministic and provenance-carrying. Possible candidates are retained as the prediction frontier for the next packet. Uncertain observations retain the last complete frontier and do not overwrite it with the observed client position. Impossible observations likewise leave the expected prediction frontier intact so the next packet is still compared against a trusted modeled trajectory. Each candidate records compact provenance and the simulation diagnostic that produced it.

## World completeness

A `WorldSnapshot` represents known air when a loaded chunk has no stored state at a position. An unloaded or unsupported region is not substituted with air. Phase 6 may only use a world branch as exhaustive when the collision volume relevant to the simulation is fully known. If a transition depends on unknown blocks/chunks, the branch is unevaluable and Phase 8 is `UNCERTAIN`, not `IMPOSSIBLE`.

## Packet chronology

Raw capture sequence numbers are global across both directions. Server-to-client chunk, velocity, and correction packets can legitimately interleave with client-to-server movement packets, so a global sequence gap is not itself a missing movement packet. Duplicate, out-of-order, and pre-epoch movement records are chronology uncertainty. Phase 8 does not invent missing movement packets.

## Teleports and corrections

A server position/correction packet creates a validation barrier. Candidates are discarded at the barrier and movement before the matching teleport confirmation is `UNCERTAIN`; a matching confirmation clears the barrier and the next movement establishes a fresh anchor. A correction is evidence about synchronization, not an automatic violation.

## Evidence and enforcement

Phase 8 separates observations, simulation results, verdicts, evidence, diagnostics, and enforcement. `Phase8MovementValidation` never kicks or bans. The accumulator only builds repeated impossible evidence; the Paper adapter exposes operator alerts. Any future enforcement policy remains outside the validation core.

Evidence includes server/client timing ranges, world reference, input/timing assumptions, candidate counts, matching/elimination counts, deterministic reasons, closest/provenance witness, uncertainty sources, phase versions, and a prediction reference.

## Targeted debug mode

Normal operation is quiet. High-frequency diagnostics are disabled by default.

Use:

```text
/phantom debug <player>
/phantom debug off
/phantom debug status
/phantom validate <player>
```

`phantom.admin` is required. Targeted debug selects one online player at runtime. `validate` is a manual, operator-requested diagnostic run.

Targeted diagnostics are compact rather than per-packet dumps. They include player, movement count, `P/U/I` verdict totals, latest tick/verdict/reason, candidate and matching counts, client-tick range, world reference, uncertainty sources, replay reference, packet chronology counters, and chunk seen/decoded/pending/failure counters. Scheduled summaries are rate-limited; state/result changes remain observable without printing the same state every tick.

Diagnostics use the plugin logger with categories such as `[PhantomAC][PHASE8]`, `[PhantomAC][CANDIDATE]`, and `[PhantomAC][CHUNK]`. Genuine exceptions and decode failures remain visible at warning level.

## Chunk diagnostics and performance

Client chunk decoding is asynchronous and bounded by the decoder-thread count. Targeted debug exposes decode duration, decoded state count, queue depth, total decoded chunks, and failures. This instrumentation is observational and does not enqueue extra work. Normal mode does not format or emit these high-volume diagnostics.

Phase 8 evidence exposes candidate counts and simulation diagnostics. Production latency/performance must be measured on representative Paper 1.21.11 captures; the deterministic benchmark is not a production guarantee.

## Replay and forensic analysis

Live validation is incremental and does not replay the retained packet journal on every packet. Deterministic replay remains available through `CausalMovementPipeline` for forensic analysis and regression tests. The live prediction frontier is the authoritative expected client trajectory between corrections.

## Normal console expectations

With default configuration, normal movement, chunk decoding, and validation produce no per-packet/per-candidate/per-tick debug stream. Operator alerts and genuine warnings/errors remain visible. Enable targeted debug only while investigating a selected player.

## Validation boundary

The target is Java 1.21.11 / Paper 1.21.11. A Phase 8 `IMPOSSIBLE` result means only that the observation is outside the currently modeled and exhaustively searched legitimate state space. It is not, by itself, an independent claim that a player cheated.

## What Phase 8 does not do

- no automatic punishment;
- no threshold-based speed/fly/reach substitute;
- no second physics or world model;
- no invented packets or timing offsets;
- no treating missing world information as cheating;
- no debug-dependent validation behavior;
- no unrelated-version compatibility layer.
