# Phantom vanilla 1.21.11 trace capture

This directory is the empirical Phase 5 capture harness. It targets exactly Minecraft Java Edition `1.21.11`, Java 21, Fabric Loader `0.18.2`, Yarn `1.21.11+build.4`, and Fabric Loom `1.14.10`.

The harness is observation-only: it reads the real client's post-tick player state and observes inbound velocity/correction packets. It does not replace `travel`, alter input, alter collision, alter world state, or inject a substitute movement implementation.

## Exact-client procedure

1. Install the official Minecraft Launcher and use the release installation for **Java Edition 1.21.11**. The official 1.21.11 release page states that the release is launched from the Minecraft Launcher; do not use a snapshot or release candidate for the corpus.
2. Use a separate test game directory/world so the reference run cannot be contaminated by unrelated saves or mods.
3. Build this capture harness from this directory with Java 21:

```powershell
gradle build
```

4. Run the Loom client with capture explicitly enabled. Example Windows command:

```powershell
gradle runClient -Dphantom.capture.enabled=true -Dphantom.capture.output="C:\phase5\capture.tsv" -Dphantom.capture.events="C:\phase5\events.tsv"
```

5. For an empirical corpus, each scenario must begin from a documented initial state. Record the exact Minecraft version, seed/world identity, player coordinates, rotation, gamemode, attributes/effects, relevant block/fluid geometry, and the scenario identifier.
6. Exercise exactly one scenario at a time, holding the documented input for a fixed number of client ticks. Avoid resource packs, optimization mods, replay mods, shaders, or gameplay-changing client modifications.
7. Stop the run only after the scenario's terminal tick has been captured. Preserve the TSV and event TSV together.
8. Import and validate the trace using `Phase5VanillaTrace.read()` / `validate()`. Any unknown field must remain listed in `missing_fields`; do not replace it with guessed zero/false values.

## What is captured

The main TSV contains position, velocity, rotation, ground state, discrete keyboard input, sprint/sneak/jump state, resolved client pose, fluid state, submerged/climbing/gliding state, movement-speed base/modifiers, movement effects, world identity/tick, collision flags, input provenance, and client version.

The companion event TSV records inbound `ENTITY_VELOCITY` and `POSITION_CORRECTION` packets with receive time and packet payload. These events are intentionally separate because packet arrival is not the same observation point as the post-player-tick state.

`missing_fields` is mandatory for fields that cannot be reconstructed from observation alone. The current capture harness therefore does **not** claim that its placeholder values are real vanilla values for knockback, correction pending state, per-axis clipping, or step outcome.

## Scenario corpus manifest

Use `docs/phase5-vanilla-corpus/scenarios.tsv` as the required capture checklist. A scenario moves from `BLOCKED_BY_EXTERNAL_DATA` to `CAPTURED` only after an actual 1.21.11 run has produced a preserved trace.

No repository-generated row is considered empirical evidence.

## Why 1.21.11 is pinned

Minecraft's official 1.21.11 release is the target release for this phase. Fabric documents that 1.21.11 is the last obfuscated release before the 26.1 versioning/unobfuscation transition, and the capture project consequently pins the exact 1.21.11 Minecraft artifact and mappings rather than following `latest`.
