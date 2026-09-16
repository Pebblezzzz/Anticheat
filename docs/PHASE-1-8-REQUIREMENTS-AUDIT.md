# Phases 1–8 requirements audit
Audit date: 2026-09-16. This is a closure audit, not a completion claim. `COMPLETE` means the behavior is implemented and adequately covered for the declared scope; independent vanilla parity is listed separately.

## Summary
| Phase | Status | Main limitation |
|---|---|---|
| 1 | PARTIAL | Protocol coverage, packet-payload timing, and adversarial corpus are incomplete |
| 2 | PARTIAL | Attributes, pose, environment provenance, and correction metadata are incomplete |
| 3 | PARTIAL | Downstream world/timing/validation result capture is not one complete replay artifact |
| 4 | PARTIAL | Exact client-payload decoding and entity collision are incomplete |
| 5 | PARTIAL / UNVERIFIED | Explicit deterministic step model and trace ingestion exist; independent 1.21.11 parity and several mechanics remain unverified |
| 6 | PARTIAL | Conservative bounded search exists; timing/collision uncertainty envelope is incomplete |
| 7 | PARTIAL | No complete client-tick/acknowledgement reconstruction |
| 8 | PARTIAL | Continuous adapter exists; evidence still depends on unverified Phase 5 |

## Phase 1
| Requirement group | Status | Implementation/tests | Limitation/independent validation |
|---|---|---|---|
| Typed packets, provenance, sequence, ordering | COMPLETE | `Packets`, `Timeline`; `CoreTest`, `PacketReplayTest` | Only modeled packet variants are covered |
| Duplicate/out-of-order/gaps/delays | COMPLETE | `Packets.Normalizer`; `PacketReplayTest` | No large external adversarial capture corpus |
| Server ticks, epoch, multiple packets/tick | COMPLETE | `Timeline.assign`; timeline tests | Server arrival projection is not a client tick model |
| No silent loss/replay ordering | COMPLETE | `Timeline.Codec`; byte-stability/corruption tests | Capture adapter coverage remains limited |
| Platform isolation | COMPLETE | `ArchitectureTest`; Paper adapter package | Live PacketEvents capture still needs field validation |

## Phase 2
| Requirement group | Status | Implementation/tests | Limitation/independent validation |
|---|---|---|---|
| Immutable position/rotation/velocity/ground/gamemode/effects | COMPLETE | `State.Player`; `PlayerStateTest`, `CoreTest` | Attribute and pose fields are absent |
| Input and teleport/correction barrier | PARTIAL | `State.apply`, teleport tests | Correction metadata and client tick provenance are incomplete |
| Environment/synchronization/provenance | PARTIAL | `Seed`, `StateFrame`, `Fact`, `Environment` | Environment is mostly explicit unknown, not fully reconstructed |
| Contradictions/missing data/uncertainty | COMPLETE | uncertainty tests | Contradictions are represented mainly by one boolean rather than field-level confidence |
| Deterministic history | COMPLETE | `State.reconstruct`, `Replay`; replay tests | Downstream synchronization is not in the state artifact |

## Phase 3
| Requirement group | Status | Implementation/tests | Limitation/independent validation |
|---|---|---|---|
| Versioned strict packet/timeline capture | COMPLETE | `Timeline.Codec`; corruption tests | Schema does not yet include full validation output |
| State and replay determinism | PARTIAL | `Replay`, first divergence tests | World/sync/validation references are not serialized as a single result |
| First divergence and regression corpus | PARTIAL | `Replay.firstDivergence`, `Simulation.firstDivergence` | No independent long-running capture corpus |

## Phase 4
| Requirement group | Status | Implementation/tests | Limitation/independent validation |
|---|---|---|---|
| Immutable per-player snapshots and coverage | COMPLETE | `WorldSnapshot`, `VisibilityHistory`; world tests | Exact client packet decoding is not complete |
| Known/unloaded/unsupported vs air | COMPLETE | `Coverage`, world query tests | Adapter may still lack exact payload facts |
| State-dependent and neighbor-dependent shapes | PARTIAL | block catalogue and geometry tests | Catalogue is not exhaustive for every 1.21.11 state |
| AABB/voxel, slabs/stairs/fences/walls/panes/doors/gates | PARTIAL | `BlockShapeTest`, `WorldQueryTest` | Remaining shapes and exact parity require independent data |
| Fluids/environment/entity collision | PARTIAL | world model/environment tests | Live entity tracking and full fluid behavior remain incomplete |

