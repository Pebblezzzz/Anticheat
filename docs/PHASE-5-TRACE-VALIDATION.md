# Phase 5 trace validation contract

Audit date: 2026-09-17.

## Status
The repository now contains the code-side machinery for the full Phase 5 empirical corpus: a canonical scenario manifest, a corpus-mode Fabric 1.21.11 driver, causal pre-tick input capture, vanilla `move()` request/result observation, teleport-confirm observation, explicit missing-field provenance, rich first-divergence diagnostics, and a coverage-aware rich-world collision/physics path.

The existing controlled Part 1–5 evidence remains a separately validated subset: `C:\phase5\phase5-all.tsv` contains 1,917 accepted rows after 301 pre-settle boundary rows were discarded, and the existing batch audit/replay completed successfully on 2026-09-17.

The only remaining Phase 5 work that cannot be executed in this environment is producing the independent real-client 1.21.11 corpus and then feeding those artifacts through the already implemented validation/replay loop. No source file claims those future observations as captured.

## Exact target client

The empirical reference is exactly **Minecraft Java Edition 1.21.11**. The capture project pins Minecraft `1.21.11`, Yarn `1.21.11+build.4`, Fabric Loader `0.18.2`, Loom `1.14.10`, and Java 21.

## Code-side Phase 5 corpus coverage

`src/main/java/dev/phantom/ac/Phase5ScenarioCatalog.java` is the canonical required manifest. It now has discrete coverage for:

- idle, forward/backward walking, left/right strafe, diagonal input;
- sprint-forward, sprint-strafe, sprint-diagonal;
- single jump, sprint-jump, repeated jumps, controlled ascent, apex, fall, combined ascent/apex/fall, landing;
- sneak, slabs, stairs, step-up, partial collision, edge, corner, corner-sprint;
- water surface, deep swimming, water sprint, swim transition, lava;
- ladder, ladder-sprint, vines;
- speed, slowness, jump boost, slow falling, levitation, movement-speed attribute modifiers;
- ground knockback, airborne knockback;
- teleport/correction, correction into water, correction after velocity impulse;
- gliding, sleeping, and step+sprint+jump / water+jump / climb+jump combinations.

The corpus driver is available through `phantom.capture.mode=corpus` and `phantom.capture.scenario=<id>`. The PowerShell runner mirrors the canonical scenario list and creates one directory containing `trace.tsv`, `events.tsv`, and `metadata.json` per scenario.

## Observation contract

The capture point is split deliberately:

1. `ClientPlayerEntity.tick` HEAD records the player input that vanilla is about to consume.
2. `ClientPlayerEntity.move` HEAD/RETURN records the exact requested movement vector and the resulting displacement.
3. `ClientPlayerEntity.tick` TAIL records vanilla's resulting state.
4. inbound `ENTITY_VELOCITY` records are preserved as packet evidence;
5. inbound `POSITION_CORRECTION` and outgoing `TELEPORT_CONFIRM_C2S` records preserve correction/confirmation timing.

The recorder does not call `player.jump()`, replace vanilla movement, or write synthetic movement coordinates. Scenario setup may prepare a deterministic integrated-server test fixture; that setup is not presented as a vanilla movement observation.

Step and per-axis collision fields are derived only from the actual vanilla `move()` request/result observation. The raw request/result pair is also kept in the event stream, so the claim is auditable rather than inferred from final position alone.

Knockback is not mislabeled: a velocity packet is recorded exactly, while `knockback_x/y/z` remains explicitly missing until a semantic knockback-to-packet association is independently established.

Correction-pending is backed by observed server correction packets plus client-side `TeleportConfirmC2SPacket` observation; rows before the first correction event remain marked missing rather than being fabricated as `false`.

## Rich-world physics path

`dev.phantom.ac.world.WorldSnapshot` remains the authoritative client-knowledge representation for exact block states, shape properties, loaded/unloaded coverage and neighbour-dependent geometry.

`RichWorldCollision` now resolves per-axis movement directly against those exact world-space voxel shapes and refuses to integrate a sweep that crosses unloaded or unsupported coverage.

`Vanilla12111RichPhysics` consumes `WorldSnapshot` directly and applies the same Phase 5 mechanics model without first collapsing the world into the legacy `World.Block` enum. This closes the previous architectural gap between exact captured world information and movement collision.

## Existing-capture batch replay

From the repository root:

`pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\phase5\run-existing-phase5.ps1`

The completed run on 2026-09-17 produced 1,917 merged rows and passed the existing batch audit and replay with zero failures and zero errors.

## Live corpus capture

The prepared command is:

`pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\phase5\run-vanilla-corpus.ps1`

The script requires a real Minecraft Java 1.21.11 client environment and intentionally fails instead of fabricating trace artifacts when the game cannot be launched or a scenario fails.

Each scenario writes:

`C:\phase5\corpus\<scenario>\trace.tsv`

`C:\phase5\corpus\<scenario>\events.tsv`

`C:\phase5\corpus\<scenario>\metadata.json`

## Import and comparison

`Phase5VanillaTrace.read()` validates the version-2 header, 47-column schema, monotonic timing, finite numeric state and explicit missing-field encoding.

`Phase5VanillaCorpusAudit` rejects a corpus that lacks required scenario rows, required observations, exact client version, or causal pre-tick input provenance.

`Phase5VanillaDetailedComparison.firstDivergence()` preserves the previous/current vanilla context and previous/current simulated state so the first divergence carries enough information for a deterministic source investigation.

The validation order is:

**real vanilla capture → structural audit → missing-field review → world/collision reconstruction → numerical replay → rich first divergence → code fix → regression test → repeat capture**.

## Completion boundary

Code-side Phase 5 implementation is now in place. A scenario becomes `CAPTURED` only after a real 1.21.11 execution produces and preserves its trace/events/metadata. Phase 5 as a release gate becomes fully empirically complete only after the remaining captured scenarios replay without unexplained first divergence.

The repository therefore does **not** claim the live corpus has been captured here; that is the single external execution step remaining outside this environment.
