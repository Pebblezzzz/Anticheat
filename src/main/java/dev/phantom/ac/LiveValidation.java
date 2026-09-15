package dev.phantom.ac;

import java.util.*;
import static dev.phantom.ac.Packets.*;
import static dev.phantom.ac.Simulation.*;
import static dev.phantom.ac.State.*;
import static dev.phantom.ac.Validation.*;

/**
 * Diagnostic multi-tick observed-vs-reachable comparison. It intentionally
 * produces evidence, not enforcement. Movement packets do not contain client
 * velocity, so this layer compares the observed fields only and records that
 * limitation rather than fabricating an observed velocity.
 */
public final class LiveValidation {
  private LiveValidation() {}
  public record Finding(long tick,Verdict verdict,int candidateCount,List<String> reasons) { public Finding { reasons=List.copyOf(reasons); } }
  public record Report(List<Finding> findings,int movementObservations) { public Report { findings=List.copyOf(findings); } }

  public static Report analyze(Timeline.Snapshot timeline,int maximumCandidates) {
    if(maximumCandidates<1) throw new IllegalArgumentException("maximumCandidates must be positive");
    World.VisibilityHistory worldHistory=World.fromTimeline(timeline);
    Vanilla12111Physics physics=new Vanilla12111Physics();
    Set<Player> candidates=Set.of();
    Input currentInput=null;
    List<Finding> findings=new ArrayList<>(); int movements=0;
    for(Timeline.Event event:timeline.events()) {
      Packet packet=event.packet().packet();
      if(packet instanceof ClientInput input) { currentInput=new Input(axis(input.forward(),input.backward()),axis(input.right(),input.left()),input.jump()); continue; }
      if(!(packet instanceof Move move) || move.position()==null) continue;
      movements++;
      Player observed=State.apply(candidates.isEmpty()?Player.initial(move.position()):candidates.iterator().next(),event.packet());
      if(candidates.isEmpty()) {
        // A first movement packet is only an anchor, not evidence that a prior state was impossible.
        candidates=Set.of(Player.initial(move.position()));
        findings.add(new Finding(event.serverTick(),Verdict.UNCERTAIN,1,List.of("first observed position anchors the replay; no preceding client state is available")));
        continue;
      }
      World.Snapshot world=worldHistory.at(event.serverTick()); Set<Player> next=new HashSet<>(); boolean unsupported=false;
      for(Player candidate:candidates) {
        if(candidate.uncertain()||world.hasUnsupportedAt(candidate.position())) { unsupported=true; continue; }
        if(currentInput!=null) next.add(physics.tick(candidate,currentInput,world));
        else for(int forward=-1;forward<=1;forward++)for(int strafe=-1;strafe<=1;strafe++)for(boolean jump:List.of(false,true)) next.add(physics.tick(candidate,new Input(forward,strafe,jump),world));
        if(next.size()>maximumCandidates) { findings.add(new Finding(event.serverTick(),Verdict.UNCERTAIN,next.size(),List.of("candidate budget exceeded; no branches were discarded"))); return new Report(findings,movements); }
      }
      if(unsupported) { findings.add(new Finding(event.serverTick(),Verdict.UNCERTAIN,0,List.of("client-visible world has unsupported or unknown space"))); candidates=Set.of(); continue; }
      candidates=next.stream().filter(candidate -> candidate.position().equals(observed.position()) && candidate.onGround()==observed.onGround()).collect(java.util.stream.Collectors.toUnmodifiableSet());
      if(candidates.isEmpty()) findings.add(new Finding(event.serverTick(),Verdict.IMPOSSIBLE,0,List.of("no simulated state matches the observed position and ground state", "diagnostic only: 1.21.11 mechanics are not independently trace-validated")));
      else findings.add(new Finding(event.serverTick(),Verdict.POSSIBLE,candidates.size(),List.of("observed position and ground state remain reachable", "velocity is not present in movement packets and was not invented")));
    }
    return new Report(findings,movements);
  }
  private static int axis(boolean positive,boolean negative) { return positive==negative?0:positive?1:-1; }
}
