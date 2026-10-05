package dev.phantom.ac;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class MovementAdvantageTrackerTest {

  @Test
  void reachableEnvelopeProducesSignedAccumulatingAdvantage() {
    MovementAdvantageTracker tracker = new MovementAdvantageTracker();
    Maths.Vec3 origin = new Maths.Vec3(0.0, 64.0, 0.0);
    List<Maths.Vec3> reachable = List.of(
        new Maths.Vec3(0.10, 64.0, 0.0),
        new Maths.Vec3(0.11, 64.0, 0.0));

    var first = tracker.observe(
        reachable, origin, new Maths.Vec3(0.13, 64.0, 0.0), true);
    var second = tracker.observe(
        reachable, origin, new Maths.Vec3(0.13, 64.0, 0.0), true);

    assertEquals(0.02, first.signedHorizontal(), 1.0E-12);
    assertTrue(second.accumulatedHorizontal() > first.accumulatedHorizontal());
    assertEquals(
        first.accumulatedHorizontal() * MovementAdvantageTracker.DECAY
            + second.signedHorizontal(),
        second.accumulatedHorizontal(),
        1.0E-12);
  }

  @Test
  void observationInsideOutwardEnvelopeProducesNoHorizontalExcess() {
    MovementAdvantageTracker tracker = new MovementAdvantageTracker();
    Maths.Vec3 origin = new Maths.Vec3(0.0, 64.0, 0.0);
    List<Maths.Vec3> reachable = List.of(
        new Maths.Vec3(0.10, 64.0, 0.0),
        new Maths.Vec3(0.11, 64.0, 0.0));

    var inside = tracker.observe(
        reachable, origin, new Maths.Vec3(0.05, 64.0, 0.0), true);

    assertEquals(0.0, inside.signedHorizontal(), 1.0E-12);
    assertEquals(0.0, inside.accumulatedHorizontal(), 1.0E-12);
  }

  @Test
  void lateralCandidateCannotCancelOutwardExcess() {
    MovementAdvantageTracker tracker = new MovementAdvantageTracker();
    Maths.Vec3 origin = new Maths.Vec3(0.0, 64.0, 0.0);
    List<Maths.Vec3> reachable = List.of(
        new Maths.Vec3(0.10, 64.0, 0.0),
        new Maths.Vec3(0.20, 64.0, 0.15));

    var observation = tracker.observe(
        reachable, origin, new Maths.Vec3(0.30, 64.0, 0.0), true);

    assertEquals(0.10, observation.signedHorizontal(), 1.0E-12);
  }

  @Test
  void accumulatedAdvantageSurvivesNonEvidenceStateWithoutReset() {
    MovementAdvantageTracker tracker = new MovementAdvantageTracker();
    Maths.Vec3 origin = new Maths.Vec3(0.0, 64.0, 0.0);
    List<Maths.Vec3> reachable = List.of(
        new Maths.Vec3(0.10, 64.0, 0.0),
        new Maths.Vec3(0.11, 64.0, 0.0));

    var beforeRebase = tracker.observe(
        reachable, origin, new Maths.Vec3(0.13, 64.0, 0.0), true);
    var afterRebase = tracker.decayOnly();

    assertTrue(afterRebase.accumulatedHorizontal() > 0.0);
    assertTrue(
        afterRebase.accumulatedHorizontal() < beforeRebase.accumulatedHorizontal(),
        () -> "normal non-evidence recovery may decay advantage, but must not erase it: "
            + afterRebase);
  }
}
