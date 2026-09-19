# Phantom Phase 8 Causal Architecture

Phantom Phase 8 is an independent movement validator. Paper supplies authoritative server context and adapter telemetry; Paper's own movement rejection is never a Phantom detector input.

## Data flow

packet stream
  -> client state reducer
  -> persistent Phase 6 prediction frontier
  -> latency-compensated per-player packet world
  -> deterministic Phase 5 physics forward step
  -> observed packet comparison
  -> retain the trusted prediction frontier
  -> provenance-bearing evidence
  -> POSSIBLE / UNCERTAIN / IMPOSSIBLE

`Phase8PredictionRunner` is the canonical live orchestration boundary. It is stateful: the client observation state and the Phase 6 prediction frontier survive between validation calls. Each new movement only advances the frontier from its last complete simulation tick.

`CausalMovementPipeline` remains an offline/replay analysis utility for deterministic test and forensic workflows. It is no longer used by the hardened live Paper adapter.

There is no live replay-on-every-packet adapter. The old `Phase8IncrementalRunner` has been removed.

## Authority

Every authoritative server snapshot is retained with capture sequence, receive timestamp, server tick, and full `PlayerContext`.

A movement packet is aligned only to an authoritative snapshot that is causally before the packet. A stale or missing snapshot is reported as such.

Authoritative position, velocity, ground state, gamemode, flight permission, effects, attributes, pose, and entity collisions remain separate from client-reported movement observations. They are not silently copied into the observed state.

## World causality

The validator consumes the client-visible world at the simulation tick being modeled. Transaction-backed world mutations become visible only after their acknowledgement. A future world mutation is never used to explain an earlier packet.

A live acknowledged world replica may be used for the final step of a current observation only when no later world mutation is present in the retained causal journal. Historical/intermediate simulation remains tied to the reconstructed world timeline.

Incomplete coverage, unsupported block states, and non-exhaustive world branches produce `UNCERTAIN`; they are never transformed into an `IMPOSSIBLE` result merely because collision information is missing.

## Recovery

Prediction state is not repaired from a failed client observation.

Prediction is allowed to advance only through a matched `POSSIBLE` observation. An `UNCERTAIN` or `IMPOSSIBLE` observation therefore does not become a new trusted client baseline.

Chronology ambiguity, sub-tick movement that the full-tick simulator cannot represent, teleports, velocity transitions, and world synchronization boundaries can invalidate the current prediction frontier. Recovery requires a new causally usable authoritative anchor or a clean replay that establishes one.

## Evidence channels

`MOVEMENT_REACHABILITY` means the finite deterministic candidate envelope was exhausted and no candidate matched the declared observed fields.

`UNAUTHORIZED_FLIGHT_TOGGLE_ATTEMPT` is a separate authoritative evidence channel. It requires an explicit authoritative server snapshot saying flight is not permitted and an observed flight-on transition. It does not depend on Paper movement rejection.

`PaperMovementRejection` is telemetry only. The adapter records `MOVED_TOO_QUICKLY`, `MOVED_WRONGLY`, and related Paper rejection observations as `PAPER_CORROBORATION_ONLY`. They do not create, strengthen, or synthesize a Phantom `IMPOSSIBLE` verdict.

Client/server ground disagreement and client/server position divergence are likewise corroboration. They may be useful diagnostics, but they are not hard Phantom verdicts by themselves.

## Verdict contract

- `POSSIBLE`: at least one complete legitimate modeled state explains the declared observations.
- `UNCERTAIN`: timing, chronology, world knowledge, supported mechanics, or candidate budget prevents an exhaustive statement.
- `IMPOSSIBLE`: the modeled envelope is exhaustive and every legitimate candidate is contradicted by the observed facts, or a separate explicit authoritative contradiction satisfies its own stated rule.

The system should prefer uncertainty over false certainty.
