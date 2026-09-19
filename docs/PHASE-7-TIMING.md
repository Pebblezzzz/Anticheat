# Phase 7 — Client/server synchronization and temporal envelopes

Audit/implementation date: 2026-09-19.

Phase 7 has one responsibility: determine which client/server timing histories remain possible from the packet history and synchronization evidence available to the server. It does not make movement or anti-cheat decisions.

## Temporal model

Phase 7 keeps server tick, capture/arrival time, packet-generation time, client-processing time, client simulation tick, and synchronization state as separate quantities. Server tick is never treated as the client tick. The capture sequence is preserved as chronology evidence, not reinterpreted as an inferred client ordering.

`Timeline.assign(...)` deterministically projects capture chronology onto server ticks:

```text
serverTick = floor((captureNanos - captureEpochNanos) / serverTickNanos)
```

The canonical ordering remains `(serverTick, captureNanos, sequence)`. Phase 7 never reorders packets by an inferred client tick.

## Temporal envelope and client-tick reconstruction

Phase7Timing.TickEnvelope is the primary discrete representation. It contains known/unknown state, a complete bounded Range, optionally materialized possible tick candidates, and an exhaustive flag. A wide range is retained rather than narrowed when the discrete candidate budget is insufficient.

Move.clientTick is capture-side timing metadata when present and is the strongest client-tick witness available. The first unwatermarked client event establishes relative tick zero; its network-generation interval remains separately represented. CLIENT_TICK_END is a temporal boundary, not a movement timestamp. Later movement is constrained by boundary timing and latency rather than automatically assigned the boundary tick.

This supports multiple client ticks between observations, multiple movement packets in one server tick, bursts, delayed packets, missing observations, ambiguous alignment, and idle periods without interpreting an observation gap as “client did nothing”.

## Latency and input-to-packet timing

Input, simulation, and packet generation are distinct fields in `EventTiming`:

```text
input generation
      ↓ inputToSimulation bound
client simulation tick
      ↓ simulationToPacket bound
movement packet generation
      ↓ upstream latency bound
server arrival
```

The packet is therefore not treated as the exact instant that its underlying input occurred.

CLIENT_TICK_END is retained as a boundary observation. It constrains later packet-generation time but is not itself used as a universal movement timestamp. Multiple packets can still share a client tick, and packet arrival time does not establish client emission order.

An exact relative client tick is a timing fact even when upstream latency remains bounded rather than exact. Phase 7 therefore separates **client-tick certainty** from **network-time uncertainty**: bounded upstream jitter no longer poisons an otherwise exact movement tick. Server-to-client world/correction timing remains uncertain while downstream delivery latency is bounded rather than exact.


## Latency and jitter

Client→server and server→client latency are independent configurable [min,max] envelopes. Serverbound generation time is arrival minus upstream latency; clientbound processing time is send/capture plus downstream latency. Jitter widens the possible time and therefore the possible client tick set. A timing budget never shrinks that range.

## Chronology, gaps, duplicates, and reordering

Phase 1 remains the single canonical chronology. The sequence is a capture sequence, not a claimed protocol sequence. A gap means capture information is absent; it is never interpreted as inactivity. Duplicate and reordering flags remain evidence. Replay/history reconstruction applies each capture sequence at most once, so duplicates cannot advance movement, velocity, correction, or acknowledgement state twice.

## Corrections and acknowledgement

`Teleport` creates a new synchronization epoch and enters `RECOVERING`. `TeleportConfirm` is asynchronous: matching confirmation clears the pending correction, but stable post-correction movement observations are still required before `SYNCHRONIZED`. An unexpected confirmation enters `AMBIGUOUS`.

For the target 1.21.11 implementation, teleport confirmation is the modeled correction acknowledgement. No unverified generic transaction mechanism is claimed.

## Velocity timing

Velocity is a server-to-client event. Its server observation time, client processing interval, and possible simulation ticks remain separate. The Phase 7→Phase 6 bridge maps every materialized possible simulation tick to the external transition; Phase 5 remains the movement authority.

## World-update visibility timing

Historical client-visible world state remains owned by World.VisibilityHistory. Server-to-client block/chunk updates use the client-processing/simulation envelope for visibility timing, not packet-generation time. Server knowledge of a change therefore does not imply that the client had already processed it.

## Synchronization states and recovery

The model exposes:

- `UNKNOWN` — no client/server timing anchor exists;
- `PARTIALLY_SYNCHRONIZED` — a relative anchor exists but strong timing is not established;
- `SYNCHRONIZED` — configured timing constraints are satisfied by clean observations;
- `AMBIGUOUS` — timing/chronology is uncertain without a pending correction;
- `RECOVERING` — correction, gap, or synchronization disruption requires stable observations.

`SynchronizationWindow` records the event type, server-tick span, possible client ticks, trigger sequence, and explanation. Recovery is explicit and never returns to strong synchronization from a single packet.

## Deterministic budgets, replay, and divergence

maxTimingCandidates bounds discrete materialization of an individual envelope. maxTimingHistories bounds the Cartesian timing-history upper bound. On exhaustion, Phase 7 retains the original range, marks the envelope non-exhaustive, records a TIMING_BUDGET window, and returns uncertain synchronization information rather than fabricated precision.

Phase7Replay stores the canonical Phase 1 timeline plus complete Phase 7 configuration. Current replay format is phase7-replay-v2 and remains backward-readable for version 1 data. Phase7Replay.verifyAgainst and Phase7Timing.firstDivergence expose the first differing timing event and synchronization state. Wall-clock processing time is never part of the replay signature.

## Phase 6 boundary

Phase 7 provides a clean Phase7Timing.toPhase6Envelope(...) containing possible simulation ticks, enumeration completeness, synchronization state, active windows, and uncertainty. The live causal bridge also maps materialized possible input and correction/velocity ticks into Phase 6. Phase 7 does not duplicate reachability or physics.


## Event association and uncertainty

Every EventTiming preserves sequence, server tick, capture time, capture provenance, optional authoritative server tick, packet-generation interval, client-processing interval, packet-generation/client-processing/simulation/input tick envelopes, ordering constraints, synchronization windows, and reasons. Sequence gaps, duplicates, reordering, packet gaps, and server-tick gaps remain explicit uncertainty. Missing observations are never interpreted as inactivity.

## Empirical limitation

The repository still lacks independent real Minecraft Java 1.21.11 client timing traces. Therefore the timing model is deterministic and internally regression-tested, but real-client packet-generation timing, tick phase, acknowledgement delay, jitter distribution, and final numeric parity remain external validation items that cannot be manufactured without launching/capturing the real client.
