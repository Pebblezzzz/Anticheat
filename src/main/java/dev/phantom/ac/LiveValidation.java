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
  /** Bounded, replayable pipeline observability; it contains counts and reasons, not packet dumps. */
  public record Report(List<Finding> findings,int movementObservations,int timelineEvents,int anchoredObservations,int possibleFindings,int uncertainFindings,int impossibleFindings) {
    public Report { findings=List.copyOf(findings); if(movementObservations<0||timelineEvents<0||anchoredObservations<0||possibleFindings<0||uncertainFindings<0||impossibleFindings<0) throw new IllegalArgumentException("negative validation metric"); }
    public Report(List<Finding> findings,int movementObservations) { this(findings,movementObservations,0,0,0,0,0); }
  }

  public static Report analyze(Timeline.Snapshot timeline,int maximumCandidates) {
    if(maximumCandidates<1) throw new IllegalArgumentException("maximumCandidates must be positive");
    World.VisibilityHistory worldHistory=World.fromTimeline(timeline);
    Vanilla12111Physics physics=new Vanilla12111Physics();
    Set<Player> candidates=Set.of();
    Input currentInput=null;
    boolean anchored=false;
    List<Finding> findings=new ArrayList<>(); int movements=0;
    for(Timeline.Event event:timeline.events()) {
      Packet packet=event.packet().packet();
      if(packet instanceof ClientInput input) { currentInput=new Input(axis(input.forward(),input.backward()),axis(input.right(),input.left()),input.jump()); continue; }
      if(!(packet instanceof Move move) || move.position()==null) continue;
      movements++;
      if (anchored && candidates.isEmpty()) {
        // A previous world/timing gap invalidated the envelope. Re-establishing
        // an anchor is uncertainty, never a violation, and prevents iterator
        // failure on the next packet.
        anchored=false;
        findings.add(new Finding(event.serverTick(),Verdict.UNCERTAIN,0,List.of("prediction envelope was lost during an unsupported interval; observation re-anchors validation")));
      }
      Player observed=State.apply(anchored?candidates.iterator().next():Player.initial(move.position()),event.packet());
      if(!anchored) {
        // The first movement packet establishes an observation anchor. It cannot
        // prove or disprove a prior client state that was not captured.
        candidates=Set.of(Player.initial(move.position()));
        anchored=true;
        findings.add(new Finding(event.serverTick(),Verdict.UNCERTAIN,1,List.of("first observed position anchors the replay; no preceding client state is available")));
        continue;
      }
      World.Snapshot world=worldHistory.at(event.serverTick()); Set<Player> next=new HashSet<>(); boolean unsupported=false; boolean uncertainTransition=false;
      for(Player candidate:candidates) {
        if(candidate.uncertain()||world.hasUnsupportedAt(candidate.position())) { unsupported=true; continue; }
        Set<Player> transitions=new HashSet<>();
        if(currentInput!=null) transitions.add(physics.tick(candidate,currentInput,world));
        else for(int forward=-1;forward<=1;forward++) for(int strafe=-1;strafe<=1;strafe++) for(boolean jump:List.of(false,true)) transitions.add(physics.tick(candidate,new Input(forward,strafe,jump),world));
        for(Player transition:transitions) { if(transition.uncertain()) uncertainTransition=true; else next.add(transition); }
        if(next.size()>maximumCandidates) { findings.add(new Finding(event.serverTick(),Verdict.UNCERTAIN,next.size(),List.of("candidate budget exceeded; result is incomplete"))); candidates=Set.of(); anchored=false; continue; }
      }
      if(unsupported && next.isEmpty()) { findings.add(new Finding(event.serverTick(),Verdict.UNCERTAIN,0,List.of("client-visible world has unsupported or unknown space"))); candidates=Set.of(); anchored=false; continue; }
      if(next.isEmpty()) { findings.add(new Finding(event.serverTick(),Verdict.UNCERTAIN,0,List.of(uncertainTransition?"all simulated transitions are uncertain; no violation inferred":"no candidate transition was produced; no violation inferred"))); candidates=Set.of(); anchored=false; continue; }
      Set<Player> matches=next.stream().filter(candidate -> candidate.position().equals(observed.position()) && candidate.onGround()==observed.onGround()).collect(java.util.stream.Collectors.toUnmodifiableSet());
      if(matches.isEmpty()) {
        // Keep the simulated envelope after an impossible observation. Resetting
        // to the next observed position would turn every later packet into a new
        // anchor and make sustained flight produce only one isolated finding.
        candidates=Set.copyOf(next);
        findings.add(new Finding(event.serverTick(),Verdict.IMPOSSIBLE,0,List.of("no simulated state matches the observed position and ground state", "prediction envelope retained after divergence", "1.21.11 mechanics are not independently trace-validated")));
      } else {
        candidates=matches;
        findings.add(new Finding(event.serverTick(),Verdict.POSSIBLE,candidates.size(),List.of("observed position and ground state remain reachable", "velocity is not present in movement packets and was not invented")));
      }
    }
    int possible=(int)findings.stream().filter(f->f.verdict()==Verdict.POSSIBLE).count();
    int uncertain=(int)findings.stream().filter(f->f.verdict()==Verdict.UNCERTAIN).count();
    int impossible=(int)findings.stream().filter(f->f.verdict()==Verdict.IMPOSSIBLE).count();
    int anchoredObservations=Math.max(0,movements-uncertain);
    return new Report(findings,movements,timeline.events().size(),anchoredObservations,possible,uncertain,impossible);
  }
  private static int axis(boolean positive,boolean negative) { return positive==negative?0:positive?1:-1; }
}
