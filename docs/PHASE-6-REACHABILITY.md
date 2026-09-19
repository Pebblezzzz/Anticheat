# Phase 6 — Legitimate reachable-state search

Phase 6 answers one question only:

> What states could a legitimate vanilla 1.21.11 client have reached from the information currently available?

It does not decide whether a player cheated and it does not implement alerts, punishments, thresholds, or policy. Those decisions belong outside Phase 6.

## Inputs and ownership

The authoritative transition inputs are:

- an immutable Phase 6 Context containing PlayerState plus movement environment, attributes, effects, pose, entity-collision history, and explicit uncertainty;
- one or more initial contexts;
- a per-relative-tick InputConstraint;
- zero or more WorldBranch hypotheses per simulation tick;
- ordered ExternalPath hypotheses for authoritative velocity/correction transitions;
- a bounded simulation horizon and deterministic search budgets.

Phase 5 is the only movement transition authority. For every successful candidate transition Phase 6 constructs a Vanilla12111RichPhysics.Context and calls Phase 5 exactly once. Phase 6 contains no second copy of gravity, acceleration, collision, step-up, fluid, climb, or movement constants.

Phase 7 owns synchronization reconstruction. Phase 6 only consumes the already reconstructed timing window through searchWithinTimingWindow.

## State-space exploration

For each relative simulation step, Phase 6 explores the Cartesian product:

`parents × world hypotheses × external paths × realizable inputs`

Every produced state is the direct result of one Phase 5 transition. Multiple initial states and timing offsets remain separate roots until safe equivalence proves that a merge is valid.

An external path is an ordered sequence of authoritative transitions. searchWithTransitionBranches permits several alternative paths to represent unresolved reconstruction without treating alternatives as if they all happened at once.

## Input uncertainty

InputConstraint represents each known field independently:

- forward: -1, 0, or 1;
- strafe: -1, 0, or 1;
- jump;
- sprint;
- sneak.

An exact constraint produces one input. An unspecified field enumerates the complete declared AdvancedInput envelope. Unknown or missing input is therefore not silently converted into a single choice: the search explores all declared possibilities and the final result is UNCERTAIN because the information was incomplete.

The input envelope is deterministic and comes from the existing complete 72-state AdvancedInput enumeration.

## Timing uncertainty

Phase 6 does not equate a server tick with a client movement tick. searchWithinTimingWindow evaluates every supplied client simulation tick in the inclusive envelope and unions the resulting legitimate candidates.

An explicitly uncertain timing envelope remains UNCERTAIN even when one or more supplied offsets produce matching candidates. Timing uncertainty is evidence about the search boundary, not an anti-cheat verdict.

## World knowledge

The engine distinguishes four Phase 6 knowledge states:

| State | Meaning |
|---|---|
| KNOWN | The supplied world branch is exhaustive and the relevant snapshot coverage is known. |
| UNKNOWN | The supplied branch is explicitly non-exhaustive; additional client-visible world hypotheses may exist. |
| UNLOADED | Relevant geometry is outside the client-visible loaded world model. |
| UNSUPPORTED | Relevant geometry was received but its 1.21.11 state/shape is not verified. |

UNLOADED and UNSUPPORTED are never treated as air.

A non-exhaustive branch with otherwise known geometry may still be simulated through Phase 5. The generated candidate carries WORLD uncertainty so the legitimate state is retained without pretending the hypothesis envelope is complete.

When the relevant starting/environment geometry is not known, that transition is recorded as an elimination and the overall search remains UNCERTAIN.

## Candidate representation

Each Candidate retains:

- immutable PlayerState and Phase 6 Context;
- simulation/client tick;
- optional server-tick association supplied by the caller;
- timing reference;
- world-branch reference and world knowledge;
- movement mode;
- input assumption;
- transition diagnostics;
- parent/path provenance.

Provenance also retains merged parent identifiers, bounded alternative assumptions, and Phase 5 diagnostics. A candidate is therefore replayable and explainable without requiring Phase 8 to reverse-engineer internal search state.

## Candidate equivalence and merging

Candidate merging uses a canonical full-context key plus:

- world branch reference;
- timing reference;
- optional server-tick association;
- movement mode.

The Context key includes position, velocity, rotation, ground state, gamemode, effects, pending correction, input, attributes, pose, environment, tick range, uncertainty, movement environment, sleeping state, and entity collision state.

Candidates from different world branches never merge merely because their positions are similar. Merging is exact at the represented state abstraction. Alternative merged paths are retained in Provenance.

World and external branch identifiers are required to be unique for a single simulated tick. This prevents two semantically different hypotheses from accidentally sharing one equivalence key.

