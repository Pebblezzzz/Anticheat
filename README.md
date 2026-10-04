# Phantom AC Core

Production-oriented, deterministic server-side anti-cheat for Minecraft Java 1.21.11.

Clean-room, deterministic server-side validation for **Minecraft Java 1.21.11**. Platform integration is isolated in `dev.phantom.ac.paper`; the deterministic core has no Bukkit/Paper or PacketEvents types.

The Paper adapter captures movement/input, timing, corrections, compensated client-visible world state, entity lifecycle, interaction packets, block use, inventory clicks, and operator evidence. Expensive prediction runs asynchronously and returns immutable reports to the main thread for policy/enforcement actions.

## License

The project is GPL-3.0-only because its Paper packet adapter requires the separately installed GPL-3.0 PacketEvents plugin. No PacketEvents or GrimAC source code has been copied. See [LICENSE.md](LICENSE.md).

## Production status and version discipline

The 1.21.11 movement path is version-pinned, deterministic, replayable, and protected by explicit `POSSIBLE`, `UNCERTAIN`, and exhaustively proven `IMPOSSIBLE` verdicts. The live adapter captures compensated world/timing/entity state and runs expensive prediction asynchronously. Modern movement modifiers represented in the current implementation include Depth Strider, Swift Sneak, Soul Speed, and Dolphin's Grace. The packet/evidence layer also covers position/rotation sanity, held-slot integrity, timer-burst evidence, reach, far break/place, block-placement cursor/face integrity, inventory packet integrity, conservative FastBreak evidence, and ground-claim corroboration.

Production enforcement is downstream from simulation. It requires repeated consecutive exhaustive contradictions plus numeric evidence that a non-empty reachable candidate set was completely eliminated. Incomplete world/timing/entity coverage remains `UNCERTAIN`, never a punishment decision.

No GrimAC source was copied or linked into this repository. Grim is used as an engineering reference for prediction, compensated world state, latency handling, collision ordering, and edge-case coverage; Phantom implements those concepts independently.

## Core invariants

* Packets retain source sequence, receive time, client tick when supplied, and normalized type.
* Timeline ordering is `(receiveNanos, sourceSequence)`; duplicates are retained as `DUPLICATE`, never silently discarded.
* Every state transition is a pure function of prior state, event, and world snapshot.
* Collision is independently testable; physics never reads mutable world state.
* `IMPOSSIBLE` is emitted only after exhaustive candidates in the declared uncertainty envelope fail.
* Replay serialization is byte-stable within this build and replay compares every reconstructed state.

## Validation scope

Run `mvn test`. See [the Phase-0 architecture contract](docs/ARCHITECTURE.md) and `docs/VALIDATION.md` for the honest validation inventory and trace format.
