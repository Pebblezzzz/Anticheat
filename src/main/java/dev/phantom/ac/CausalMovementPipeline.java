              candidate.provenance().mergedPathCount(),
              candidate.provenance().mergedParentIds(),
              candidate.provenance().assumptions())));
    }
    return Optional.empty();
  }

  private static Optional<Candidate> authorityObservationWitness(
      MovementEvent movement,
      long simulationTick) {
    Optional<AuthoritativeSnapshot> authority = movement.authority().snapshot();
    if (authority.isEmpty() || simulationTick < 0) return Optional.empty();
    AuthoritativeSnapshot snapshot = authority.get();
    if (snapshot.sequence() == 0L) return Optional.empty();
    long age = movement.event().serverTick() - snapshot.serverTick();
    if (age < 0 || age > 1L) return Optional.empty();
    Player authoritative = playerFromAuthority(snapshot.context());
    Player observed = movement.stateFrame().after();
    if (!Phase6Reachability.positionMatches(authoritative.position(), observed.position())) return Optional.empty();
    if (!movement.world().fullyKnown(playerCollisionBox(observed))) return Optional.empty();
    if (authoritative.onGround() != observed.onGround()) return Optional.empty();
    if (movement.move().onGround() != null && authoritative.onGround() != movement.move().onGround()) return Optional.empty();
    /*
     * PlayerContext intentionally has no server yaw/pitch. The movement packet's
     * orientation is therefore the observed client orientation and must not be
     * compared against the synthetic 0/0 values used by playerFromAuthority().
     * Likewise, the authority's client-tick watermark identifies its capture
     * boundary; it need not equal the later movement's reconstructed client tick.
     */
    float yaw = movement.move().yaw() == null ? observed.yaw() : movement.move().yaw();
    float pitch = movement.move().pitch() == null ? observed.pitch() : movement.move().pitch();
    Player witnessPlayer = new Player(
        observed.position(),
        authoritative.velocity(),
        yaw,
        pitch,
        movement.move().onGround() == null ? authoritative.onGround() : movement.move().onGround(),
        authoritative.gamemode(),
        authoritative.effects(),
        authoritative.awaitingTeleport(),
        false,
        observed.input(),
        authoritative.attributes(),
        authoritative.pose(),
        authoritative.environment(),
        observed.clientTickRange(),
        authoritative.provenance(),
        authoritative.uncertaintyReasons());
    MovementEnvironment environment = movementEnvironmentOf(witnessPlayer);
    Context context = new Context(
        simulationTick,
        witnessPlayer,
        simulationEnvironmentFor(environment),
        witnessPlayer.attributes(),
        movementEffects(witnessPlayer),
        witnessPlayer.pose(),
        environment,
        witnessPlayer.pose() == Pose.SLEEPING,
        entityCollisionsFor(movement));
    return Optional.of(new Candidate(
        0,
        context,
        new Phase6Reachability.Provenance(
            0,
            snapshot.sequence(),
            simulationTick,
            "AUTHORITATIVE_ZERO_DELTA",
            "AUTHORITY",
            "None",
            List.of("observed position matches authoritative snapshot; no client physics step required"),
            1,
            List.of())));
  }

  private static boolean exceedsConservativeKinematicBound(
      Candidate candidate,
      Player observed,
      long targetTick) {
    long startTick = candidate.context().simulationTick();
    long ticks = targetTick - startTick;