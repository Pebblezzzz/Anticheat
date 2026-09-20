package dev.phantom.ac;

import java.util.*;
import java.util.function.LongFunction;

import dev.phantom.ac.Packets.ClientInput;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.Simulation.Input;
import dev.phantom.ac.Simulation.PhysicsContext;
import dev.phantom.ac.Simulation.StepResult;
import dev.phantom.ac.Simulation.Vanilla12111Physics;
import dev.phantom.ac.State.Player;

import static dev.phantom.ac.Maths.*;

/**
 * Phase 6 reachability, synchronization, and explainable evidence.
 *
 * <p>The legacy API remains for downstream compatibility. New code should use
 * {@link Phase6Reachability}, which carries the complete rich Phase 5 context.
 * Both APIs preserve the same safety rule: an incomplete finite search is
 * UNCERTAIN and never exposes a provisional candidate subset as evidence.</p>
 */
public final class Validation {
  private Validation() {}

  public enum Verdict { POSSIBLE, UNCERTAIN, IMPOSSIBLE }

  public record Reachability(Verdict verdict, Set<Player> candidates, List<String> reasons) {
    public Reachability {
      candidates = Set.copyOf(candidates);
      reasons = List.copyOf(reasons);
    }
  }

  public record SearchResult(Verdict verdict, Set<Player> candidates, int simulatedTicks,
                             int peakCandidates, int prunedCandidates, List<String> reasons) {
    public SearchResult {
      candidates = Set.copyOf(candidates);
      reasons = List.copyOf(reasons);
      if (simulatedTicks < 0 || peakCandidates < 0 || prunedCandidates < 0) {
        throw new IllegalArgumentException("invalid search metrics");
      }
    }

    public SearchResult(Verdict verdict, Set<Player> candidates, int simulatedTicks, List<String> reasons) {
      this(verdict, candidates, simulatedTicks, candidates.size(), 0, reasons);
    }
  }

  /** Result of evaluating one finite server/client synchronization window. */
  public record TimingSearchResult(Verdict verdict, Set<Player> candidates,
                                    Map<Long, SearchResult> byFirstTick,
                                    int evaluatedOffsets, int skippedOffsets,
                                    List<String> reasons) {
    public TimingSearchResult {
      candidates = Set.copyOf(candidates);
      byFirstTick = Map.copyOf(byFirstTick);
      reasons = List.copyOf(reasons);
      if (evaluatedOffsets < 0 || skippedOffsets < 0) {
        throw new IllegalArgumentException("invalid timing-search metrics");
      }
    }
  }

  public static final class ReachableStates implements Contracts.ReachabilityEngine {
    private static final int MAX_TIMING_OFFSETS = 128;
    private final Vanilla12111Physics physics;

    public ReachableStates(Vanilla12111Physics physics) {
      this.physics = Objects.requireNonNull(physics, "physics");
    }

    /** The original Phase 6 basic envelope: forward/strafe in {-1,0,1}, with jump independently enabled. */
    public Reachability next(Player state, World.Snapshot world, boolean timingUncertain) {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(world, "world");
      if (state.uncertain() || timingUncertain || world.hasUnsupported(Aabb.playerAt(state.position()))) {
        return new Reachability(Verdict.UNCERTAIN, Set.of(), List.of(
            "missing or unsupported state prevents sound exhaustive simulation"));
      }

      Set<Player> out = new HashSet<>();
      for (int forward = -1; forward <= 1; forward++) {
        for (int strafe = -1; strafe <= 1; strafe++) {
          for (boolean jump : new boolean[]{false, true}) {
            out.add(physics.tick(state, new Input(forward, strafe, jump), world));
          }
        }
      }
      return new Reachability(Verdict.POSSIBLE, out, List.of("enumerated 18 discrete basic input combinations"));
    }

    /** Basic compatibility contract; advanced sprint/sneak enumeration uses nextAdvanced. */
    public Reachability next(Player state, World.Snapshot world, boolean timingUncertain, ClientInput observedInput) {
      Objects.requireNonNull(observedInput, "observedInput");
      if (observedInput.sneak() || observedInput.sprint()) {
        return new Reachability(Verdict.UNCERTAIN, Set.of(), List.of(
            "observed sneak or sprint input requires the Phase 6 advanced-input envelope"));
      }
      return nextAdvanced(state, world, timingUncertain, toAdvancedInput(observedInput));
    }

