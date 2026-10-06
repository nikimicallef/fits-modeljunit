package fits.model;

import nz.ac.waikato.modeljunit.GreedyTester;
import nz.ac.waikato.modeljunit.StopOnFailureListener;
import nz.ac.waikato.modeljunit.coverage.ActionCoverage;
import nz.ac.waikato.modeljunit.coverage.StateCoverage;
import nz.ac.waikato.modeljunit.coverage.TransitionCoverage;
import nz.ac.waikato.modeljunit.coverage.TransitionPairCoverage;
import nz.ac.waikato.modeljunit.timing.TimedModel;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configures timed ModelJUnit execution, deterministic scenario replay, seeded path generation,
 * graph export and coverage checks. StopOnFailureListener prevents assertion failures from being
 * silently continued.
 */
final class TimedRuns {
    /**
     * Wraps the FSM in TimedModel, sets timeout probability to one for direct fast-forwarding, and
     * installs fail-fast handling.
     */
    static TimedModel timed(FitsTimedModel fsm) {
        TimedModel timed = new TimedModel(fsm);
        timed.setTimeoutProbability(1.0);
        timed.addListener(new StopOnFailureListener());
        return timed;
    }

    /**
     * Enters a single scenario through ModelJUnit's action API and executes scheduled groups until
     * completion. Checks progress to catch disabled actions or loops.
     */
    static void replay(FitsTimedModel fsm, Scenario scenario) {
        TimedModel timed = timed(fsm);
        assertTrue(timed.doAction(timed.getActionNumber(fsm.entryAction(scenario))));
        int steps = 0;
        while (!fsm.finished()) {
            assertTrue(++steps <= scenario.events().size(), "Timed scenario made no progress");
            assertTrue(timed.doAction(timed.getActionNumber("executeEvent")), "Scheduled action must be enabled");
        }
    }

    /**
     * Discovers the abstract graph without calling FITS, reseeds both RNGs, generates the
     * configured number of steps and exports coverage. Safe runs must visit all reachable states
     * and transitions.
     */
    static void generate(Property property, boolean violations) throws IOException {
        FitsTimedModel fsm = new FitsTimedModel(property, violations);
        TimedModel timed = timed(fsm);
        GreedyTester tester = new GreedyTester(timed);
        long seed = Long.getLong("fits.seed", 20261006L);
        int steps = Integer.getInteger("fits.steps", 3000);
        assertTrue(steps >= 100, "Use at least 100 generated steps");
        tester.setResetProbability(0.1);
        Path directory = Path.of("target", "modeljunit", property.name());
        Files.createDirectories(directory);
        // reset(false) updates only model state: graph discovery never calls the SUT.
        var graph = tester.buildGraph(5000);
        assertTrue(graph.isComplete(), "Abstract model graph must be complete");
        graph.printGraphDot(directory.resolve(violations ? "conformance.dot" : "safe.dot").toString());
        // Restore both sources of randomness AFTER graph discovery.
        tester.setRandom(new Random(seed));
        timed.setRandom(new Random(seed));
        var actions = tester.addCoverageMetric(new ActionCoverage());
        var states = tester.addCoverageMetric(new StateCoverage());
        var transitions = tester.addCoverageMetric(new TransitionCoverage());
        var pairs = tester.addCoverageMetric(new TransitionPairCoverage());
        try (PrintWriter output = new PrintWriter(Files.newBufferedWriter(
                directory.resolve(violations ? "conformance.txt" : "safe-coverage.txt")))) {
            timed.setOutput(output);
            output.printf("%s seed=%d steps=%d allowViolations=%s%n", property, seed, steps, violations);
            try {
                tester.generate(steps);
            } finally {
                tester.printCoverage();
            }
        }
        // ActionCoverage includes actions with permanently false guards. Graph state
        // and transition coverage correctly describe reachable paths in safe mode.
        assertEquals(states.getMaximum(), states.getCoverage(), "All reachable states must be visited");
        assertEquals(transitions.getMaximum(), transitions.getCoverage(), "All reachable transitions must be visited");
        assertTrue(actions.getCoverage() >= 4);
        assertTrue(pairs.getCoverage() > 0);
    }
}
