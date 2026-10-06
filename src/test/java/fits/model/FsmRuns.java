package fits.model;

import nz.ac.waikato.modeljunit.GreedyTester;
import nz.ac.waikato.modeljunit.GraphListener;
import nz.ac.waikato.modeljunit.Model;
import nz.ac.waikato.modeljunit.StopOnFailureListener;
import nz.ac.waikato.modeljunit.coverage.ActionCoverage;
import nz.ac.waikato.modeljunit.coverage.StateCoverage;
import nz.ac.waikato.modeljunit.coverage.TransitionCoverage;
import nz.ac.waikato.modeljunit.coverage.TransitionPairCoverage;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configures untimed ModelJUnit execution, minimal counterexamples, seeded generation and
 * graph/coverage reporting. Explicit P7 paths guarantee that deep counter boundaries are
 * discovered and exercised.
 */
final class FsmRuns {
    /**
     * Wraps a configured untimed FSM and installs fail-fast property failure handling.
     */
    static Model model(FitsFsmModel.Rule rule, boolean violations) {
        Model model = new Model(new FitsFsmModel(rule, violations));
        model.addListener(new StopOnFailureListener());
        return model;
    }

    /**
     * Executes a named action through ModelJUnit, asserting that its guard allowed execution.
     */
    static void action(Model model, String name) {
        assertTrue(model.doAction(model.getActionNumber(name)), "Action must be enabled: " + name);
    }

    /**
     * Drives a minimal violating action sequence for the selected rule, expecting a property
     * failure to propagate.
     */
    static void counterexample(FitsFsmModel.Rule rule) {
        Model model = model(rule, true);
        switch (rule) {
            case P2 -> action(model, "login");
            case P5 -> { action(model, "login"); action(model, "disable"); action(model, "pay"); }
            case P6 -> { action(model, "greylist"); action(model, "whitelist"); }
            case P7 -> { action(model, "login"); for (int i = 0; i < 11; i++) action(model, "requestAccount"); }
            case P9 -> { for (int i = 0; i < 4; i++) action(model, "login"); }
            case P10 -> { action(model, "login"); action(model, "logout"); action(model, "requestAccount"); }
        }
    }

    /**
     * Discovers a finite graph, generates reproducible paths and writes coverage. P7 uses
     * deterministic graph discovery plus threshold paths or a strict counterexample fallback.
     */
    static void generate(FitsFsmModel.Rule rule, boolean violations) throws Exception {
        Model model = model(rule, violations);
        GreedyTester tester = new GreedyTester(model);
        tester.setResetProbability(0.1);
        Path directory = Path.of("target", "modeljunit", rule.name());
        Files.createDirectories(directory);
        GraphListener graph;
        if (rule == FitsFsmModel.Rule.P7) {
            // Exhaust the small counter graph explicitly, without invoking the SUT.
            // Random graph discovery can otherwise miss the deepest request state.
            graph = (GraphListener) model.addListener("graph");
            boolean wasTesting = model.setTesting(false);
            int limit = violations ? 11 : 10;
            for (int count = 0; count <= limit; count++) {
                model.doReset("P7 abstract graph discovery");
                action(model, "login");
                for (int i = 0; i < count; i++) action(model, "requestAccount");
                action(model, "logout");
                action(model, "login");
            }
            assertEquals(0, graph.numTodo(), "Every P7 graph branch must be discovered");
            model.setTesting(wasTesting);
            model.doReset("P7 start testing");
            graph.clearDoneTodo();
        } else {
            graph = tester.buildGraph(10000);
        }
        assertTrue(graph.isComplete(), "FSM graph should be complete");
        graph.printGraphDot(directory.resolve(violations ? "conformance.dot" : "safe.dot").toString());
        long seed = Long.getLong("fits.seed", 20261006L);
        int steps = Integer.getInteger("fits.steps", 3000);
        tester.setRandom(new Random(seed));
        tester.addCoverageMetric(new ActionCoverage());
        var states = tester.addCoverageMetric(new StateCoverage());
        var transitions = tester.addCoverageMetric(new TransitionCoverage());
        tester.addCoverageMetric(new TransitionPairCoverage());
        try (PrintWriter output = new PrintWriter(Files.newBufferedWriter(
                directory.resolve(violations ? "conformance.txt" : "safe-coverage.txt")))) {
            model.setOutput(output);
            output.printf("%s seed=%d steps=%d allowViolations=%s%n", rule, seed, steps, violations);
            try {
                tester.generate(steps);
                if (violations && rule == FitsFsmModel.Rule.P7) {
                    model.doReset("P7 deterministic counterexample");
                    action(model, "login");
                    for (int i = 0; i < 11; i++) action(model, "requestAccount");
                }
                if (!violations && rule == FitsFsmModel.Rule.P7) {
                    // Deep counter boundaries must not depend on random luck.
                    for (int count = 0; count <= 10; count++) {
                        model.doReset("P7 deterministic boundary coverage");
                        action(model, "login");
                        for (int i = 0; i < count; i++) action(model, "requestAccount");
                        action(model, "logout");
                        action(model, "login"); // Also verify the new session resets the count.
                    }
                }
            } finally { tester.printCoverage(); }
        }
        assertEquals(states.getMaximum(), states.getCoverage(), "All reachable FSM states must be visited");
        assertEquals(transitions.getMaximum(), transitions.getCoverage(), "All reachable FSM transitions must be visited");
    }
}
