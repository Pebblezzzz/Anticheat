# Grim reference comparison (Phases 1–8)

> Audit status: this document is a design comparison, not proof of feature parity. Phantom does **not** meet every requested completion requirement yet. In particular, independent 1.21.11 vanilla traces, complete packet-payload world replication, full client-tick/ack timing reconstruction, and several Phase 5 mechanics remain outstanding. The implementation must not be used as proof that a player is cheating until those limitations are resolved or the result is explicitly classified as uncertain.

This document records a clean-room technical comparison with the public [Grim repository](https://github.com/GrimAnticheat/Grim), its [README](https://github.com/GrimAnticheat/Grim), [design philosophy](https://github.com/GrimAnticheat/Grim/wiki/Design-philosophy), and [project documentation](https://grim.ac/page/about). No Grim source code is copied into Phantom AC.

## Shared principles
- **Simulation before policy.** Both designs treat movement validation as a prediction/simulation problem rather than a primary speed/distance threshold. Grim describes a predictive movement engine; Phantom exposes deterministic `PhysicsEngine`, reachable states, evidence, and a separate operator policy layer.
- **Per-player client-visible world.** Grim documents per-player world replication and latency compensation. Phantom's `VisibilityHistory` and immutable `WorldSnapshot` follow the same essential principle: server truth is not automatically client truth.
- **Version-specific mechanics.** Grim emphasizes version-specific collision ordering and bounding boxes. Phantom isolates the 1.21.11 model in `Vanilla12111Physics` and the 1.21.11 block catalogue rather than importing cross-version constants.
- **Asynchronous boundary awareness.** Grim emphasizes asynchronous/multithreaded processing. Phantom retains platform packet capture outside the deterministic core and uses immutable values so a future worker pipeline can replay safely.
- **Evidence buffering.** Grim's design philosophy rejects an automatic-ban dependency on a single transient event. Phantom's `OperatorValidation.Aggregator` requires repeated impossible evidence and debounces alerts.

## Intentional differences
### Deterministic replay is a first-class contract
Grim is a production anti-cheat with a broad live compatibility target. Phantom is a narrower 1.21.11 foundation whose core must reproduce the same result from the same capture. Therefore Phantom retains packet provenance, explicit timeline ordering, replay codecs, first-divergence diagnostics, and `UNCERTAIN` as a formal outcome. This makes incomplete data visible instead of hiding it behind live-check behavior.

### Uncertainty is not a tolerance
Grim's production checks necessarily make policy decisions around prediction envelopes and compatibility. Phantom does not convert missing chunks, unsupported block states, unmeasured timing, incomplete inputs, or budget exhaustion into movement violations. These cases produce `UNCERTAIN`, and the operator layer refuses to alert on them.

### No direct source reuse
Grim is GPL-3.0. Phantom uses independent implementations of general technical ideas and does not copy Grim classes, algorithms, or source fragments. The repository remains GPL-3.0-only for its own stated dependency/licensing reasons; any future direct reuse would require a separate license review, attribution, and preservation of applicable notices.

### Scope is deliberately smaller
Grim documents support for many client/server versions and mechanics including entities and vehicles. Phantom does not claim those capabilities. Its current core targets Java 1.21.11, and unsupported mechanics remain explicit limitations rather than being filled with guessed behavior.

### Timing model
Grim documents latency compensation and queued world changes. Phantom models a synchronization interval and records timing uncertainty as data consumed by reachability and evidence. This is less feature-rich than a mature production latency subsystem, but it is easier to replay and test independently. It must not be described as equivalent to Grim's mature implementation.

### Platform notifications
Phantom's operator alert value is pure and testable; it has no punishment side effects. Paper integration may translate an emitted alert into chat messages, but the core never imports Bukkit/Paper. Grim offers a broader production ecosystem and configuration surface; Phantom intentionally postpones punishment and enforcement outside Phases 1–8.

## Concepts that materially improved Phantom
1. Treating client-visible world replication as a separate per-player state, rather than reading the live server world.
2. Treating movement as a set of possible simulated states, not one guessed state.
3. Maintaining version-specific collision and physics boundaries.
4. Designing latency/correction handling as compensation and synchronization data.
5. Buffering evidence and debouncing operator notifications.
6. Treating collision ordering and edge cases as correctness concerns rather than cosmetic optimizations.

## Approaches rejected for this project
- Copying Grim source or adapting its internal classes: rejected for licensing, architectural, and clean-room reasons.
- Claiming 1:1 vanilla parity based only on an implementation resemblance: rejected because Phantom has no independent 1.21.11 client trace corpus yet.
- Treating a fixed movement tolerance as a substitute for simulation: rejected by the project requirements.
- Treating all unknown world data as air: rejected because it creates unsound reachability conclusions.
- Making the core depend on Netty, Bukkit, PacketEvents, or live clocks: rejected because deterministic replay and platform isolation are explicit contracts.
- Adding broad multi-version or vehicle support before the 1.21.11 trace baseline is proven: rejected as breadth-first scaffolding.

## Why flight was not being detected
The previous live path had two concrete faults. First, validation was only meaningfully surfaced by the manual command path; the scheduled path did not provide a complete operator-facing workflow. Second, `LiveValidation` discarded the prediction envelope after a mismatch by allowing the next empty candidate set to behave like a fresh anchor. Sustained flight could therefore produce one finding and then lose the continuity needed for repeated evidence. The live validator now retains the simulated envelope after divergence and continues producing findings. This is a correctness repair, not a flight-distance threshold.

A flight client can still remain `UNCERTAIN` when the initial state is not anchored, the client-visible world is incomplete, timing is ambiguous, or the 1.21.11 simulator lacks the relevant mechanic. That behavior is required by the false-positive constraints, not a successful detection. The scheduled adapter is a test-server policy layer; Phase 8 core itself remains alert/evidence-only and does not own punishment.

## Validation still missing
Phantom still lacks equivalent validation in several areas:

- independent vanilla 1.21.11 traces for acceleration, friction, collision ordering, step/slope behavior, fluids, poses, effects, attributes, knockback, and corrections;
- complete client-visible chunk decoding from the packet payload rather than the current Paper-side server chunk reconstruction path;
- production-grade asynchronous scheduling and stress/throughput measurements;
- complete entity/vehicle replication and collision;
- a full live alert dispatch path driven by validated evidence rather than command-time diagnostics;
- long-running replay corpus tests covering packet delay, reordering, correction recovery, and synchronization loss.

These gaps are limitations, not claims of parity or superiority. Grim remains a mature reference implementation; Phantom deliberately chooses a smaller, more explicit, replay-oriented foundation.
