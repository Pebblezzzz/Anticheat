package dev.phantom.ac;

import dev.phantom.ac.Phase5Mechanics.MovementEffects;
import dev.phantom.ac.Phase5Mechanics.MovementEnvironment;
import dev.phantom.ac.Phase5Mechanics.Pose;
import dev.phantom.ac.Phase6Reachability.Candidate;
import dev.phantom.ac.Phase6Reachability.ExternalTransition;
import dev.phantom.ac.Phase6Reachability.InputConstraint;
import dev.phantom.ac.Phase6Reachability.SearchResult;
import dev.phantom.ac.Phase6Reachability.WorldBranch;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.world.BlockState;
import dev.phantom.ac.world.EntityCollisions;
import dev.phantom.ac.world.WorldSnapshot;
import dev.phantom.ac.world.v12111.BlockCatalogue12111;

import java.util.*;

/** Canonical live Phase 8 orchestration over the authoritative Phase 1-7 engines. */
public final class Phase8LiveValidation {
  private Phase8LiveValidation() {}

  public record Report(List<Phase8MovementValidation.Result> results, int movementObservations,
                       int possible, int uncertain, int impossible) { public Report { results = List.copyOf(results); } }
  record ParentAggregation(SearchResult result, Set<Candidate> candidates, boolean timingOffsetsExhaustive) { ParentAggregation { Objects.requireNonNull(result); candidates=Set.copyOf(candidates); } }

  static ParentAggregation aggregateParentSearches(List<Phase6Reachability.TimingSearchResult> searches,int maximumCandidates,long timingSpan){
    Objects.requireNonNull(searches);Contracts.requireCandidateBudget(maximumCandidates);if(timingSpan<1)throw new IllegalArgumentException("timing span must be positive");
    Set<Candidate> nextAll=new LinkedHashSet<>();boolean hasPossible=false,hasImpossible=false,hasUncertain=false,timingExhaustive=true;int simulatedTicks=0,peakCandidates=0,merged=0,nonExhaustiveWorldBranches=0,uncertainTransitions=0,provenanceMerges=0;LinkedHashSet<String> reasons=new LinkedHashSet<>();
    for(Phase6Reachability.TimingSearchResult search:searches){boolean offsetPossible=false,offsetUncertain=false,offsetImpossible=true;int offsetResults=0;for(SearchResult perOffset:search.byFirstTick().values()){offsetResults++;simulatedTicks=Math.max(simulatedTicks,perOffset.simulatedTicks());peakCandidates=Math.max(peakCandidates,perOffset.peakCandidates());merged+=perOffset.mergedStates();nonExhaustiveWorldBranches+=perOffset.nonExhaustiveWorldBranches();uncertainTransitions+=perOffset.uncertainTransitions();provenanceMerges+=perOffset.provenanceMerges();switch(perOffset.verdict()){case POSSIBLE->{offsetPossible=true;offsetImpossible=false;nextAll.addAll(perOffset.candidates());}case IMPOSSIBLE->{ }case UNCERTAIN->{offsetUncertain=true;offsetImpossible=false;nextAll.addAll(perOffset.candidates());}}}
      boolean evaluatedAllOffsets=search.evaluatedOffsets()==timingSpan&&search.skippedOffsets()==0&&offsetResults==timingSpan;if(!evaluatedAllOffsets||offsetUncertain)timingExhaustive=false;if(offsetUncertain)hasUncertain=true;else if(offsetPossible)hasPossible=true;else if(evaluatedAllOffsets&&offsetImpossible)hasImpossible=true;else hasUncertain=true;reasons.addAll(search.reasons());if(nextAll.size()>maximumCandidates){timingExhaustive=false;hasUncertain=true;nextAll.clear();reasons.add("combined Phase 6 timing candidate budget exceeded; provisional candidates are not safe to continue");break;}}
    Phase6Reachability.Verdict verdict;if(hasUncertain)verdict=Phase6Reachability.Verdict.UNCERTAIN;else if(hasPossible&&!nextAll.isEmpty())verdict=Phase6Reachability.Verdict.POSSIBLE;else if(hasImpossible)verdict=Phase6Reachability.Verdict.IMPOSSIBLE;else{verdict=Phase6Reachability.Verdict.UNCERTAIN;timingExhaustive=false;reasons.add("no parent branch produced an evaluable result");}if(verdict==Phase6Reachability.Verdict.IMPOSSIBLE&&reasons.isEmpty())reasons.add("all exhaustively modeled parent branches are impossible");
    SearchResult aggregate=new SearchResult(verdict,Set.copyOf(nextAll),simulatedTicks,peakCandidates,merged,nonExhaustiveWorldBranches,uncertainTransitions,provenanceMerges,List.copyOf(reasons));return new ParentAggregation(aggregate,nextAll,timingExhaustive);
  }