    public Reachability nextAdvanced(Player state, World.Snapshot world, boolean timingUncertain,
                                     AdvancedInput input) {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(world, "world");
      Objects.requireNonNull(input, "input");
      if (state.uncertain() || timingUncertain || world.hasUnsupported(Aabb.playerAt(state.position()))) {
        return new Reachability(Verdict.UNCERTAIN, Set.of(), List.of(
            "missing or unsupported state prevents sound exhaustive simulation"));
      }

      StepResult result = physics.step(new PhysicsContext(0, state, input, world,
          Simulation.Environment.DRY, Simulation.Attributes.DEFAULT));
      if (result.state().uncertain()) {
        return new Reachability(Verdict.UNCERTAIN, Set.of(), List.of(
            "physics transition declared the resulting state uncertain: " + result.diagnostic()));
      }
      return new Reachability(Verdict.POSSIBLE, Set.of(result.state()), List.of(
          "constrained by the complete declared advanced client input"));
    }

    /** Multi-tick search over the original 18-state basic input envelope. */
    public SearchResult advance(Player start, long firstTick, List<Optional<Input>> inputs,
                                LongFunction<World.Snapshot> worlds, int maximumCandidates) {
      Contracts.requireCandidateBudget(maximumCandidates);
      Objects.requireNonNull(start, "start");
      Objects.requireNonNull(inputs, "inputs");
      Objects.requireNonNull(worlds, "worlds");
      if (start.uncertain()) {
        return new SearchResult(Verdict.UNCERTAIN, Set.of(), 0, 0, 0,
            List.of("initial state is uncertain"));
      }

      Set<Player> current = Set.of(start);
      int peak = current.size();
      for (int offset = 0; offset < inputs.size(); offset++) {
        World.Snapshot world = Objects.requireNonNull(worlds.apply(firstTick + offset), "world snapshot");
        Optional<Input> requested = Objects.requireNonNull(inputs.get(offset), "input slot");
        Set<Player> next = new HashSet<>();
        for (Player candidate : current) {
          if (candidate.uncertain() || world.hasUnsupported(Aabb.playerAt(candidate.position()))) {
            return new SearchResult(Verdict.UNCERTAIN, Set.of(), offset, peak, 0,
                List.of("unsupported environment or uncertain state at tick " + (firstTick + offset)));
          }
          if (requested.isPresent()) {
            Player result = physics.tick(candidate, requested.orElseThrow(), world);
            if (result.uncertain()) {
              return new SearchResult(Verdict.UNCERTAIN, Set.of(), offset + 1, peak, 0,
                  List.of("basic transition became uncertain at tick " + (firstTick + offset)));
            }
            next.add(result);
          } else {
            for (int forward = -1; forward <= 1; forward++) {
              for (int strafe = -1; strafe <= 1; strafe++) {
                for (boolean jump : new boolean[]{false, true}) {
                  Player result = physics.tick(candidate, new Input(forward, strafe, jump), world);
                  if (result.uncertain()) {
                    return new SearchResult(Verdict.UNCERTAIN, Set.of(), offset + 1, peak, 0,
                        List.of("basic transition became uncertain at tick " + (firstTick + offset)));
                  }
                  next.add(result);
                }
              }
            }
          }
        }
        peak = Math.max(peak, next.size());
        if (next.size() > maximumCandidates) {
          return new SearchResult(Verdict.UNCERTAIN, Set.of(), offset + 1, peak,
              next.size() - maximumCandidates,
              List.of("reachable-state budget exceeded; exhaustive evidence is unavailable"));
        }
        current = Set.copyOf(next);
      }
      return new SearchResult(Verdict.POSSIBLE, current, inputs.size(), peak, 0,
          List.of("searched " + inputs.size() + " ticks; identical exact states were merged"));
    }

