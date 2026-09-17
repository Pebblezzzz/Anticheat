# Phase 1-7 internal hardening

The internal gaps identified by the 2026-09-17 audit have now been addressed in code wherever no real Minecraft execution is required.

Closed internally:

- Packet capture records carry immutable capture provenance and optional authoritative server-tick association; provenance survives normalization.
- PlayerState is one immutable `State.Player` value carrying input, attributes, pose, environment, client-tick range, correction state, provenance, and structured uncertainty reasons.
- `Simulation.Vanilla12111Physics` is only a compatibility adapter. The movement mechanics implementation is `Vanilla12111RichPhysics`.
- Entity collision is an explicit deterministic provider; incomplete entity histories produce uncertainty, while complete recorded entity AABBs participate in axis clipping and stepping.
- `PipelineReplay` is a single deterministic artifact that reconstructs player history, client-visible world history, timing/synchronization, simulation-input projection, and validation outputs from one capture.
- Adversarial packet-ordering/provenance/replay/entity-collision regressions and packet/timeline benchmark coverage are committed.

Validation boundary:

- CI must pass against the current branch head before merge.
- Empirical Minecraft Java Edition 1.21.11 validation remains external because the repository environment cannot launch/capture a licensed client.
- No synthetic trace in this change is presented as vanilla evidence.
- Phase 8 policy/enforcement is not implemented.
