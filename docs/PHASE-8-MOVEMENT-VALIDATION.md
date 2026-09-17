# Phase 8 — Movement Validation

Phase 8 is the first movement-validation layer. It consumes the existing Phase 1–7 pipeline; it does not replace packet normalization, state reconstruction, world history, physics, reachability, or timing reconstruction.

## Responsibilities

```text
Packets
  -> Phase 1 timeline
  -> Phase 2 PlayerState
  -> Phase 3 replay/capture
  -> Phase 4 WorldSnapshot
  -> Phase 5 deterministic physics
  -> Phase 6 reachable states
  -> Phase 7 synchronization/timing
  -> Phase 8 movement validation
```

`Phase8MovementValidation` is a pure comparison/evidence layer. It does not contain movement physics and does not perform threshold-based speed/fly/reach checks.

## Verdict semantics

- **POSSIBLE** — at least one exhaustively modeled Phase 6 candidate matches every declared observed field.
- **UNCERTAIN** — Phase 6 was non-exhaustive, Phase 7 timing is ambiguous, world knowledge is incomplete, or a search budget/horizon prevents a safe conclusion.
- **IMPOSSIBLE** — Phase 6 was exhaustive for the declared finite envelope and every legitimate candidate was eliminated.

Candidate-budget exhaustion is never converted to `IMPOSSIBLE`.

## Evidence

`Phase8MovementValidation.Evidence` is immutable and replayable. It records:

- player/session identifier;
- server tick and client-tick range;
- prior and observed PlayerState;
- target version and world reference;
- input and timing assumptions;
- reachable/matching/eliminated candidate counts;
- elimination reason and first inconsistent tick;
- closest candidate/provenance witness;
- simulation diagnostics and uncertainty sources;
- Phase 5/6/7 versions;
- replay reference.

Evidence is designed to explain exhaustion of the legitimate state space rather than emit labels such as `SPEED HACK`, `FLY`, or `NOCLIP`.

## Accumulation and alerts

`Accumulator` tracks repeated impossible observations, recoveries, and uncertainty periods per player/rule. `POSSIBLE` and `UNCERTAIN` never increase impossible evidence. A single impossible observation is recorded but does not alert by default; the observation-only default requires repeated impossible observations and debounces operator alerts.

Alerts are structured as:

```text
[AntiCheat] player=<id> type=MOVEMENT result=IMPOSSIBLE tick=<tick> first-inconsistent-tick=<tick> reason=<reason> confidence=<value> replay=<id>
```

Phase 8 has **no punishment semantics**. It does not kick, ban, or otherwise punish players as part of the movement-validation contract. Operator alerts are evidence/observation output only.

## Replay

`Phase8Replay` wraps an existing Phase 6 search result and the Phase 7 timing window. Replaying the same artifact calls the same pure Phase 8 comparison and therefore reproduces the same verdict and evidence.

## False-positive protection

Timing uncertainty, delayed/reordered/duplicated packets, incomplete input, incomplete world coverage, unsupported blocks, teleport/correction state, velocity transitions, and Phase 6 budget exhaustion must remain uncertainty. Phase 8 cannot turn these conditions into a violation.

## Validation boundary

Phase 5 empirical Minecraft Java Edition 1.21.11 validation is still ongoing. Phase 8 therefore starts as an observation/research system. A Phase 8 `IMPOSSIBLE` result means that the observation is outside the **currently modeled and exhaustively searched** legitimate state space; it is not an independent claim that a player cheated.

## What Phase 8 does not enforce

- no automatic punishment;
- no combat checks;
- no unrelated packet heuristics;
- no generic speed/fly/reach thresholds;
- no duplicate physics implementation;
- no duplicate reachability engine;
- no bypass of Phase 7 timing uncertainty;
- no Phase 9 policy/enforcement layer.
