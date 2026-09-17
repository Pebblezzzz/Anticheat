# Phase 6 — Reachability and timing uncertainty

Audit date: 2026-09-17.

Phase 6 extends the deterministic 1.21.11 movement model into a finite reachability engine. The goal is to answer whether an observed state is **POSSIBLE**, **UNCERTAIN**, or **IMPOSSIBLE** inside an explicitly declared input and synchronization envelope.

## Implemented

### Finite input envelope

Unknown input ticks can be exhaustively enumerated over the declared discrete client envelope:

- forward: -1, 0, 1
- strafe: -1, 0, 1
- jump: false/true
- sprint: false/true
- sneak: false/true

This produces 72 deterministic `AdvancedInput` combinations. The original 18-state basic envelope remains available for the existing contract and legacy traces.

Observed `ClientInput` packets now retain sprint and sneak instead of automatically becoming `UNCERTAIN`.

### Multi-tick search

`Validation.ReachableStates.advanceAdvanced(...)` propagates exact `State.Player` candidates through a supplied sequence of worlds. Identical states are merged. A candidate budget is mandatory.

A budget overflow returns `UNCERTAIN` with an empty candidate set. The engine never exposes a sampled subset as if it were exhaustive evidence.

### Timing-window search

`advanceWithinWindow(...)` evaluates each permitted client-tick alignment in a finite `SyncWindow`. Windows wider than the declared search envelope are rejected as `UNCERTAIN` without sampling.

Even when every offset is simulated, a synchronization window explicitly marked uncertain remains `UNCERTAIN`; timing ambiguity cannot turn movement evidence into `IMPOSSIBLE`.

### Evidence

`Validation.validate(observed, reachable, synchronization)` makes the synchronization state part of the evidence decision. Exact state equality remains the criterion for a `POSSIBLE` candidate; no broad positional tolerance is introduced.

## Explicit limitations

The engine is only as complete as the declared physics/world envelope. Unsupported world coverage, uncertain player state, unknown environmental facts, or an exhausted search budget produce `UNCERTAIN` rather than a false `IMPOSSIBLE` result.

The implementation does not claim empirical vanilla parity. Real Minecraft Java 1.21.11 traces from Phase 5 remain the required external reference for validating numeric movement behavior.
