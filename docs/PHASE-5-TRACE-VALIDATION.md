# Phase 5 trace validation contract

Audit date: 2026-09-16.

## Status
Phase 5 remains **PARTIAL / BLOCKED BY EXTERNAL DATA** for empirical vanilla validation. The repository now contains the deterministic simulator primitives, the original 46-column trace validator, an extended version-2 capture format with explicit missing-field provenance, and an observation-only Fabric capture harness pinned to Minecraft Java 1.21.11.

No real vanilla trace is committed by this pass because the actual licensed 1.21.11 client cannot be launched in this execution environment. This is an external-data blocker, not a permission to synthesize traces.

## Exact target client

The empirical reference is exactly **Minecraft Java Edition 1.21.11**, not latest, a release candidate, a snapshot, or a different 1.21.x release. The official 1.21.11 release page directs players to launch the release through the Minecraft Launcher. Fabric documents that 1.21.11 is the final obfuscated release before the 26.1 transition; the capture harness therefore pins Minecraft `1.21.11`, Yarn `1.21.11+build.4`, Fabric Loader `0.18.2`, and Loom `1.14.10`.

## Version-2 empirical trace format

The original `Phase5TraceTool` 46-column schema remains supported for fully observed legacy data. The empirical capture harness uses version 2, which adds a single `missing_fields` column to prevent unknown observations from being misrepresented as zero/false values.

The first four lines of a version-2 capture are:

1. `# phantom-phase5-trace version=2 protocol=minecraft-java-1.21.11 format=tsv`
2. `# source_id=<capture id>`
3. `# captured_at_utc=<UTC timestamp>`
4. the exact 47-column header emitted by `VanillaTraceCaptureClient`.

Columns 1–46 retain the Phase 5 fields: tick/timing, position, velocity, rotation, ground/input state, pose/gamemode, fluid/submerged/climbing/gliding, movement-speed attribute data, movement effects, impulse/correction fields, world identity/tick, collision/step data, and provenance.

Column 47 is `missing_fields`, a comma-separated list of fields that the capture mechanism could not independently reconstruct at the recorded observation point. `-` means no missing field was declared. Unknown must never be silently encoded as zero or false.

## Observation points

The capture harness takes player state **after the real `ClientPlayerEntity.tick()` returns**. This is intentionally an observation point; it does not replace or wrap the movement algorithm.

A second event stream records inbound `ENTITY_VELOCITY` and `POSITION_CORRECTION` packets with their receive timestamp and packet payload. These packet events are kept separate from the post-tick row because packet arrival and player-state observation are distinct timing points.

The current harness can independently observe position, velocity, rotation, ground state, discrete `PlayerInput`, sprint/sneak/jump state, resolved client pose, fluid/submerged/climbing/gliding state, movement-speed base/modifiers, movement effects, world identity/tick, and aggregate collision flags. It deliberately declares knockback decomposition, correction-pending state, step attempt/result, and per-axis collision clipping missing until a measurement procedure can reconstruct them without inventing data.

## Controlled-corpus procedure

Every scenario must use a separate deterministic test world or a documented fresh state. Record the world identity/seed, exact client version, initial position and rotation, gamemode, attribute state, effect state, relevant blocks/fluid, and scenario id before the first measured tick.

Run isolated scenarios first, then controlled combinations. Each scenario must have a fixed start state, a fixed input sequence, and a fixed terminal tick. Preserve the main TSV and packet-event TSV together.

The required scenario checklist is stored in `docs/phase5-vanilla-corpus/scenarios.tsv`.

A scenario may be changed from `BLOCKED_BY_EXTERNAL_DATA` to `CAPTURED` only when an actual Minecraft Java 1.21.11 run produced the trace and the artifacts were preserved outside the simulator source tree.

## Import and comparison

`Phase5VanillaTrace.read()` rejects malformed version-2 headers, wrong column counts, timing regressions, malformed primitive fields, and non-finite numeric state. `Phase5VanillaTrace.validate()` produces field-level timing/world/version diagnostics. `fullyObservedForLegacyComparison()` identifies rows that can safely be compared by the original 46-column comparator without hiding missing observations.

For parity, use the order:

**vanilla capture → structural validation → missing-field review → simulation replay → first divergence → source/cause investigation → simulator fix → regression test → repeat capture**.

The existing `Phase5TraceTool.firstDivergence()` remains the detailed state comparator for traces whose required fields are available. It reports the first mismatching tick and field rather than only the final displacement error.

## Required empirical corpus

The initial corpus manifest requires at least: idle, walking, sprinting, sneaking, forward, strafe, diagonal movement, jumping, sprint-jumping, falling, landing, slabs, stairs, step-ups, corners/edges, water, lava, swimming, ladders/vines/climbables, movement effects, attribute changes, knockback, teleport/correction, and controlled combinations of these mechanics.

No repository fixture currently claims to be one of these real-client captures.

## Vanilla-validation classification

- **IMPLEMENTED:** modeled in the simulator or capture tooling.
- **INTERNALLY TESTED:** repository tests exercise the model/tooling.
- **VANILLA VALIDATED:** reserved for a behavior actually compared against an independently captured 1.21.11 client trace.
- **PARTIAL:** some machinery exists but an empirical requirement remains.
- **BLOCKED BY EXTERNAL DATA:** the missing evidence must come from running the exact client outside this execution environment.

Phase 5 is currently **PARTIAL / BLOCKED BY EXTERNAL DATA**. No simulator-generated row is valid empirical evidence and no tolerance/fudge factor is accepted as a substitute for fixing the first actual divergence.