    /** Multi-tick search over forward, strafe, jump, sprint, and sneak. Unknown ticks enumerate all 72 combinations. */
    public SearchResult advanceAdvanced(Player start, long firstTick,
                                         List<Optional<AdvancedInput>> inputs,
                                         LongFunction<World.Snapshot> worlds,
                                         int maximumCandidates) {
      Contracts.requireCandidateBudget(maximumCandidates);
      Objects.requireNonNull(start, "start");
      Objects.requireNonNull(inputs, "inputs");
      Objects.requireNonNull(worlds, "worlds");
      if (start.uncertain()) {
        return new SearchResult(Verdict.UNCERTAIN, Set.of(), 0, 0, 0,
            List.of("initial state is uncertain"));
      }

      Set<Player> current = Set.of(start);
      int peak = current.size();

      for (int offset = 0; offset < inputs.size(); offset++) {
        World.Snapshot world = Objects.requireNonNull(worlds.apply(firstTick + offset), "world snapshot");
        Optional<AdvancedInput> requested = Objects.requireNonNull(inputs.get(offset), "input slot");
        Set<Player> next = new HashSet<>();

        for (Player candidate : current) {
          if (candidate.uncertain() || world.hasUnsupported(Aabb.playerAt(candidate.position()))) {
            return new SearchResult(Verdict.UNCERTAIN, Set.of(), offset, peak, 0,
                List.of("unsupported environment or uncertain state at tick " + (firstTick + offset)));
          }

          if (requested.isPresent()) {
            next.add(stepAdvanced(candidate, requested.orElseThrow(), world, firstTick + offset));
          } else {
            for (AdvancedInput input : allInputs()) {
              Player result = stepAdvanced(candidate, input, world, firstTick + offset);
              if (result.uncertain()) {
                return new SearchResult(Verdict.UNCERTAIN, Set.of(), offset + 1, peak, 0,
                    List.of("simulation encountered an unsupported or uncertain transition at tick "
                        + (firstTick + offset)));
              }
              next.add(result);
            }
          }
        }

        peak = Math.max(peak, next.size());
        if (next.size() > maximumCandidates) {
          return new SearchResult(Verdict.UNCERTAIN, Set.of(), offset + 1, peak,
              next.size() - maximumCandidates,
              List.of("reachable-state budget exceeded; exhaustive evidence is unavailable"));
        }
        current = Set.copyOf(next);
      }

      return new SearchResult(Verdict.POSSIBLE, current, inputs.size(), peak, 0,
          List.of("searched " + inputs.size() + " ticks; identical exact states were merged"));
    }

    /** Applies finite reachability at every client-tick offset in a synchronization window. */
    public TimingSearchResult advanceWithinWindow(Player start, SyncWindow window,
                                                   List<Optional<AdvancedInput>> inputs,
                                                   LongFunction<World.Snapshot> worlds,
                                                   int maximumCandidates) {
      Contracts.requireCandidateBudget(maximumCandidates);
      Objects.requireNonNull(window, "window");

      long span = window.latestClientTick() - window.earliestClientTick() + 1;
      if (span <= 0) {
        throw new IllegalArgumentException("invalid synchronization window");
      }
      if (span > MAX_TIMING_OFFSETS) {
        return new TimingSearchResult(Verdict.UNCERTAIN, Set.of(), Map.of(), 0,
            safeOffsetCount(span), List.of("synchronization window exceeds the declared timing-search envelope"));
      }

      Map<Long, SearchResult> results = new LinkedHashMap<>();
      Set<Player> candidates = new HashSet<>();
      int evaluated = 0;
      for (long firstTick = window.earliestClientTick(); firstTick <= window.latestClientTick(); firstTick++) {
        SearchResult result = advanceAdvanced(start, firstTick, inputs, worlds, maximumCandidates);
        results.put(firstTick, result);
        evaluated++;
        candidates.addAll(result.candidates());
        if (result.verdict() == Verdict.UNCERTAIN) {
          return new TimingSearchResult(Verdict.UNCERTAIN, Set.of(), results, evaluated,
              safeOffsetCount(span) - evaluated, List.of("one or more timing offsets could not be exhaustively simulated"));
        }
        if (candidates.size() > maximumCandidates) {
          return new TimingSearchResult(Verdict.UNCERTAIN, Set.of(), results, evaluated,
              safeOffsetCount(span) - evaluated, List.of("combined reachable-state budget exceeded across timing offsets"));
        }
      }

      if (window.uncertain()) {
        return new TimingSearchResult(Verdict.UNCERTAIN, Set.copyOf(candidates), results, evaluated, 0,
            List.of("all timing offsets were simulated, but synchronization remains uncertain"));
      }
      return new TimingSearchResult(Verdict.POSSIBLE, Set.copyOf(candidates), results, evaluated, 0,
          List.of("all client-tick offsets in the synchronization window were exhaustively simulated"));
    }

    private Player stepAdvanced(Player state, AdvancedInput input, World.Snapshot world, long tick) {
      StepResult result = physics.step(new PhysicsContext(tick, state, input, world,
          Simulation.Environment.DRY, Simulation.Attributes.DEFAULT));
      return result.state();
    }
  }

  /** Deterministic 72-state input envelope used for unknown client input ticks. */
  public static List<AdvancedInput> allInputs() {
    List<AdvancedInput> inputs = new ArrayList<>(72);
    for (int forward = -1; forward <= 1; forward++) {
      for (int strafe = -1; strafe <= 1; strafe++) {
        for (boolean jump : new boolean[]{false, true}) {
          for (boolean sprint : new boolean[]{false, true}) {
            for (boolean sneak : new boolean[]{false, true}) {
              inputs.add(new AdvancedInput(forward, strafe, jump, sprint, sneak));
            }
          }
        }
      }
    }
    return List.copyOf(inputs);
  }

