# Phases 1–8 requirements audit
Audit date: 2026-09-17. Phase 5 implementation is complete except for the external real-client corpus; this pass advances Phase 6.

## Summary
| Phase | Status | Main limitation |
|---|---|---|
| 1 | PARTIAL | Protocol coverage, packet-payload timing, and adversarial corpus are incomplete |
| 2 | PARTIAL | Attribute/pose provenance and correction metadata remain outside the immutable Player record |
| 3 | PARTIAL | Downstream world/timing/validation result capture is not one complete replay artifact |
| 4 | PARTIAL | Exact client-payload decoding and exhaustive 1.21.11 shapes remain incomplete |
| 5 | PARTIAL / BLOCKED BY EXTERNAL DATA | Empirical capture workflow is implemented; no actual 1.21.11 vanilla corpus has been captured in this environment |
| 6 | IN PROGRESS | Finite reachability, timing-window search, and explainable evidence are implemented and internally tested; empirical vanilla validation still depends on Phase 5 traces |
| 7 | PARTIAL | Not advanced in this pass |
| 8 | PARTIAL | Not advanced in this pass |

## Phase 5 requirement-by-requirement audit

| Requirement | Classification | Evidence in repository | Remaining blocker |
|---|---|---|---|
| Deterministic physics/collision/replay | IMPLEMENTED / INTERNALLY TESTED | `Simulation.Vanilla12111Physics`, shared resolver, Phase 5 tests | VANILLA VALIDATED evidence |
| Acceleration/friction/gravity/jump/basic collision | IMPLEMENTED / INTERNALLY TESTED | `Simulation`, existing tests | Exact vanilla first-divergence comparison |
| Sprint/sneak transitions | IMPLEMENTED / INTERNALLY TESTED | `AdvancedInput`, simulator speed path | Actual client trace |
| Player pose transitions | IMPLEMENTED / INTERNALLY TESTED | `Phase5Mechanics.Pose`, `nextPose`, `StepResult.pose` | Full client pose/dimension timing trace |
| Swimming-related pose/state transitions | IMPLEMENTED / INTERNALLY TESTED | submerged/swimming branch | Actual client trace |
| Movement-affecting effects | IMPLEMENTED / INTERNALLY TESTED | `MovementEffects` | Actual client ordering/numeric trace |
| Vanilla attribute modifier semantics | IMPLEMENTED / INTERNALLY TESTED | three-operation order; regression fixed after Maven test exposed base-stage bug | Actual client numeric trace |
| Water movement | IMPLEMENTED / INTERNALLY TESTED | explicit fluid environment seam | Actual client trace |
| Lava movement | IMPLEMENTED / INTERNALLY TESTED | explicit fluid environment seam | Actual client trace |
| Ladders/vines/climbables | IMPLEMENTED / INTERNALLY TESTED | climbable state and vertical branch | Actual client trace |
| Knockback | IMPLEMENTED / PARTIAL | `Knockback`, packet-event capture hook | Empirical packet→state alignment |
| Step behavior | IMPLEMENTED / PARTIAL | shared resolver and trace fields | Empirical per-tick step reconstruction |
| Collision ordering | IMPLEMENTED / PARTIAL | shared axis clipping | Empirical first-divergence evidence |
| Teleport/server correction recovery | IMPLEMENTED / PARTIAL | `CorrectionRecovery`, correction packet observation | Empirical client correction/confirmation sequence |
| Original 46-column trace schema | IMPLEMENTED / INTERNALLY TESTED | `Phase5TraceTool` | It does not represent unknown observations |
| Extended empirical trace schema | IMPLEMENTED / INTERNALLY TESTED | `Phase5VanillaTrace`, 47th `missing_fields` column | Actual capture corpus |
| Observation-only vanilla capture harness | IMPLEMENTED | `tools/vanilla-trace-capture`, post-tick state hook + packet observation hook | Must be run against licensed vanilla 1.21.11 |
| Trace import/validation | IMPLEMENTED / INTERNALLY TESTED | `Phase5VanillaTrace.read/validate` | Actual captured files |
| First-divergence diagnostics | IMPLEMENTED / INTERNALLY TESTED | `Phase5TraceTool.firstDivergence` | More fields become comparable as observations become complete |
| Mechanic-combination matrix | IMPLEMENTED / INTERNALLY TESTED | existing Phase 5 matrix covers 3,840 combinations | Independent vanilla matrix |
| Required vanilla scenario corpus | PARTIAL / BLOCKED BY EXTERNAL DATA | `docs/phase5-vanilla-corpus/scenarios.tsv` contains the controlled scenario checklist | Actual trace files |
| Independent vanilla reference comparison | BLOCKED BY EXTERNAL DATA | Capture harness and importer are present | Run exact Minecraft Java 1.21.11 client and preserve traces |

## Phase 6 requirement-by-requirement audit

| Requirement | Classification | Evidence in repository | Remaining work |
|---|---|---|---|
| Finite one-tick input reachability | IMPLEMENTED / INTERNALLY TESTED | `Validation.ReachableStates.next` | Validate against independent 1.21.11 traces |
| Complete declared discrete input envelope | IMPLEMENTED / INTERNALLY TESTED | `Validation.allInputs`, 72 `AdvancedInput` combinations | Empirical client-input confirmation |
| Sprint/sneak-aware observed-input reachability | IMPLEMENTED / INTERNALLY TESTED | `ReachableStates.next(...ClientInput)` and `nextAdvanced` | Empirical client-input confirmation |
| Multi-tick finite reachability | IMPLEMENTED / INTERNALLY TESTED | `advanceAdvanced` with exact-state merging | Larger real trace sequences |
| Exhaustive candidate-budget discipline | IMPLEMENTED / INTERNALLY TESTED | budget overflow returns `UNCERTAIN` and never exposes a sampled subset | Operational tuning from real workloads |
| Timing-window enumeration | IMPLEMENTED / INTERNALLY TESTED | `advanceWithinWindow` | Derive windows from richer live timing observations |
| Wide-window safety | IMPLEMENTED / INTERNALLY TESTED | bounded timing-search envelope returns `UNCERTAIN` | Tune envelope from production timing data |
| Explainable POSSIBLE/UNCERTAIN/IMPOSSIBLE evidence | IMPLEMENTED / INTERNALLY TESTED | `Validation.validate` overloads and reasons | Feed complete real-client observations |
| Synchronization uncertainty cannot become IMPOSSIBLE | IMPLEMENTED / INTERNALLY TESTED | `validate(observed, reachable, synchronization)` | Validate through end-to-end live timing captures |

## Empirical evidence rule

`IMPLEMENTED` and `INTERNALLY TESTED` are not equivalent to `VANILLA VALIDATED`. A behavior becomes `VANILLA VALIDATED` only after a real Minecraft Java Edition 1.21.11 client trace is imported and compared. Mappings, decompiled code, documentation, and simulator-generated traces remain explanatory or regression evidence only.

## Current external dependency

The execution environment used to make repository changes cannot launch the user's licensed Minecraft client or provide a GUI/game session. The real 1.21.11 corpus remains a Phase 5 external dependency and is intentionally deferred while Phase 6 development proceeds.

## Phase 6 closure condition

Do not mark Phase 6 COMPLETE until its finite reachability and timing-window behavior has been exercised against representative independent 1.21.11 traces and any first-divergence issues have regression coverage. Phase 7–8 remain separate milestones.
