              inputHistory, simulationTick, targetTick, movementSequence,
              movementTimingUncertain);

          Candidate beforeCandidate = local.stream().findFirst().orElse(null);
          if (beforeCandidate != null) {
            MovementEnvironment frontierEnvironment =
                beforeCandidate.context().movementEnvironment();
            trace.add("SIM_INPUT_OPTIONS tick=" + simulationTick
                + " keyOptions=" + inputOptions
                + " physicalSprint=" + frontierEnvironment.sprinting()
                + " physicalSneak=" + frontierEnvironment.sneaking()
                + " startPos=" + beforeCandidate.context().player().position()
                + " startVel=" + beforeCandidate.context().player().velocity()
                + " startGround=" + beforeCandidate.context().player().onGround()
                + " inputSelection=grim-held-state");
          }

          GrimPredictionEngine.TickResult engineResult = grimPredictionEngine.tick(
              local,
              inputOptions,
              world,
              maximumCandidates,
              movementSequence,
              simulationTick,
              targetTick,
              actualMovementReference,
              lastOnGroundForPrediction,
              authoritativeMovementEnvironment,
              movementTimingUncertain);

          trace.addAll(engineResult.trace());
          LinkedHashSet<String> stepReasons = new LinkedHashSet<>(engineResult.reasons());
          boolean stepExhaustive = engineResult.exhaustive();
          Set<Candidate> stepCandidates = engineResult.candidates();

          simulatedTicks++;
          if (!stepExhaustive) {
            reasons.addAll(stepReasons);
            reasons.add("prediction step " + simulationTick
                + " was not exhaustively modeled");
            trace.add("SIM_STEP tick=" + simulationTick
                + " exhaustive=false"
                + " branchCandidates=" + stepCandidates.size()
                + " reasons=" + stepReasons);
            exhaustive = false;
          }

          if (stepCandidates.isEmpty()) {
            local = Set.of();
            break;
          }

          if (stepCandidates.size() > maximumCandidates) {
            return new AdvanceResult(Set.of(), false, simulatedTicks,
                List.of("prediction candidate budget exceeded across input chronologies"),
                List.copyOf(trace));
          }

          /*
           * Grim continues its possible-vector frontier after a non-exhaustive
           * input/timing step. We cannot promote the result to POSSIBLE, but a
           * non-empty modeled vector is still a valid causal state and must be
           * carried into the next tick rather than freezing the parent frontier.
           */
          local = Set.copyOf(stepCandidates);
          Candidate afterCandidate = local.stream().findFirst().orElse(null);
          if (afterCandidate != null) {
            MovementEnvironment resultEnvironment =
                afterCandidate.context().movementEnvironment();
            trace.add("SIM_STEP tick=" + simulationTick
                + " exhaustive=true"
                + " resultPos=" + afterCandidate.context().player().position()
                + " resultVel=" + afterCandidate.context().player().velocity()
                + " resultGround=" + afterCandidate.context().player().onGround()
                + " resultPhysicalSprint=" + resultEnvironment.sprinting()
                + " resultPhysicalSneak=" + resultEnvironment.sneaking());
          }

          localTick++;
        }

        union.addAll(local);