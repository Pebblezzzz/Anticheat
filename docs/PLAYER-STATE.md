# Phase 2: deterministic player-state reconstruction

`State.apply` is a pure transition from one `Player` value and one normalized
packet to another. It has no world reads, clocks, platform objects, or hidden
state. `State.reconstruct` applies that transition over a timeline and emits a
`StateFrame` for every event.

Each frame records:

- `before` and `after` player state;
- facts known after the event;
- facts refreshed by this exact event;
- the most recently received client input, if any;
- explicit environment knowledge.

Supported state domains are position, rotation, velocity, ground state, input,
game mode, effects, teleport acknowledgement state, and packet uncertainty.
Relative teleports are resolved against the preceding reconstructed state;
teleports reset velocity and create an acknowledgement barrier. A mismatched
confirmation remains visible and widens uncertainty.

Unknown is not converted into a plausible value. For example, a movement
packet with no ground bit retains the previous ground value for continuity but
marks the state uncertain and does not list `GROUND` as refreshed. Input is
absent until a client-input packet arrives. Environment is `UNKNOWN` unless a
caller explicitly seeds a known environment; detailed environment evaluation
belongs to the version-specific world/collision phase.

The supplied `Seed` declares which initial facts are authoritative. The
convenience `serverAnchor` declares a known server-side position/rotation/
velocity/ground/game-mode/effects/teleport baseline but deliberately does not
pretend the client input or environment is known.