  public static Report analyze(String playerId,Timeline.Snapshot timeline,int maximumCandidates,Phase7Timing.Config timingConfig){
    return analyze(playerId,timeline,maximumCandidates,timingConfig,null,null);
  }

  /**
   * Live entry point. The optional liveWorld is used only for the newest movement
   * observation, where it represents the persistent acknowledged client-visible
   * world. Earlier observations continue to use the historical replay world.
   */
  public static Report analyze(String playerId,Timeline.Snapshot timeline,int maximumCandidates,Phase7Timing.Config timingConfig,WorldSnapshot liveWorld){
    return analyze(playerId,timeline,maximumCandidates,timingConfig,liveWorld,null);
  }

  /**
   * Live entry point with an optional authoritative server-state seed captured at
   * player join/world-change time. Supplying this seed lets the first observed
   * movement be validated against a real starting state instead of automatically
   * declaring that first movement an untestable replay anchor.
   */
  public static Report analyze(String playerId,Timeline.Snapshot timeline,int maximumCandidates,Phase7Timing.Config timingConfig,WorldSnapshot liveWorld,Player initialAnchor){
    Objects.requireNonNull(playerId);Objects.requireNonNull(timeline);Objects.requireNonNull(timingConfig);Contracts.requireCandidateBudget(maximumCandidates);

    World.VisibilityHistory history=World.fromTimeline(timeline);
    long latestMovementSequence=timeline.events().stream()
        .filter(e->e.packet().packet() instanceof Packets.Move m&&m.position()!=null)
        .mapToLong(e->e.packet().sequence()).max().orElse(-1L);
    Phase7Timing.Reconstruction timing=Phase7Timing.reconstruct(timeline,timingConfig);
    Phase6Reachability engine=new Phase6Reachability(new Vanilla12111RichPhysics());
    State.Reconstruction observedStates=reconstructObservedStates(timeline,initialAnchor);
    Map<Long,State.StateFrame> observedBySequence=observedStatesBySequence(observedStates);
    Map<Long,List<ExternalTransition>> externalByClientTick=externalTransitions(timeline,timing);

    Set<Candidate> candidates=Set.of();
    Continuation continuation=Continuation.UNANCHORED;
    Integer pendingTeleportId=null;
    InputConstraint currentInput=InputConstraint.any();
    Map<Long,InputConstraint> inputByClientTick=inputConstraintsByClientTick(timeline);
    boolean hasClientTickBoundaries=timeline.events().stream().anyMatch(e->e.packet().packet() instanceof Packets.ClientTickEnd);
    EntityCollisions currentEntityCollisions=EntityCollisions.NONE_TRACKED;
    List<Phase8MovementValidation.Result> results=new ArrayList<>();
    int movements=0;
    long previousMovementTick=-1;

    for(Timeline.Event event:timeline.events()){
      Packets.NormalizedPacket normalized=event.packet();
      Packets.Packet packet=normalized.packet();

      if(packet instanceof Packets.ClientInput input){
        if(!normalized.flags().contains(Packets.PacketFlag.DUPLICATE)) {
          // Minecraft input state is held until the client sends a replacement.
          // Missing input packets are therefore not converted into "no input".
          currentInput=InputConstraint.fromClientInput(input);
        }
        continue;
      }

      if(packet instanceof Packets.PlayerContext context){
        if(!normalized.flags().contains(Packets.PacketFlag.DUPLICATE)) {
          currentEntityCollisions=EntityCollisions.of(context.entityBoxes());
        }
        continue;
      }

      if(packet instanceof Packets.Teleport teleport){
        pendingTeleportId=teleport.id();
        State.StateFrame frame=observedBySequence.get(normalized.sequence());
        Phase7Timing.EventTiming teleportTiming=timing.timingFor(normalized.sequence()).orElse(null);
        if(frame!=null&&teleportTiming!=null){
          Player anchored=teleportAnchorState(frame.after());
          WorldSnapshot world=(liveWorld!=null&&normalized.sequence()==latestMovementSequence)
          ?liveWorld
          :history.statesAt(event.serverTick());
          Validation.SyncWindow sync=Phase7Timing.toPhase6Window(teleportTiming);
          Player safe=simulationSafe(anchored);
          if(!safe.uncertain()){
            long anchorTick=Math.max(0,sync.earliestClientTick());
            candidates=Set.of(new Candidate(0,anchorContext(safe,world,currentInput,anchorTick,currentEntityCollisions),
                new Phase6Reachability.Provenance(0,-1,event.serverTick(),"ROOT","ROOT","TeleportCorrection",
                    List.of("authoritative server correction anchor"),1,List.of())));
            continuation=Continuation.ACTIVE;
          } else {
            candidates=Set.of();
            continuation=Continuation.UNANCHORED;
          }
        } else {
          candidates=Set.of();
          continuation=Continuation.UNANCHORED;
        }
        continue;
      }

      if(packet instanceof Packets.TeleportConfirm confirm){
        if(pendingTeleportId!=null&&pendingTeleportId==confirm.id()) pendingTeleportId=null;
        continue;
      }

      if(!(packet instanceof Packets.Move move)||move.position()==null) continue;
      movements++;

      Phase7Timing.EventTiming eventTiming=timing.timingFor(normalized.sequence()).orElse(null);
      State.StateFrame stateFrame=observedBySequence.get(normalized.sequence());
      WorldSnapshot world=(liveWorld!=null&&normalized.sequence()==latestMovementSequence)
          ?liveWorld
          :history.statesAt(event.serverTick());
      if(eventTiming==null||stateFrame==null){
        results.add(anchorUncertain(playerId,event.serverTick(),stateFrame,world,
            "missing Phase 7 timing or Phase 2 state frame","live:phase8:"+normalized.sequence()));
        continue;
      }

      Player prior=stateFrame.before();
      Player observed=stateFrame.after();
      Validation.SyncWindow sync=Phase7Timing.toPhase6Window(eventTiming);
      String replayReference="live:phase8:"+playerId+":"+normalized.sequence();

      // Multiple movement packets within one client tick are sub-tick observations.
      // Grim retains these separately instead of pretending each packet consumed a
      // complete physics tick. Our core currently has one deterministic step per tick,
      // so such an observation is explicitly uncertain rather than falsely impossible.
      long movementTick=eventTiming.simulationClientTicks().min();
      String worldReference="timeline-world:serverTick="+event.serverTick()+":chunks="+world.loadedChunks().size();
      if(previousMovementTick>=0
          &&eventTiming.simulationClientTicks().isExact()
          &&movementTick==previousMovementTick){
        SearchResult subTick=new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,candidates,0,candidates.size(),
            0,0,1,0,List.of("multiple movement packets occurred in the same client simulation tick; sub-tick motion is not yet modeled"));
        results.add(Phase8MovementValidation.validate(playerId,event.serverTick(),prior,observed,world,
            worldReference,sync,List.of("sub-tick movement packet; no full physics tick was advanced"),subTick,replayReference));
        // We cannot safely consume the next full-tick observation until the missing
        // sub-tick trajectory is modeled or a fresh authoritative anchor arrives.
        continuation=Continuation.UNCERTAIN_EMPTY;
        previousMovementTick=movementTick;
        continue;
      }
      // Record before any early-return validation path, including the initial anchor.
      if(eventTiming.simulationClientTicks().isExact()) previousMovementTick=movementTick;
      // Record the last exact movement tick before any early-return path so the
      // next movement packet in the same client tick is recognized correctly.
      if(eventTiming.simulationClientTicks().isExact()) previousMovementTick=movementTick;
      String inputDescription="client-input="+currentInput
          +", input-held-until-replacement=true"
          +", timing-offsets="+sync.earliestClientTick()+".."+sync.latestClientTick();
      List<String> inputAssumptions=List.of(inputDescription);

      boolean chronologyUncertain=normalized.flags().stream().anyMatch(flag ->
          flag==Packets.PacketFlag.DUPLICATE
              ||flag==Packets.PacketFlag.OUT_OF_ORDER
              ||flag==Packets.PacketFlag.BEFORE_CAPTURE_EPOCH);
      if(chronologyUncertain){
        SearchResult uncertain=new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,candidates.size(),
            0,0,1,0,List.of("movement record chronology is not exhaustive: "+normalized.flags()));
        results.add(Phase8MovementValidation.validate(playerId,event.serverTick(),prior,observed,world,
            worldReference,sync,inputAssumptions,uncertain,replayReference));
        continue;
      }