## Deterministic pruning and budgets

SearchConfig exposes:

- maximumCandidates;
- maximumHorizonTicks;
- maximumBranchesPerTransition;
- maximumSimulationSteps;
- optional serverTickAssociation;
- timingReference.

The priority rule is deterministic: candidates are ordered by their canonical full-context key and the lexicographically greatest excess candidate is pruned first.

Budget exhaustion is never IMPOSSIBLE. It sets budgetReached and forces UNCERTAIN. Retained candidates are explicitly marked as a non-exhaustive result; no sampled subset is presented as proof of impossibility.

Elimination diagnostics are bounded to a deterministic maximum so diagnostic growth cannot become an unbounded memory side channel.

## Result semantics

Phase 6 search itself returns only:

### POSSIBLE

The complete finite envelope supplied to Phase 6 was exhaustively represented for the requested horizon and at least one legitimate candidate survives.

### UNCERTAIN

Some part of the legitimate state space could not be exhaustively represented, including unknown input, incomplete world hypotheses, unsupported/unloaded geometry, uncertain Phase 5 transitions, incomplete timing offsets, candidate/branch/step budgets, or missing initial information.

Candidates that were successfully generated before uncertainty are retained whenever doing so is sound.

### IMPOSSIBLE

Phase 6 search does not manufacture this verdict. It is produced only by the observation-comparison API after an exhaustive Phase 6 result has been obtained and every candidate fails every declared observed field.

This keeps search from confusing "not fully explored" with "not reachable."

## Observation matching

compare accepts an Observation with an explicit set of known fields. It returns:

- matching provenance witnesses;
- closest reachable candidates;
- per-candidate mismatch dimensions and numeric distances;
- transition eliminations;
- first-divergence metadata.

Position matching uses the existing 0.01 metre numerical envelope. Other declared fields are compared deterministically according to their represented state values.

When the search is UNCERTAIN, compare remains UNCERTAIN even when a retained candidate happens to match. A matching candidate is still useful evidence, but incomplete search cannot be upgraded to exhaustive evidence.

## First divergence and replay

firstDivergence accepts ordered SearchResult/Observation pairs and finds the earliest fully exhaustive observation that is not reachable. It preserves the previous reachable candidate, mismatch dimensions, and the earliest recorded transition elimination.

Phase6Replay stores the search configuration and replay inputs. replay and replayTiming invoke the same Phase 6 engine again. canonicalText and canonicalSignature order state by canonical keys rather than hash iteration order.

The deterministic search signature excludes wall-clock processing time. Runtime is an external benchmark metric because including wall-clock time would make replay signatures non-deterministic.

## Metrics

SearchMetrics records:

- generated candidates;
- merged candidates;
- pruned candidates;
- branch evaluations;
- Phase 5 simulation steps;
- peak candidate count;
- evaluated ticks;
- whether a budget was reached;
- whether the search was exhaustive.

Phase6PerformanceBenchmark reports elapsed runtime separately and provides both a normal one-tick full-input benchmark and a deterministic multi-tick adversarial-growth benchmark.

## External transitions and state preservation

Velocity impulses, teleport corrections, and teleport confirmations are represented as Phase 6 transitions and preserve the rich PlayerState fields. A mismatched teleport confirmation becomes uncertainty; an awaiting correction cannot be silently simulated as ordinary movement.

These are reachability transitions only. No movement rejection, violation count, alert, or punishment exists in Phase 6.

## Testing boundary

The Phase 6 regression layer covers:

- exact walking/sprinting/sneaking/strafing/diagonal movement;
- jumping, falling, landing and collision-heavy slab/stair/corner/edge fixtures;
- water, lava, climbable and swimming states;
- effects and attributes;
- velocity/knockback;
- corrections and confirmations;
- unknown and partial input;
- timing windows;
- unknown/non-exhaustive/unloaded/unsupported worlds;
- multiple initial states and alternative external paths;
- deterministic merging and deterministic pruning;
- branch and simulation-step budgets;
- impossible synthetic observations;
- uncertainty-to-UNCERTAIN invariants;
- first divergence and replay determinism;
- search metrics and adversarial candidate growth.

## Known correctness limitation

Phase 6's numerical correctness is bounded by the correctness of the authoritative Phase 5 1.21.11 simulator and the world-state model supplied to it. The repository does not contain an independent real-client 1.21.11 corpus sufficient to establish empirical parity for every movement mechanic.

Therefore this audit establishes a sound deterministic Phase 6 architecture and regression contract, not a claim that every supported mechanic has been empirically matched against a licensed vanilla client trace.
