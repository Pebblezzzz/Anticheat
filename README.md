# Phantom AC Core

Clean-room, deterministic server-side movement-validation foundation for **Minecraft Java 1.21.11**. Platform integration is isolated in `dev.phantom.ac.paper`; the deterministic core has no Bukkit/Paper or PacketEvents types.

The Paper adapter is diagnostic-only. It captures client movement/input and outbound teleport, velocity, chunk, and block packets into normalized records without letting platform types enter the core.

## License

The project is GPL-3.0-only because its Paper packet adapter requires the separately installed GPL-3.0 PacketEvents plugin. No PacketEvents or GrimAC source code has been copied. See [LICENSE.md](LICENSE.md).

## Status and version discipline

The implementation is internally tested, deterministic, and version-isolated through `Vanilla12111Physics`. It is **not claimed to be 1:1 vanilla**. Its walking/jumping constants and simplified block catalogue require comparison against recorded 1.21.11 vanilla-client traces before enforcement. Unsupported mechanics are represented as uncertainty, not as violations.

No GrimAC source was copied or linked into this repository. GrimAC was consulted only for high-level architectural concerns (per-player world history, predictive/reachable simulation, and latency-aware state); it is GPL-3.0, so direct reuse would require GPL compliance and attribution.

## Core invariants

* Packets retain source sequence, receive time, client tick when supplied, and normalized type.
* Timeline ordering is `(receiveNanos, sourceSequence)`; duplicates are retained as `DUPLICATE`, never silently discarded.
* Every state transition is a pure function of prior state, event, and world snapshot.
* Collision is independently testable; physics never reads mutable world state.
* `IMPOSSIBLE` is emitted only after exhaustive candidates in the declared uncertainty envelope fail.
* Replay serialization is byte-stable within this build and replay compares every reconstructed state.

## Validation scope

Run `mvn test`. See [the Phase-0 architecture contract](docs/ARCHITECTURE.md) and `docs/VALIDATION.md` for the honest validation inventory and trace format.