      if(continuation==Continuation.UNANCHORED){
        Player safeObserved=simulationSafe(observed);
        if(initialAnchor!=null&&!initialAnchor.uncertain()){
          Player safeInitial=simulationSafe(initialAnchor);
          long earliest=Math.max(0,sync.earliestClientTick());
          long latest=Math.max(earliest,sync.latestClientTick());
          long timingSpan=latest-earliest+1;
          if(timingSpan>timingConfig.maxTimingCandidates()){
            SearchResult uncertain=new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,1,0,0,1,0,
                List.of("first movement timing window exceeds the configured exhaustive envelope"));
            results.add(Phase8MovementValidation.validate(playerId,event.serverTick(),prior,observed,world,
                worldReference,sync,inputAssumptions,uncertain,replayReference));
            continuation=Continuation.UNCERTAIN_EMPTY;
            continue;
          }

          // For the first movement only, a server-captured anchor removes the artificial
          // "first packet is always the root" blind spot. The snapshot is exhaustive
          // only when no world mutations occurred before this observation; otherwise a
          // historical client-world trace is required before IMPOSSIBLE is safe.
          boolean worldStable=!timeline.events().stream().anyMatch(e->
              e.packet().packet().mutatesWorld()
                  && e.serverTick()>0
                  && e.serverTick()<=event.serverTick());
          long anchorTick=0L;
          InputConstraint anchorInput=inputForTick(inputByClientTick,anchorTick);
          Phase6Reachability.Context root=anchorContext(safeInitial,world,anchorInput,anchorTick,currentEntityCollisions);

          List<SearchResult> searches=new ArrayList<>();
          boolean exhaustive=worldStable;
          for(long targetTick=earliest;targetTick<=latest;targetTick++){
            if(targetTick<anchorTick){
              exhaustive=false;
              searches.add(new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,1,0,0,1,0,
                  List.of("first observed client simulation tick precedes the server join anchor")));
              continue;
            }
            long steps=targetTick-anchorTick;
            List<InputConstraint> inputs=new ArrayList<>((int)Math.min(Integer.MAX_VALUE,steps));
            for(long i=anchorTick;i<targetTick;i++){
              if(hasClientTickBoundaries) inputs.add(inputForTick(inputByClientTick,i));
              else inputs.add(currentInput);
            }
            SearchResult search=engine.search(root,inputs,
                tick->List.of(new WorldBranch("initial-client-visible-"+event.serverTick(),world,worldStable,
                    worldStable?"world remained unchanged before the first movement":"world changed before the first movement; historical client-world replay required")),
                tick->externalByClientTick.getOrDefault(tick,List.of(new Phase6Reachability.None())),
                maximumCandidates);
            searches.add(search);
            if(search.verdict()!=Phase6Reachability.Verdict.POSSIBLE)exhaustive=false;
          }

