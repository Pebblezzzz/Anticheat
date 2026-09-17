# Phase 6 — Reachability and timing uncertainty

Phase 6 is the authoritative bounded reachable-state engine. It consumes the timing envelope supplied by Phase 7 and never implements a second synchronization model.

## Timing handoff

`Phase7Timing.Reconstruction` derives possible client simulation ticks for each observed movement event. `Phase7Timing.toPhase6Window(...)` exposes the bounded interval in the existing `Validation.SyncWindow` compatibility form, while live validation calls `Phase6Reachability.searchWithinTimingWindow(...)` directly so the rich Phase 6 engine remains authoritative.

If Phase 7 reports ambiguity or inconsistency, Phase 6 is invoked with `timingUncertain=true`, preserving `UNCERTAIN` rather than promoting a timing problem to `IMPOSSIBLE`.

## External transitions

Velocity, teleport correction, and teleport confirmation events are mapped by LiveValidation across every still-possible client simulation tick. Their packet arrival server tick is therefore not treated as an exact client simulation tick.

## World timing

Historical client-visible world snapshots remain owned by `World.VisibilityHistory`. When a relevant world update has ambiguous Phase 7 timing, the live bridge marks the corresponding Phase 6 world hypothesis non-exhaustive. Phase 6 then returns `UNCERTAIN` instead of using the latest server world as an assumed historical client world.

## Existing guarantees

Phase 6 keeps its finite 72-input envelope, full-context candidate merging, explicit world uncertainty, provenance, candidate budgets, timing-window enumeration, explainable evidence, deterministic replay artifact, and performance regression benchmark. Timing/model uncertainty is never itself a violation.

## Explicit external limitation

Phase 6 numeric movement behavior and Phase 7 timing assumptions still require independent real Minecraft Java 1.21.11 traces before any empirical vanilla-parity claim can be made. This environment cannot launch the licensed client or capture those sessions.
