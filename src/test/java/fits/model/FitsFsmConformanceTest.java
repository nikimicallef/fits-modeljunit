package fits.model;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.stream.Stream;

/**
 * Strict untimed JUnit entry point enabled by the conformance profile. Uses the same models with
 * violating inputs enabled and leaves property failures uncaught.
 */
@EnabledIfSystemProperty(named = "fits.conformance", matches = "true")
class FitsFsmConformanceTest {
    /**
     * Creates strict generated tests for all six untimed rules; deliberately forbidden inputs
     * expose failures instead of being caught as expected.
     */
    @TestFactory Stream<DynamicTest> untimedRequirements() {
        return Stream.of(FitsFsmModel.Rule.values()).map(rule -> DynamicTest.dynamicTest(
                rule + " / strict generated FSM conformance", () -> FsmRuns.generate(rule, true)));
    }
}
