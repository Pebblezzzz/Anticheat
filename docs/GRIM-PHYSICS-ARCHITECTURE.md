# Grim-style physics architecture in Phantom

Phantom now models movement as a set of independent state and lifecycle
boundaries, following the architecture used by Grim while keeping Phantom's
own clean-room 1.21.11 mechanics.

## State boundaries

Held client input is evidence. Physical sprinting and sneaking are simulation
state. A newer exact input packet can create a new physical-state hypothesis,
but it does not overwrite an already-retained client-physics hypothesis.

Client physics state is separate from server authority. Server position and
velocity are causal evidence and correction/anchor inputs, not substitutes for
the client's per-tick movement velocity.

Observed movement is represented separately and can be supplied to collision
selection without replacing client velocity.

World visibility, input chronology, timing reliability, movement hypotheses,
and uncertainty remain distinct evidence domains.

## Tick boundaries

The intended flow is:

    packet/timing capture
      -> client state + authority state
      -> GrimPredictionEngine
      -> candidate movement expansion
      -> Phase 6 reachable-state envelope
      -> GrimMovementTicker
      -> canonical 1.21.11 physics
      -> observed movement comparison
      -> retained client frontier

GrimPredictionEngine is responsible for candidate expansion and locomotion
alternatives. GrimMovementTicker is the explicit boundary around a single
world-bound movement tick.

## Collision and uncertainty

RichWorldCollision retains the epsilon-aware axis collision behavior already
ported from Grim's collision model. The observed movement vector remains a
separate comparison/reference value.

Unknown world facts and uncertain chronology do not force an invented velocity
or locomotion state.

This is an architecture-level clean-room implementation, not a source-code
copy. Version-pinned Minecraft mechanics remain implemented in Phantom's own
physics code.
