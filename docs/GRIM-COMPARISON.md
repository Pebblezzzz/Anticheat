# Grim reference comparison and clean-room production baseline

> Phantom follows Grim's broad production architecture where it improves correctness, but it is not a source fork or a multi-version Grim clone. The current target is Java 1.21.11. Direct Grim source is not copied; the implementation is independently written around the same publicly documented engineering principles.

This document records a clean-room technical comparison with the public [Grim repository](https://github.com/GrimAnticheat/Grim), its [README](https://github.com/GrimAnticheat/Grim), [design philosophy](https://github.com/GrimAnticheat/Grim/wiki/Design-philosophy), and [project documentation](https://grim.ac/page/about). No Grim source code is copied into Phantom AC.

## Shared principles
- **Simulation before policy.** Both designs treat movement validation as a prediction/simulation problem rather than a primary speed/distance threshold. Grim describes a predictive movement engine; Phantom exposes deterministic `PhysicsEngine`, reachable states, evidence, and a separate operator policy layer.
- **Per-player client-visible world.** Grim documents per-player world replication and latency compensation. Phantom now retains PacketEvents palette-backed `Column` objects per player, applies them behind transaction acknowledgement, and exposes them to deterministic physics through a lazy immutable `WorldSnapshot` backend. The older `VisibilityHistory`/map representation remains for legacy replay compatibility rather than as the hot-path chunk storage.
- **Version-specific mechanics.** Grim emphasizes version-specific collision ordering, bounding boxes, and modern-version edge cases. Phantom isolates the 1.21.11 model in `Vanilla12111RichPhysics`, `GrimVehiclePhysics`, `GrimFluidPhysics`, `GrimElytraPhysics`, and the 1.21.11 block catalogue.
- **Asynchronous boundary awareness.** Grim emphasizes asynchronous/multithreaded processing. Phantom keeps packet capture lightweight, runs CPU-heavy prediction on a dedicated executor, coalesces per-player validation work, and returns immutable reports to the Bukkit main thread.
- **Evidence buffering.** Grim's design philosophy rejects an automatic-ban dependency on a single transient event. Phantom's `OperatorValidation.Aggregator` requires repeated impossible evidence and debounces alerts.

## Intentional differences
### Deterministic replay is a first-class contract
Grim is a production anti-cheat with a broad live compatibility target. Phantom is a narrower 1.21.11 foundation whose core must reproduce the same result from the same capture. Therefore Phantom retains packet provenance, explicit timeline ordering, replay codecs, first-divergence diagnostics, and `UNCERTAIN` as a formal outcome. This makes incomplete data visible instead of hiding it behind live-check behavior.

### Uncertainty is not a tolerance
Grim's production checks necessarily make policy decisions around prediction envelopes and compatibility. Phantom does not convert missing chunks, unsupported block states, unmeasured timing, incomplete inputs, or budget exhaustion into movement violations. These cases produce `UNCERTAIN`, and the operator layer refuses to alert on them.

### No direct source reuse
Grim is GPL-3.0. Phantom uses independent implementations of general technical ideas and does not copy Grim classes, algorithms, or source fragments. The repository remains GPL-3.0-only for its own stated dependency/licensing reasons; any future direct reuse would require a separate license review, attribution, and preservation of applicable notices.

### Scope is deliberately version-pinned
Grim supports a broad client/server matrix. Phantom intentionally targets Java 1.21.11 so collision ordering, physics constants, modern movement attributes, vehicles, and world state can remain version-specific instead of being hidden behind guessed cross-version behavior.

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
- Claiming 1:1 vanilla parity based only on implementation resemblance: rejected. Phantom remains explicitly version-pinned and empirical client sessions are optional deployment-conformance data, not fabricated proof.
- Treating a fixed movement tolerance as a substitute for simulation: rejected by the project requirements.
- Treating all unknown world data as air: rejected because it creates unsound reachability conclusions.
- Making the core depend on Netty, Bukkit, PacketEvents, or live clocks: rejected because deterministic replay and platform isolation are explicit contracts.
- Adding broad multi-version support before the 1.21.11 movement contract is stable: rejected as breadth-first scaffolding.

## Why flight was not being detected
The previous live path had two concrete faults. First, validation was only meaningfully surfaced by the manual command path; the scheduled path did not provide a complete operator-facing workflow. Second, `LiveValidation` discarded the prediction envelope after a mismatch by allowing the next empty candidate set to behave like a fresh anchor. Sustained flight could therefore produce one finding and then lose the continuity needed for repeated evidence. The live validator now retains the simulated envelope after divergence and continues producing findings. This is a correctness repair, not a flight-distance threshold.

A flight client can still remain `UNCERTAIN` when the initial state is not anchored, the client-visible world is incomplete, timing is ambiguous, or the 1.21.11 simulator lacks the relevant mechanic. That behavior is required by the false-positive constraints, not a successful detection. The scheduled adapter is a test-server policy layer; Phase 8 core itself remains alert/evidence-only and does not own punishment.

## Current compensated-world implementation

The live adapter now mirrors Grim's core world-visibility and storage idea without copying Grim source: each player owns a compensated client-world cache of the original palette-backed PacketEvents `Column` values; outbound chunk/block/unload mutations are associated with a synthetic negative PING transaction; the mutation is not committed to the player's simulation world until the matching PONG is received. Each lazy world snapshot carries the acknowledgement sequence that bounds what the client could have known. Replay still retains transaction records and the legacy state timeline for deterministic historical captures. The deterministic core continues to consume immutable `WorldSnapshot` values and never reaches into Paper's live world.

This is intentionally a semantic reimplementation rather than a source-level copy. Grim uses the same transaction-backed client-world concept and its CompensatedWorld is advanced through LatencyUtils transaction barriers.

## Current state-evidence improvements

Phantom now keeps the client's movement claims separate from the server-authoritative movement context at the adapter boundary. Each live PlayerContext records the server position, server velocity, physical ground state, and flight permissions/state with capture provenance. The live predictor does not use the client's ground bit as a physical simulation truth; ground spoofing is evaluated as a separate evidence channel.

Live validation also distinguishes position-bearing movement packets from rotation-only/heartbeat packets. Rotation-only packets no longer advance the ground-contradiction streak, while sustained airborne hover can produce an authoritative flight-state contradiction without requiring a ClientInput packet first. Paper's authoritative PlayerFailMoveEvent path is surfaced as its own evidence source.

This follows the same broad architectural lesson visible in Grim's current player model: client claims, authoritative movement state, flying capability/status, prediction state, and compensated world state are tracked as separate concepts rather than collapsing them into one boolean. Grim also uses tick-boundary and packet-order information as independent movement evidence. This is an architectural comparison, not a claim of feature parity.
## Production hardening status
Phantom now includes the modern movement modifiers and vehicle families relevant to the 1.21.11 target, persistent candidate-frontier prediction, client-visible world compensation, explicit timing uncertainty, numeric enforcement-proof checks, production validation health metrics, interaction/reach evidence, packet-integrity checks, timer-burst evidence, conservative FastBreak evidence, and regression coverage for the evidence gate.

Remaining work is feature expansion rather than a movement rewrite: deeper combat/aim heuristics, broader inventory/item semantics, more sophisticated scaffold/build analysis, long-running server soak measurements, and future version adapters. Those should continue to use the same evidence/uncertainty contract.

These are deliberately kept outside the 1.21.11 movement proof so unsupported information cannot become a false movement violation.