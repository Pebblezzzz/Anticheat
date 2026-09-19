package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Simulation.AdvancedInput;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.WorldQueries;

import java.io.Serializable;
import java.util.*;
import java.util.function.LongFunction;

/** Sound Phase 6 finite reachable-state search over the complete Phase 5 context. */
public final class Phase6Reachability {
  public static final int MAX_HORIZON_TICKS=512, MAX_TIMING_OFFSETS=128, MAX_PROVENANCE_PARENTS=8;
  /** Small numerical envelope for client/server position reconstruction. */
  static final double POSITION_MATCH_TOLERANCE = 0.01D;
  public enum Verdict { POSSIBLE, UNCERTAIN, IMPOSSIBLE }
  public enum UncertainDimension { POSITION, ROTATION, VELOCITY, GROUND, INPUT, ENVIRONMENT, ATTRIBUTES, EFFECTS, POSE, TELEPORT, WORLD, TIMING }

  public record Context(long simulationTick,Player player,Simulation.Environment environment,Simulation.Attributes attributes,
                        MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping,
                        EntityCollisions entityCollisions,Set<UncertainDimension> uncertainty) implements Serializable {
    public Context(long tick,Player player,Simulation.Environment env,Simulation.Attributes attributes,MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping){this(tick,player,env,attributes,effects,pose,movementEnvironment,sleeping,EntityCollisions.of(List.of()),Set.of());}
    public Context(long tick,Player player,Simulation.Environment env,Simulation.Attributes attributes,MovementEffects effects,Pose pose,MovementEnvironment movementEnvironment,boolean sleeping,EntityCollisions entityCollisions){this(tick,player,env,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,Set.of());}
    public Context {if(simulationTick<0)throw new IllegalArgumentException("simulationTick must be non-negative");Objects.requireNonNull(player);Objects.requireNonNull(environment);Objects.requireNonNull(attributes);Objects.requireNonNull(effects);Objects.requireNonNull(pose);Objects.requireNonNull(movementEnvironment);Objects.requireNonNull(entityCollisions);uncertainty=Set.copyOf(uncertainty);}
    public Context withTick(long tick){return new Context(tick,player,environment,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,uncertainty);}
    public Context withUncertainty(UncertainDimension... dimensions){EnumSet<UncertainDimension> u=EnumSet.noneOf(UncertainDimension.class);u.addAll(uncertainty);u.addAll(List.of(dimensions));return new Context(simulationTick,player,environment,attributes,effects,pose,movementEnvironment,sleeping,entityCollisions,u);}
  }

  public record InputConstraint(OptionalInt forward,OptionalInt strafe,Optional<Boolean> jump,Optional<Boolean> sprint,Optional<Boolean> sneak) implements Serializable {
    public InputConstraint {Objects.requireNonNull(forward);Objects.requireNonNull(strafe);Objects.requireNonNull(jump);Objects.requireNonNull(sprint);Objects.requireNonNull(sneak);if(forward.isPresent()&&Math.abs(forward.getAsInt())>1)throw new IllegalArgumentException("forward must be -1..1");if(strafe.isPresent()&&Math.abs(strafe.getAsInt())>1)throw new IllegalArgumentException("strafe must be -1..1");}
    public static InputConstraint any(){return new InputConstraint(OptionalInt.empty(),OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty());}
    public static InputConstraint exact(AdvancedInput i){Objects.requireNonNull(i);return new InputConstraint(OptionalInt.of(i.forward()),OptionalInt.of(i.strafe()),Optional.of(i.jump()),Optional.of(i.sprint()),Optional.of(i.sneak()));}
    public static InputConstraint fromClientInput(Packets.ClientInput i){Objects.requireNonNull(i);return new InputConstraint(OptionalInt.of(axis(i.forward(),i.backward())),OptionalInt.of(axis(i.right(),i.left())),Optional.of(i.jump()),Optional.of(i.sprint()),Optional.of(i.sneak()));}
    public List<AdvancedInput> enumerate(){List<AdvancedInput> out=new ArrayList<>();for(AdvancedInput i:Validation.allInputs())if(matches(i))out.add(i);return List.copyOf(out);}
    private boolean matches(AdvancedInput i){return(!forward.isPresent()||forward.getAsInt()==i.forward())&&(!strafe.isPresent()||strafe.getAsInt()==i.strafe())&&(!jump.isPresent()||jump.get()==i.jump())&&(!sprint.isPresent()||sprint.get()==i.sprint())&&(!sneak.isPresent()||sneak.get()==i.sneak());}
    private static int axis(boolean p,boolean n){return p==n?0:p?1:-1;}
  }

  public record WorldBranch(String id,WorldSnapshot world,boolean exhaustive,String description) implements Serializable {public WorldBranch{if(id==null||id.isBlank())throw new IllegalArgumentException("world branch id is required");Objects.requireNonNull(world);if(description==null||description.isBlank())throw new IllegalArgumentException("description is required");}}
  public sealed interface ExternalTransition extends Serializable permits None,VelocityImpulse,TeleportCorrection,TeleportConfirmation{}
  public record None() implements ExternalTransition{}
  public record VelocityImpulse(Maths.Vec3 impulse,String source) implements ExternalTransition{public VelocityImpulse{Objects.requireNonNull(impulse);if(source==null||source.isBlank())throw new IllegalArgumentException("source is required");}}
  public record TeleportCorrection(int id,Maths.Vec3 position,Maths.Vec3 velocity,Pose pose,boolean awaitingConfirmation,Float yaw,Float pitch) implements ExternalTransition{
    public TeleportCorrection(int id,Maths.Vec3 position,Maths.Vec3 velocity,Pose pose,boolean awaitingConfirmation){
      this(id,position,velocity,pose,awaitingConfirmation,null,null);
    }
    public TeleportCorrection{
      if(id<0)throw new IllegalArgumentException("teleport id must be non-negative");
      Objects.requireNonNull(position);Objects.requireNonNull(velocity);Objects.requireNonNull(pose);
      if(yaw!=null&&!Float.isFinite(yaw))throw new IllegalArgumentException("teleport yaw must be finite");
      if(pitch!=null&&!Float.isFinite(pitch))throw new IllegalArgumentException("teleport pitch must be finite");
    }
  }
  public record TeleportConfirmation(int id) implements ExternalTransition{public TeleportConfirmation{if(id<0)throw new IllegalArgumentException("teleport id must be non-negative");}}
  public enum MovementMode { SURVIVAL_GROUND, SURVIVAL_AIR, FLUID, CLIMBABLE, GLIDING, NON_SURVIVAL, UNKNOWN }
  public enum WorldKnowledge { KNOWN, UNKNOWN, UNLOADED, UNSUPPORTED }

