# Phase 1-7 internal hardening

The internal gaps identified by the 2026-09-17 audit have now been addressed in code wherever no real Minecraft execution is required.

Closed internally:

- Packet capture records carry immutable capture provenance and optional authoritative server-tick association; provenance survives normalization.
- PlayerState is one immutable `State.Player` value carrying input, attributes, pose, environment, client-tick range, correction state, provenance, and structured uncertainty reasons.
- `Simulation.Vanilla12111Physics` is only a compatibility adapter. The movement mechanics implementation is `Vanilla12111RichPhysics`.
- Entity collision is an explicit deterministic provider; incomplete entity histories produce uncertainty, while complete recorded entity AABBs participate in axis clipping and stepping.
- `PipelineReplay` is a single deterministic artifact that reconstructs player history, client-visible world history, timing/synchronization, simulation-input projection, and validation outputs from one capture.
- Adversarial packet-ordering/provenance tests and packet/timeline benchmark coverage are committed.

Still external by design:

- empirical Minecraft Java Edition 1.21.11 movement parity;
- real client tick-generation timing, latency/jitter distributions, correction acknowledgement timing, and real packet-ordering behavior;
- exhaustive validation of every required 1.21.11 scenario against an independently observed client;
- any empirical performance claim for a real production server.

No synthetic trace in this change is presented as vanilla evidence, and Phase 8 policy/enforcement is not implemented.
