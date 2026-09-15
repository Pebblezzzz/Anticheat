# Independent 1.21.11 trace capture

The simulator must not write its own expected trace. `TraceExchange` accepts a CSV whose positions, velocities, ground state, input and collision flags come from an independently observed vanilla 1.21.11 client.

Header (required):

```text
tick,x,y,z,vx,vy,vz,onGround,forward,strafe,jump,collision
```

Ticks must strictly increase. The capture adapter must document how it obtains each field and record client build, server build, dimension, game mode, effects, world seed/setup, latency, and all world changes. A trace without those metadata is useful for debugging but cannot certify 1:1 fidelity.

The Paper adapter now records normalized server observations via `PlayerMoveEvent`, `PlayerTeleportEvent`, and `PlayerVelocityEvent`. Install its JAR in a Paper 1.21.11 server's `plugins/` directory and use `/phantom <player>` as an operator to inspect capture health. This is deliberately not called a vanilla trace: Paper does not provide client key input or raw packet receive time through these events. A controlled trace test will require a packet-level adapter plus a prescribed input sequence.
