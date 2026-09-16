# Phase 5 implementation

Phase 5 is the deterministic movement layer. The implementation deliberately separates mechanics from empirical parity: code may model a mechanic, but the validator must not call a value vanilla-accurate until it has a 1.21.11 observation trace behind it.

## Implemented in this branch

- Pose-aware player collision bounds for standing, crouching, swimming and fall-flying.
- Collision-safe pose transitions: expanding a pose is rejected when the requested box intersects known world geometry.
- Explicit survival and adventure simulation; creative and spectator are treated as non-physical modes.
- Explicit unknown-environment barrier. Unknown environment returns an uncertain simulation result instead of silently using dry-land physics.
- Movement input acceleration, sprint/sneak scaling, gravity, drag, ground friction and jump integration remain deterministic and replayable.
- Water, lava and climbable movement remain explicit environment inputs rather than inferred from server truth.
- Movement effects include speed, slowness, jump boost, levitation and slow-falling state representation.
- Attribute modifier ordering remains explicit: ADD_VALUE, ADD_MULTIPLIED_BASE, then ADD_MULTIPLIED_TOTAL.
- Server velocity packets continue to enter the state as velocity provenance, so knockback is simulated from the authoritative velocity rather than invented by a separate heuristic.
- Teleport confirmation remains a hard simulation barrier.
- One-command Windows validation runner at `tools/phase5/run-capture.ps1`.

## Empirical gate

A Phase 5 release is only considered empirically complete after the local 1.21.11 observation harness produces traces for the required scenario manifest and the replay/audit tests show no unexplained first divergence. The harness observes the normal client; it does not replace Minecraft movement or collision code.

The remaining human step is starting the legitimate 1.21.11 client when the local script launches it. Everything else in the capture/audit loop is automated by the repository tooling.
