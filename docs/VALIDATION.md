# Validation report

Phase 0 architecture and Phases 1–7 have deterministic code paths in `src/main/java/dev/phantom/ac`. Phase 8 policy/enforcement remains a separate milestone.

The implementation records explicit limitations and does not claim vanilla parity where independent real-client traces are absent.

- 0: immutable component contracts and deterministic data flow;
- 1–2: normalized packet/timeline events and explicit uncertain state reconstruction;
- 3: serializable timeline and deterministic replay;
- 4: immutable block snapshots and independently exercised AABB collision;
- 5: `Vanilla12111Physics` plus trace-comparison tooling;
- 6: finite rich-context input reachability with explicit uncertainty and provenance;
- 7: client/server timing reconstruction, latency/jitter bounds, synchronization state/recovery, deterministic timing replay, and Phase 6 timing integration;
- 8: not advanced by this work.

The repository contains unit and integration coverage across these layers. Current CI runs the Java 21 Maven suite and the observation-only 1.21.11 capture harness. A local fresh Maven run is only considered valid when Maven/JDK are available in the execution environment.

The simulator has no independent real vanilla-client corpus in this repository. Consequently, deterministic code behavior, replay fidelity, and synthetic timing scenarios are internally testable, while exact 1.21.11 movement parity and empirical client/network timing remain unverified.

## Phase 7 implementation status

`Phase7Timing` is the authoritative client/server timing layer after canonical Phase 1/3 chronology. It explicitly represents:

- server capture time and deterministic server-tick projection;
- client packet-generation intervals;
- client processing intervals for clientbound packets;
- optional explicit client movement ticks;
- bounded relative client-tick reconstruction when no explicit tick is present;
- asymmetric client→server and server→client latency bounds;
- input→simulation and simulation→packet timing bounds;
- packet bursts, observation gaps, server-tick gaps, duplicates, reordering, and sequence gaps;
- teleport/correction synchronization boundaries and delayed acknowledgements;
- velocity timing windows;
- client-visible world-update timing windows;
- explicit synchronization states and recovery requirements;
- deterministic Phase 7 replay and synthetic performance measurement.

`LiveValidation.analyze(...)` reconstructs Phase 7 timing before invoking the existing authoritative Phase 6 reachability engine. Phase 7 does not duplicate candidate simulation.

Duplicate capture records are preserved as evidence but are no longer allowed to advance semantic state twice during replay/history reconstruction.

## External validation rule

`IMPLEMENTED` and `INTERNALLY TESTED` do not mean `VANILLA VALIDATED`. Real Minecraft Java 1.21.11 client traces are required to validate numeric timing and movement behavior empirically. This environment cannot launch that client or produce those sessions.

No automatic punishment/setback logic has been added as part of the Phase 7 work.
