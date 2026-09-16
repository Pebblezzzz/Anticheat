# Validation report

Phases 1–8 have deterministic implementations in `src/main/java/dev/phantom/ac`.
**Phase 0's architecture contract is complete** and documented in
[`ARCHITECTURE.md`](ARCHITECTURE.md). This document records verified behavior and
explicit limitations; it does not claim vanilla parity where no independent trace exists.

* 0: immutable component contracts and deterministic data flow;
* 1–2: normalized packet/timeline events and explicit uncertain state reconstruction;
* 3: serializable timeline and deterministic replay;
* 4: immutable block snapshots and independently exercised AABB collision;
* 5: `Vanilla12111Physics`, with first-divergence trace comparison;
* 6–8: finite input reachability, timing-window uncertainty, and explainable possible/uncertain/impossible evidence.

The repository contains unit and integration coverage across the core. A clean Maven
execution must be run in an environment with JDK 21 and Maven available; this audit
must not claim a fresh pass when those tools are unavailable. It covers architecture boundary contracts and platform-leak guards,
 immutable value boundaries, replay round trips/determinism/invalid input,
 packet ordering/deduplication, timeline boundaries, state transitions, the Phase 4
 voxel/AABB geometry catalogue, block-state-dependent shapes, immutable chunk and
 snapshot coverage, unloaded-versus-air handling, deterministic collision/path/
 floor/ceiling queries, fluids/environment facts, entity-collision completeness,
 world-history-to-snapshot integration, and the existing diagnostic layers.

The simulator has **no independent vanilla-client traces in this repository**. Consequently: collision primitives and deterministic/replay properties are internally proven by tests; exact 1.21.11 movement fidelity is unverified. The trace comparator reports the first divergent tick once an independent trace is supplied. No automatic punishment/setback functionality exists.

Architecture review: collision has no dependency on physics, replay has no dependency on live time, and uncertain packets/timing/environments cannot become an `IMPOSSIBLE` result. Phase 4 world facts are provided through one `WorldView` seam; callers can distinguish KNOWN, UNLOADED and UNSUPPORTED coverage. Collision boxes are internally proven by the new regression suites, but exact 1.21.11 parity for every unlisted block still requires independent vanilla data/traces.

Known limitations: the Phase 4 entity interface is complete, but live entity
 tracking is intentionally not implemented; the default provider reports an
 incomplete entity set. The 1.21.11 block catalogue is broad but not exhaustive:
 blocks whose state or shape is not verified are explicitly UNSUPPORTED rather
 than guessed. Fluid height is UNKNOWN when required neighbours are unavailable.
 Pose/sneaking, effects, speed modifiers, and actual vanilla movement response
 remain Phase 5 concerns and are deliberately not implemented here. Paper chunk
 capture uses the platform boundary and must still be validated against live
 PacketEvents/Paper capture traces.

GrimAC was reviewed as a serious technical reference. The comparison is recorded in
[`GRIM-COMPARISON.md`](GRIM-COMPARISON.md). No Grim GPL source code is present.
