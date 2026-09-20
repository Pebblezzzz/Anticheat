# Grim-Inspired Engineering Plan (Clean-Room)

This document records engineering practices worth adopting from mature predictive anticheat implementations while keeping Phantom's own causal/reachability architecture. It does not copy Grim source code.

## Goals

1. Treat the vanilla client as the behavioral authority.
2. Keep all movement mechanics in one version-pinned physics authority.
3. Maintain a per-player client-visible world, not merely the current server world.
4. Model packet delivery, acknowledgement, latency, and corrections as causal state.
5. Make predictions explainable and replayable.
6. Turn every discovered vanilla discrepancy into a regression case.
7. Keep unsupported or incompletely observed mechanics as uncertainty.
8. Prefer real-client differential traces over tests that only exercise Phantom against itself.

## Target architecture

```
server/client packets
      |
      v
ordered timeline
      |
      +--> client knowledge / acknowledgements
      |
      +--> per-player compensated world
      |
      +--> client/entity/inventory state
      |
      v
version-pinned vanilla physics
      |
      +--> candidate input/state transitions
      +--> uncertainty envelope
      |
      v
reachable-state frontier
      |
      v
observed state comparison
      |
      v
provenance-bearing evidence
      |
      +--> POSSIBLE
      +--> UNCERTAIN
      +--> IMPOSSIBLE
```

## What to adopt from mature predictive implementations

### Player-centric state

A player's validation context should own the state required to reproduce that player's client view:

- network/timeline state;
- client movement state;
- compensated world;
- entity history;
- inventory/equipment effects;
- physics state;
- prediction frontier;
- synchronization state.

Do not make individual checks reconstruct these independently.

### Client-visible world

The authoritative simulation world is the world the client could have known, at the relevant causal time.

World changes therefore follow:

```
server mutation -> packet emission -> delivery/acknowledgement -> client-visible mutation
```

The hot path must never silently substitute the current Bukkit/Paper world for a missing historical client snapshot.

### Version-specific physics

Physics constants, collision ordering, bounding boxes, and mechanics belong to a versioned profile. Do not share constants across Minecraft versions unless the equivalence has been demonstrated.

### Explainable prediction

Every validation result should be capable of explaining:

- observation tick;
- client-state inputs;
- world generation/snapshot;
- candidate count;
- accepted/rejected candidate reasons;
- closest candidate and error;
- uncertainty causes;
- external correction/teleport/velocity provenance.

### Differential validation

The development loop is:

```
real vanilla client
    -> trace
    -> Phantom replay
    -> first divergence
    -> root-cause fix
    -> regression test
```

A unit test is not evidence of vanilla parity when both the test fixture and implementation encode the same assumption.

## What not to copy

- Grim source code.
- Undocumented constants merely because another implementation uses them.
- A single predicted state when multiple states are causally possible.
- Threshold checks that bypass the reachability model.
- Enforcement decisions inside the physics engine.

## Definition of done for a mechanic

A mechanic is production-ready only when it has:

1. a version-pinned implementation;
2. documented vanilla provenance;
3. real-client traces;
4. deterministic replay;
5. differential comparison;
6. uncertainty behavior for missing information;
7. adversarial/regression tests;
8. explainable diagnostics.

## Suggested mechanics order

walking -> sprinting -> jumping -> friction -> step-up -> block collision -> slopes/shapes -> fluids -> climbing -> knockback -> entities -> vehicles -> status effects -> special movement.

Do not expand version coverage until the selected version has a strong differential corpus.
