# Phase 6 — Reachability and timing uncertainty

Audit date: 2026-09-17.

Phase 6 code is implemented and internally regression-tested. The only remaining Phase 6 closure dependency is independent empirical validation against real Minecraft Java 1.21.11 client traces, which this development environment cannot capture or launch.

## Reachable-state engine

`Phase6Reachability` is the authoritative Phase 6 search layer. It models the complete Phase 5 simulation context rather than only `State.Player` position/velocity:

- simulation tick
- player position, velocity, rotation, ground, gamemode, effects, and teleport state
- simulation environment and movement environment
- movement effects and attributes
- pose and sleeping state
- explicit uncertainty dimensions

Candidates are merged only on exact full-context equality. Provenance records the parent candidate, simulation tick, input, world branch, external transition, diagnostic causes, and merged-path lineage.

## Input uncertainty

`InputConstraint` supports exact or partially observed input. Missing dimensions are exhaustively expanded through the deterministic 72-combination envelope:

- forward: -1, 0, 1
- strafe: -1, 0, 1
- jump: false/true
- sprint: false/true
- sneak: false/true

Opposing directional flags are normalized to zero. There is no sampled-input fallback.

## World and collision uncertainty

`WorldBranch` accepts rich immutable `WorldSnapshot` hypotheses and explicitly declares whether the branch set is exhaustive. The engine refuses to treat unknown or unsupported collision coverage as air. Rich Phase 5 collision is used through `Vanilla12111RichPhysics`.

A non-exhaustive world envelope, unknown swept collision coverage, or an uncertain physics transition produces `UNCERTAIN` and never produces an `IMPOSSIBLE` result.

## External movement transitions

The engine explicitly branches authoritative external movement events:

- `VelocityImpulse` for knockback / velocity packets
- `TeleportCorrection` for server corrections
- `TeleportConfirmation` for correction acknowledgement
- `None` when no external transition occurs

Callers can provide multiple alternatives when an event itself is uncertain.

## Timing

`searchWithinTimingWindow(...)` exhaustively evaluates every allowed first client tick up to `MAX_TIMING_OFFSETS` (128). Wider windows are rejected as `UNCERTAIN` without sampling. Even after every offset is evaluated, an explicitly uncertain timing envelope remains `UNCERTAIN`.

## Verdict semantics

`compare(...)` returns:

- `POSSIBLE` when an exact candidate matches every declared observed field
- `UNCERTAIN` whenever the search envelope is incomplete
- `IMPOSSIBLE` only after exhaustive finite search has completed and no candidate matches

The engine does not use an arbitrary positional tolerance as a substitute for state uncertainty.

## Live integration

`LiveValidation.analyze(...)` now uses the rich Phase 6 engine, rich `World.VisibilityHistory`, advanced sprint/sneak input observations, and captured velocity/teleport/confirmation packets. When the live trace lacks enough world or state information to construct an exhaustive envelope, the result is `UNCERTAIN` and the candidate envelope is not converted into a violation.

## Explainability and regression infrastructure

`Phase6Replay` defines a self-contained `phase6-replay-v1` artifact with a deterministic canonical text form. `Phase6PerformanceBenchmark` exercises the full one-tick 72-input envelope. `Phase6SoundReachabilityTest` covers candidate budgets, full-context merging, partial inputs, rich coverage uncertainty, non-exhaustive world hypotheses, knockback, teleport confirmation, timing ambiguity, impossible-state semantics, provenance, replay determinism, and performance.

## Explicit external limitation

Independent vanilla parity is not claimed here. Real Minecraft Java 1.21.11 traces remain necessary to establish `VANILLA VALIDATED` numeric behavior and first-divergence agreement. No local code change can manufacture that evidence without running the real client.
