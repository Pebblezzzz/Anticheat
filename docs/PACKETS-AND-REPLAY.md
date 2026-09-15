# Phases 1 and 3: packet timeline and replay contracts

## Packet normalization and timeline reconstruction

Every raw packet has a monotonically assigned source sequence and a monotonic
receive timestamp. Normalization orders events by `(receiveNanos, sequence)`;
it never drops duplicate, delayed, or out-of-order packets.

The following facts stay attached to the packet as evidence:

- `DUPLICATE`: the same source sequence was observed more than once.
- `OUT_OF_ORDER`: a lower source sequence arrived after a higher one.
- `SEQUENCE_GAP`: a higher sequence exposed missing source sequence numbers.
- `BEFORE_CAPTURE_EPOCH`: receive time precedes the declared capture epoch; it
  is clamped to server tick zero and remains explicit.

Timeline events use a supplied epoch and server tick duration. They are
canonically ordered by `(serverTick, receiveNanos, sequence)`. Multiple
packets in one tick are preserved. A server tick is not asserted to equal a
client simulation tick.

`Timeline.inspect` reports timeline health counts only. It does not classify
cheating.

## Replay format

`Timeline.Codec` writes a versioned, fixed binary `PHAC` format. It includes:

- target model version, capture epoch, and server-tick duration;
- every normalized packet, its timing, sequence, and flags;
- all currently modeled packet variants, including world-history events.

There is no Java object deserialization. Decode rejects bad magic/version,
truncation, invalid lengths/flags/tags, and trailing bytes. Encoding the same
snapshot produces identical bytes.

`Replay.replay` produces a frame before/after every event. `Replay.firstDivergence`
reports the first event or exact player-state field that differs; it never
only compares final state.

## Verified scope

The core has tests for normal ordering, same-timestamp ordering, gaps,
duplicates, delayed packets, pre-epoch packets, multi-packet server ticks,
all supported packet variants, byte-stable recording, corruption rejection,
replay determinism, and first divergence.

These phases preserve information accurately within the adapter's captured
packet set. They do not claim complete Minecraft protocol coverage or infer
missing client-tick data; unavailable information remains absent/uncertain for
the synchronization and validation phases.
