# Phase 0 architecture contract

Target model: **Minecraft Java 1.21.11** only. A mechanic may not borrow a
constant, packet interpretation, or collision shape from another version.

```text
platform adapter
  -> raw packets (sequence + monotonic receive time)
  -> normalized packets
  -> deterministic timeline
  -> [player-state reducer | per-player client-world history]
  -> version-isolated physics + collision
  -> reachable-state search + synchronization envelope
  -> observed-vs-reachable evidence
  -> diagnostics / future enforcement policy
```

## Ownership and contracts

`Contracts` defines the stable interfaces used across boundaries:

| Layer | Owns | Must not own |
|---|---|---|
| Packet normalizer | packet ordering markers and source metadata | player state or server ticks |
| Timeline reconstructor | deterministic server-tick projection | client simulation ticks or latency conclusions |
| Player-state reducer | packet-derived state and explicit uncertainty | world lookups or physics |
| Client-world history | outbound client-visible chunks and block deltas | Bukkit live-world reads in core |
| Collision resolver | AABB displacement against an immutable snapshot | movement constants or input |
| Physics engine | one version's deterministic tick transition | clocks, packets, or mutable worlds |
| Reachability engine | exhaustive bounded candidate sets | silently pruning legitimate branches |
| Synchronizer | a possible client-tick interval | cheat verdicts |
| Validator | evidence from declared candidates | policy/punishment |
| Paper adapter | PacketEvents/Paper translation only | physics, collision, or evidence rules |

## Non-negotiable invariants

1. Core packages must not import Paper, Bukkit, PacketEvents, clocks, file I/O, or
   mutable server world state. Only `dev.phantom.ac.paper` may use platform APIs.
2. A normalized packet is never discarded for being duplicate, delayed, or
   out of order. Its ambiguity remains in the timeline and player state.
3. The world model describes what this client could have received. An unseen
   chunk is `UNKNOWN`, not air. A received but unimplemented block is
   `UNSUPPORTED`, not a guessed shape.
4. All core transitions are pure functions over immutable values. Replaying the
   same timeline and world history must reproduce the same result.
5. Candidate-budget exhaustion and unsupported mechanics produce `UNCERTAIN`.
   They cannot be converted into `IMPOSSIBLE`.
6. `IMPOSSIBLE` means no state in the explicitly declared simulation envelope
   matched; it is evidence only. Enforcement requires independent 1.21.11
   trace validation for the relevant mechanic.
7. The first observed movement is an anchor unless a prior client state is
   established. It is not retroactive movement evidence.

## Completion boundary for Phase 0

Phase 0 is complete when these contracts are public, the production classes
implement them, the no-platform-leak invariant is tested, immutable value
boundaries are tested, and a clean build passes. It does **not** assert that
physics, collision, synchronization, or validation are feature-complete;
those are later phases.
