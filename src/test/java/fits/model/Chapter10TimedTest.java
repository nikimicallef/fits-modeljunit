package fits.model;

import nz.ac.waikato.modeljunit.TestFailureException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Default JUnit entry point for timed requirements. Replays the full catalog, verifies expected
 * violations at their named events, and generates safe paths with reproducible seeds.
 */
class Chapter10TimedTest {
    /**
     * Replays every timed catalog case. Safe cases must complete; negative cases must fail at the
     * named event with the expected property ID.
     */
    @TestFactory Stream<DynamicTest> boundariesAndIsolation() {
        return Scenario.all().stream().map(s -> DynamicTest.dynamicTest(
                s.property() + " / " + s.name() + (s.violates() ? " [known violation]" : " [safe]"), () -> {
                    FitsTimedModel fsm = new FitsTimedModel(s);
                    if (!s.violates()) {
                        TimedRuns.replay(fsm, s);
                    } else {
                        TestFailureException failure = assertThrows(TestFailureException.class,
                                () -> TimedRuns.replay(fsm, s));
                        assertEquals(s.failureEvent(), fsm.lastEvent, "Failure must occur at the intended event");
                        assertTrue(failure.getMessage().contains(s.property() + " violated"), failure.getMessage());
                        System.out.println("Known trace violation: " + s.property() + " / " + s.name());
                    }
                }));
    }

    /**
     * Creates one generated safe/boundary-path coverage test per timed property.
     */
    @TestFactory Stream<DynamicTest> generatedSafePaths() {
        return Stream.of(Property.values()).map(p -> DynamicTest.dynamicTest(
                p + " / seeded GreedyTester safe and boundary paths", () -> TimedRuns.generate(p, false)));
    }
}
