# Phases 1–8 requirements audit
Audit date: 2026-09-17. Phase 5 implementation is complete except for the external real-client corpus; this pass completes the Phase 6 implementation and regression layer.

## Summary
| Phase | Status | Main limitation |
|---|---|---|
| 1 | PARTIAL | Protocol coverage, packet-payload timing, and adversarial corpus are incomplete |
| 2 | PARTIAL | Attribute/pose provenance and correction metadata remain outside the immutable Player record |
| 3 | PARTIAL | Downstream world/timing/validation result capture is not one complete replay artifact |
| 4 | PARTIAL | Exact client-payload decoding and exhaustive 1.21.11 shapes remain incomplete |
| 5 | PARTIAL / BLOCKED BY EXTERNAL DATA | Empirical capture workflow is implemented; no actual 1.21.11 vanilla corpus has been captured in this environment |
| 6 | IMPLEMENTED / INTERNALLY TESTED / BLOCKED BY EXTERNAL DATA | Code-level finite reachability, uncertainty handling, timing search, provenance, rich-world integration, live integration, replay artifact, and regression coverage are implemented; only real-client empirical validation remains |
| 7 | PARTIAL | Not advanced in this pass |
| 8 | PARTIAL | Not advanced in this pass |

## Phase 6 requirement-by-requirement audit

| Requirement | Classification | Evidence | Remaining external dependency |
|---|---|---|---|
| Finite one-tick reachability | IMPLEMENTED / INTERNALLY TESTED | `Phase6Reachability.search` | Independent 1.21.11 trace comparison |
| Complete 72-state declared input envelope | IMPLEMENTED / INTERNALLY TESTED | `Validation.allInputs`, `InputConstraint.enumerate` | Empirical input confirmation |
| Sprint/sneak-aware reachability | IMPLEMENTED / INTERNALLY TESTED | `InputConstraint`, full `Context` | Empirical client confirmation |
| Partial / unknown input branching | IMPLEMENTED / INTERNALLY TESTED | optional input dimensions + exhaustive enumeration | Empirical payload completeness |
| Multi-tick finite search | IMPLEMENTED / INTERNALLY TESTED | `search` horizon and exact state propagation | Larger real traces |
| Full future-affecting candidate context | IMPLEMENTED / INTERNALLY TESTED | `Phase6Reachability.Context` | Empirical field completeness |
| First-class state uncertainty | IMPLEMENTED / INTERNALLY TESTED | `UncertainDimension` | Real capture field provenance |
| Rich world/collision integration | IMPLEMENTED / INTERNALLY TESTED | `WorldBranch`, `WorldSnapshot`, `Vanilla12111RichPhysics` | Real client/world trace alignment |
| Non-exhaustive world handling | IMPLEMENTED / INTERNALLY TESTED | `WorldBranch.exhaustive` => `UNCERTAIN` | Production world-envelope tuning |
| Candidate merge soundness | IMPLEMENTED / INTERNALLY TESTED | exact full-context map keys | Workload tuning |
| Candidate budget safety | IMPLEMENTED / INTERNALLY TESTED | overflow => `UNCERTAIN`, empty candidates | Production budget tuning |
| No sampled-subset impossible claims | IMPLEMENTED / INTERNALLY TESTED | explicit budget regression | None beyond empirical load measurement |
| Timing-window enumeration | IMPLEMENTED / INTERNALLY TESTED | `searchWithinTimingWindow` | Real RTT/jitter derivation |
| Wide timing-window safety | IMPLEMENTED / INTERNALLY TESTED | >128 offsets => `UNCERTAIN`, zero sampling | Production envelope tuning |
| Corrections / knockback | IMPLEMENTED / INTERNALLY TESTED | external transition types | Empirical packet/state ordering |
| Teleport confirmation safety | IMPLEMENTED / INTERNALLY TESTED | correction + confirmation branch | Real packet sequence |
| Explainable provenance | IMPLEMENTED / INTERNALLY TESTED | `Provenance`, `Evidence.witnesses` | None for code completeness |
| Live engine integration | IMPLEMENTED / INTERNALLY TESTED | `LiveValidation.analyze` delegates to rich engine | Complete real client traces |
| Strong IMPOSSIBLE semantics | IMPLEMENTED / INTERNALLY TESTED | only exhaustive search can return it | Empirical numeric parity |
| Replay artifact | IMPLEMENTED / INTERNALLY TESTED | `Phase6Replay` / `phase6-replay-v1` | None for artifact format |
| Candidate-explosion performance coverage | IMPLEMENTED / INTERNALLY TESTED | `Phase6PerformanceBenchmark` + regression test | Production benchmark corpus |
| Direct ambiguity/impossible regression matrix | IMPLEMENTED / INTERNALLY TESTED | `Phase6SoundReachabilityTest` | Real client scenario matrix |

## Empirical evidence rule

`IMPLEMENTED` and `INTERNALLY TESTED` are not equivalent to `VANILLA VALIDATED`. A behavior becomes `VANILLA VALIDATED` only after a real Minecraft Java Edition 1.21.11 client trace is imported and compared. Simulator-generated traces, documentation, or inferred mappings are not substitutes for that evidence.

## Current external dependency

The development environment used for this implementation cannot launch a licensed Minecraft client or provide the required game-session capture. The observation-only 1.21.11 harness still builds successfully, but running it and importing representative real traces remains external.

## Phase 6 closure state

The implementation milestone is complete and the code paths are internally tested. The remaining gate is empirical 1.21.11 validation: execute the observation-only capture harness against the real client, preserve traces, compare first divergence, and add any numeric regressions found there. Phase 7–8 remain separate milestones.
