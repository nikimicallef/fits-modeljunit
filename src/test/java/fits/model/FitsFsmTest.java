package fits.model;

import nz.ac.waikato.modeljunit.TestFailureException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Default JUnit entry point for untimed requirements. Confirms named defects in the original FITS
 * implementation and checks safe generated paths; a passing run characterizes the system rather
 * than proving conformance.
 */
class FitsFsmTest {
    /**
     * Creates a dynamic JUnit test for each minimal untimed counterexample and requires the
     * expected property failure.
     */
    @TestFactory Stream<DynamicTest> knownCounterexamples() {
        return Stream.of(FitsFsmModel.Rule.values()).map(rule -> DynamicTest.dynamicTest(
                rule + " / known untimed violation", () -> {
                    var failure = assertThrows(TestFailureException.class, () -> FsmRuns.counterexample(rule));
                    assertTrue(failure.getMessage().contains(rule + " violated"), failure.getMessage());
                }));
    }
    /**
     * Checks whitelisting after zero, one, two and three incoming transfers: the first three
     * violate P6, while three transfers satisfy it.
     */
    @TestFactory Stream<DynamicTest> greylistingBoundaries() {
        return java.util.stream.IntStream.rangeClosed(0, 3).mapToObj(count -> DynamicTest.dynamicTest(
                "P6 / whitelist after " + count + " incoming transfers", () -> {
                    var model = FsmRuns.model(FitsFsmModel.Rule.P6, true);
                    FsmRuns.action(model, "login");
                    FsmRuns.action(model, "greylist");
                    for (int i = 0; i < count; i++) FsmRuns.action(model, "incomingTransfer");
                    if (count == 3) {
                        FsmRuns.action(model, "whitelist");
                    } else {
                        var failure = assertThrows(TestFailureException.class,
                                () -> FsmRuns.action(model, "whitelist"));
                        assertTrue(failure.getMessage().contains("P6 violated"));
                    }
                }));
    }

    /**
     * Creates one generated safe-path coverage test per untimed rule.
     */
    @TestFactory Stream<DynamicTest> generatedLegalPaths() {
        return Stream.of(FitsFsmModel.Rule.values()).map(rule -> DynamicTest.dynamicTest(
                rule + " / generated legal FSM paths", () -> FsmRuns.generate(rule, false)));
    }
}
