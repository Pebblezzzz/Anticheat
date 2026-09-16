# Phase 5 trace validation contract

Audit date: 2026-09-16.

## Status
Phase 5 remains **PARTIAL / BLOCKED BY EXTERNAL DATA** for empirical vanilla validation. The repository contains the deterministic simulator primitives, the original 46-column trace validator, an extended version-2 capture format with explicit missing-field provenance, and an observation-only Fabric capture harness pinned to Minecraft Java 1.21.11.

The harness now includes a deterministic integrated-server course builder and a batch scenario driver. This removes the need to manually place the test geometry or hold movement keys. The exact vanilla client still has to be run locally, because that executable is the external reference.

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

The external 1.21.11 captures supplied for Phase 5 have established controlled **stone-ground** walking, sprinting, jumping/landing, sneaking, diagonal input, height-transition, and collision behavior. Those observations are covered by the existing regression tests and were used to correct the simulator's sprint multiplier, air acceleration, jump lifecycle, landing velocity, step/height handling, and collision fixtures.

The exploratory water trace previously captured was **not** accepted as a controlled calibration run because the old scenario held jump and depended on the existing world geometry. The batch harness replaces that flow with a self-built course and a two-tick jump pulse.

## One-command batch capture

From the repository root, run:

`powershell -ExecutionPolicy Bypass -File .\tools\vanilla-trace-capture\run-phase5.ps1`

The runner:

1. executes the complete Maven regression suite;
2. builds the 1.21.11 Fabric capture harness;
3. launches one local singleplayer 1.21.11 client with `phantom.capture.scenario=all`;
4. lets the harness build a clean test course and drive the controlled phases automatically;
5. waits for the harness to stop the client after the batch; and
6. runs `Phase5VanillaBatchAuditTest` against `C:\phase5\phase5-all.tsv`.

The current batch covers setup, walking, sprinting, one-pulse jumping, sneaking, diagonal input, straight collision, water, lava, speed, slowness, jump boost, and step geometry. Each phase is labeled in `input_source` and begins from a server-authoritative teleport so prior phase momentum does not silently contaminate the next phase.

The final audit is intentionally empirical: it requires actual rows from a real 1.21.11 run, verifies the structural validator, checks phase coverage, requires observed water/lava/effect states, and rejects the old “hold jump for 100 ticks” failure mode.

## Controlled-corpus procedure

Every scenario must use a deterministic test state. Record the world identity/seed, exact client version, initial position and rotation, gamemode, attribute state, effect state, relevant blocks/fluid, and scenario id before the first measured tick.

Run isolated scenarios first, then controlled combinations. Preserve the main TSV and packet-event TSV together.

The required scenario checklist is stored in `docs/phase5-vanilla-corpus/scenarios.tsv`. The automated batch is a reproducible subset of that manifest; scenarios whose evidence depends on packet reconstruction, per-axis clipping, or other telemetry explicitly declared missing remain outside the automated acceptance gate.

A scenario may be changed from `BLOCKED_BY_EXTERNAL_DATA` to `CAPTURED` only when an actual Minecraft Java 1.21.11 run produced the trace and the artifacts were preserved outside the simulator source tree.

## Import and comparison

`Phase5VanillaTrace.read()` rejects malformed version-2 headers, wrong column counts, timing regressions, malformed primitive fields, and non-finite numeric state. `Phase5VanillaTrace.validate()` produces field-level timing/world/version diagnostics. `fullyObservedForLegacyComparison()` identifies rows that can safely be compared by the original 46-column comparator without hiding missing observations.

For parity, use the order:

**vanilla capture → structural validation → missing-field review → simulation replay → first divergence → source/cause investigation → simulator fix → regression test → repeat capture**.

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

Phase 5 remains **PARTIAL / BLOCKED BY EXTERNAL DATA** until the new batch capture has been executed and the resulting trace has been inspected for first divergences. No simulator-generated row is valid empirical evidence and no tolerance/fudge factor is accepted as a substitute for fixing the first actual divergence.