  private static AdvancedInput toAdvancedInput(ClientInput input) {
    return new AdvancedInput(axis(input.forward(), input.backward()), axis(input.left(), input.right()),
        input.jump(), input.sprint(), input.sneak());
  }

  private static int axis(boolean positive, boolean negative) {
    return positive == negative ? 0 : positive ? 1 : -1;
  }

  private static int safeOffsetCount(long span) {
    return span > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) span;
  }

  public record SyncWindow(long earliestClientTick, long latestClientTick, boolean uncertain, List<String> reasons) {
    public SyncWindow {
      reasons = List.copyOf(reasons);
      if (earliestClientTick < 0 || latestClientTick < 0 || earliestClientTick > latestClientTick) {
        throw new IllegalArgumentException("invalid synchronization window");
      }
    }
  }

  public static final class DefaultSynchronizer implements Contracts.Synchronizer {
    @Override
    public SyncWindow reconstruct(long serverTick, long roundTripNanos, long jitterNanos, boolean awaitingTeleport) {
      return checkedSynchronize(serverTick, roundTripNanos, jitterNanos, awaitingTeleport);
    }
  }

  public static SyncWindow synchronize(long serverTick, long rttNanos, long jitterNanos, boolean awaitingTeleport) {
    return checkedSynchronize(serverTick, rttNanos, jitterNanos, awaitingTeleport);
  }

  public static SyncWindow checkedSynchronize(long serverTick, long rttNanos, long jitterNanos, boolean awaitingTeleport) {
    if (serverTick < 0 || rttNanos < 0 || jitterNanos < 0) {
      throw new IllegalArgumentException("timing values must be non-negative");
    }
    long half = (rttNanos + jitterNanos + 99_999_999L) / 100_000_000L;
    boolean uncertain = jitterNanos > 0 || awaitingTeleport;
    return new SyncWindow(Math.max(0, serverTick - half), serverTick + half, uncertain,
        uncertain
            ? List.of("arrival timing or teleport acknowledgement is ambiguous")
            : List.of("stable acknowledgement timing"));
  }

  public record Evidence(Verdict verdict, String rule, double nearestHorizontalDistance, List<String> reasons) {
    public Evidence {
      reasons = List.copyOf(reasons);
    }
  }

  public static final class ReachabilityValidator implements Contracts.MovementValidator {
    @Override
    public Evidence compare(Player observed, Reachability reachable) {
      return validate(observed, reachable);
    }
  }

  /** Synchronization ambiguity is explicitly elevated to uncertainty before movement evidence is interpreted. */
  public static Evidence validate(Player observed, Reachability reachable, SyncWindow synchronization) {
    Objects.requireNonNull(synchronization, "synchronization");
    if (synchronization.uncertain()) {
      return new Evidence(Verdict.UNCERTAIN, "MOVEMENT_REACHABILITY", Double.NaN,
          List.of("movement timing is uncertain within client tick window "
              + synchronization.earliestClientTick() + ".." + synchronization.latestClientTick()));
    }
    return validate(observed, reachable);
  }

  public static Evidence validate(Player observed, Reachability reachable) {
    Objects.requireNonNull(observed, "observed");
    Objects.requireNonNull(reachable, "reachable");
    if (reachable.verdict() == Verdict.UNCERTAIN) {
      return new Evidence(Verdict.UNCERTAIN, "MOVEMENT_REACHABILITY", Double.NaN, reachable.reasons());
    }
    if (reachable.candidates().stream().anyMatch(s -> sameState(s, observed))) {
      return new Evidence(Verdict.POSSIBLE, "MOVEMENT_REACHABILITY", 0,
          List.of("observed position, velocity, and ground state are a simulated reachable state"));
    }
    double d = reachable.candidates().stream()
        .mapToDouble(s -> Math.sqrt(s.position().horizontalDistanceSquared(observed.position())))
        .min().orElse(Double.POSITIVE_INFINITY);
    return new Evidence(Verdict.IMPOSSIBLE, "MOVEMENT_REACHABILITY", d,
        List.of("no candidate produced the observed position, velocity, and ground state in the declared input envelope"));
  }

  private static boolean sameState(Player a, Player b) {
    return a.position().equals(b.position())
        && a.velocity().equals(b.velocity())
        && a.onGround() == b.onGround();
  }
}
