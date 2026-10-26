package fits.model;

import nz.ac.waikato.modeljunit.GreedyTester;
import nz.ac.waikato.modeljunit.GraphListener;
import nz.ac.waikato.modeljunit.StopOnFailureListener;
import nz.ac.waikato.modeljunit.Transition;
import nz.ac.waikato.modeljunit.VerboseListener;
import nz.ac.waikato.modeljunit.coverage.ActionCoverage;
import nz.ac.waikato.modeljunit.coverage.StateCoverage;
import nz.ac.waikato.modeljunit.coverage.TransitionCoverage;
import nz.ac.waikato.modeljunit.coverage.TransitionPairCoverage;
import nz.ac.waikato.modeljunit.timing.TimedModel;

import java.util.Random;

/** Small shared runner for the five independent FITS property models. */
final class FitsModelRunner {
    /** This helper is only a collection of static runner methods, so it cannot be instantiated. */
    private FitsModelRunner() {}

    /** Builds the model graph, configures a tester, runs actions and prints coverage. */
    static void run(String name, TimedModel model) {
        // For a repeatable run, comment out the next line and uncomment this fixed-seed example.
        // long seed = 1L;
        // Otherwise, use a fresh seed for this run and print it so the run can be repeated later.
        long seed = new Random().nextLong();
        // Give the seed to TimedModel for virtual-time and timeout choices.
        model.setRandom(new Random(seed));
        // Set the chance that TimedModel chooses an enabled timeout action.
        model.setTimeoutProbability(0.1);
        // Wrap the timed model in a tester that chooses and executes its guarded actions.
        GreedyTester tester = new GreedyTester(model);
        // Explore the reachable abstract states and transitions before generating a test sequence.
        GraphListener graph = tester.buildGraph();
        // Report the size and completeness of the graph that was just built.
        model.printMessage(name + " seed: " + seed + "\n"
                + name + " graph: " + graph.getGraph().numVertices() + " states, "
                + graph.getGraph().numEdges() + " transitions; complete=" + graph.isComplete());
        // Give the same seed to GreedyTester for action choices, using an independent stream.
        tester.setRandom(new Random(seed));
        // Stop the current run immediately if an action's conformance assertion fails.
        tester.addListener(new StopOnFailureListener());
        // Print every completed transition in a readable, multi-line form.
        tester.addListener(new ReadableTransitionListener());
        // Measure how many distinct graph states are visited.
        tester.addCoverageMetric(new StateCoverage());
        // Measure how many distinct actions are exercised.
        tester.addCoverageMetric(new ActionCoverage());
        // Measure how many individual state-to-state transitions are exercised.
        tester.addCoverageMetric(new TransitionCoverage());
        // Measure how many consecutive pairs of transitions are exercised.
        tester.addCoverageMetric(new TransitionPairCoverage());
        try {
            // Generate 100 guarded actions, checking each SUT result against the model.
            tester.generate(100);
        } finally {
            // Print coverage even if generation stops because an assertion fails.
            tester.printCoverage();
        }
    }

    /** Prints each completed transition with its virtual time, source state, action and result. */
    private static final class ReadableTransitionListener extends VerboseListener {
        @Override public void doneTransition(int action, Transition transition) {
            // Timed transitions stringify as a time followed by the state/action/state tuple.
            String timed = transition.toString();
            // Keep only the timestamp before the first colon for the separate Time line.
            String time = timed.substring(0, timed.indexOf(':'));
            // Print the transition components separately so long states remain readable.
            model_.printMessage("Time: " + time + " ms\nState: " + transition.getStartState()
                    + "\nAction: " + transition.getAction() + "\nState: "
                    + transition.getEndState() + "\n");
        }
    }
}