  public record SearchConfig(
      int maximumCandidates,
      int maximumHorizonTicks,
      int maximumBranchesPerTransition,
      long maximumSimulationSteps,
      Long serverTickAssociation,
      String timingReference) implements Serializable {
    public SearchConfig {
      Contracts.requireCandidateBudget(maximumCandidates);
      if (maximumHorizonTicks < 1 || maximumHorizonTicks > MAX_HORIZON_TICKS) {
        throw new IllegalArgumentException("maximumHorizonTicks must be 1.." + MAX_HORIZON_TICKS);
      }
      if (maximumBranchesPerTransition < 1) throw new IllegalArgumentException("maximumBranchesPerTransition must be positive");
      if (maximumSimulationSteps < 1) throw new IllegalArgumentException("maximumSimulationSteps must be positive");
      if (serverTickAssociation != null && serverTickAssociation < 0) {
        throw new IllegalArgumentException("serverTickAssociation must be non-negative");
      }
      if (timingReference == null || timingReference.isBlank()) {
        throw new IllegalArgumentException("timingReference is required");
      }
    }
    public static SearchConfig defaults(int maximumCandidates) {
      return new SearchConfig(maximumCandidates, MAX_HORIZON_TICKS, 16_384, 250_000L, null, "phase6");
    }
    public SearchConfig withTimingReference(String reference) {
      return new SearchConfig(maximumCandidates, maximumHorizonTicks, maximumBranchesPerTransition,
          maximumSimulationSteps, serverTickAssociation, reference);
    }
    public SearchConfig withServerTickAssociation(Long serverTick) {
      return new SearchConfig(maximumCandidates, maximumHorizonTicks, maximumBranchesPerTransition,
          maximumSimulationSteps, serverTick, timingReference);
    }
  }

  public record SearchMetrics(
      long generatedCandidates,
      long mergedCandidates,
      long prunedCandidates,
      long branchEvaluations,
      long simulationSteps,
      int peakCandidates,
      int evaluatedTicks,
      boolean budgetReached,
      boolean exhaustive) implements Serializable {
    public SearchMetrics {
      if (generatedCandidates < 0 || mergedCandidates < 0 || prunedCandidates < 0
          || branchEvaluations < 0 || simulationSteps < 0 || peakCandidates < 0
          || evaluatedTicks < 0) {
        throw new IllegalArgumentException("negative search metric");
      }
    }
  }

  public record Elimination(
      long simulationTick,
      long parentCandidateId,
      String stage,
      String reason,
      String inputAssumption,
      String worldReference,
      String externalTransition,
      WorldKnowledge worldKnowledge,
      List<String> diagnostics) implements Serializable {
    public Elimination {
      if (simulationTick < 0 || parentCandidateId < -1) {
        throw new IllegalArgumentException("invalid elimination provenance");
      }
      Objects.requireNonNull(stage);
      Objects.requireNonNull(reason);
      Objects.requireNonNull(inputAssumption);
      Objects.requireNonNull(worldReference);
      Objects.requireNonNull(externalTransition);
      Objects.requireNonNull(worldKnowledge);
      diagnostics = List.copyOf(diagnostics);
    }
  }

  public record ExternalPath(String id, List<ExternalTransition> transitions, String description) implements Serializable {
    public ExternalPath {
      if (id == null || id.isBlank()) throw new IllegalArgumentException("external path id is required");
      transitions = List.copyOf(transitions);
      if (description == null || description.isBlank()) throw new IllegalArgumentException("external path description is required");
    }
    public static ExternalPath of(String id, List<ExternalTransition> transitions, String description) {
      return new ExternalPath(id, transitions, description);
    }
  }

  public record Provenance(
      long candidateId,
      long parentId,
      long tick,
      String input,
      String worldBranch,
      String externalTransition,
      List<String> causes,
      int mergedPathCount,
      List<Long> mergedParentIds,
      List<String> assumptions) implements Serializable {
    public Provenance(
        long candidateId,
        long parentId,
        long tick,
        String input,
        String worldBranch,
        String externalTransition,
        List<String> causes,
        int mergedPathCount,
        List<Long> mergedParentIds) {
      this(candidateId, parentId, tick, input, worldBranch, externalTransition,
          causes, mergedPathCount, mergedParentIds, List.of());
    }
    public Provenance {
      if (candidateId < 0 || parentId < -1 || tick < 0 || mergedPathCount < 1) {
        throw new IllegalArgumentException("invalid provenance");
      }
      Objects.requireNonNull(input);
      Objects.requireNonNull(worldBranch);
      Objects.requireNonNull(externalTransition);
      causes = List.copyOf(causes);
      mergedParentIds = List.copyOf(mergedParentIds);
      assumptions = List.copyOf(assumptions);
    }
  }

  public record Candidate(
      long id,
      Context context,
      Provenance provenance,
      Long serverTickAssociation,
      String timingReference,
      String worldReference,
      WorldKnowledge worldKnowledge,
      MovementMode movementMode,
      String inputAssumption,
      List<String> transitionDiagnostics) implements Serializable {
    public Candidate {
      if (id < 0) throw new IllegalArgumentException("candidate id must be non-negative");
      Objects.requireNonNull(context);
      Objects.requireNonNull(provenance);
      if (serverTickAssociation != null && serverTickAssociation < 0) {
        throw new IllegalArgumentException("serverTickAssociation must be non-negative");
      }
      Objects.requireNonNull(timingReference);
      Objects.requireNonNull(worldReference);
      Objects.requireNonNull(worldKnowledge);
      Objects.requireNonNull(movementMode);
      Objects.requireNonNull(inputAssumption);
      transitionDiagnostics = List.copyOf(transitionDiagnostics);
    }
    public Candidate(long id, Context context, Provenance provenance) {
      this(id, context, provenance, null, "unspecified",
          provenance.worldBranch(), WorldKnowledge.KNOWN,
          movementModeFor(context), provenance.input(), provenance.causes());
    }
  }

  public record SearchResult(
      Verdict verdict,
      Set<Candidate> candidates,
      int simulatedTicks,
      int peakCandidates,
      int mergedStates,
      int nonExhaustiveWorldBranches,
      int uncertainTransitions,
      int provenanceMerges,
      List<String> reasons,
      SearchMetrics metrics,
      List<Elimination> eliminations) implements Serializable {
    public SearchResult(
        Verdict verdict,
        Set<Candidate> candidates,
        int simulatedTicks,
        int peakCandidates,
        int mergedStates,
        int nonExhaustiveWorldBranches,
        int uncertainTransitions,
        int provenanceMerges,
        List<String> reasons) {
      this(verdict, candidates, simulatedTicks, peakCandidates, mergedStates,
          nonExhaustiveWorldBranches, uncertainTransitions, provenanceMerges, reasons,
          new SearchMetrics(
              mergedStates, mergedStates, 0, 0, 0, peakCandidates, simulatedTicks,
              false, verdict == Verdict.POSSIBLE),
          List.of());
    }
    public SearchResult {
      Objects.requireNonNull(verdict);
      candidates = Set.copyOf(candidates);
      reasons = List.copyOf(reasons);
      Objects.requireNonNull(metrics);
      eliminations = List.copyOf(eliminations);
    }
    public boolean exhaustive() {
      return verdict == Verdict.POSSIBLE && metrics.exhaustive();
    }
  }

