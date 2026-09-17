# Phase 7 — Client/server timing and synchronization

Audit/implementation date: 2026-09-17.

Phase 7 reconstructs **possible client-side timing** from the authoritative Phase 1/3 timeline. It does not assume one server tick equals one client movement tick and it never turns timing ambiguity into a cheating verdict.

## Exact model

The capture layer records a monotonic capture timestamp plus a capture sequence. For serverbound packets the timestamp is the server receive observation; for clientbound packets the same field records the server-side send/capture observation. Phase 7 uses packet direction to interpret the field correctly.

`Timeline.assign(...)` deterministically projects capture chronology onto server ticks:

```text
serverTick = floor((captureNanos - captureEpochNanos) / serverTickNanos)
```

The canonical ordering remains `(serverTick, captureNanos, sequence)`. Phase 7 never reorders packets by an inferred client tick.

## Client tick reconstruction

`Phase7Timing.Reconstruction` anchors relative client chronology on the first client→server event. If `Move.clientTick` exists, that tick is a hard observation. Otherwise the event gets a range derived from its latency-bounded client time relative to the anchor, using the configured client tick-period range.

This supports multiple client ticks between observations, multiple movement packets in one server tick, bursts, delayed packets, missing observations, ambiguous alignment, and idle periods without interpreting an observation gap as “client did nothing”.

## Input-to-packet timing

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

## Latency and jitter

Latency is represented separately for client→server and server→client directions as explicit `[min,max]` bounds. The difference is the allowed jitter envelope. Variable latency widens possible client time rather than selecting a single tick. The default configuration is conservative and is replay input data, not hidden state.

## Chronology, gaps, duplicates, and reordering

Phase 1 remains the single canonical chronology. The sequence is a capture sequence, not a claimed protocol sequence. A gap means capture information is absent; it is never interpreted as inactivity. Duplicate and reordering flags remain evidence. Replay/history reconstruction applies each capture sequence at most once, so duplicates cannot advance movement, velocity, correction, or acknowledgement state twice.

## Corrections and acknowledgement

`Teleport` creates a new synchronization epoch and enters `RECOVERING`. `TeleportConfirm` is asynchronous: matching confirmation clears the pending correction, but stable post-correction movement observations are still required before `SYNCHRONIZED`. An unexpected confirmation enters `AMBIGUOUS`.

For the target 1.21.11 implementation, teleport confirmation is the modeled correction acknowledgement. No unverified generic transaction mechanism is claimed.

## Velocity timing

`Velocity` creates a synchronization window because server packet arrival is not the same as the client tick on which knockback is processed. Live Phase 6 receives the velocity transition on each still-possible client simulation tick represented by Phase 7.

## World-state timing

Historical client-visible world state remains owned by `World.VisibilityHistory`. World updates receive an explicit `WORLD_UPDATE` timing window. When timing is ambiguous, LiveValidation marks the Phase 6 world hypothesis non-exhaustive, so Phase 6 returns `UNCERTAIN` rather than using the latest server world as an assumed historical client world.

## Synchronization states and recovery

The model exposes:

- `UNKNOWN` — no client/server timing anchor exists;
- `PARTIALLY_SYNCHRONIZED` — a relative anchor exists but strong timing is not established;
- `SYNCHRONIZED` — configured timing constraints are satisfied by clean observations;
- `AMBIGUOUS` — timing/chronology is uncertain without a pending correction;
- `RECOVERING` — correction, gap, or synchronization disruption requires stable observations.

`SynchronizationWindow` records the event type, server-tick span, possible client ticks, trigger sequence, and explanation. Recovery is explicit and never returns to strong synchronization from a single packet.

## Replay and determinism

`Phase7Replay` wraps the existing fixed `Timeline.Codec` with the complete immutable Phase 7 configuration. Reconstructing a given replay with the same configuration produces identical timing ranges, synchronization states, windows, and consistency. `Phase7PerformanceBenchmark` measures a deterministic synthetic 10,000-event workload.

## Phase 6 boundary

Phase 7 does not duplicate reachable-state simulation. `Phase7Timing.toPhase6Window(...)` exposes the same bounded timing envelope already accepted by `Phase6Reachability.searchWithinTimingWindow(...)`. The live validator reconstructs Phase 7 first and then delegates reachability to the authoritative Phase 6 engine.

## Empirical limitation

The repository still lacks independent real Minecraft Java 1.21.11 client timing traces. Therefore the timing model is deterministic and internally regression-tested, but real-client packet-generation timing, tick phase, acknowledgement delay, jitter distribution, and final numeric parity remain external validation items that cannot be manufactured without launching/capturing the real client.
