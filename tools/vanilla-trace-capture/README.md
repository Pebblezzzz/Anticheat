# Phantom vanilla 1.21.11 trace capture

This directory is the empirical Phase 5 capture harness. It targets exactly Minecraft Java Edition `1.21.11`, Java 21, Fabric Loader `0.18.2`, Yarn `1.21.11+build.4`, and Fabric Loom `1.14.10`.

The harness is observation-only: it reads the real client's post-tick player state and observes inbound velocity/correction packets. It does not replace `travel`, alter input, alter collision, alter world state, or inject a substitute movement implementation.

## Build

From PowerShell:

```powershell
cd C:\Users\Dell\Desktop\Anticheat\tools\vanilla-trace-capture
.\gradlew.bat clean build
```

A successful build creates the capture mod JAR under:

```text
C:\Users\Dell\Desktop\Anticheat\tools\vanilla-trace-capture\build\libs\
```

The expected JAR name is `phantom-vanilla-trace-capture-0.1.0-SNAPSHOT.jar`.

## Recommended capture method: pinned Loom client

For reproducible Phase 5 reference traces, the repository's preferred method is to launch the pinned `1.21.11` Minecraft artifact through Loom. This avoids accidentally running a different Minecraft version.

From the capture directory:

```powershell
.\gradlew.bat runClient `
  -Dphantom.capture.enabled=true `
  -Dphantom.capture.output="C:\phase5\capture.tsv" `
  -Dphantom.capture.events="C:\phase5\events.tsv"
```

The first launch may download the pinned Minecraft/Fabric dependencies. Keep the machine online until that completes.

The resulting files are:

```text
C:\phase5\capture.tsv
C:\phase5\events.tsv
```

The capture is disabled unless `-Dphantom.capture.enabled=true` is supplied.

## Using the built JAR in a separate Fabric 1.21.11 profile

The built JAR can also be loaded by a separately installed Fabric Loader `1.21.11` profile. Do **not** put it in a plain vanilla profile and do not mix it with other mods.

1. Install Fabric Loader for Minecraft `1.21.11` and create a separate test game directory/profile.
2. Copy `phantom-vanilla-trace-capture-0.1.0-SNAPSHOT.jar` from `build\libs` into that profile's `mods` directory.
3. Launch that Fabric `1.21.11` profile with Java 21.
4. Add these JVM system properties to the profile's Java arguments:

```text
-Dphantom.capture.enabled=true
-Dphantom.capture.output=C:\phase5\capture.tsv
-Dphantom.capture.events=C:\phase5\events.tsv
```

5. Do not add gameplay-changing mods, replay mods, shaders, or optimization mods to the reference profile.

The Loom method above is preferred because this repository pins the exact Minecraft artifact, mappings, and Fabric Loader version in the build itself.

## Exact-client procedure

1. Use Minecraft Java Edition `1.21.11`. Do not use a snapshot or release candidate.
2. Use a separate test game directory/world so the reference run cannot be contaminated by unrelated saves or mods.
3. Use Java 21.
4. Confirm the capture is enabled and that both output paths point to a dedicated `C:\phase5` directory.
5. For an empirical corpus, each scenario must begin from a documented initial state. Record the exact Minecraft version, seed/world identity, player coordinates, rotation, gamemode, attributes/effects, relevant block/fluid geometry, and the scenario identifier.
6. Exercise exactly one scenario at a time, holding the documented input for a fixed number of client ticks.
7. Stop the run only after the scenario's terminal tick has been captured. Preserve the main TSV and event TSV together.
8. Import and validate the trace using `Phase5VanillaTrace.read()` / `validate()`. Any unknown field must remain listed in `missing_fields`; do not replace it with guessed zero/false values.

## What is captured

The main TSV contains position, velocity, rotation, ground state, discrete keyboard input, sprint/sneak/jump state, resolved client pose, fluid state, submerged/climbing/gliding state, movement-speed base/modifiers, movement effects, world identity/tick, collision flags, input provenance, and client version.

The companion event TSV records inbound `ENTITY_VELOCITY` and `POSITION_CORRECTION` packets with receive time and packet payload. These events are intentionally separate because packet arrival is not the same observation point as the post-player-tick state.

`missing_fields` is mandatory for fields that cannot be reconstructed from observation alone. The current capture harness therefore does **not** claim that its placeholder values are real vanilla values for knockback, correction pending state, per-axis clipping, or step outcome.

## Scenario corpus manifest

Use `docs/phase5-vanilla-corpus/scenarios.tsv` as the required capture checklist. A scenario moves from `BLOCKED_BY_EXTERNAL_DATA` to `CAPTURED` only after an actual 1.21.11 run has produced a preserved trace.

No repository-generated row is considered empirical evidence.

## Why 1.21.11 is pinned

Minecraft's official 1.21.11 release is the target release for this phase. The capture project consequently pins the exact `1.21.11` Minecraft artifact and mappings rather than following `latest`.
