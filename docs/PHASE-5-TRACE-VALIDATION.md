# Phase 5 trace-validation contract
## Scope and status
`Simulation.Vanilla12111Physics` is a deterministic, version-isolated movement model. It is **not** declared 1:1 vanilla: this repository does not contain an independently captured Minecraft Java 1.21.11 client trace corpus. `TraceExchange` is an ingestion and comparison format for such traces; simulator-generated traces are not valid reference evidence.

## Explicit step model
`Simulation.TickContext` contains:

- explicit simulation tick;
- immutable prior `State.Player`;
- immutable `Simulation.Input`;
- immutable legacy `World.Snapshot`.

`Vanilla12111Physics.step` returns `Simulation.StepResult`, including the tick, resulting state, collision indication, and a bounded diagnostic. The pure `PhysicsEngine.tick` contract remains available for existing callers and delegates to the same step implementation.

The world is checked before and across the swept player volume. `UNKNOWN`/unloaded/unsupported coverage produces an uncertain state; it is never treated as air. Collision displacement is resolved through `World.resolveWithStep`; Phase 5 does not contain a second block-shape model.

## Mechanics matrix
| Mechanic | Current status | Evidence/limitation |
|---|---|
| Explicit tick, input, rotation, prior/result state | Implemented internally | `SimulationTickTest`; no external client-tick corpus |
| Basic acceleration and directional rotation | Implemented, unvalidated | `Vanilla12111Physics`; constants require reference traces |
| Gravity and basic jump | Implemented, unvalidated | `CoreTest`, `SimulationTickTest`; not vanilla parity evidence |
| Ground/floor/ceiling/wall clipping | Implemented through Phase 4 resolver, unvalidated | collision tests; exact ordering still needs reference traces |
| Step attempt | Implemented through Phase 4 resolver, unvalidated | step regression tests; no vanilla trace |
| Slabs/stairs/fences/walls/panes/doors/gates | Phase 4 shape support is partial | unsupported states remain uncertain; catalogue is not exhaustive |
| Sprint/sneak/pose | Sprint/sneak are explicit `AdvancedInput` fields; pose remains missing | Sprint/sneak transitions are deterministic inputs but not vanilla-validated; pose still requires Phase 2 data |
| Fluids/climbables/swimming | Explicit environment input supports dry/water/lava/climbable branches | Factors are model inputs and remain unvalidated; exact fluid/climbable behavior requires real 1.21.11 traces |
| Effects/attributes/modifiers | Explicit movement-speed `Attributes` input exists | Effects and full attribute modifier semantics remain missing; no vanilla validation |
| Velocity/knockback | Explicit prior velocity and velocity packet state are consumed | Knockback impulse semantics and client response remain unvalidated; no independent trace |
| Teleport/correction/mode transitions | State barriers exist; physics interaction is partial | correction recovery needs client traces |
| Independent vanilla reference comparison | Framework implemented, evidence unavailable | `TraceExchange`, `Simulation.firstDivergence`; no fabricated corpus |

## Comparison contract
External traces must be captured independently and must include the expanded `TraceExchange.HEADER` fields. `TraceExchange.read` rejects malformed rows, non-increasing ticks, invalid numbers, and wrong schemas. `TraceExchange.compare` delegates to the first-divergence comparator; a mismatch is reported at the first tick rather than only at final state.

A future reference corpus must record the exact initial state, world snapshot identity, input, timing model, and version. Without those facts, a position trace alone cannot establish vanilla parity.

## Why this is not declared complete
The implementation now has an explicit deterministic step seam, explicit sprint/sneak/environment/attribute inputs, and a richer external-trace format. Pose, effect modifiers, full attribute semantics, exact fluid/climbable behavior, knockback semantics, and independently measured 1.21.11 behavior remain unverified or incomplete. Passing deterministic tests does not establish vanilla parity; real client traces are still required. No generic flight or speed threshold is permitted as a substitute.
