# Phases 1 and 3: packet timeline and replay contracts

Every raw packet retains its monotonic capture sequence and monotonic capture time. The capture sequence is a local observation sequence, not a claimed Minecraft protocol sequence number. Normalization orders events by `(captureNanos, sequence)` and never silently drops duplicate, delayed, or out-of-order records.

For serverbound packets the capture timestamp is the server receive observation. For clientbound packets the same field records the server-side send/capture observation. Phase 7 uses packet direction to interpret that field correctly rather than treating both as the same semantic time.

The facts retained on every packet include:

- `DUPLICATE`: the same capture sequence was observed more than once;
- `OUT_OF_ORDER`: a lower capture sequence arrived after a higher one;
- `SEQUENCE_GAP`: capture records are missing between observed sequence values;
- `BEFORE_CAPTURE_EPOCH`: capture time predates the declared timeline epoch.

Timeline events are canonically ordered by `(serverTick, captureNanos, sequence)`. Multiple packets in one server tick are preserved. No client tick is inferred or substituted by the Phase 1 timeline layer.

## Replay

`Timeline.Codec` is a fixed versioned binary `PHAC` format. It records target model version, capture epoch, server tick duration, every normalized packet, capture timing, sequence, flags, and supported world-history payloads. The codec is independent of Java object serialization and rejects malformed/truncated/trailing data.

`Replay.replay` retains one frame per timeline event but applies each capture sequence at most once. A duplicate record remains visible as an uncertain replay frame and cannot advance movement, velocity, correction, or acknowledgement state twice.

`Replay.firstDivergence` reports the first differing event or player-state field rather than comparing only final state.

## Phase 7 boundary

Phase 1/3 intentionally own only canonical capture chronology. `Phase7Timing` consumes that chronology and reconstructs bounded client packet-generation, client-processing, simulation, and input timing. It does not create a second packet ordering system.

`Phase7Replay` wraps the existing timeline replay with an immutable Phase 7 timing configuration so timing reconstruction is reproducible from the same capture. The Phase 7 artifact remains independent of wall-clock state at replay time.

## Verified scope

Tests cover normal ordering, same-timestamp ordering, capture gaps, duplicates, delayed/out-of-order records, pre-epoch packets, multiple packets per server tick, all supported packet variants, byte-stable recording, corruption rejection, replay determinism, duplicate semantic safety, and first-divergence reporting.

Complete Minecraft protocol coverage and empirical client tick/generation timing remain outside the information available in a generic capture. Those values are bounded explicitly by Phase 7 rather than invented.
