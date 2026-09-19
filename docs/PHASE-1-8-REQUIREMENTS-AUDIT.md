# Phases 1–8 requirements audit
Audit date: 2026-09-19. Phase 5 implementation is complete except for the external real-client corpus; Phase 6 has been re-audited as a deterministic reachable-state engine with explicit uncertainty, provenance, budgets, replay, diagnostics, and dedicated regression coverage; Phase 7 timing implementation and Phase 8 remain outside this Phase 6-only change.

## Summary
| Phase | Status | Main limitation |
|---|---|---|
| 1 | PARTIAL | Protocol coverage, packet-payload timing, and adversarial corpus are incomplete |
| 2 | PARTIAL | Attribute/pose provenance and correction metadata remain outside the immutable Player record |
| 3 | PARTIAL | Downstream world/timing/validation result capture is not one complete replay artifact |
| 4 | PARTIAL | Exact client-payload decoding and exhaustive 1.21.11 shapes remain incomplete |
| 5 | PARTIAL / BLOCKED BY EXTERNAL DATA | Empirical capture workflow exists, but no real 1.21.11 vanilla corpus is present in this environment |
| 6 | IMPLEMENTED / INTERNALLY TESTED / BLOCKED BY EXTERNAL DATA | Deterministic rich reachable-state search, uncertainty propagation, provenance, budgets, replay, diagnostics, and Phase 6-specific regressions are implemented; real-client numeric validation remains external |
| 7 | IMPLEMENTED / INTERNALLY TESTED / BLOCKED BY EXTERNAL DATA | Client/server clock reconstruction, latency/jitter bounds, synchronization state/recovery, replay, Phase 6 timing integration, and synthetic timing regressions are implemented; real-client timing validation remains external |
| 8 | PARTIAL | Policy/enforcement remains a later milestone and was not expanded by this Phase 7 work |

## Phase 7 closure state

`Phase7Timing` is now the authoritative client/server timing layer after canonical Phase 1/3 chronology. It models server capture time, client packet generation bounds, client processing bounds, explicit client movement ticks when supplied, relative client-tick reconstruction, asymmetric latency, jitter bounds, input-to-simulation delay, simulation-to-packet delay, packet gaps, server-tick gaps, corrections, teleport acknowledgements, velocity timing, world-update timing, synchronization windows, synchronization states, and deterministic recovery.

`Phase7Replay` captures the canonical timeline plus the immutable Phase 7 configuration so the same recording reconstructs identical timing and synchronization results. `Phase7PerformanceBenchmark` provides a deterministic synthetic 10,000-event timing workload.

The authoritative Phase 6 engine remains `Phase6Reachability`; Phase 7 does not duplicate it. `LiveValidation.analyze(...)` now reconstructs Phase 7 timing first, then passes the resulting client simulation-tick envelope into `Phase6Reachability.searchWithinTimingWindow(...)`. Velocity/correction acknowledgement transitions are mapped across all still-possible client ticks instead of being tied to server-arrival tick.

Duplicate capture packets no longer advance player state twice. Duplicate, reordered, and missing capture records remain timeline evidence and propagate uncertainty.

## Empirical evidence rule

`IMPLEMENTED` and `INTERNALLY TESTED` are not equivalent to `VANILLA VALIDATED`. A behavior becomes empirically validated only after a real Minecraft Java Edition 1.21.11 client trace is imported and compared. Synthetic timelines, inferred timing, documentation, or simulator-generated traces are not substitutes for that evidence.

## External limitation

The development environment cannot launch a licensed Minecraft client or conduct a real network/session capture. The observation-only 1.21.11 harness still builds in CI, but representative real timing traces, packet-generation timing, real acknowledgement delays, real-world jitter distributions, and client tick alignment remain external validation work.
