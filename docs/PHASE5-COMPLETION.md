# Phase 5 — Vanilla 1.21.11 Movement Completion Contract

Phase 5 is the deterministic movement layer. It converts an explicit pre-tick player state, input, visible world snapshot, movement environment, attributes, effects, pose, and authoritative correction state into exactly one next state.

## Required state inputs

- Position, velocity, rotation and on-ground state.
- Survival/adventure/non-physical gamemode state.
- Forward/strafe/jump/sprint/sneak input.
- Player pose and pose-specific collision volume.
- Visible block/world geometry with unknown or unsupported geometry propagated as uncertainty.
- Water/lava/climbable/gliding movement environment.
- Movement-speed attributes and ordered modifiers.
- Speed, slowness, jump boost, levitation and slow-falling effects.
- Authoritative velocity/knockback and teleport/correction barriers.

## Movement model

### Ground and air

The integrator applies deterministic acceleration, friction/drag, gravity, jump launch, landing and collision resolution. Diagonal input is normalized rather than treated as two independent full-strength axes.

### Poses

Standing, crouching, swimming, fall-flying and sleeping are explicit states. Pose selection is checked against the proposed collision volume so a transition that would place the player inside known solid geometry is rejected.

### Fluids

Water and lava remain separate movement paths. Their drag and gravity factors are explicit environment inputs rather than inferred from a generic fluid rule. The real 1.21.11 client exposes distinct water and lava travel methods; Phase 5 keeps that distinction in the model.

### Climbables

Ladder/vine-like movement has a bounded vertical velocity. Forward input controls vertical climb motion, and downward descent is capped rather than allowing unconstrained falling while attached to a climbable surface.

### Gliding

Fall-flying is an explicit movement mode with its own horizontal drag and bounded downward acceleration rather than ordinary airborne gravity.

### Effects and attributes

Movement-speed modifiers support additive, base-multiplied and total-multiplied operations. Speed, slowness, jump boost, levitation and slow-falling are explicit simulation inputs.

### External impulses

Observed server velocity/knockback is represented as an explicit velocity impulse. It is applied before subsequent deterministic movement integration instead of being treated as unexplained motion.

### Corrections

Teleport/correction state establishes an authoritative barrier. Motion is not integrated through an outstanding correction confirmation. Matching confirmation clears the barrier; a mismatched confirmation does not.

## World and collision requirements

The collision layer must distinguish full blocks, partial blocks, slabs, stairs, edges/corners and step-up resolution. Unknown/unloaded or unsupported geometry is never treated as air for validation.

## Empirical reference requirements

The canonical reference is an observation corpus captured from a real, unmodified Minecraft Java 1.21.11 client. Missing telemetry remains explicitly marked missing. Synthetic values are not used as empirical observations.

The capture/audit pipeline validates:

1. Client version and source identity.
2. Complete required phase labels.
3. Survival-mode capture purity.
4. Fluid and pose state transitions.
5. Effect and attribute observations.
6. Jump and step trajectories.
7. Replay through the deterministic simulator for states where the trace preserves enough causal information.
8. Explicitly skips underdetermined fluid/jump-transition replay rather than masking them with broad tolerances.

## Completion gate

Phase 5 is complete only when all of the following remain green:

- Full Maven regression suite.
- Phase 5 mechanics acceptance tests.
- Vanilla 1.21.11 batch capture audit.
- Vanilla-to-Phantom replay audit for causally reconstructable rows.
- No fabricated telemetry.
- No broad replay tolerance used to hide simulator divergence.
- Unknown world/correction state propagates as uncertainty.

Phase 6 must consume this layer as a deterministic primitive; it must not compensate for missing Phase 5 movement mechanics.
