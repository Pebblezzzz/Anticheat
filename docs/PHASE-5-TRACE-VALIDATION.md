# Phase 5 trace validation contract

Audit date: 2026-09-16.

## Status
Phase 5 remains **PARTIAL / UNVERIFIED**. The repository now has explicit mechanics primitives, stricter trace import/validation, correction barriers, detailed divergence diagnostics, and a substantially expanded local combination matrix. It is intentionally **not** declared vanilla-complete because this repository cannot generate an independent Minecraft Java 1.21.11 client reference trace.

Fabric Yarn 1.21.11 mappings establish the existence and structure of the relevant client/entity movement paths (`travel`, `travelInWater`, `travelInLava`, climbing, movement-speed attributes, pose/bounding-box queries, and status-effect handling), but mappings are not an empirical movement trace and do not establish every numeric/order detail. See the 1.21.11 `LivingEntity` mapping and the Fabric attribute documentation in the project validation notes.

## Exact external trace format

The machine-readable format is `Phase5TraceTool` TSV, schema version 1. The first four lines are fixed:

1. `# phantom-phase5-trace version=1 protocol=minecraft-java-1.21.11 format=tsv`
2. `# source_id=<escaped independent-capture-id>`
3. `# captured_at_utc=<escaped UTC timestamp>`
4. the exact `Phase5TraceTool.HEADER` column line.

Every subsequent non-comment row has exactly 46 tab-separated columns:

| # | Field | Meaning |
|---:|---|---|
| 1–3 | `tick`, `client_tick`, `receive_nanos` | server/simulation tick, client tick, capture receive timestamp |
| 4–6 | `x,y,z` | client-observed position |
| 7–9 | `vx,vy,vz` | client-observed velocity/state velocity |
| 10–11 | `yaw,pitch` | rotation |
| 12 | `on_ground` | client ground flag |
| 13–17 | `forward,strafe,jump,sprint,sneak` | input for this movement step |
| 18–19 | `pose,gamemode` | resolved pose and game mode |
| 20–23 | `fluid,submerged,climbable,gliding` | movement environment |
| 24–24 | `base_movement_speed` | attribute base value before modifiers |
| 25 | `modifiers` | ordered modifier records `id:amount:operation`, where operation is `ADD_VALUE`, `ADD_MULTIPLIED_BASE`, or `ADD_MULTIPLIED_TOTAL` |
| 26–30 | effect fields | speed/slowness/jump-boost amplifiers plus levitation/slow-falling flags |
| 31–33 | `knockback_x/y/z` | applied knockback impulse for the step |
| 34 | `velocity_packet` | whether a server velocity packet was observed for the state transition |
| 35–36 | correction fields | correction id and whether recovery is still pending |
| 37–38 | world identity/tick | immutable client-visible world snapshot identity and world tick |
| 39–44 | collision/step fields | collision result plus step attempted/succeeded and per-axis clipping |
| 45–46 | provenance | input source and exact client version |

Escaping replaces `%` with `%25`, tab with `%09`, newline with `%0A`, and carriage return with `%0D`. A missing modifier list is `-`.

The importer rejects missing magic/header, wrong column count, non-increasing simulation ticks, decreasing client ticks, decreasing receive time, malformed modifiers, invalid enums, and non-finite numeric values. `validate()` additionally reports field-level timing/world/version diagnostics.

## Required capture procedure for real vanilla 1.21.11 evidence

A reference capture **must be produced independently of this simulator**. Do not generate the reference rows by calling `Vanilla12111Physics` or copying simulator output.

1. Use an unmodified Minecraft Java **1.21.11** client and record the exact client version.
2. Start from a deterministic test world and record its identity, relevant block/fluid states, chunk visibility and the initial player state.
3. Record client input per tick, including forward/strafe/jump/sprint/sneak and the state used to resolve pose.
4. Record client-observed position/rotation/ground state and the velocity available to the capture mechanism. If a field cannot be independently observed, use an explicit missing/unknown capture representation rather than inventing it.
5. Record server correction/teleport ids, velocity packets, their receive times, and the first subsequent client movement state.
6. Record the client-visible world snapshot identity/tick used by the trace and the relevant fluid/climbable/collision states.
7. Run isolated scenarios and combination scenarios, preserving exact tick order and capture timing.
8. Export the fixed TSV schema and run `Phase5TraceTool.read()` followed by `validate()` before using the trace for parity comparison.

The repository's server-side adapter is not, by itself, an independent vanilla client trace generator. This distinction is mandatory for Phase 5 closure.

## First-divergence diagnostics

`Phase5TraceTool.firstDivergence()` compares the independent rows against a reconstructed `Simulation.Trace` and returns the first divergent tick with field name, expected value, actual value, numeric delta, and a reason. It checks tick identity, position, velocity, rotation, ground state and input before declaring later rows relevant. A trace-length mismatch is reported at the first missing row.

## Mechanics matrix

| Mechanic | Implementation status | Validation status |
|---|---|---|
| Pose transitions | Explicit `Phase5Mechanics.Pose`; standing/crouching/swimming/fall-flying transitions | Mapping-informed; independent trace required |
| Swimming pose/state | Explicit submerged + swimming-input transition | Independent client trace required |
| Movement effects | Speed/slowness/jump-boost/levitation/slow-falling state is explicit | Numeric/order parity still requires client traces |
| Attribute modifiers | Full three-operation evaluation order represented | Independent numeric trace required |
| Teleport/correction recovery | `CorrectionRecovery` is a hard barrier; simulation refuses to integrate while awaiting confirmation | Packet/client ordering still requires independent trace |
| Water/lava/climbables | Environment carries fluid, drag, speed and gravity inputs; climbable branch is explicit | Exact 1.21.11 factors/order remain trace-dependent |
| Knockback | Impulse and velocity-packet provenance are explicit | Exact client response remains unverified |
| Step/collision order | Uses the shared Phase 4 resolver and records step/collision outcomes | Exact vanilla order remains unverified |
| Combination matrix | Expanded deterministic test covers modifier, pose, correction and trace paths; existing Phase 5 matrix remains | Coverage is local, not vanilla parity evidence |

## Vanilla-reference limitation

The official 1.21.11 release notes establish the target release, while Yarn 1.21.11 mappings provide authoritative structural names. Neither source supplies an independently captured end-to-end client movement trace for this repository. Therefore this phase must not be marked COMPLETE until real independent reference traces are imported and compared.

No fabricated trace, simulator-generated trace, generic speed threshold, or inferred numeric constant is accepted as parity evidence.
