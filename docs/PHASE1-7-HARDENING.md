# Phase 1–8 internal hardening

The repository now contains the completed internal Phase 1–8 hardening path on `main`. No Phase 9+ work is part of this change.

Closed internally:

- Phase 1: canonical packet normalization, capture provenance, chronology handling, and adversarial packet-order tests.
- Phase 2: one immutable rich `State.Player` model carrying movement, input, attributes, pose, environment, correction, client-tick, provenance, and uncertainty state.
- Phase 3: deterministic replay that preserves rich state and reports first divergence across rich fields.
- Phase 4: authoritative client-visible world replica with explicit known/unknown/unloaded/unsupported coverage, version-pinned 1.21.11 collision data, and explicit entity-collision completeness.
- Phase 5: one deterministic 1.21.11 movement authority covering the represented movement modes and delegating collision to Phase 4.
- Phase 6: deterministic reachable-state search that routes transitions through Phase 5 and never promotes an incomplete search to `IMPOSSIBLE`.
- Phase 7: authoritative live timing reconstruction with bounded history and explicit chronology/timing uncertainty.
- Phase 8: persistent live prediction, observation comparison, evidence accumulation, and a downstream exhaustive-evidence enforcement policy.

## Validation boundary

`main` has green Maven and Phase 5 validation workflow runs for the Phase 1–8 hardening merge.

The movement implementation is source-derived and pinned to Minecraft Java Edition 1.21.11. The repository does not claim that synthetic traces are real-client measurements. Real-client empirical conformance remains a separate evidence layer.

## Enforcement defaults

Setback, kick, and punishment-command actions are configurable and disabled by default. Enforcement requires exhaustive movement evidence and repeated impossible observations; incomplete timing/world/entity coverage remains uncertainty.

## Scope

This document and the current implementation stop at Phase 8.