          previousMovementTick=movementTick;
      ParentAggregation aggregated=aggregateDirectSearches(searches,maximumCandidates,exhaustive);
          SearchResult reachable=aggregated.result();
          Phase8MovementValidation.Result validation=Phase8MovementValidation.validate(playerId,event.serverTick(),prior,observed,world,
              worldReference,sync,inputAssumptions,reachable,replayReference,aggregated.timingOffsetsExhaustive());
          results.add(validation);

          if(reachable.verdict()==Phase6Reachability.Verdict.POSSIBLE){
            Set<Candidate> matching=new LinkedHashSet<>();
            for(Candidate candidate:aggregated.candidates())
              if(matchesObserved(candidate.context().player(),observed))matching.add(candidate);
            if(matching.isEmpty()){continuation=Continuation.IMPOSSIBLE;candidates=Set.of();}
            else {candidates=Set.copyOf(matching);continuation=Continuation.ACTIVE;}
          }else if(reachable.verdict()==Phase6Reachability.Verdict.UNCERTAIN){
            candidates=aggregated.candidates();
            continuation=candidates.isEmpty()?Continuation.UNCERTAIN_EMPTY:Continuation.ACTIVE;
          }else{
            candidates=Set.of();
            continuation=Continuation.IMPOSSIBLE;
          }
          continue;
        }

        // Without an authoritative seed, preserve the original conservative contract:
        // the first packet becomes a replay anchor and cannot itself prove a violation.
        SearchResult anchor=new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,0,
            0,0,0,0,List.of("first movement observation establishes the Phase 6 replay anchor because no authoritative seed was captured"));
        results.add(Phase8MovementValidation.validate(playerId,event.serverTick(),prior,observed,world,
            worldReference,sync,inputAssumptions,anchor,replayReference));
        if(safeObserved.uncertain()){
          continuation=Continuation.UNANCHORED;
          continue;
        }
        long anchorTick=Math.max(0,sync.earliestClientTick());
        candidates=Set.of(new Candidate(0,anchorContext(safeObserved,world,currentInput,anchorTick,currentEntityCollisions),
            new Phase6Reachability.Provenance(0,-1,event.serverTick(),"ROOT","ROOT","None",
                List.of("live movement anchor"),1,List.of())));
        continuation=Continuation.ACTIVE;
        continue;
      }

      if(continuation==Continuation.UNCERTAIN_EMPTY||continuation==Continuation.IMPOSSIBLE){
        Phase6Reachability.Verdict terminalVerdict=
            continuation==Continuation.IMPOSSIBLE?Phase6Reachability.Verdict.IMPOSSIBLE:Phase6Reachability.Verdict.UNCERTAIN;
        List<String> terminalReasons=continuation==Continuation.IMPOSSIBLE
            ?List.of("existing Phase 6 reachable candidate set was exhaustively eliminated by an observed movement")
            :List.of("Phase 6 candidate chain is unavailable because prior validation was uncertain");
        SearchResult terminal=new SearchResult(terminalVerdict,Set.of(),0,0,0,0,
            terminalVerdict==Phase6Reachability.Verdict.UNCERTAIN?1:0,0,terminalReasons);
        results.add(Phase8MovementValidation.validate(playerId,event.serverTick(),prior,observed,world,
            worldReference,sync,inputAssumptions,terminal,replayReference,
            terminalVerdict!=Phase6Reachability.Verdict.UNCERTAIN));
        continue;
      }

      long earliest=Math.max(0,sync.earliestClientTick());
      long latest=Math.max(earliest,sync.latestClientTick());
      long timingSpan=latest-earliest+1;
      if(timingSpan>timingConfig.maxTimingCandidates()){
        SearchResult uncertain=new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,candidates.size(),
            0,0,1,0,List.of("Phase 7 timing window exceeds the configured exhaustive envelope"));
        continuation=Continuation.UNCERTAIN_EMPTY;
        results.add(Phase8MovementValidation.validate(playerId,event.serverTick(),prior,observed,world,
            worldReference,sync,inputAssumptions,uncertain,replayReference));
        continue;
      }

      List<SearchResult> searches=new ArrayList<>();
      boolean exhaustive=true;

      for(Candidate parent:candidates){
        long parentTick=parent.context().simulationTick();
        boolean parentWorldComplete=worldCoverageExhaustive(world,parent);
        for(long targetTick=earliest;targetTick<=latest;targetTick++){
          if(targetTick<parentTick){
            exhaustive=false;
            searches.add(new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,1,0,0,1,0,
                List.of("observed simulation tick precedes the current candidate tick; clocks cannot be inverted")));
            continue;
          }

          if(!parentWorldComplete){
            exhaustive=false;
            searches.add(new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,1,0,1,1,0,
                List.of("client-visible world does not fully cover the current candidate collision volume")));
            continue;
          }

          long steps=targetTick-parentTick;
          if(steps==0){
            Candidate same=parent;
            searches.add(new SearchResult(Phase6Reachability.Verdict.POSSIBLE,Set.of(same),0,1,0,0,0,0,
                List.of("observed packet falls in the current simulation tick; no physics tick is advanced")));
            continue;
          }

          if(steps>Phase6Reachability.MAX_HORIZON_TICKS){
            exhaustive=false;
            searches.add(new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,1,0,0,1,0,
                List.of("movement gap exceeds the finite Phase 6 simulation horizon")));
            continue;
          }

          List<InputConstraint> perTickInput=new ArrayList<>((int)steps);
          for(long i=0;i<steps;i++){
            long simulationTick=parentTick+i;
            perTickInput.add(hasClientTickBoundaries?inputForTick(inputByClientTick,simulationTick):currentInput);
          }

          Phase6Reachability.Context prepared=withObservedEnvironment(parent.context(),world,currentInput,parentTick,currentEntityCollisions);
          List<InputConstraint> inputs=List.copyOf(perTickInput);
          SearchResult search=engine.search(prepared,inputs,
              tick->List.of(new WorldBranch("client-visible-"+event.serverTick(),world,parentWorldComplete,
                  parentWorldComplete
                      ?"historical client-visible world covers the candidate start volume"
                      :"Phase 4 visibility does not fully cover the candidate start volume; unknown cells remain unevaluable")),
              tick->externalByClientTick.getOrDefault(tick,List.of(new Phase6Reachability.None())),
              maximumCandidates);
          searches.add(search);
          if(search.verdict()==Phase6Reachability.Verdict.UNCERTAIN) exhaustive=false;
        }
      }

      ParentAggregation aggregated=aggregateDirectSearches(searches,maximumCandidates,exhaustive);
      SearchResult reachable=aggregated.result();
      Phase8MovementValidation.Result validation=Phase8MovementValidation.validate(playerId,event.serverTick(),prior,observed,world,
          worldReference,sync,inputAssumptions,reachable,replayReference,aggregated.timingOffsetsExhaustive());
      results.add(validation);

      if(reachable.verdict()==Phase6Reachability.Verdict.POSSIBLE){
        Set<Candidate> matching=new LinkedHashSet<>();
        for(Candidate candidate:aggregated.candidates()) {
          if(matchesObserved(candidate.context().player(),observed)) matching.add(candidate);
        }
        if(matching.isEmpty()){
          // The simulator exhausted every modeled path but none reached the observed state.
          continuation=Continuation.IMPOSSIBLE;
          candidates=Set.of();
        } else {
          candidates=Set.copyOf(matching);
          continuation=Continuation.ACTIVE;
        }
      } else if(reachable.verdict()==Phase6Reachability.Verdict.UNCERTAIN){
        candidates=aggregated.candidates();
        if(candidates.isEmpty()&&canReanchorAfterUncertainty(observed,world,reachable)){
          long anchorTick=Math.max(0,sync.earliestClientTick());
          Player safe=simulationSafe(observed);
          candidates=Set.of(new Candidate(0,anchorContext(safe,world,currentInput,anchorTick,currentEntityCollisions),
              new Phase6Reachability.Provenance(0,-1,event.serverTick(),"RECOVERY","UNCERTAIN_WORLD","None",
                  List.of("re-anchored after previously incomplete client-visible world became exhaustive"),1,List.of())));
          continuation=Continuation.ACTIVE;
        } else {
          continuation=candidates.isEmpty()?Continuation.UNCERTAIN_EMPTY:Continuation.ACTIVE;
        }
      } else {
        candidates=Set.of();
        continuation=Continuation.IMPOSSIBLE;
      }
    }

    int possible=(int)results.stream().filter(r->r.verdict()==Phase8MovementValidation.Verdict.POSSIBLE).count();
    int uncertain=(int)results.stream().filter(r->r.verdict()==Phase8MovementValidation.Verdict.UNCERTAIN).count();
    int impossible=(int)results.stream().filter(r->r.verdict()==Phase8MovementValidation.Verdict.IMPOSSIBLE).count();
    return new Report(results,movements,possible,uncertain,impossible);
  }

  private static Map<Long,InputConstraint> inputConstraintsByClientTick(Timeline.Snapshot timeline){
    TreeMap<Long,InputConstraint> out=new TreeMap<>();
    long clientTick=0;
    for(Timeline.Event event:timeline.events()){
      Packets.Packet packet=event.packet().packet();
      if(packet instanceof Packets.ClientTickEnd){
        if(!event.packet().flags().contains(Packets.PacketFlag.DUPLICATE))clientTick++;
      }else if(packet instanceof Packets.ClientInput input && !event.packet().flags().contains(Packets.PacketFlag.DUPLICATE)){
        out.put(clientTick,InputConstraint.fromClientInput(input));
      }
    }
    return Map.copyOf(out);
  }

  private static InputConstraint inputForTick(Map<Long,InputConstraint> inputByClientTick,long tick){
    if(inputByClientTick.isEmpty())return InputConstraint.any();
    long best=Long.MIN_VALUE;
    InputConstraint selected=null;
    for(var entry:inputByClientTick.entrySet()){
      if(entry.getKey()<=tick&&entry.getKey()>best){best=entry.getKey();selected=entry.getValue();}
    }
    return selected==null?InputConstraint.any():selected;
  }

  static ParentAggregation aggregateDirectSearches(List<SearchResult> searches,int maximumCandidates,boolean timingOffsetsExhaustive){
    Objects.requireNonNull(searches);Contracts.requireCandidateBudget(maximumCandidates);
    LinkedHashSet<Candidate> candidates=new LinkedHashSet<>();
    LinkedHashSet<String> reasons=new LinkedHashSet<>();
    boolean hasPossible=false,hasImpossible=false,hasUncertain=!timingOffsetsExhaustive;
    int simulatedTicks=0,peakCandidates=0,merged=0,nonExhaustiveWorldBranches=0,uncertainTransitions=0,provenanceMerges=0;
    for(SearchResult result:searches){
      simulatedTicks=Math.max(simulatedTicks,result.simulatedTicks());
      peakCandidates=Math.max(peakCandidates,result.peakCandidates());
      merged+=result.mergedStates();
      nonExhaustiveWorldBranches+=result.nonExhaustiveWorldBranches();
      uncertainTransitions+=result.uncertainTransitions();
      provenanceMerges+=result.provenanceMerges();
      reasons.addAll(result.reasons());
      switch(result.verdict()){
        case POSSIBLE->{hasPossible=true;candidates.addAll(result.candidates());}
        case IMPOSSIBLE->{hasImpossible=true;}
        case UNCERTAIN->{hasUncertain=true;candidates.addAll(result.candidates());}
      }
      if(candidates.size()>maximumCandidates){
        return new ParentAggregation(
            new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),simulatedTicks,
                Math.max(peakCandidates,candidates.size()),merged,nonExhaustiveWorldBranches,
                uncertainTransitions+1,provenanceMerges,
                List.of("combined Phase 6 candidate budget exceeded; no provisional subset is exposed")),
            Set.of(),false);
      }
    }
    Phase6Reachability.Verdict verdict;
    if(hasUncertain) verdict=Phase6Reachability.Verdict.UNCERTAIN;
    else if(hasPossible&&!candidates.isEmpty()) verdict=Phase6Reachability.Verdict.POSSIBLE;
    else if(hasImpossible) verdict=Phase6Reachability.Verdict.IMPOSSIBLE;
    else {
      verdict=Phase6Reachability.Verdict.UNCERTAIN;
      timingOffsetsExhaustive=false;
      reasons.add("no direct Phase 6 target search produced an evaluable result");
    }
    if(verdict==Phase6Reachability.Verdict.IMPOSSIBLE&&reasons.isEmpty())
      reasons.add("all exhaustively modeled direct target searches were impossible");
    SearchResult aggregate=new SearchResult(verdict,Set.copyOf(candidates),simulatedTicks,peakCandidates,merged,
        nonExhaustiveWorldBranches,uncertainTransitions,provenanceMerges,List.copyOf(reasons));
    return new ParentAggregation(aggregate,candidates,timingOffsetsExhaustive&&verdict!=Phase6Reachability.Verdict.UNCERTAIN);
  }

  private static boolean canReanchorAfterUncertainty(Player observed,WorldSnapshot world,SearchResult reachable){
    if(observed==null||world==null||reachable==null||!reachable.candidates().isEmpty()) return false;
    Player safe=simulationSafe(observed);
    if(safe.uncertain()) return false;
    Maths.Aabb box=Maths.Aabb.playerAt(safe.position(),safe.pose());
    // A temporary missing-world window must not permanently poison the live candidate chain.
    // Re-anchor only once the exact collision volume is fully known; timing ambiguity alone
    // is not enough to justify a reset.
    return world.fullyKnown(new dev.phantom.ac.geometry.BlockBox(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()))
        && reachable.nonExhaustiveWorldBranches()>0;
  }

  private enum Continuation { UNANCHORED, ACTIVE, UNCERTAIN_EMPTY, IMPOSSIBLE }
  private static boolean worldCoverageExhaustive(WorldSnapshot world,Candidate parent){Maths.Aabb box=Maths.Aabb.playerAt(parent.context().player().position(),parent.context().pose());return world.fullyKnown(new dev.phantom.ac.geometry.BlockBox(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()));}
  private static boolean matchesObserved(Player candidate,Player observed){return candidate.position().equals(observed.position())&&Float.compare(candidate.yaw(),observed.yaw())==0&&Float.compare(candidate.pitch(),observed.pitch())==0&&candidate.onGround()==observed.onGround();}
  private static Player simulationSafe(Player player){
    Objects.requireNonNull(player);
    return player;
  }
  private static Player teleportAnchorState(Player player){EnumSet<State.UncertaintyReason> reasons=EnumSet.noneOf(State.UncertaintyReason.class);reasons.addAll(player.uncertaintyReasons());reasons.remove(State.UncertaintyReason.TELEPORT_CORRECTION);reasons.remove(State.UncertaintyReason.EXPLICIT_UNCERTAINTY);return new Player(player.position(),player.velocity(),player.yaw(),player.pitch(),player.onGround(),player.gamemode(),player.effects(),OptionalInt.empty(),!reasons.isEmpty(),player.input(),player.attributes(),player.pose(),player.environment(),player.clientTickRange(),player.provenance(),reasons);}
  private static Phase8MovementValidation.Result anchorUncertain(String playerId,long serverTick,State.StateFrame frame,WorldSnapshot world,String reason,String replayReference){Player state=frame==null?Player.initial(Maths.Vec3.ZERO):frame.after();Player prior=frame==null?state:frame.before();Validation.SyncWindow sync=new Validation.SyncWindow(Math.max(0,serverTick),Math.max(0,serverTick),true,List.of(reason));SearchResult uncertain=new SearchResult(Phase6Reachability.Verdict.UNCERTAIN,Set.of(),0,0,0,0,1,0,List.of(reason));return Phase8MovementValidation.validate(playerId,serverTick,prior,state,world,"timeline-world:tick="+serverTick,sync,List.of("input unavailable"),uncertain,replayReference);}
  private static State.Reconstruction reconstructObservedStates(Timeline.Snapshot timeline,Player initialAnchor){
    if(initialAnchor!=null&&!initialAnchor.uncertain())
      return State.reconstruct(State.Seed.serverAnchor(initialAnchor),timeline);
    for(Timeline.Event event:timeline.events())
      if(event.packet().packet() instanceof Packets.Move move&&move.position()!=null)
        return State.reconstruct(State.Seed.serverAnchor(Player.initial(move.position())),timeline);
    return new State.Reconstruction(List.of());
  }
  private static Map<Long,State.StateFrame> observedStatesBySequence(State.Reconstruction reconstruction){Map<Long,State.StateFrame> map=new HashMap<>();for(State.StateFrame frame:reconstruction.frames())map.put(frame.event().packet().sequence(),frame);return map;}
  private static Phase6Reachability.Context anchorContext(Player player,WorldSnapshot world,InputConstraint input,long tick,EntityCollisions entityCollisions){MovementEnvironment env=inferEnvironment(world,player,input);MovementEffects effects=movementEffects(player);Pose pose=player.pose();return new Phase6Reachability.Context(tick,player,environmentFor(env),player.attributes(),effects,pose,env,pose==Pose.SLEEPING,entityCollisions);}
  private static Phase6Reachability.Context withObservedEnvironment(Phase6Reachability.Context context,WorldSnapshot world,InputConstraint input,long tick,EntityCollisions entityCollisions){MovementEnvironment env=inferEnvironment(world,context.player(),input);return new Phase6Reachability.Context(tick,context.player(),environmentFor(env),context.player().attributes(),movementEffects(context.player()),context.player().pose(),env,context.player().pose()==Pose.SLEEPING,entityCollisions,context.uncertainty());}
  private static Simulation.Environment environmentFor(MovementEnvironment env){if(env.fluid()==Phase5Mechanics.Fluid.WATER)return Simulation.Environment.WATER;if(env.fluid()==Phase5Mechanics.Fluid.LAVA)return Simulation.Environment.LAVA;if(env.climbable())return Simulation.Environment.CLIMBABLE;return Simulation.Environment.DRY;}
  private static MovementEnvironment inferEnvironment(WorldSnapshot world,Player player,InputConstraint input){Maths.Aabb box=Maths.Aabb.playerAt(player.position(),player.pose());int minX=(int)Math.floor(box.minX()),maxX=(int)Math.floor(Math.nextDown(box.maxX())),minY=(int)Math.floor(box.minY()),maxY=(int)Math.floor(Math.nextDown(box.maxY())),minZ=(int)Math.floor(box.minZ()),maxZ=(int)Math.floor(Math.nextDown(box.maxZ()));boolean water=false,lava=false,climb=false;for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){BlockState state=world.blockAtOrNull(x,y,z);if(state==null)continue;if(state.variant()==BlockState.Variant.LADDER)climb=true;var fluid=BlockCatalogue12111.fluid(state);if(fluid.type()==dev.phantom.ac.world.FluidState.Type.WATER)water=true;if(fluid.type()==dev.phantom.ac.world.FluidState.Type.LAVA)lava=true;}boolean sprint=input.sprint().orElse(false),sneak=input.sneak().orElse(false),swim=player.pose()==Pose.SWIMMING;MovementEnvironment env;if(water)env=MovementEnvironment.vanillaWater(player.onGround(),sprint,sneak,swim);else if(lava)env=MovementEnvironment.vanillaLava(player.onGround(),sprint,sneak);else if(climb)env=MovementEnvironment.vanillaClimbable(player.onGround(),sprint,sneak);else env=MovementEnvironment.dry(player.onGround(),sprint,sneak);return new MovementEnvironment(env.fluid(),env.submerged(),env.climbable(),player.onGround(),sprint,sneak,swim,player.pose()==Pose.FALL_FLYING,env.fluidSpeedMultiplier(),env.fluidDrag(),env.gravityMultiplier());}
  private static MovementEffects movementEffects(Player player){return new MovementEffects(amplifier(player.effects(),"speed","minecraft:speed"),amplifier(player.effects(),"slowness","minecraft:slowness"),amplifier(player.effects(),"jump_boost","minecraft:jump_boost"),amplifier(player.effects(),"levitation","minecraft:levitation"),player.effects().keySet().stream().anyMatch(id->id.equals("slow_falling")||id.equals("minecraft:slow_falling")));}
  private static int amplifier(Map<String,Integer> effects,String... ids){for(String id:ids){Integer value=effects.get(id);if(value!=null)return value;}return -1;}
  private static Map<Long,List<ExternalTransition>> externalTransitions(Timeline.Snapshot timeline,Phase7Timing.Reconstruction timing){
    Map<Long,List<ExternalTransition>> out=new HashMap<>();
    for(Timeline.Event event:timeline.events()){
      if(event.packet().flags().contains(Packets.PacketFlag.DUPLICATE)) continue;
      Packets.Packet packet=event.packet().packet();
      if(!(packet instanceof Packets.Velocity velocity)) continue;
      Phase7Timing.EventTiming eventTiming=timing.timingFor(event.packet().sequence()).orElse(null);
      if(eventTiming==null) continue;
      Phase7Timing.Range range=eventTiming.simulationClientTicks();
      long min=Math.max(0,range.min()),max=Math.max(min,range.max());
      long span=max-min+1;
      if(span>timing.config().maxTimingCandidates()) continue;
      ExternalTransition transition=new Phase6Reachability.VelocityImpulse(velocity.velocity(),"server velocity packet");
      for(long tick=min;tick<=max;tick++) out.computeIfAbsent(tick,ignored->new ArrayList<>()).add(transition);
    }
    Map<Long,List<ExternalTransition>> frozen=new HashMap<>();
    for(Map.Entry<Long,List<ExternalTransition>> entry:out.entrySet()) frozen.put(entry.getKey(),List.copyOf(entry.getValue()));
    return Map.copyOf(frozen);
  }
}