  public record TimingSearchResult(
      Verdict verdict,
      Set<Candidate> candidates,
      Map<Long, SearchResult> byFirstTick,
      int evaluatedOffsets,
      int skippedOffsets,
      List<String> reasons,
      SearchMetrics metrics,
      List<Elimination> eliminations) implements Serializable {
    public TimingSearchResult(
        Verdict verdict,
        Set<Candidate> candidates,
        Map<Long, SearchResult> byFirstTick,
        int evaluatedOffsets,
        int skippedOffsets,
        List<String> reasons) {
      this(verdict, candidates, byFirstTick, evaluatedOffsets, skippedOffsets, reasons,
          aggregateMetrics(byFirstTick.values()), flattenEliminations(byFirstTick.values()));
    }
    public TimingSearchResult {
      Objects.requireNonNull(verdict);
      candidates = Set.copyOf(candidates);
      byFirstTick = Map.copyOf(byFirstTick);
      reasons = List.copyOf(reasons);
      Objects.requireNonNull(metrics);
      eliminations = List.copyOf(eliminations);
      if (evaluatedOffsets < 0 || skippedOffsets < 0) throw new IllegalArgumentException("invalid timing-search metrics");
    }
  }

  public enum ObservedField { POSITION, VELOCITY, ROTATION, GROUND, GAMEMODE, EFFECTS, TELEPORT_PENDING }

  public record Observation(Player observed, Set<ObservedField> known) implements Serializable {
    public Observation {
      Objects.requireNonNull(observed);
      known = Set.copyOf(known);
      if (known.isEmpty()) throw new IllegalArgumentException("known observations required");
    }
  }

  public record ObservationMismatch(
      long candidateId,
      Set<ObservedField> dimensions,
      double positionDistance,
      double velocityDistance,
      double yawDistance,
      double pitchDistance,
      List<String> details) implements Serializable {
    public ObservationMismatch {
      if (candidateId < 0) throw new IllegalArgumentException("candidateId must be non-negative");
      dimensions = Set.copyOf(dimensions);
      details = List.copyOf(details);
    }
    public double score() {
      int dimensionPenalty = dimensions.size() * 1_000_000;
      double numeric = (Double.isFinite(positionDistance) ? positionDistance : 0.0)
          + (Double.isFinite(velocityDistance) ? velocityDistance : 0.0)
          + Math.abs(yawDistance) + Math.abs(pitchDistance);
      return dimensionPenalty + numeric;
    }
  }

  public record FirstDivergence(
      int observationIndex,
      long observedSimulationTick,
      Optional<Candidate> lastReachableCandidate,
      Optional<Elimination> firstElimination,
      Set<ObservedField> mismatchDimensions,
      List<String> reasons) implements Serializable {
    public FirstDivergence {
      if (observationIndex < 0 || observedSimulationTick < 0) {
        throw new IllegalArgumentException("invalid first-divergence location");
      }
      lastReachableCandidate = Objects.requireNonNull(lastReachableCandidate);
      firstElimination = Objects.requireNonNull(firstElimination);
      mismatchDimensions = Set.copyOf(mismatchDimensions);
      reasons = List.copyOf(reasons);
    }
  }

  public record Evidence(
      Verdict verdict,
      int matchingCandidates,
      List<Provenance> witnesses,
      List<String> reasons,
      List<Candidate> closestCandidates,
      List<ObservationMismatch> mismatches,
      List<Elimination> eliminations,
      Optional<FirstDivergence> firstDivergence) implements Serializable {
    public Evidence(Verdict verdict, int matchingCandidates, List<Provenance> witnesses, List<String> reasons) {
      this(verdict, matchingCandidates, witnesses, reasons, List.of(), List.of(), List.of(), Optional.empty());
    }
    public Evidence {
      Objects.requireNonNull(verdict);
      witnesses = List.copyOf(witnesses);
      reasons = List.copyOf(reasons);
      closestCandidates = List.copyOf(closestCandidates);
      mismatches = List.copyOf(mismatches);
      eliminations = List.copyOf(eliminations);
      firstDivergence = Objects.requireNonNull(firstDivergence);
    }
  }

  private record CandidateKey(
      String context,
      String worldReference,
      String timingReference,
      Long serverTickAssociation,
      MovementMode movementMode) {}

  private static final int MAX_DIAGNOSTICS = 256;
  private static final int MAX_CLOSEST_CANDIDATES = 4;
  private final Vanilla12111RichPhysics physics;
  public Phase6Reachability(Vanilla12111RichPhysics physics){this.physics=Objects.requireNonNull(physics);}

  public SearchResult search(
      Context start,
      List<InputConstraint> inputs,
      LongFunction<List<WorldBranch>> worlds,
      LongFunction<List<ExternalTransition>> externalTransitions,
      int maximumCandidates) {
    return search(
        List.of(start), inputs, worlds,
        tick -> List.of(new ExternalPath(
            "ordered-external@" + tick,
            Objects.requireNonNull(externalTransitions.apply(tick), "external transitions"),
            "ordered external transitions supplied for the simulation tick")),
        SearchConfig.defaults(maximumCandidates));
  }

  public SearchResult search(
      Collection<Context> starts,
      List<InputConstraint> inputs,
      LongFunction<List<WorldBranch>> worlds,
      LongFunction<List<ExternalTransition>> externalTransitions,
      SearchConfig config) {
    return search(
        starts, inputs, worlds,
        tick -> List.of(new ExternalPath(
            "ordered-external@" + tick,
            Objects.requireNonNull(externalTransitions.apply(tick), "external transitions"),
            "ordered external transitions supplied for the simulation tick")),
        config);
  }

  public SearchResult searchWithTransitionBranches(
      Collection<Context> starts,
      List<InputConstraint> inputs,
      LongFunction<List<WorldBranch>> worlds,
      LongFunction<List<ExternalPath>> externalPaths,
      SearchConfig config) {
    Objects.requireNonNull(externalPaths);
    return searchInternal(starts, inputs, worlds, externalPaths, config);
  }

  private SearchResult search(
      Collection<Context> starts,
      List<InputConstraint> inputs,
      LongFunction<List<WorldBranch>> worlds,
      LongFunction<List<ExternalPath>> externalPaths,
      SearchConfig config) {
    return searchInternal(starts, inputs, worlds, externalPaths, config);
  }

