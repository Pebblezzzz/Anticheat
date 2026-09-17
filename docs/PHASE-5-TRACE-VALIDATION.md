# Phase 5 trace validation contract

Audit date: 2026-09-17.

## Status
The **automated controlled Phase 5 batch is now captured, structurally audited, and replayed through the simulator successfully** using the existing Minecraft Java 1.21.11 Part 1–5 captures. The existing captures were merged into `C:\phase5\phase5-all.tsv` with measurements preserved; only pre-settle phase-boundary rows were omitted and tick/timing metadata was reindexed for unified validation. The merged trace contains 1,917 accepted rows after 301 pre-settle boundary rows were discarded.

The broader Phase 5 empirical corpus remains **PARTIAL / BLOCKED BY EXTERNAL DATA** because the manifest contains scenarios and telemetry requirements beyond this automated controlled batch. In particular, scenarios that require packet reconstruction, per-axis clipping, serialized geometry, or other currently missing telemetry are not silently treated as validated.

The repository contains the deterministic simulator primitives, the version-2 capture format with explicit missing-field provenance, an observation-only Fabric capture harness pinned to Minecraft Java 1.21.11, and an existing-capture replay path for reusing completed vanilla evidence without recapturing the client.

## Exact target client

The empirical reference is exactly **Minecraft Java Edition 1.21.11**, not latest, a release candidate, a snapshot, or a different 1.21.x release. The capture harness pins Minecraft `1.21.11`, Yarn `1.21.11+build.4`, Fabric Loader `0.18.2`, and Loom `1.14.10`.

## Version-2 empirical trace format

The original `Phase5TraceTool` 46-column schema remains supported for fully observed legacy data. The empirical capture harness uses version 2, which adds a single `missing_fields` column to prevent unknown observations from being misrepresented as zero/false values.

The first four lines of a version-2 capture are:

1. `# phantom-phase5-trace version=2 protocol=minecraft-java-1.21.11 format=tsv`
2. `# source_id=<capture id>`
3. `# captured_at_utc=<UTC timestamp>`
4. the exact 47-column header emitted by `VanillaTraceCaptureClient`.

The trace keeps the existing 47-column schema. `input_source` now carries the deterministic batch phase label as `capture-post-tick:all:<phase>` so phase-specific evidence can be audited without changing the schema.

## Observation points

The capture harness takes player state **after the real `ClientPlayerEntity.tick()` returns**. The input driver runs at the start of the client tick only to set KeyBinding state; vanilla then performs the actual movement. The course builder uses the integrated server command API only for deterministic setup transitions and does not substitute for vanilla movement logic.

A second event stream records inbound `ENTITY_VELOCITY` and `POSITION_CORRECTION` packets with their receive timestamp and packet payload. Packet arrival and post-tick player-state observation remain separate timing points.

Unknown knockback decomposition, correction-pending state, step attempt/result, and per-axis collision clipping continue to be declared missing until independently reconstructable.

## Empirical baselines captured so far

The existing 1.21.11 captures establish controlled **stone-ground** walking, sprinting, jumping/landing, sneaking, diagonal input, height-transition, collision behavior, water, lava, movement effects, jump boost, stair/step behavior, climbable interaction, edge/corner movement, swimming transition, gliding, and correction behavior across the five capture parts. These observations are covered by the automated batch audit where the available telemetry supports deterministic checks.

The jump-boost capture uses a direct vanilla `player.jump()` trigger after the Jump Boost effect is present. The batch audit verifies the observed positive launch trajectory, while one-tick numerical replay does not treat the post-tick jump-key observation as proof of the exact causal input consumption order.

## Existing-capture batch replay

From the repository root, run:

`pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\phase5\run-existing-phase5.ps1`

The existing-capture runner:

1. reads `C:\phase5\phase5-part1.tsv` through `C:\phase5\phase5-part5.tsv`;
2. validates the version-2 headers and 47-column shape;
3. removes only leading pre-settle rows at phase boundaries where the row is still in the prior lane;
4. normalizes the part phase labels to `capture-post-tick:all:<phase>`;
5. reindexes batch tick/timing metadata without changing observed movement/effect/pose/state fields;
6. writes `C:\phase5\phase5-all.tsv`; and
7. runs both `Phase5VanillaBatchAuditTest` and `Phase5VanillaSimulationReplayTest`.

The completed run on 2026-09-17 produced 1,917 merged rows and passed both tests with zero failures and zero errors. The replay therefore verified the current simulator against the stable, replayable subset of the captured vanilla observations without requiring another Minecraft capture.

The original one-command live batch-capture path remains available for obtaining a fresh unified capture when needed, but it is not required merely to consume the already completed Part 1–5 evidence.

## Controlled-corpus procedure

Every scenario must use a deterministic test state. Record the world identity/seed, exact client version, initial position and rotation, gamemode, attribute state, effect state, relevant blocks/fluid, and scenario id before the first measured tick.

Run isolated scenarios first, then controlled combinations. Preserve the main TSV and packet-event TSV together.

The required scenario checklist is stored in `docs/phase5-vanilla-corpus/scenarios.tsv`. The automated batch is a reproducible subset of that manifest; scenarios whose evidence depends on packet reconstruction, per-axis clipping, or other telemetry explicitly declared missing remain outside the automated acceptance gate.

A scenario may be changed from `BLOCKED_BY_EXTERNAL_DATA` to `CAPTURED` only when an actual Minecraft Java 1.21.11 run produced the trace and the artifacts were preserved outside the simulator source tree.

## Import and comparison

`Phase5VanillaTrace.read()` rejects malformed version-2 headers, wrong column counts, timing regressions, malformed primitive fields, and non-finite numeric state. `Phase5VanillaTrace.validate()` produces field-level timing/world/version diagnostics. `fullyObservedForLegacyComparison()` identifies rows that can safely be compared by the original 46-column comparator without hiding missing observations.

For parity, use the order:

**vanilla capture → structural validation → missing-field review → simulation replay → first divergence → source/cause investigation → simulator fix → regression test → repeat capture**.

The completed controlled batch has now gone through this loop for the replayable subset: the vanilla evidence was inspected, the simulator replay produced no remaining divergences in the calibrated phases, and the regression tests pass.

The existing `Phase5VanillaComparison.firstDivergence()` remains the detailed state comparator for captures whose required fields are available. It reports the first mismatching tick and field rather than only a final displacement error.

## Required empirical corpus

The initial corpus manifest requires at least: idle, walking, sprinting, sneaking, forward, strafe, diagonal movement, jumping, sprint-jumping, falling, landing, slabs, stairs, step-ups, corners/edges, water, lava, swimming, ladders/vines/climbables, movement effects, attribute changes, knockback, teleport/correction, and controlled combinations of these mechanics.

The automated batch does not silently claim empirical coverage for manifest rows it does not capture. Those remain **PARTIAL / BLOCKED BY EXTERNAL DATA** until a real-client procedure produces the required evidence.

## Vanilla-validation classification

- **IMPLEMENTED:** modeled in the simulator or capture tooling.
- **INTERNALLY TESTED:** repository tests exercise the model/tooling.
- **VANILLA VALIDATED:** reserved for a behavior actually compared against an independently captured 1.21.11 client trace.
- **PARTIAL:** some machinery exists but an empirical requirement remains.
- **BLOCKED BY EXTERNAL DATA:** the missing evidence must come from running the exact client outside this execution environment.

The automated controlled Phase 5 batch is **VANILLA VALIDATED for the replayable, audited subset represented by the existing Part 1–5 captures**. Phase 5 as a whole remains **PARTIAL / BLOCKED BY EXTERNAL DATA** until the remaining corpus-manifest scenarios and their required telemetry are independently captured and validated.
