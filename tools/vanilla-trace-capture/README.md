# Phantom vanilla 1.21.11 trace capture

This directory is the empirical Phase 5 capture harness. It targets exactly Minecraft Java Edition `1.21.11`, Java 21, Fabric Loader `0.18.2`, Yarn `1.21.11+build.4`, and Fabric Loom `1.14.10`.

The **recorder** is observation-only: it reads the real client's causal pre-tick input, vanilla `move()` request/result, post-tick state, and packet traffic. The separate **corpus driver** prepares deterministic test fixtures and key state; it never substitutes for vanilla movement calculations.

## Exact-client procedure

1. Use the official Minecraft Launcher with the Java Edition `1.21.11` release. Do not use a snapshot or release candidate for the empirical reference.
2. Use a separate test directory/world so reference runs are isolated from unrelated saves or gameplay mods.
3. Build this capture project with Java 21:

```powershell
.\gradlew.bat clean build
```

4. Run one scenario in corpus mode:

```powershell
.\gradlew.bat --project-prop=phantom.capture.enabled=true --project-prop=phantom.capture.mode=corpus --project-prop=phantom.capture.scenario=walk-forward --project-prop=phantom.capture.output=C:\phase5\corpus\walk-forward\trace.tsv --project-prop=phantom.capture.events=C:\phase5\corpus\walk-forward\events.tsv runClient
```

5. Or use the repository automation to run the complete manifest:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File ..\phase5\run-vanilla-corpus.ps1
```

6. Each scenario must be isolated and start from its documented setup. Preserve the trace, packet-event TSV and generated metadata together.
7. Import with `Phase5VanillaTrace.read()` / `validate()` and reject malformed or missing evidence. Unknown fields remain in `missing_fields`; they are never converted into guessed zero/false observations.

## Observation points

The recorder samples the same client player tick at three causal points:

- `ClientPlayerEntity.tick` HEAD: player input before vanilla consumes it.
- `ClientPlayerEntity.move` HEAD/RETURN: requested movement vector and the resulting vanilla displacement.
- `ClientPlayerEntity.tick` TAIL: final player state after vanilla movement.

Packet observers separately capture inbound `ENTITY_VELOCITY` and `POSITION_CORRECTION`, plus outgoing `TELEPORT_CONFIRM_C2S`. This preserves packet ordering/timing instead of pretending packet arrival and player state are the same instant.

The move request/result evidence is retained as raw events. Collision and step columns are derived only from that observed vanilla movement call; the event stream makes the derivation inspectable.

A velocity packet is not automatically labeled as semantic knockback. `knockback_x/y/z` remain explicitly missing until the corpus can associate a packet with a known knockback cause without guessing.

## Corpus mode

`phantom.capture.mode=corpus` activates `VanillaCorpusScenarioDriver`. The driver contains the complete Phase 5 scenario manifest, including:

- idle, forward/backward/strafe/diagonal and sprint variants;
- single, repeated, controlled-ascent/apex/fall and sprint jumps;
- slabs, stairs, steps, partial collisions, edges, corners and sprint corners;
- water surface/deep swimming/water sprint/transition and lava;
- ladder, ladder sprint and vines;
- speed, slowness, jump boost, slow falling, levitation and movement-speed attributes;
- ground/air knockback and correction combinations;
- teleport/correction, correction into water, gliding, sleeping;
- step+sprint+jump, water+jump and climb+jump combinations.

The driver performs only test-fixture preparation and input scheduling. Actual physics remains the Minecraft 1.21.11 client.

## Generated files

The corpus runner creates:

```text
C:\phase5\corpus\<scenario>\trace.tsv
C:\phase5\corpus\<scenario>\events.tsv
C:\phase5\corpus\<scenario>\metadata.json
```

No generated trace is considered empirical unless the real Minecraft client actually produced it.

## World/collision parity

For replay, use `dev.phantom.ac.world.WorldSnapshot` and `Vanilla12111RichPhysics`. The rich path preserves block-state identity, neighbour-dependent voxel shapes and unloaded/unsupported coverage instead of reducing everything to the legacy block enum.

## Important boundary

The repository now contains the code and automation for the complete Phase 5 corpus. The only step that must happen outside this execution environment is launching a real Minecraft Java 1.21.11 client and generating the independent reference artifacts.
