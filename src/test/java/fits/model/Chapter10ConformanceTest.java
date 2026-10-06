package fits.model;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.stream.Stream;

/**
 * Strict timed JUnit entry point enabled by the conformance profile. Property failures propagate
 * to JUnit. Missing administrator events violate a trace without implying that FITS must act as
 * its own administrator.
 */
@EnabledIfSystemProperty(named = "fits.conformance", matches = "true")
class Chapter10ConformanceTest {
    /**
     * Creates strict generated tests for P11 through P16 with violating paths enabled and
     * assertion failures uncaught.
     */
    @TestFactory Stream<DynamicTest> chapter10Requirements() {
        return Stream.of(Property.values()).map(p -> DynamicTest.dynamicTest(
                p + " / strict generated conformance", () -> TimedRuns.generate(p, true)));
    }
}
