package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.Context;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.world.WorldSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.phantom.ac.Maths.Vec3;

/**
 * Grim-style prediction-engine boundary.
 *
 * <p>The engine expands causal input into candidate physical movement states
 * while keeping held input, physical locomotion state, observed movement, and
 * server authority as distinct concepts.</p>
 */
public final class GrimPredictionEngine {
  private final Phase6Reachability reachability;

  public GrimPredictionEngine() {
    this(new Phase6Reachability());
  }

  public GrimPredictionEngine(Phase6Reachability reachability) {
    this.reachability = Objects.requireNonNull(reachability, "reachability");
  }

  public TickResult tick(
      Set<Candidate> starts,
      List<InputConstraint> inputOptions,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence,
      long simulationTick,
      Vec3 actualMovementReference,
      boolean lastOnGround) {
    if (starts.isEmpty()) {
      return new TickResult(
          Set.of(), false,
          List.of("prediction frontier is empty"), List.of());
    }

    LinkedHashSet<Candidate> stepCandidates = new LinkedHashSet<>();
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    List<String> trace = new ArrayList<>();
    boolean exhaustive = true;

    for (InputConstraint inputOption : inputOptions) {
      Map<MovementInputState, List<Context>> startsByMovementState = new LinkedHashMap<>();

      for (Candidate candidate : starts) {
        Context base = candidate.context()
            .withTick(simulationTick)
            .withLastOnGround(lastOnGround);

        MovementEnvironment environment = base.movementEnvironment();
        MovementInputState physical = new MovementInputState(
            environment.sprinting(), environment.sneaking());

        startsByMovementState
            .computeIfAbsent(physical, ignored -> new ArrayList<>())
            .add(withActualMovementReference(base, actualMovementReference));

        /*
         * Grim keeps KnownInput separate from physical sprint/sneak state.
         * Exact held input is therefore a legitimate newer state alternative,
         * but it must not erase the retained physical branch.
         */
        if (inputOption.sprint().isPresent() && inputOption.sneak().isPresent()) {
          MovementInputState requested = new MovementInputState(
              inputOption.sprint().get(), inputOption.sneak().get());
          if (!requested.equals(physical)) {
            startsByMovementState
                .computeIfAbsent(requested, ignored -> new ArrayList<>())
                .add(withLocomotionState(
                    withActualMovementReference(base, actualMovementReference),
                    requested.sprinting(), requested.sneaking()));
          }
        }
      }

      for (var entry : startsByMovementState.entrySet()) {
        MovementInputState state = entry.getKey();
        InputConstraint simulationInput = new InputConstraint(
            inputOption.forward(),
            inputOption.strafe(),
            inputOption.jump(),
            java.util.Optional.of(state.sprinting()),
            java.util.Optional.of(state.sneaking()));

        SearchResult result = reachability.search(
            entry.getValue(),
            List.of(simulationInput),
            ignored -> List.of(new Phase6Reachability.WorldBranch(
                "packet-world@" + simulationTick,
                world,
                true,
                "causal client-visible packet world")),
            ignored -> List.of(new Phase6Reachability.None()),
            Phase6Reachability.SearchConfig.defaults(maximumCandidates));

        trace.add("GRIM_ENGINE_TICK tick=" + simulationTick
            + " physicalSprint=" + state.sprinting()
            + " physicalSneak=" + state.sneaking()
            + " input=" + simulationInput
            + " candidates=" + result.candidates().size()
            + " exhaustive=" + result.exhaustive()
            + " actualMovementReference=" + actualMovementReference);
        stepCandidates.addAll(result.candidates());
        reasons.addAll(result.reasons());

        boolean branchExhaustive =
            result.exhaustive() || exhaustivelyEnumeratedInputEnvelope(result, inputOption);
        if (!branchExhaustive) exhaustive = false;
      }
    }

    if (stepCandidates.size() > maximumCandidates) {
      return new TickResult(
          Set.of(), false,
          List.of("prediction candidate budget exceeded in Grim-style prediction engine"),
          List.copyOf(trace));
    }

    if (stepCandidates.isEmpty()) exhaustive = false;

    reasons.add(
        "prediction engine preserved physical locomotion and exact held-input alternatives");
    return new TickResult(
        Set.copyOf(stepCandidates),
        exhaustive,
        List.copyOf(reasons),
        List.copyOf(trace));
  }

  private static Context withActualMovementReference(
      Context context,
      Vec3 actualMovementReference) {
    return actualMovementReference == null
        ? context
        : context.withActualMovementReference(actualMovementReference);
  }

  private static Context withLocomotionState(
      Context context,
      boolean sprinting,
      boolean sneaking) {
    MovementEnvironment base = context.movementEnvironment();
    MovementEnvironment environment = new MovementEnvironment(
        base.fluid(), base.submerged(), base.climbable(), base.onGround(),
        sprinting, sneaking, base.swimmingInput(), base.gliding(),
        base.fluidSpeedMultiplier(), base.fluidDrag(), base.gravityMultiplier(),
        base.vehicle());
    return new Context(
        context.simulationTick(),
        context.player(),
        context.environment(),
        context.attributes(),
        context.effects(),
        context.pose(),
        environment,
        context.sleeping(),
        context.entityCollisions(),
        context.uncertainty(),
        context.actualMovementReference(),
        context.lastOnGround());
  }

  private static boolean exhaustivelyEnumeratedInputEnvelope(
      SearchResult result,
      InputConstraint constraint) {
    return constraint.isExact() && result.exhaustive();
  }

  private record MovementInputState(boolean sprinting, boolean sneaking) {}

  public record TickResult(
      Set<Candidate> candidates,
      boolean exhaustive,
      List<String> reasons,
      List<String> trace) {
    public TickResult {
      candidates = Set.copyOf(candidates);
      reasons = List.copyOf(reasons);
      trace = List.copyOf(trace);
    }
  }
}
