# Validation and production hardening

Phantom uses a version-pinned 1.21.11 deterministic movement authority, persistent live prediction, compensated world/timing state, and explicit `POSSIBLE`, `UNCERTAIN`, and exhaustive `IMPOSSIBLE` verdicts.

## Current build gate

The repository build gate is the Java 21 Maven suite plus the dedicated Phase 5 validation harness and the Phase 1–7 hardening workflow. Phase 8 adds adversarial, persistence, reconciliation, timing, and enforcement-policy regression coverage.

The live adapter runs expensive prediction on a dedicated validation executor and coalesces work per player so bursts of movement packets do not become an unbounded task queue.

## Movement correctness contract

The canonical 1.21.11 path represents normal walking/sprinting/sneaking/jumping, jump delay, step/collision resolution, fluids, climbing, Elytra/fall-flying, creative/spectator movement, velocity/knockback, corrections, entity collision state, rideable vehicle branches, and modern movement modifiers including Depth Strider, Swift Sneak, Soul Speed, and Dolphin's Grace.

A missing world/entity/timing fact is never silently converted into air, zero velocity, neutral input, or a hard movement violation.

## Packet and interaction coverage

Position/rotation sanity, held-slot integrity, timer-burst evidence, reach, far break/place, block-placement cursor/face integrity, inventory slot/type integrity, conservative FastBreak evidence, and ground-claim corroboration are now part of the production evidence layer.

## Evidence and enforcement

`POSSIBLE` means at least one complete candidate explains the observation.

`UNCERTAIN` means the causal envelope is incomplete, ambiguous, unsupported, stale, or budget-limited.

`IMPOSSIBLE` means the complete reachable candidate set for the declared envelope has been eliminated.

Production enforcement additionally requires repeated consecutive impossible evidence and numeric proof that a non-empty candidate set has zero matches and has been completely eliminated.

## External conformance

Real-client sessions are useful as an external conformance dataset for operators who want to compare a deployment against a live vanilla client. The repository does not label synthetic fixtures as real-client measurements; external captures are an optional validation layer rather than a fabricated build prerequisite.

## Operational diagnostics

`/phantom status` reports captures, compensated-world pressure, validation queue depth, validation throughput, average validation time, slow validation runs, and verdict counts. Targeted debug modes expose replay references and closest-candidate evidence for forensic analysis.

## Phase 7 implementation status

`Phase7Timing` is the authoritative client/server timing layer after canonical Phase 1/3 chronology. It explicitly represents:

- server capture time and deterministic server-tick projection;
- client packet-generation intervals;
- client processing intervals for clientbound packets;
- optional explicit client movement ticks;
- bounded relative client-tick reconstruction when no explicit tick is present;
- asymmetric client→server and server→client latency bounds;
- input→simulation and simulation→packet timing bounds;
- packet bursts, observation gaps, server-tick gaps, duplicates, reordering, and sequence gaps;
- teleport/correction synchronization boundaries and delayed acknowledgements;
- velocity timing windows;
- client-visible world-update timing windows;
- explicit synchronization states and recovery requirements;
- deterministic Phase 7 replay and synthetic performance measurement.

`LiveValidation.analyze(...)` reconstructs Phase 7 timing before invoking the existing authoritative Phase 6 reachability engine. Phase 7 does not duplicate candidate simulation.

Duplicate capture records are preserved as evidence but are no longer allowed to advance semantic state twice during replay/history reconstruction.

## External validation rule

Real Minecraft Java 1.21.11 client traces are useful as an optional deployment-conformance dataset for numeric timing and movement behavior. They are not treated as a fabricated prerequisite or as the definition of whether the deterministic implementation exists. The repository keeps synthetic/replayable fixtures separate from external client measurements.

No client capture corpus is used as a substitute for explicit `IMPOSSIBLE` proof, and incomplete empirical coverage remains a validation limitation rather than a punishment input.
