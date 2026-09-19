# Phases 1–8 requirements audit

Audit date: 2026-09-19. This document describes the current `main` implementation after the Phase 1–8 hardening merge.

## Acceptance status

| Phase | Current status | Acceptance note |
|---|---|---|
| 1 | IMPLEMENTED / INTERNALLY TESTED | Canonical packet normalization, immutable capture provenance, ordering/duplicate handling, protocol-aware packet coverage, and adversarial chronology tests are in the mainline pipeline. |
| 2 | IMPLEMENTED / INTERNALLY TESTED | `State.Player` is immutable and rich: position, velocity, rotation, ground state, gamemode, effects, correction state, input, attributes, pose, environment, client-tick range, provenance, and structured uncertainty are preserved through reduction. |
| 3 | IMPLEMENTED / INTERNALLY TESTED | Deterministic replay preserves rich state and diagnostics; duplicate replay does not downgrade state, and first-divergence comparison includes rich state fields. |
| 4 | IMPLEMENTED / INTERNALLY TESTED | The per-player client-visible world replica is authoritative for collision. Known, unknown, unloaded, and unsupported coverage remain distinct, with 1.21.11 collision data and deterministic voxel resolution. |
| 5 | IMPLEMENTED / SOURCE-DERIVED 1.21.11 | `Phase5MovementAuthority` is the sole movement transition authority. Ground/air, sprint/sneak, jumping, fluids, climbing, gliding, poses, effects, corrections, creative flight, spectator flight, and entity collisions are represented deterministically. Real-client captures remain empirical validation, not a prerequisite for claiming the source-derived implementation itself. |
| 6 | IMPLEMENTED / INTERNALLY TESTED | Reachability exhaustively explores the declared finite input/world/timing envelope within deterministic budgets, routes every movement transition through Phase 5, preserves provenance, and returns `UNCERTAIN` whenever the modeled envelope is incomplete. |
| 7 | IMPLEMENTED / INTERNALLY TESTED | Phase 7 is the timing authority for live validation, with client-tick reconstruction, latency/jitter bounds, chronology gaps, corrections, acknowledgements, synchronization state, bounded history, replay, and deterministic uncertainty propagation. |
| 8 | IMPLEMENTED / INTERNALLY TESTED | Live prediction consumes Phase 7 timing and the Phase 6 frontier, compares observed movement without speed-threshold substitutes, preserves trusted prediction across uncertain/impossible observations, and exposes a separate exhaustive-evidence enforcement policy. Punitive actions are disabled by default. |

## Architecture

The live path is:

`packet stream → canonical state reduction → latency-compensated Phase 4 world → Phase 7 timing → Phase 5 deterministic transition authority → Phase 6 reachable-state frontier → Phase 8 comparison/evidence → optional operator policy`

There is no second live movement physics implementation. Phase 6 delegates movement transitions to Phase 5, and Phase 8 delegates possible-state exploration to Phase 6.

## Verdict contract

`POSSIBLE` means at least one complete modeled candidate matches the declared observation.

`UNCERTAIN` means the evidence envelope is incomplete, ambiguous, unsupported, chronologically unreliable, or budget-limited. Missing world/entity/timing information is never converted into movement impossibility.

`IMPOSSIBLE` means the modeled movement envelope was exhaustively represented for the relevant observation and every candidate was contradicted. An authoritative non-reachability observation does not manufacture this movement verdict.

## Enforcement safety

The enforcement layer is downstream from evidence. It requires an exhaustive Phase 6 elimination reason, a first inconsistent tick, non-uncertain states, the configured repeated-evidence threshold, and the configured confidence floor. Setback, kick, and punishment-command actions are separately configurable and disabled by default.

## Empirical validation boundary

The implementation is source-derived and version-pinned to Minecraft Java Edition 1.21.11. The repository does not fabricate real-client measurements. A real licensed 1.21.11 client trace can be imported later for empirical first-divergence comparison and conformance testing; synthetic traces are not relabelled as empirical evidence.

## Current CI gate

The Phase 1–8 hardening merge passed the repository Maven suites and the Phase 5 capture-harness build on `main`. Subsequent documentation-only commits do not change executable behavior.

## Scope

This audit stops at Phase 8. No Phase 9+ implementation is included.