  private SearchResult searchInternal(
      Collection<Context> starts,
      List<InputConstraint> inputs,
      LongFunction<List<WorldBranch>> worlds,
      LongFunction<List<ExternalPath>> externalPaths,
      SearchConfig config) {
    Objects.requireNonNull(starts);
    Objects.requireNonNull(inputs);
    Objects.requireNonNull(worlds);
    Objects.requireNonNull(externalPaths);
    Objects.requireNonNull(config);

    if (starts.isEmpty()) return uncertain(0, 0, "no initial state was supplied");
    if (inputs.size() > config.maximumHorizonTicks()) {
      return uncertain(0, starts.size(),
          "simulation horizon exceeds the configured Phase 6 envelope");
    }

    List<Context> orderedStarts = new ArrayList<>(starts);
    orderedStarts.sort(Comparator.comparing(Phase6Reachability::contextKey));

    TreeMap<String, Candidate> current = new TreeMap<>();
    long nextId = 0;
    boolean uncertain = false;
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    List<Elimination> eliminations = new ArrayList<>();
    long generated = 0;
    long merged = 0;
    long pruned = 0;
    long branchEvaluations = 0;
    long simulationSteps = 0;
    int peak = 0;
    int evaluatedTicks = 0;
    int nonExhaustiveWorldBranches = 0;
    int uncertainTransitions = 0;
    int provenanceMerges = 0;
    boolean budgetReached = false;

    for (Context root : orderedStarts) {
      if (root.player().uncertain() || !root.uncertainty().isEmpty()) {
        uncertain = true;
        reasons.add("initial state carries explicit uncertainty");
      }
      Candidate candidate = new Candidate(
          nextId++, root, new Provenance(
              nextId - 1, -1, root.simulationTick(), "ROOT", "ROOT", "None",
              List.of("initial replay anchor"), 1, List.of(),
              List.of("initial state assumptions include " + root.uncertainty())),
          config.serverTickAssociation(), config.timingReference(), "ROOT",
          root.player().uncertain() || !root.uncertainty().isEmpty()
              ? WorldKnowledge.UNKNOWN : WorldKnowledge.KNOWN,
          movementModeFor(root), "ROOT", List.of("initial state"));
      current.put(candidateKey(candidate), candidate);
    }
    peak = current.size();

    if (current.isEmpty()) return uncertain(0, 0, "initial state set collapsed to no deterministic roots");

    outer:
    for (int offset = 0; offset < inputs.size(); offset++) {
      List<AdvancedInput> allowed = orderedInputs(inputs.get(offset), reasons);
      boolean inputUncertain = !inputs.get(offset).isExact();
      if (inputUncertain) {
        uncertain = true;
        reasons.add("input uncertainty at relative step " + offset + ": " + inputs.get(offset));
      }
      if (allowed.isEmpty()) {
        uncertain = true;
        reasons.add("input constraint has no realizable advanced input at relative step " + offset);
        addElimination(eliminations, new Elimination(
            0L, -1, "INPUT", "input constraint has no realizable advanced input",
            inputs.get(offset).toString(), "UNKNOWN", "NONE",
            WorldKnowledge.UNKNOWN, List.of()));
        break;
      }

      TreeMap<String, Candidate> next = new TreeMap<>();
      List<Candidate> parents = new ArrayList<>(current.values());
      parents.sort(Comparator.comparing(c -> candidateKey(c).toString()));

      tickBranches:
      for (Candidate parent : parents) {
        long tick = parent.context().simulationTick();
        List<WorldBranch> branches = orderedWorldBranches(
            Objects.requireNonNull(worlds.apply(tick), "world branches"), tick);
        if (branches.isEmpty()) {
          uncertain = true;
          reasons.add("world hypothesis envelope is empty at tick " + tick);
          addElimination(eliminations, new Elimination(
              tick, parent.id(), "WORLD", "world hypothesis envelope is empty",
              inputs.get(offset).toString(), "UNKNOWN", "NONE",
              WorldKnowledge.UNKNOWN, List.of()));
          continue;
        }
        if (branches.stream().anyMatch(branch -> !branch.exhaustive())) {
          nonExhaustiveWorldBranches += (int) branches.stream().filter(branch -> !branch.exhaustive()).count();
          uncertain = true;
          reasons.add("world hypothesis envelope is not exhaustive at tick " + tick);
        }

        List<ExternalPath> paths = orderedExternalPaths(
            Objects.requireNonNull(externalPaths.apply(tick), "external paths"), tick);
        if (paths.isEmpty()) {
          paths = List.of(new ExternalPath("NONE@" + tick, List.of(), "implicit no-op external path"));
        }

        for (WorldBranch branch : branches) {
          WorldKnowledge branchKnowledge = worldKnowledge(branch);
          var player = parent.context().player();
          var box = Maths.Aabb.playerAt(player.position(), parent.context().pose());
          var coverageBox = new dev.phantom.ac.geometry.BlockBox(
              box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
          Set<dev.phantom.ac.world.Coverage> coverage = branch.world().coverageIn(coverageBox);
          if (!coverage.equals(Set.of(dev.phantom.ac.world.Coverage.KNOWN))) {
            uncertain = true;
            uncertainTransitions++;
            String reason = "starting collision volume is not fully known: " + coverage;
            reasons.add(reason);
            addElimination(eliminations, new Elimination(
                tick, parent.id(), "WORLD", reason,
                "input constraint=" + inputs.get(offset), branch.id(), "NONE",
                knowledgeForCoverage(coverage), List.of(branch.description(),
                    branch.world().coverageDetailAt((int)Math.floor(player.position().x()),
                        (int)Math.floor(player.position().y()),
                        (int)Math.floor(player.position().z()))));
            continue;
          }

          long parentBranchEvaluations = 0;
          for (ExternalPath path : paths) {
            Context pre = parent.context().withTick(tick);
            boolean externalUncertain = false;
            for (ExternalTransition transition : path.transitions()) {
              pre = applyExternal(pre, transition, tick);
              if (pre.player().uncertain()) {
                externalUncertain = true;
                break;
              }
            }
            if (externalUncertain) {
              uncertain = true;
              uncertainTransitions++;
              String reason = "external transition could not be represented deterministically by Phase 6/5";
              reasons.add(reason);
              addElimination(eliminations, new Elimination(
                  tick, parent.id(), "EXTERNAL", reason,
                  inputs.get(offset).toString(), branch.id(), path.id(),
                  WorldKnowledge.KNOWN, List.of(path.description(), path.transitions().toString())));
              continue;
            }

            for (AdvancedInput input : allowed) {
              branchEvaluations++;
              parentBranchEvaluations++;
              if (parentBranchEvaluations > config.maximumBranchesPerTransition()) {
                budgetReached = true;
                uncertain = true;
                reasons.add("maximum branch budget reached at tick " + tick);
                addElimination(eliminations, new Elimination(
                    tick, parent.id(), "BUDGET", "maximum branches per transition reached",
                    input.toString(), branch.id(), path.id(), branchKnowledge, List.of()));
                break tickBranches;
              }
              if (simulationSteps >= config.maximumSimulationSteps()) {
                budgetReached = true;
                uncertain = true;
                reasons.add("maximum deterministic simulation-step budget reached at tick " + tick);
                addElimination(eliminations, new Elimination(
                    tick, parent.id(), "BUDGET", "maximum simulation steps reached",
                    input.toString(), branch.id(), path.id(), branchKnowledge, List.of()));
                break tickBranches;
              }

              WorldQueries.EnvironmentSample sample =
                  WorldQueries.environment(branch.world(), new dev.phantom.ac.geometry.BlockBox(
                      box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
              if (!sample.isDefinite() || (sample.inFluid() && !sample.allFluidHeightsKnown())) {
                uncertain = true;
                uncertainTransitions++;
                String reason = "movement environment is not fully known";
                reasons.add(reason);
                addElimination(eliminations, new Elimination(
                    tick, parent.id(), "WORLD", reason,
                    input.toString(), branch.id(), path.id(),
                    !sample.isDefinite()
                        ? knowledgeForCoverage(sample.coverage())
                        : WorldKnowledge.UNKNOWN,
                    List.of("coverage=" + sample.coverage(),
                        "fluidHeightsKnown=" + sample.allFluidHeightsKnown())));
                continue;
              }

              MovementEnvironment environment =
                  movementEnvironmentFor(sample, pre, input);
              Simulation.Environment simulationEnvironment = environmentFor(environment);

              Vanilla12111RichPhysics.Context physicsContext =
                  new Vanilla12111RichPhysics.Context(
                      tick, pre.player(), input, branch.world(),
                      simulationEnvironment, pre.attributes(), pre.effects(),
                      pre.pose(), environment, pre.sleeping(), pre.entityCollisions());
              simulationSteps++;
              Vanilla12111RichPhysics.StepResult stepped = physics.step(physicsContext);
              if (stepped.state().uncertain()) {
                uncertain = true;
                uncertainTransitions++;
                String reason = "Phase 5 could not deterministically simulate this transition: "
                    + stepped.diagnostic();
                reasons.add(reason);
                addElimination(eliminations, new Elimination(
                    tick, parent.id(), "PHASE5", reason, input.toString(),
                    branch.id(), path.id(), branchKnowledge,
                    List.of(stepped.diagnostic())));
                continue;
              }

              generated++;
              Maths.Aabb nextBox = Maths.Aabb.playerAt(
                  stepped.state().position(), stepped.state().pose());
              MovementEnvironment nextEnvironment =
                  movementEnvironmentFor(
                      WorldQueries.environment(
                          branch.world(),
                          new dev.phantom.ac.geometry.BlockBox(
                              nextBox.minX(), nextBox.minY(), nextBox.minZ(),
                              nextBox.maxX(), nextBox.maxY(), nextBox.maxZ())),
                      pre, input);
              Pose nextPose = Phase5Mechanics.nextPose(
                  pre.pose(), nextEnvironment, pre.sleeping());

              Set<UncertainDimension> stateUncertainty = EnumSet.noneOf(UncertainDimension.class);
              stateUncertainty.addAll(pre.uncertainty());
              if (!branch.exhaustive()) stateUncertainty.add(UncertainDimension.WORLD);
              if (!inputs.get(offset).isExact()) stateUncertainty.add(UncertainDimension.INPUT);
              if (branchKnowledge != WorldKnowledge.KNOWN) stateUncertainty.add(UncertainDimension.WORLD);

              Context after = new Context(
                  tick + 1, stepped.state(), environmentFor(nextEnvironment),
                  pre.attributes(), pre.effects(), nextPose,
                  nextEnvironment, pre.sleeping(), pre.entityCollisions(),
                  stateUncertainty);

              MovementMode mode = movementModeFor(after);
              Candidate newCandidate = new Candidate(
                  nextId++, after,
                  new Provenance(
                      nextId - 1, parent.id(), tick, input.toString(), branch.id(),
                      path.id(), List.of(stepped.diagnostic()), 1, List.of(parent.id()),
                      List.of(
                          "input=" + input,
                          "world=" + branch.id() + " knowledge=" + branchKnowledge,
                          "timing=" + config.timingReference(),
                          "simulationTick=" + tick)),
                  config.serverTickAssociation(), config.timingReference(), branch.id(),
                  branchKnowledge, mode, input.toString(),
                  List.of(stepped.diagnostic()));

              CandidateKey key = candidateKey(newCandidate);
              Candidate existing = next.get(key);
              if (existing == null) {
                next.put(key, newCandidate);
              } else {
                merged++;
                provenanceMerges++;
                next.put(key, mergeProvenance(existing, newCandidate));
              }
              while (next.size() > config.maximumCandidates()) {
                Map.Entry<String,Candidate> worst = next.lastEntry();
                if (worst == null) break;
                next.remove(worst.getKey());
                pruned++;
                budgetReached = true;
                uncertain = true;
                reasons.add("candidate budget pruned deterministic excess state at tick " + tick);
                addElimination(eliminations, new Elimination(
                    tick, parent.id(), "BUDGET", "candidate frontier budget pruned one state",
                    input.toString(), branch.id(), path.id(), branchKnowledge,
                    List.of("priority=lexicographically-smallest canonical candidate key")));
              }
              peak = Math.max(peak, next.size());
            }
          }
        }
      }

      evaluatedTicks++;
      if (next.isEmpty()) {
        uncertain = true;
        reasons.add("no legitimate Phase 5 candidate survived tick " + tick
            + "; Phase 6 cannot prove impossibility without a complete trusted model");
        break;
      }
      current = next;
      if (budgetReached) break outer;
    }

    boolean exhaustive = !uncertain && !budgetReached && evaluatedTicks == inputs.size();
    Verdict verdict = exhaustive ? Verdict.POSSIBLE : Verdict.UNCERTAIN;
    reasons.add(exhaustive
        ? "exhaustive deterministic reachable-state search completed"
        : "reachable-state search was not exhaustive; result remains UNCERTAIN");

    SearchMetrics metrics = new SearchMetrics(
        generated, merged, pruned, branchEvaluations, simulationSteps,
        peak, evaluatedTicks, budgetReached, exhaustive);
    return new SearchResult(verdict, new LinkedHashSet<>(current.values()),
        evaluatedTicks, peak, (int)Math.min(Integer.MAX_VALUE, merged),
        nonExhaustiveWorldBranches, uncertainTransitions, provenanceMerges,
        List.copyOf(reasons), metrics, List.copyOf(eliminations));
  }

  public TimingSearchResult searchWithinTimingWindow(
      Context start,
      long earliestTick,
      long latestTick,
      boolean timingUncertain,
      List<InputConstraint> inputs,
      LongFunction<List<WorldBranch>> worlds,
      LongFunction<List<ExternalTransition>> externalTransitions,
      int maximumCandidates) {
    SearchConfig config = SearchConfig.defaults(maximumCandidates).withTimingReference(
        "phase6-consumed-client-tick-window");
    if (earliestTick < 0 || latestTick < earliestTick) {
      throw new IllegalArgumentException("invalid timing window");
    }
    long span = latestTick - earliestTick + 1L;
    if (span > MAX_TIMING_OFFSETS) {
      return new TimingSearchResult(
          Verdict.UNCERTAIN, Set.of(), Map.of(), 0,
          (int)Math.min(Integer.MAX_VALUE, span),
          List.of("timing window exceeds Phase 6 exhaustive offset envelope"));
    }

    Map<Long, SearchResult> results = new TreeMap<>();
    TreeMap<String,Candidate> union = new TreeMap<>();
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    boolean uncertain = timingUncertain;
    List<Elimination> eliminations = new ArrayList<>();

    for (long tick = earliestTick; tick <= latestTick; tick++) {
      SearchResult result = search(
          List.of(start.withTick(tick)),
          inputs,
          worlds,
          externalTransitions,
          config.withServerTickAssociation(config.serverTickAssociation()));
      results.put(tick, result);
      if (result.verdict() != Verdict.POSSIBLE) uncertain = true;
      reasons.addAll(result.reasons());
      eliminations.addAll(result.eliminations());
      for (Candidate candidate : result.candidates()) {
        union.put(candidateKey(candidate).toString(), candidate);
      }
      while (union.size() > maximumCandidates) {
        union.pollLastEntry();
        uncertain = true;
        reasons.add("combined timing-window candidate budget pruned deterministic excess state");
      }
    }

    if (timingUncertain) {
      reasons.add("timing envelope is explicitly uncertain; Phase 6 consumed every supplied offset");
    }
    Verdict verdict = uncertain ? Verdict.UNCERTAIN : Verdict.POSSIBLE;
    SearchMetrics metrics = aggregateMetrics(results.values(), verdict == Verdict.POSSIBLE, uncertain);
    return new TimingSearchResult(
        verdict, new LinkedHashSet<>(union.values()), results, results.size(), 0,
        List.copyOf(reasons), metrics, eliminations);
  }

  public Evidence compare(SearchResult result, Observation observation) {
    Objects.requireNonNull(result);
    Objects.requireNonNull(observation);

    List<Candidate> ordered = new ArrayList<>(result.candidates());
    ordered.sort(Comparator.comparingLong(Candidate::id));

    List<Provenance> matches = new ArrayList<>();
    List<ObservationMismatch> mismatches = new ArrayList<>();
    for (Candidate candidate : ordered) {
      ObservationMismatch mismatch = mismatch(candidate, observation);
      if (mismatch.dimensions().isEmpty()) {
        matches.add(candidate.provenance());
      } else {
        mismatches.add(mismatch);
      }
    }

    List<Candidate> closest = new ArrayList<>();
    List<ObservationMismatch> sortedMismatches = new ArrayList<>(mismatches);
    sortedMismatches.sort(
        Comparator.comparingDouble(ObservationMismatch::score)
            .thenComparingLong(ObservationMismatch::candidateId));
    for (ObservationMismatch mismatch : sortedMismatches) {
      ordered.stream().filter(candidate -> candidate.id() == mismatch.candidateId())
          .findFirst().ifPresent(candidate -> closest.add(candidate));
      if (closest.size() >= MAX_CLOSEST_CANDIDATES) break;
    }

    List<String> reasons = new ArrayList<>(result.reasons());
    if (!matches.isEmpty()) reasons.add("at least one reachable candidate matches all declared observed fields");
    else reasons.add("no reachable candidate matches all declared observed fields");

    Optional<FirstDivergence> divergence = Optional.empty();
    if (matches.isEmpty()) {
      Optional<Candidate> last = closest.isEmpty() ? ordered.stream().findFirst() : Optional.of(closest.getFirst());
      Optional<Elimination> elimination = result.eliminations().stream().findFirst();
      Set<ObservedField> dimensions = sortedMismatches.isEmpty()
          ? EnumSet.noneOf(ObservedField.class)
          : sortedMismatches.getFirst().dimensions();
      divergence = Optional.of(new FirstDivergence(
          0, observation.observed().clientTickRange().min(),
          last, elimination, dimensions,
          List.of(
              "first divergence is at observation index 0 for this search result",
              elimination.map(e -> "first candidate elimination: tick=" + e.simulationTick()
                  + " stage=" + e.stage() + " reason=" + e.reason()).orElse("no transition elimination was recorded"))));
    }

    Verdict verdict;
    if (result.verdict() == Verdict.UNCERTAIN) {
      verdict = Verdict.UNCERTAIN;
    } else if (!matches.isEmpty()) {
      verdict = Verdict.POSSIBLE;
    } else {
      verdict = Verdict.IMPOSSIBLE;
    }

    return new Evidence(
        verdict,
        matches.size(),
        matches,
        reasons,
        closest,
        sortedMismatches,
        result.eliminations(),
        divergence);
  }

  public Optional<FirstDivergence> firstDivergence(
      List<SearchResult> results,
      List<Observation> observations) {
    Objects.requireNonNull(results);
    Objects.requireNonNull(observations);
    int count = Math.min(results.size(), observations.size());
    Candidate lastReachable = null;
    for (int i = 0; i < count; i++) {
      SearchResult result = results.get(i);
      Observation observation = observations.get(i);
      Evidence evidence = compare(result, observation);
      if (evidence.verdict() == Verdict.UNCERTAIN) return Optional.empty();
      if (evidence.verdict() == Verdict.IMPOSSIBLE) {
        ObservationMismatch mismatch = evidence.mismatches().isEmpty()
            ? null : evidence.mismatches().getFirst();
        Set<ObservedField> dimensions = mismatch == null
            ? EnumSet.noneOf(ObservedField.class) : mismatch.dimensions();
        Optional<Elimination> elimination = result.eliminations().stream().findFirst();
        return Optional.of(new FirstDivergence(
            i,
            observation.observed().clientTickRange().min(),
            Optional.ofNullable(lastReachable),
            elimination,
            dimensions,
            List.of("earliest fully-exhaustive observation that is not reachable",
                "all prior observations were reachable under their declared Phase 6 envelopes")));
      }
      Candidate witness = result.candidates().stream()
          .filter(candidate -> matches(candidate.context().player(), observation))
          .min(Comparator.comparingLong(Candidate::id))
          .orElse(null);
      lastReachable = witness;
    }
    return Optional.empty();
  }

  static String canonicalSignature(SearchResult result) {
    StringBuilder b = new StringBuilder();
    b.append(result.verdict()).append('|')
        .append(result.simulatedTicks()).append('|')
        .append(result.peakCandidates()).append('|')
        .append(result.metrics()).append('|');
    List<Candidate> candidates = new ArrayList<>(result.candidates());
    candidates.sort(Comparator.comparing(c -> candidateKey(c).toString()));
    for (Candidate candidate : candidates) {
      b.append(candidateKey(candidate)).append('|')
          .append(candidate.provenance()).append(';');
    }
    b.append("elim=");
    for (Elimination elimination : result.eliminations()) b.append(elimination).append(';');
    return b.toString();
  }

  private static List<AdvancedInput> orderedInputs(InputConstraint constraint, Set<String> reasons) {
    Objects.requireNonNull(constraint);
    List<AdvancedInput> inputs = new ArrayList<>(constraint.enumerate());
    inputs.sort(Comparator.comparing(Phase6Reachability::inputKey));
    return List.copyOf(inputs);
  }

  private static List<WorldBranch> orderedWorldBranches(List<WorldBranch> branches, long tick) {
    List<WorldBranch> result = new ArrayList<>(branches);
    result.sort(Comparator.comparing(WorldBranch::id).thenComparing(WorldBranch::description));
    Set<String> ids = new HashSet<>();
    for (WorldBranch branch : result) {
      if (!ids.add(branch.id())) {
        throw new IllegalArgumentException("duplicate world branch id at tick " + tick + ": " + branch.id());
      }
    }
    return List.copyOf(result);
  }

  private static List<ExternalPath> orderedExternalPaths(List<ExternalPath> paths, long tick) {
    List<ExternalPath> result = new ArrayList<>(paths);
    result.sort(Comparator.comparing(ExternalPath::id).thenComparing(ExternalPath::description));
    Set<String> ids = new HashSet<>();
    for (ExternalPath path : result) {
      if (!ids.add(path.id())) {
        throw new IllegalArgumentException("duplicate external path id at tick " + tick + ": " + path.id());
      }
    }
    return List.copyOf(result);
  }

  private static String inputKey(AdvancedInput input) {
    return input.forward() + "," + input.strafe() + "," + input.jump() + ","
        + input.sprint() + "," + input.sneak();
  }

  private static String contextKey(Context context) {
    Player p = context.player();
    StringBuilder b = new StringBuilder();
    b.append(context.simulationTick()).append('|')
        .append(p.position()).append('|')
        .append(p.velocity()).append('|')
        .append(Float.floatToIntBits(p.yaw())).append('|')
        .append(Float.floatToIntBits(p.pitch())).append('|')
        .append(p.onGround()).append('|')
        .append(p.gamemode()).append('|')
        .append(sortedMap(p.effects())).append('|')
        .append(p.awaitingTeleport()).append('|')
        .append(p.input()).append('|')
        .append(p.attributes()).append('|')
        .append(p.pose()).append('|')
        .append(p.environment()).append('|')
        .append(p.clientTickRange()).append('|')
        .append(p.uncertain()).append('|')
        .append(sortedSet(p.uncertaintyReasons())).append('|')
        .append(context.environment()).append('|')
        .append(context.attributes()).append('|')
        .append(context.effects()).append('|')
        .append(context.pose()).append('|')
        .append(context.movementEnvironment()).append('|')
        .append(context.sleeping()).append('|')
        .append(context.entityCollisions()).append('|')
        .append(sortedSet(context.uncertainty()));
    return b.toString();
  }

  private static String sortedMap(Map<String,Integer> map) {
    return map.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .toList().toString();
  }

  private static <T extends Enum<T>> String sortedSet(Set<T> set) {
    return set.stream().sorted(Comparator.comparing(Enum::name)).toList().toString();
  }

  private static CandidateKey candidateKey(Candidate candidate) {
    return new CandidateKey(
        contextKey(candidate.context()),
        candidate.worldReference(),
        candidate.timingReference(),
        candidate.serverTickAssociation(),
        candidate.movementMode());
  }

  private static MovementMode movementModeFor(Context context) {
    Player player = context.player();
    if ("creative".equals(player.gamemode()) || "spectator".equals(player.gamemode())) {
      return MovementMode.NON_SURVIVAL;
    }
    if (context.movementEnvironment().gliding()) return MovementMode.GLIDING;
    if (context.movementEnvironment().fluid() != Phase5Mechanics.Fluid.NONE) return MovementMode.FLUID;
    if (context.movementEnvironment().climbable()) return MovementMode.CLIMBABLE;
    if ("survival".equals(player.gamemode()) || "adventure".equals(player.gamemode())) {
      return player.onGround() ? MovementMode.SURVIVAL_GROUND : MovementMode.SURVIVAL_AIR;
    }
    return MovementMode.UNKNOWN;
  }

  private static WorldKnowledge worldKnowledge(WorldBranch branch) {
    return branch.exhaustive() ? WorldKnowledge.KNOWN : WorldKnowledge.UNKNOWN;
  }

  private static WorldKnowledge knowledgeForCoverage(Set<dev.phantom.ac.world.Coverage> coverage) {
    if (coverage.contains(dev.phantom.ac.world.Coverage.UNSUPPORTED)) return WorldKnowledge.UNSUPPORTED;
    if (coverage.contains(dev.phantom.ac.world.Coverage.UNLOADED)) return WorldKnowledge.UNLOADED;
    return WorldKnowledge.UNKNOWN;
  }

  private static MovementEnvironment movementEnvironmentFor(
      WorldQueries.EnvironmentSample sample,
      Context context,
      AdvancedInput input) {
    boolean sprint = input.sprint();
    boolean sneak = input.sneak();
    boolean swimming = context.pose() == Pose.SWIMMING;
    boolean gliding = !sample.inFluid() && !sample.climbable()
        && context.movementEnvironment().gliding();
    if (sample.water()) {
      return Phase5Mechanics.MovementEnvironment.vanillaWater(
          context.player().onGround(), sprint, sneak, swimming);
    }
    if (sample.lava()) {
      return Phase5Mechanics.MovementEnvironment.vanillaLava(
          context.player().onGround(), sprint, sneak);
    }
    if (sample.climbable()) {
      return Phase5Mechanics.MovementEnvironment.vanillaClimbable(
          context.player().onGround(), sprint, sneak);
    }
    return new Phase5Mechanics.MovementEnvironment(
        Phase5Mechanics.Fluid.NONE, false, false,
        context.player().onGround(), sprint, sneak, false,
        gliding, 1.0, 1.0, 1.0);
  }

  private static Simulation.Environment environmentFor(MovementEnvironment env) {
    if (env.fluid() == Phase5Mechanics.Fluid.WATER) return Simulation.Environment.WATER;
    if (env.fluid() == Phase5Mechanics.Fluid.LAVA) return Simulation.Environment.LAVA;
    if (env.climbable()) return Simulation.Environment.CLIMBABLE;
    return Simulation.Environment.DRY;
  }

  private static Context applyExternal(Context c, ExternalTransition e, long tick) {
    Player s = c.player();
    if (e instanceof None) return c.withTick(tick);
    if (e instanceof VelocityImpulse v) {
      Player n = Phase5Mechanics.applyVelocityImpulse(
          s, new Phase5Mechanics.Vec3Like(v.impulse().x(), v.impulse().y(), v.impulse().z()));
      return new Context(tick, n, c.environment(), c.attributes(), c.effects(),
          c.pose(), c.movementEnvironment(), c.sleeping(), c.entityCollisions(), c.uncertainty());
    }
    if (e instanceof TeleportCorrection t) {
      int teleportId = t.awaitingConfirmation() ? t.id() : -1;
      OptionalInt pending = teleportId >= 0 ? OptionalInt.of(teleportId) : OptionalInt.empty();
      float yaw = t.yaw() == null ? s.yaw() : t.yaw();
      float pitch = t.pitch() == null ? s.pitch() : t.pitch();
      Player n = new Player(
          t.position(), t.velocity(), yaw, pitch, false,
          s.gamemode(), s.effects(), pending, s.uncertain(),
          s.input(), s.attributes(), t.pose(), s.environment(),
          s.clientTickRange(), s.provenance(), s.uncertaintyReasons());
      return new Context(tick, n, c.environment(), c.attributes(), c.effects(),
          t.pose(), c.movementEnvironment(), c.sleeping(), c.entityCollisions(), c.uncertainty());
    }
    if (e instanceof TeleportConfirmation t) {
      boolean ok = s.awaitingTeleport().isPresent()
          && s.awaitingTeleport().getAsInt() == t.id();
      if (!ok) {
        Player n = s.withUncertainty(State.UncertaintyReason.MISMATCHED_TELEPORT_ACK);
        return new Context(
            tick, n, c.environment(), c.attributes(), c.effects(), c.pose(),
            c.movementEnvironment(), c.sleeping(), c.entityCollisions(),
            addUncertainty(c.uncertainty(), UncertainDimension.TELEPORT));
      }
      Player n = new Player(
          s.position(), s.velocity(), s.yaw(), s.pitch(), s.onGround(),
          s.gamemode(), s.effects(), OptionalInt.empty(), s.uncertain(),
          s.input(), s.attributes(), s.pose(), s.environment(),
          s.clientTickRange(), s.provenance(), s.uncertaintyReasons());
      return new Context(
          tick, n, c.environment(), c.attributes(), c.effects(), c.pose(),
          c.movementEnvironment(), c.sleeping(), c.entityCollisions(), c.uncertainty());
    }
    throw new IllegalStateException("unhandled external transition " + e.getClass());
  }

  private static Set<UncertainDimension> addUncertainty(
      Set<UncertainDimension> current,
      UncertainDimension dimension) {
    EnumSet<UncertainDimension> result = EnumSet.noneOf(UncertainDimension.class);
    result.addAll(current);
    result.add(dimension);
    return result;
  }

  private static Candidate mergeProvenance(Candidate existing, Candidate alternative) {
    Provenance a = existing.provenance();
    Provenance b = alternative.provenance();
    List<Long> parents = new ArrayList<>(a.mergedParentIds());
    if (!parents.contains(b.parentId()) && parents.size() < MAX_PROVENANCE_PARENTS) {
      parents.add(b.parentId());
    }
    List<String> assumptions = new ArrayList<>(a.assumptions());
    for (String assumption : b.assumptions()) {
      if (!assumptions.contains(assumption) && assumptions.size() < 64) assumptions.add(assumption);
    }
    List<String> causes = new ArrayList<>(a.causes());
    for (String cause : b.causes()) {
      if (!causes.contains(cause) && causes.size() < MAX_DIAGNOSTICS) causes.add(cause);
    }
    int paths = a.mergedPathCount() == Integer.MAX_VALUE
        ? Integer.MAX_VALUE : a.mergedPathCount() + b.mergedPathCount();
    Provenance merged = new Provenance(
        a.candidateId(), a.parentId(), a.tick(), a.input(), a.worldBranch(),
        a.externalTransition(), causes, paths, parents, assumptions);
    return new Candidate(
        existing.id(), existing.context(), merged,
        existing.serverTickAssociation(), existing.timingReference(),
        existing.worldReference(), existing.worldKnowledge(),
        existing.movementMode(), existing.inputAssumption(),
        existing.transitionDiagnostics());
  }

  private static ObservationMismatch mismatch(Candidate candidate, Observation observation) {
    Player actual = candidate.context().player();
    Player expected = observation.observed();
    EnumSet<ObservedField> dimensions = EnumSet.noneOf(ObservedField.class);
    List<String> details = new ArrayList<>();
    double positionDistance = Double.NaN;
    double velocityDistance = Double.NaN;
    double yawDistance = 0.0;
    double pitchDistance = 0.0;

    if (observation.known().contains(ObservedField.POSITION)) {
      positionDistance = distance(actual.position(), expected.position());
      if (!positionMatches(actual.position(), expected.position())) {
        dimensions.add(ObservedField.POSITION);
        details.add("position distance=" + positionDistance);
      }
    }
    if (observation.known().contains(ObservedField.VELOCITY)) {
      velocityDistance = distance(actual.velocity(), expected.velocity());
      if (!actual.velocity().equals(expected.velocity())) {
        dimensions.add(ObservedField.VELOCITY);
        details.add("velocity mismatch actual=" + actual.velocity() + " expected=" + expected.velocity());
      }
    }
    if (observation.known().contains(ObservedField.ROTATION)) {
      yawDistance = actual.yaw() - expected.yaw();
      pitchDistance = actual.pitch() - expected.pitch();
      if (Float.compare(actual.yaw(), expected.yaw()) != 0
          || Float.compare(actual.pitch(), expected.pitch()) != 0) {
        dimensions.add(ObservedField.ROTATION);
        details.add("rotation mismatch actualYaw=" + actual.yaw()
            + " expectedYaw=" + expected.yaw()
            + " actualPitch=" + actual.pitch()
            + " expectedPitch=" + expected.pitch());
      }
    }
    if (observation.known().contains(ObservedField.GROUND) && actual.onGround() != expected.onGround()) {
      dimensions.add(ObservedField.GROUND);
      details.add("ground mismatch actual=" + actual.onGround() + " expected=" + expected.onGround());
    }
    if (observation.known().contains(ObservedField.GAMEMODE) && !actual.gamemode().equals(expected.gamemode())) {
      dimensions.add(ObservedField.GAMEMODE);
      details.add("gamemode mismatch actual=" + actual.gamemode() + " expected=" + expected.gamemode());
    }
    if (observation.known().contains(ObservedField.EFFECTS) && !actual.effects().equals(expected.effects())) {
      dimensions.add(ObservedField.EFFECTS);
      details.add("effects mismatch");
    }
    if (observation.known().contains(ObservedField.TELEPORT_PENDING)
        && (actual.awaitingTeleport().isPresent() != expected.awaitingTeleport().isPresent())) {
      dimensions.add(ObservedField.TELEPORT_PENDING);
      details.add("teleport-pending mismatch");
    }

    return new ObservationMismatch(
        candidate.id(), dimensions, positionDistance, velocityDistance,
        yawDistance, pitchDistance, details);
  }

  private static double distance(Maths.Vec3 a, Maths.Vec3 b) {
    double dx = a.x() - b.x();
    double dy = a.y() - b.y();
    double dz = a.z() - b.z();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private static boolean matches(Player c, Observation o) {
    Player expected = o.observed();
    for (ObservedField field : o.known()) {
      switch (field) {
        case POSITION -> {
          if (!positionMatches(c.position(), expected.position())) return false;
        }
        case VELOCITY -> {
          if (!c.velocity().equals(expected.velocity())) return false;
        }
        case ROTATION -> {
          if (Float.compare(c.yaw(), expected.yaw()) != 0
              || Float.compare(c.pitch(), expected.pitch()) != 0) return false;
        }
        case GROUND -> {
          if (c.onGround() != expected.onGround()) return false;
        }
        case GAMEMODE -> {
          if (!c.gamemode().equals(expected.gamemode())) return false;
        }
        case EFFECTS -> {
          if (!c.effects().equals(expected.effects())) return false;
        }
        case TELEPORT_PENDING -> {
          if (c.awaitingTeleport().isPresent() != expected.awaitingTeleport().isPresent()) return false;
        }
      }
    }
    return true;
  }

  private static SearchMetrics aggregateMetrics(
      Collection<SearchResult> results,
      boolean exhaustive,
      boolean budgetReached) {
    long generated = 0;
    long merged = 0;
    long pruned = 0;
    long branches = 0;
    long steps = 0;
    int peak = 0;
    int ticks = 0;
    for (SearchResult result : results) {
      SearchMetrics m = result.metrics();
      generated += m.generatedCandidates();
      merged += m.mergedCandidates();
      pruned += m.prunedCandidates();
      branches += m.branchEvaluations();
      steps += m.simulationSteps();
      peak = Math.max(peak, m.peakCandidates());
      ticks += m.evaluatedTicks();
    }
    return new SearchMetrics(generated, merged, pruned, branches, steps, peak, ticks,
        budgetReached || results.stream().anyMatch(r -> r.metrics().budgetReached()),
        exhaustive);
  }

  private static List<Elimination> flattenEliminations(Collection<SearchResult> results) {
    List<Elimination> out = new ArrayList<>();
    for (SearchResult result : results) out.addAll(result.eliminations());
    return List.copyOf(out);
  }

  private static void addElimination(List<Elimination> eliminations, Elimination elimination) {
    if (eliminations.size() < MAX_DIAGNOSTICS) eliminations.add(elimination);
  }

  private static SearchResult uncertain(int ticks, int peak, String reason) {
    SearchMetrics metrics = new SearchMetrics(0, 0, 0, 0, 0, Math.max(0, peak),
        Math.max(0, ticks), false, false);
    return new SearchResult(
        Verdict.UNCERTAIN, Set.of(), ticks, Math.max(0, peak), 0,
        0, 1, 0, List.of(reason), metrics, List.of());
  }

}
