# Vanilla Physics Plan

## Canonical authority

For Minecraft 1.21.11, `Vanilla12111RichPhysics` remains the sole movement transition authority. Phase 6 and Phase 8 must consume it rather than duplicating movement constants or algorithms.

The existing constants are a starting point, not proof of 1:1 parity.

## Physics profile

The profile makes the dependency on version-pinned constants explicit and gives each constant a provenance slot.

Required categories:

- gravity;
- jump velocity;
- air/ground friction;
- movement acceleration;
- sprint acceleration;
- sprint multiplier;
- sneak multiplier;
- step height;
- input normalization/friction;
- vertical drag;
- water drag;
- water sprint drag;
- lava drag;
- climb limits;
- gliding gravity;
- sprint-jump horizontal boost;
- collision/probe tolerances.

## Algorithm parity matters

Matching a number is insufficient. The implementation must also match:

- operation order;
- floating-point behavior;
- collision axis order;
- collision shape selection;
- block-state-dependent shapes;
- neighbour-dependent shapes;
- step-up resolution;
- fluid handling;
- pose transitions;
- entity collisions;
- status/equipment modifiers;
- client-visible world selection.

## Version profile contract

```text
Minecraft version
    |
    +-- constants
    +-- movement algorithm
    +-- collision rules
    +-- fluid rules
    +-- entity interaction rules
    +-- provenance
    +-- differential corpus
```

## Provenance rule

Every non-trivial constant should eventually have:

- the Minecraft version;
- the vanilla implementation/source location used for derivation;
- the exact numeric representation;
- whether it is an algorithmic constant or a measured value;
- trace scenarios that exercise it;
- differential status: UNVERIFIED / MATCHED / INVESTIGATING.

Grim may be used as an independent comparison point. Vanilla source/behavior remains the authority.

## Differential corpus

The vanilla trace capture tool should grow into a corpus containing:

### Movement
- idle;
- walking;
- sprinting;
- sneaking;
- jumping;
- sprint jumping;
- air control;
- directional changes;
- diagonal input.

### Collision
- full blocks;
- slabs;
- stairs;
- partial voxel shapes;
- corners;
- ceilings;
- walls;
- step-up/step-down;
- neighbouring block shape changes.

### Environment
- water;
- lava;
- climbable blocks;
- cobweb-like slowdown;
- honey/ice-like surfaces;
- unloaded/unknown coverage.

### External transitions
- server velocity;
- knockback;
- teleport;
- correction;
- block changes;
- chunk changes;
- entity interaction.

### State
- pose transitions;
- gamemodes;
- status effects;
- equipment/inventory modifiers;
- vehicles.

## Acceptance criteria

For each corpus case, record:

- first divergent tick;
- position error;
- velocity error;
- on-ground disagreement;
- collision disagreement;
- world snapshot identity;
- uncertainty/provenance;
- candidate frontier size.

No aggregate accuracy number should hide first-divergence failures.
