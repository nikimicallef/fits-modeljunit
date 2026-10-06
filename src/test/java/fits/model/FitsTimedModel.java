package fits.model;

import nz.ac.waikato.modeljunit.Action;
import nz.ac.waikato.modeljunit.timing.Time;
import nz.ac.waikato.modeljunit.timing.TimedFsmModel;
import nz.ac.waikato.modeljunit.timing.Timeout;

import java.util.List;
import java.util.Random;

/**
 * Timed ModelJUnit model that chooses and traverses finite event plans. One tick represents one
 * millisecond. The selected scenario and event index form the abstract state; FitsFixture performs
 * system calls and temporal assertions.
 */
public final class FitsTimedModel implements TimedFsmModel {
    /** Virtual clock in milliseconds, advanced by ModelJUnit rather than wall-clock waiting. */
    @Time public int now;
    /** Absolute time of the next event group; -1 means that no timeout is scheduled. */
    @Timeout("executeEvent") public int nextEvent = TIMEOUT_DISABLED;

    private final Property property;
    private final List<Scenario> choices;
    private final boolean allowViolations;
    /** False during graph discovery, when callbacks must not touch the real system. */
    private boolean testing;
    private FitsFixture fixture;
    private Scenario selected;
    private int index;
    /** Positive absolute start time added to scenario-relative offsets. */
    private int epoch;
    /** Label of the most recently attempted event, used to verify negative-test failure location. */
    String lastEvent;

    /** Loads the selected property's full catalog and controls whether a violating path is exposed. */
    FitsTimedModel(Property property, boolean allowViolations) {
        this(property, Scenario.all().stream().filter(s -> s.property() == property).toList(), allowViolations);
    }

    /** Restricts the model to one scenario for deterministic replay, including a negative case. */
    FitsTimedModel(Scenario scenario) {
        this(scenario.property(), List.of(scenario), true);
    }

    /** Stores the selected property, available event plans and violation-path configuration. */
    private FitsTimedModel(Property property, List<Scenario> choices, boolean allowViolations) {
        this.property = property;
        this.choices = choices;
        this.allowViolations = allowViolations;
    }

    /**
     * Returns START or the selected scenario name and event index. Clock changes alone must not
     * change this abstract state.
     */
    @Override public Object getState() {
        return selected == null ? "START" : selected.name() + ":" + index;
    }

    /**
     * Returns a one-millisecond fallback increment. Scheduled timeouts let TimedModel jump
     * directly to event times.
     */
    @Override public int getNextTimeIncrement(Random random) {
        return 1; // One millisecond; @Timeout jumps directly to scheduled events.
    }

    /**
     * Clears scenario progress and disables the timeout. Creates a fresh fixture only for actual
     * testing, not graph discovery.
     */
    @Override public void reset(boolean testing) {
        this.testing = testing;
        fixture = testing ? new FitsFixture(property) : null;
        selected = null;
        index = 0;
        epoch = 0;
        now = 0;
        nextEvent = TIMEOUT_DISABLED;
        lastEvent = null;
    }

    /**
     * Returns catalog scenarios without an expected violation, preserving catalog order.
     */
    private List<Scenario> safeChoices() {
        return choices.stream().filter(s -> !s.violates()).toList();
    }

    /**
     * Enables the first safe path when no scenario has been selected.
     */
    public boolean chooseSafeGuard() { return selected == null && !safeChoices().isEmpty(); }
    /**
     * Selects the first safe catalog scenario for this property.
     */
    @Action public void chooseSafe() { select(safeChoices().getFirst()); }

    /**
     * Enables the second safe path when at least two safe catalog scenarios exist.
     */
    public boolean chooseBoundaryGuard() { return selected == null && safeChoices().size() > 1; }
    /**
     * Selects the second safe scenario; the method name is a path label, while its exact boundary
     * depends on catalog order.
     */
    @Action public void chooseBoundary() { select(safeChoices().get(1)); }

    /**
     * Enables a violating path only at START and only when violation mode is configured.
     */
    public boolean chooseViolationGuard() {
        return selected == null && allowViolations && choices.stream().anyMatch(Scenario::violates);
    }
    /**
     * Selects the first scenario marked with an expected violation.
     */
    @Action public void chooseViolation() {
        select(choices.stream().filter(Scenario::violates).findFirst().orElseThrow());
    }

    /**
     * Records the scenario and schedules its first event at a positive epoch, avoiding ModelJUnit
     * 2.5's ignored zero timeout.
     */
    private void select(Scenario scenario) {
        selected = scenario;
        // ModelJUnit 2.5 ignores timeout values of zero; begin at a positive epoch.
        epoch = now + 1;
        nextEvent = epoch + selected.events().getFirst().offset();
    }

    /**
     * Enables execution only when a pending event's scheduled timestamp equals the virtual clock.
     */
    public boolean executeEventGuard() {
        return selected != null && index < selected.events().size() && now == nextEvent;
    }

    /**
     * Executes the next event group and advances the model index. Equal-time callbacks run in
     * insertion order; fixture interaction is skipped during graph discovery.
     */
    @Action public void executeEvent() {
        int offset = selected.events().get(index).offset();
        // Execute equal-time events in declared order before moving time. This avoids
        // ModelJUnit disabling a timeout rearmed at the same timestamp.
        do {
            Scenario.Event event = selected.events().get(index);
            lastEvent = event.label();
            if (testing) {
                fixture.at(now);
                event.operation().accept(fixture);
            }
            index++;
        } while (index < selected.events().size() && selected.events().get(index).offset() == offset);
        nextEvent = index == selected.events().size()
                ? TIMEOUT_DISABLED : epoch + selected.events().get(index).offset();
    }

    /**
     * Unguarded fallback action with no state change, permitting clock progress when no ordinary
     * action is enabled.
     */
    @Action public void elapse() {}

    /**
     * Returns whether the selected scenario has consumed every event.
     */
    boolean finished() { return selected != null && index == selected.events().size(); }
    /**
     * Returns the guarded action name used to enter a single safe or violating replay scenario.
     */
    String entryAction(Scenario scenario) { return scenario.violates() ? "chooseViolation" : "chooseSafe"; }
}
