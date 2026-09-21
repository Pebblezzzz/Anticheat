package dev.phantom.ac;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.WorldSnapshot;

import java.io.Serializable;
import java.util.*;

/**
 * Explicit live movement state for the Phase 8 runner.
 *
 * <p>This keeps the four important concepts separate instead of encoding them
 * only in ad-hoc runner fields: client physics state versus server authority,
 * causally compensated world state, tick reliability, and a materialized set of
 * movement hypotheses.</p>
 *
 * <p>The design is Grim-inspired but independently implemented using Phantom's
 * own state, timing, and world models.</p>
 */
public final class Phase8ClientModel {
  private Phase8ClientModel() {}

  public record ClientPhysicsState(
      long clientTick,
      Vec3 position,
      Vec3 clientVelocity,
      Vec3 predictedVelocity,
      Vec3 serverVelocity,
      Vec3 actualMovement,
      boolean lastOnGround,
      boolean onGround,
      Long serverTick,
      String source) implements Serializable {
    public ClientPhysicsState {
      if (clientTick < 0L) throw new IllegalArgumentException("clientTick must be non-negative");
      Objects.requireNonNull(position);
      Objects.requireNonNull(clientVelocity);
      Objects.requireNonNull(serverVelocity);
      Objects.requireNonNull(actualMovement);
      Objects.requireNonNull(source);
    }

    public static ClientPhysicsState initial(Player anchor) {
      Objects.requireNonNull(anchor);
      return new ClientPhysicsState(
          0L,
          anchor.position(),
          anchor.velocity(),
          anchor.velocity(),
          anchor.velocity(),
          Vec3.ZERO,
          anchor.onGround(),
          anchor.onGround(),
          null,
          "initial-authoritative-anchor");
    }

    public ClientPhysicsState withServerAuthority(
        long tick,
        Vec3 authoritativePosition,
        Vec3 authoritativeVelocity,
        Long authoritativeServerTick) {
      return new ClientPhysicsState(
          Math.max(clientTick, tick),
          position,
          clientVelocity,
          predictedVelocity,
          Objects.requireNonNull(authoritativeVelocity),
          actualMovement,
          lastOnGround,
          onGround,
          authoritativeServerTick,
          "server-authority-overlay");
    }


    /** Client movement velocity and authoritative server velocity are intentionally independent. */
    public boolean serverVelocityIsDistinctFromClientVelocity() {
      return !serverVelocity.equals(clientVelocity) || serverTick != null;
    }

    public ClientPhysicsState observe(
        long tick,
        Player observedAfter,
        Vec3 movement,
        Vec3 retainedClientVelocity,
        Vec3 retainedPredictedVelocity,
        Vec3 retainedServerVelocity,
        Long authoritativeServerTick,
        String reason) {
      return new ClientPhysicsState(
          Math.max(0L, tick),
          observedAfter.position(),
          Objects.requireNonNull(retainedClientVelocity),
          Objects.requireNonNull(retainedPredictedVelocity),
          Objects.requireNonNull(retainedServerVelocity),
          Objects.requireNonNull(movement),
          onGround,
          observedAfter.onGround(),
          authoritativeServerTick,
          Objects.requireNonNull(reason));
    }
  }

  public record CompensatedWorld(
      WorldSnapshot snapshot,
      long movementSequence,
      long causalSequence,
      boolean causallyBounded,
      String source) implements Serializable {
    public CompensatedWorld {
      Objects.requireNonNull(snapshot);
      if (movementSequence < 0L) throw new IllegalArgumentException("movementSequence must be non-negative");
      if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
    }

    public static CompensatedWorld forMovement(
        WorldSnapshot snapshot,
        long movementSequence,
        String source) {
      Objects.requireNonNull(snapshot);
      long causalSequence = snapshot.causalSequence();
      boolean bounded = causalSequence < 0L || causalSequence <= movementSequence;
      return new CompensatedWorld(
          snapshot,
          movementSequence,
          causalSequence,
          bounded,
          source);
    }
  }

  public enum Reliability {
    RELIABLE,
    PARTIAL,
    UNRELIABLE
  }

  public record TickReliabilityState(
      long clientTick,
      Reliability reliability,
      boolean exact,
      boolean timingUncertain,
      boolean sequenceGap,
      boolean historyTruncated,
      List<String> reasons) implements Serializable {
    public TickReliabilityState {
      if (clientTick < 0L) throw new IllegalArgumentException("clientTick must be non-negative");
      Objects.requireNonNull(reliability);
      reasons = List.copyOf(reasons);
    }

    public static TickReliabilityState assess(
        long clientTick,
        boolean known,
        boolean exact,
        boolean timingUncertain,
        boolean sequenceGap,
        boolean historyTruncated) {
      LinkedHashSet<String> reasons = new LinkedHashSet<>();
      Reliability level;
      if (!known) {
        level = Reliability.UNRELIABLE;
        reasons.add("client simulation tick is not known");
      } else {
        if (!exact) reasons.add("client tick is represented by a non-exact timing state");
        if (timingUncertain) reasons.add("Phase 7 timing reconstruction remains uncertain");
        if (sequenceGap) reasons.add("packet sequence gap weakens client-tick chronology");
        if (historyTruncated) reasons.add("bounded timing history was truncated");
        level = reasons.isEmpty() ? Reliability.RELIABLE : Reliability.PARTIAL;
      }
      return new TickReliabilityState(
          Math.max(0L, clientTick),
          level,
          exact,
          timingUncertain,
          sequenceGap,
          historyTruncated,
          List.copyOf(reasons));
    }
  }

  public record MovementHypothesis(
      long candidateId,
      long simulationTick,
      Vec3 position,
      Vec3 velocity,
      boolean onGround,
      String inputAssumption,
      String worldReference,
      boolean uncertain) implements Serializable {
    public MovementHypothesis {
      if (candidateId < 0L || simulationTick < 0L) {
        throw new IllegalArgumentException("invalid hypothesis identity");
      }
      Objects.requireNonNull(position);
      Objects.requireNonNull(velocity);
      Objects.requireNonNull(inputAssumption);
      Objects.requireNonNull(worldReference);
    }

    public static MovementHypothesis fromCandidate(Phase6Reachability.Candidate candidate) {
      Objects.requireNonNull(candidate);
      return new MovementHypothesis(
          candidate.id(),
          candidate.context().simulationTick(),
          candidate.context().player().position(),
          candidate.context().player().velocity(),
          candidate.context().player().onGround(),
          candidate.inputAssumption(),
          candidate.worldReference(),
          !candidate.context().uncertainty().isEmpty()
              || candidate.context().player().uncertain());
    }
  }

  public static List<MovementHypothesis> hypotheses(
      Set<Phase6Reachability.Candidate> candidates) {
    if (candidates == null || candidates.isEmpty()) return List.of();
    return candidates.stream()
        .map(MovementHypothesis::fromCandidate)
        .sorted(Comparator.comparingLong(MovementHypothesis::candidateId))
        .toList();
  }
}
