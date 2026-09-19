# Phase 5 — deterministic vanilla movement authority

## Purpose

Phase 5 is the sole movement transition authority. It consumes a reconstructed client state, explicit client input, the client-visible Phase 4 world, environment facts, movement attributes/effects, pose, movement mode, entity-collision facts, and a simulation tick. It returns one deterministic next state plus collision and diagnostic information.

It does not perform reachability search, timing inference, violation scoring, alerts, punishments, or other anti-cheat decisions.

## Target

The implementation is explicitly pinned to **Minecraft Java Edition 1.21.11**. The core simulator is independent of Bukkit/Paper and rejects a world snapshot for another model version.

The public 1.21.11 Yarn mappings expose distinct vanilla travel paths for air, fluids, flying and gliding, and expose movement helpers such as `applyMovementInput`, `getMovementSpeed(float)`, and `applyFluidMovingSpeed`. The implementation follows those conceptual boundaries rather than treating movement as a single speed threshold.

## Deterministic inputs

`Phase5MovementAuthority.SimulationContext` is the stable API:

- simulation tick;
- immutable `State.Player`;
- forward/strafe/jump/sprint/sneak input;
- 1.21.11 `WorldSnapshot`;
- explicit movement environment;
- movement attributes and ordered modifiers;
- movement effects;
- pose;
- sleeping/flying mode state;
- deterministic entity collision provider.

No wall-clock time, random value, mutable global state, thread scheduling, server TPS, Bukkit physics, or unordered iteration is used by the authority.

## Movement model

The 1.21.11 model now has explicit paths for:

- ground and air acceleration;
- input normalization and yaw rotation;
- sprint and sneak scaling;
- jump impulse and sprint-jump impulse;
- gravity before collision movement and post-move vertical drag;
- water and lava movement;
- climbing;
- fall-flying;
- creative flight;
- spectator no-physics movement;
- collision-safe pose transitions;
- speed, slowness, jump boost, levitation and slow falling;
- externally applied velocity already reconstructed into `PlayerState`;
- teleport/correction barriers already reconstructed into `PlayerState`.

Vanilla float constants are represented with explicit float-to-double widening where the client uses float literals, preserving Java's rounding behavior.

## Collision

Phase 4 `WorldSnapshot` remains the authoritative client-visible collision source. Phase 5 does not contain a second block-shape catalogue.

The resolver consumes Phase 4 voxel shapes, preserves UNKNOWN/UNLOADED/UNSUPPORTED coverage, resolves Y first and then the larger horizontal movement axis before the smaller horizontal axis, and exposes deterministic step/collision diagnostics. Partial shapes therefore remain geometry facts rather than tolerance bands.

Entity movement collision is a separate Phase 4 provider seam. An incomplete entity list produces uncertainty instead of pretending the missing entities are absent.

## Unknown world

Missing collision information is never converted to air. A simulation whose required world volume is not fully known returns the unchanged state marked uncertain, with a diagnostic describing the missing authority.

## Attributes and effects

Attribute modifiers are applied in the required operation sequence:

1. ADD_VALUE
2. ADD_MULTIPLIED_BASE
3. ADD_MULTIPLIED_TOTAL

Modifier input is canonicalized by stable identifier for replay determinism.

Effects are explicit simulation inputs. `MovementEffects.fromStateEffects` maps the reconstructed effect state into movement-relevant Speed, Slowness, Jump Boost, Levitation and Slow Falling behavior.

## Corrections and velocity

Teleport corrections remain hard simulation barriers through `PlayerState.awaitingTeleport`. A pending correction is not simulated through.

Velocity packets are represented by the reconstructed velocity state rather than by a separate speed exception. `Phase5Mechanics.applyVelocityImpulse` and `Knockback` retain explicit provenance at the Phase 5 boundary.

## Validation status

### IMPLEMENTED

- deterministic authority API;
- 1.21.11 version gate;
- ground/air acceleration and friction;
- sprint/sneak;
- jump and sprint jump;
- gravity/drag;
- pose transitions;
- water/lava/climb/glide mode separation;
- movement effects;
- deterministic attribute modifier ordering;
- creative/spectator movement modes;
- Phase 4 collision integration;
- unknown-world propagation;
- correction barriers;
- first-divergence trace infrastructure;
- deterministic regression coverage.

### INTERNALLY TESTED

The Maven test suite contains deterministic movement, collision, pose, replay, trace and Phase 5 regression tests, including new authority tests for gravity ordering, unknown-world propagation and explicit movement modes.

### TARGETED VANILLA VALIDATED

Existing repository traces labelled as independent 1.21.11 observations remain targeted evidence only. They are not reclassified as full empirical parity by source inspection.

### PARTIAL / NOT EMPIRICALLY VALIDATED

The repository does not claim a complete independent real-client 1.21.11 corpus for every mechanic. In particular, fall-flying, fluid edge cases, climbable transitions, exact step candidate parity, entity-push/riding interactions, and some pose transitions still require targeted real-client regression captures before being labelled empirically validated.

## First divergence

Trace comparison remains the preferred debugging loop:

real client observation -> earliest meaningful divergence -> identify input/timing/world/collision/physics cause -> fix source mechanic -> add regression.

A tolerance increase is not an acceptable substitute for a first-divergence investigation.

## Performance

The existing Phase 5 benchmark remains in place. The authority is allocation-light at the API boundary and reuses the immutable world snapshot; repeated simulation does not rebuild Phase 4 block geometry.

Performance is measured by the existing benchmark rather than by an arbitrary target. Correctness remains the primary requirement.

## Phase boundary

Phase 5 owns the physical state transition.

Phase 6 owns possible-state exploration.

Phase 7 owns temporal synchronization.

Phase 8 owns movement validation and enforcement.

No Phase 6/7/8 movement decision logic is added by this redesign.


<!-- CI rerun after movement-authority baseline restoration. -->
