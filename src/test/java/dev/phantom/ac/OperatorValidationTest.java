package dev.phantom.ac;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.phantom.ac.Maths.Vec3;
import dev.phantom.ac.State.Player;
import dev.phantom.ac.Validation.Evidence;
import dev.phantom.ac.Validation.Reachability;
import dev.phantom.ac.Validation.SyncWindow;
import dev.phantom.ac.Validation.Verdict;

class OperatorValidationTest {

    private static OperatorValidation.Observation observation(Verdict verdict, boolean uncertain, long tick) {
        Player p = Player.initial(Vec3.ZERO);
        Reachability r = new Reachability(verdict, Set.of(), List.of("no simulated state matches"));
        Evidence e = new Evidence(verdict, "MOVEMENT_REACHABILITY", 10, List.of("no simulated state matches"));
        return new OperatorValidation.Observation("Alex", tick, p,
                new SyncWindow(tick, tick, uncertain, List.of()), "Minecraft Java 1.21.11", List.of(), r, e);
    }

    @Test
    void uncertainNeverAlerts() {
        var result = OperatorValidation.aggregate(OperatorValidation.Aggregator.empty(), observation(Verdict.UNCERTAIN, false, 1));
        assertTrue(result.alert().isEmpty());
    }

    @Test
    void oneImpossibleObservationDoesNotAlertAndTwoDo() {
        var state = OperatorValidation.Aggregator.empty();
        var first = OperatorValidation.aggregate(state, observation(Verdict.IMPOSSIBLE, false, 1));
        assertTrue(first.alert().isEmpty());
        var second = OperatorValidation.aggregate(first.state(), observation(Verdict.IMPOSSIBLE, false, 2));
        assertTrue(second.alert().isPresent());
        assertTrue(second.alert().orElseThrow().message().contains("Alex"));
    }

    @Test
    void timingUncertaintyBlocksAlertButEvidenceCanRecover() {
        var first = OperatorValidation.aggregate(OperatorValidation.Aggregator.empty(), observation(Verdict.IMPOSSIBLE, true, 1));
        var second = OperatorValidation.aggregate(first.state(), observation(Verdict.IMPOSSIBLE, false, 2));
        assertTrue(second.alert().isPresent());
    }

    @Test
    void debounceSuppressesRepeatedAlerts() {
        var state = OperatorValidation.Aggregator.empty();
        state = state.accept(observation(Verdict.IMPOSSIBLE, false, 1), 20, 1).state();
        var repeated = state.accept(observation(Verdict.IMPOSSIBLE, false, 2), 20, 1);
        assertTrue(repeated.alert().isEmpty());
    }
}