## Phase 5
| Requirement group | Status | Implementation/tests | Limitation/independent validation |
|---|---|---|---|
| Deterministic physics/collision/replay | PARTIAL | `Vanilla12111Physics.step`, `Simulation.TickContext`, `SimulationTickTest`, trace tests | Internal determinism is tested; external parity is unverified |
| Acceleration/friction/gravity/jump/basic collision | IMPLEMENTED BUT UNVALIDATED | `Simulation`; `SimulationTickTest`, core/trace tests | Model constants and collision ordering require independent 1.21.11 reference traces |
| Sprint/sneak/pose/effects/attributes | PARTIAL | `Simulation.AdvancedInput`, `Simulation.Attributes`, `Phase5MechanicsTest` | Sprint/sneak and base speed input are explicit; pose/effects/full modifiers remain incomplete and all require vanilla traces |
| Fluids/climbables/slopes/knockback/modes | PARTIAL | `Simulation.Environment`, `PhysicsContext`, `Phase5MechanicsTest` | Explicit environment branches exist; exact fluid/climbable/knockback/mode behavior remains unvalidated |
| Teleports/corrections/velocity | PARTIAL | `State`, packet model | Full client physics interaction is unverified |
| Independent reference traces | PARTIAL | Expanded `TraceExchange`, `Simulation.firstDivergence`, `docs/PHASE-5-TRACE-VALIDATION.md` | No independently captured 1.21.11 reference corpus is checked in |

## Phase 6
| Requirement group | Status | Implementation/tests | Limitation/independent validation |
|---|---|---|---|
| Input branching and possible states | PARTIAL | `Validation.ReachableStates`, `CoreTest` | Input model is discrete and incomplete |
| Possible/uncertain/impossible | COMPLETE | `Validation`, validator tests | Soundness depends on Phase 5 model |
| Candidate merging/budget metrics | PARTIAL | exact set merging and metrics | Budget sampling is conservative but not a complete abstract-state lattice |
| Timing/world/state uncertainty | PARTIAL | uncertainty propagation tests | Phase 7 timing model is incomplete |
| Deterministic replay/performance | PARTIAL | deterministic tests and metrics | No production stress/memory benchmark corpus |

## Phase 7
| Requirement group | Status | Implementation/tests | Limitation/independent validation |
|---|---|---|---|
| Server arrival windows and uncertainty | PARTIAL | `Validation.SyncWindow`, timing tests | This is not complete client tick reconstruction |
| Latency variation/delays/reordering | PARTIAL | packet flags and sync window | No transaction/ack-derived estimator |
| Teleports/corrections/velocity | PARTIAL | packet/state barriers | Recovery model is incomplete |
| Phase 6/8 integration | PARTIAL | live evidence path | Adapter currently uses conservative finding-derived sync metadata |

## Phase 8
| Requirement group | Status | Implementation/tests | Limitation/independent validation |
|---|---|---|---|
| Simulation/reachability comparison and verdicts | PARTIAL | `LiveValidation`, `Validation`; live tests | Observed velocity and complete timing are unavailable in some packets |
| Structured evidence/confidence/aggregation | COMPLETE | `OperatorValidation`; `OperatorValidationTest` | Confidence is policy evidence, not vanilla proof |
| Continuous alerts/debounce/no uncertain alerts | PARTIAL | Paper scheduler, operator tests | Requires live integration testing on Paper |
| Replayable detections and synthetic impossible cases | PARTIAL | live regression tests | Full downstream validation artifact is not serialized |
| No core punishment | COMPLETE | core contracts/tests | Paper kick policy is separate and disabled by default in the checked-in config |

## Watchdog correction
The previous implementation ran `LiveValidation.analyze`, including repeated `World.VisibilityHistory.at` map reconstruction, on the Paper server thread. That caused the watchdog dump at `World.Snapshot`/`LiveValidation`.

The adapter now performs immutable core validation on a Bukkit async scheduler, guards against overlapping validation per capture, and schedules only operator notification/kick operations back on the server thread. Chunk decoding remains bounded on the server thread but is still a known performance limitation and should be replaced with exact client-payload decoding for production.

## Grim comparison
See [`GRIM-COMPARISON.md`](GRIM-COMPARISON.md). Grim's predictive movement, compensated per-player world, version-specific collision, and buffered evidence principles are relevant. Phantom intentionally retains clean-room deterministic contracts and explicit `UNCERTAIN`; it does not claim equivalence. No Grim source code was copied.
