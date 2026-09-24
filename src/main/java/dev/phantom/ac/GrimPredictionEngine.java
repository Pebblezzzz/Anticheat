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
      long targetTick,
      Vec3 actualMovementReference,
      boolean lastOnGround) {
    return tick(
        starts, inputOptions, world, maximumCandidates, movementSequence,
        simulationTick, targetTick, actualMovementReference, lastOnGround, null, false);
  }

  public TickResult tick(
      Set<Candidate> starts,
      List<InputConstraint> inputOptions,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence,
      long simulationTick,
      long targetTick,
      Vec3 actualMovementReference,
      boolean lastOnGround,
      MovementEnvironment authoritativeMovementEnvironment) {
    return tick(
        starts, inputOptions, world, maximumCandidates, movementSequence,
        simulationTick, targetTick, actualMovementReference, lastOnGround,
        authoritativeMovementEnvironment, false);
  }

  public TickResult tick(
      Set<Candidate> starts,
      List<InputConstraint> inputOptions,
      WorldSnapshot world,
      int maximumCandidates,
      long movementSequence,
      long simulationTick,
      long targetTick,
      Vec3 actualMovementReference,
      boolean lastOnGround,
      MovementEnvironment authoritativeMovementEnvironment,
      boolean movementTimingUncertain) {
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
            .add(withActualMovementReference(base, actualMovementReference, simulationTick, targetTick));

        /*
         * Grim keeps KnownInput separate from physical sprint/sneak state.
         * Exact held input is therefore a legitimate newer state alternative,
         * but it must not erase the retained physical branch.
         */
        /*
         * Grim keeps the physical locomotion flag independently from KnownInput.
         * A fresh authoritative movement snapshot can therefore disagree with the
         * retained frontier while both remain causally possible at the final tick.
         * Carry that authority state as a final-tick alternative only; historical
         * simulation ticks must remain driven by their retained chronology.
         */
        if (authoritativeMovementEnvironment != null
            && simulationTick == targetTick - 1L) {
          MovementInputState authoritative = new MovementInputState(
              authoritativeMovementEnvironment.sprinting(),
              authoritativeMovementEnvironment.sneaking());
          if (!authoritative.equals(physical)) {
            startsByMovementState
                .computeIfAbsent(authoritative, ignored -> new ArrayList<>())
                .add(withLocomotionState(
                    withActualMovementReference(base, actualMovementReference, simulationTick, targetTick),
                    authoritative.sprinting(), authoritative.sneaking()));
          }
        }

        /*
         * Grim receives sprinting as a separate ENTITY_ACTION state. Phantom's
         * reduced packet model currently has held-key input but not that action
         * event, so an uncertain client/server boundary cannot safely assume
         * that the retained physical sprint flag still applied to this tick.
         * Keep a bounded non-sprinting sibling rather than turning the stale
         * sprint assumption into an exhaustive IMPOSSIBLE result.
         */
        if (movementTimingUncertain
            && inputOption.sprint().orElse(false)
            && physical.sprinting()) {
          MovementInputState nonSprinting = new MovementInputState(false, physical.sneaking());
          startsByMovementState
              .computeIfAbsent(nonSprinting, ignored -> new ArrayList<>())
              .add(withLocomotionState(
                  withActualMovementReference(base, actualMovementReference, simulationTick, targetTick),
                  false, physical.sneaking()));
          exhaustive = false;
        }

        if (inputOption.sprint().isPresent() && inputOption.sneak().isPresent()) {
          MovementInputState requested = new MovementInputState(
              inputOption.sprint().get(), inputOption.sneak().get());
          if (!requested.equals(physical)) {
            startsByMovementState
                .computeIfAbsent(requested, ignored -> new ArrayList<>())
                .add(withLocomotionState(
                    withActualMovementReference(base, actualMovementReference, simulationTick, targetTick),
                    requested.sprinting(), requested.sneaking()));
          }
        }
      }

      for (var entry : startsByMovementState.entrySet()) {
        MovementInputState state = entry.getKey();
        /*
         * Grim's 1.21.2+ end-tick input loop has an important physical-state
         * rule: while actually sprinting and not swimming it suppresses
         * backwards/neutral locomotion and evaluates forward movement. The
         * server-side sprint flag is therefore a movement constraint, not just
         * another field carried into physics.
         */
        boolean swimming = entry.getValue().stream()
            .findFirst()
            .map(Context::movementEnvironment)
            .map(MovementEnvironment::swimmingInput)
            .orElse(false);

        /*
         * A retained sprint action and the current movement axis are separate
         * causal facts. At the final tick, an explicit neutral axis can coexist
         * with a sprint-forward boundary transition, so keep both hypotheses
         * instead of overwriting the observed neutral state.
         */
        LinkedHashSet<Integer> forwardOptions = new LinkedHashSet<>();
        if (inputOption.forward().isPresent()) {
          forwardOptions.add(inputOption.forward().getAsInt());
          if (state.sprinting()
              && !swimming
              && simulationTick == targetTick - 1L
              && inputOption.forward().getAsInt() == 0) {
            forwardOptions.add(1);
          }
        } else if (state.sprinting() && !swimming) {
          forwardOptions.add(1);
        } else {
          forwardOptions.add(0);
        }

        for (int forward : forwardOptions) {
          InputConstraint simulationInput = new InputConstraint(
              java.util.OptionalInt.of(forward),
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
              + " authoritySprint=" + (authoritativeMovementEnvironment == null ? "unknown"
                  : Boolean.toString(authoritativeMovementEnvironment.sprinting()))
              + " authoritySneak=" + (authoritativeMovementEnvironment == null ? "unknown"
                  : Boolean.toString(authoritativeMovementEnvironment.sneaking()))
              + " physicalSprint=" + state.sprinting()
              + " physicalSneak=" + state.sneaking()
              + " movementSprint=" + state.sprinting()
              + " movementSneak=" + state.sneaking()
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
      Vec3 actualMovementReference,
      long simulationTick,
      long targetTick) {
    return actualMovementReference != null && simulationTick == targetTick - 1L
        ? context.withActualMovementReference(actualMovementReference)
        : context;
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
        context.clientVelocity(),
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