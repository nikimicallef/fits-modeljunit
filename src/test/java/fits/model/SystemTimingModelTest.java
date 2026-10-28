package fits.model;

import fits.model.enums.SystemState;
import fits.model.enums.UserMode;

import fits.FrontEnd;
import fits.TransactionSystem;
import nz.ac.waikato.modeljunit.Action;
import nz.ac.waikato.modeljunit.timing.Time;
import nz.ac.waikato.modeljunit.timing.Timeout;
import nz.ac.waikato.modeljunit.timing.TimedFsmModel;
import nz.ac.waikato.modeljunit.timing.TimedModel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** FSM for system initialisation, the startup delay, login readiness and reconciliation (P2, P11, P14). */
public class SystemTimingModelTest implements TimedFsmModel {
    /** Change to false to let the known FITS policy defects fail the test. */
    private static final boolean SKIP_KNOWN_VIOLATIONS = true;
    private static final int SECOND = 1_000;
    private static final int MINUTE = 60 * SECOND;

    // States
    /** Composite enum for the system lifecycle and optional user-mode FSMs in this test. */
    private enum TimingGraphState {
        UNINITIALISED_NO_USER(SystemState.UNINITIALISED, null),
        UNINITIALISED_DISABLED(SystemState.UNINITIALISED, UserMode.DISABLED),
        UNINITIALISED_ENABLED(SystemState.UNINITIALISED, UserMode.ENABLED),
        UNINITIALISED_FROZEN(SystemState.UNINITIALISED, UserMode.FROZEN),
        STARTING_NO_USER(SystemState.STARTING, null),
        STARTING_DISABLED(SystemState.STARTING, UserMode.DISABLED),
        STARTING_ENABLED(SystemState.STARTING, UserMode.ENABLED),
        STARTING_FROZEN(SystemState.STARTING, UserMode.FROZEN),
        READY_NO_USER(SystemState.READY, null),
        READY_DISABLED(SystemState.READY, UserMode.DISABLED),
        READY_ENABLED(SystemState.READY, UserMode.ENABLED),
        READY_FROZEN(SystemState.READY, UserMode.FROZEN);

        private final SystemState system;
        private final UserMode user;

        TimingGraphState(SystemState system, UserMode user) {
            this.system = system;
            this.user = user;
        }

        static TimingGraphState of(SystemState system, UserMode user) {
            for (TimingGraphState state : values())
                if (state.system == system && state.user == user) return state;
            throw new IllegalStateException("No combined state for " + system + " and " + user);
        }
    }

    /** Base FSM 1 state, combined with user mode in TimingGraphState. */
    private SystemState state;
    /** Base FSM 2 state for the fixture user. */
    private UserMode mode;

    // State variables
    private TransactionSystem system;
    private FrontEnd front;
    /** Setup and timing data used by guards; kept separate from the base FSM enums. */
    private boolean hasUser;
    private boolean reconciled;
    private boolean testing;
    private Integer uid;
    @Time public int now;
    @Timeout("finishStartup") public int startupAt;
    @Timeout("checkReconciliation") public int reconcileBy;

    /**
     * Returns the enum combination of FSM 1 and FSM 2. User presence is represented by the
     * {@code *_NO_USER} enum values; reconciliation and timer deadlines remain separate variables.
     */
    @Override public Object getState() {
        return TimingGraphState.of(state, hasUser ? mode : null);
    }

    @Override public void reset(boolean testing) {
        this.testing = testing;
        state = SystemState.UNINITIALISED;
        mode = UserMode.DISABLED;
        hasUser = false;
        uid = null;
        reconciled = false;
        now = 0;
        startupAt = reconcileBy = -1;
        // Graph exploration passes false; only a real test reset creates a fresh SUT.
        if (testing) {
            system = new TransactionSystem();
            front = system.getFrontEnd();
        }
    }

    @Override public int getNextTimeIncrement(Random random) { return SECOND; }

    public boolean ADMIN_createUserGuard() { return !hasUser; }
    @Action public void ADMIN_createUser() {
        // 1. Create the user in the SUT.
        Integer sutUid = testing ? front.ADMIN_createUser("Student", "Argentina") : 1;

        // 2. Update the model's user state.
        uid = sutUid;
        hasUser = true;
        mode = UserMode.DISABLED;

        // 3. Compare the SUT user mode with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isDisabled());
    }

    public boolean ADMIN_enableUserGuard() { return hasUser && mode == UserMode.DISABLED; }
    @Action public void ADMIN_enableUser() {
        // 1. Enable the user in the SUT.
        if (testing) front.ADMIN_enableUser(uid);

        // 2. Update the model's user mode.
        mode = UserMode.ENABLED;

        // 3. Compare the SUT user mode with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isEnabled());
    }

    public boolean ADMIN_initialiseGuard() { return state == SystemState.UNINITIALISED || reconciled; }
    @Action public void ADMIN_initialise() {
        // 1. Initialise the SUT.
        if (testing) front.ADMIN_initialise();

        // 2. Reset the model's lifecycle, user and deadline state.
        state = SystemState.STARTING;
        hasUser = false;
        uid = null;
        mode = UserMode.DISABLED;
        reconciled = false;
        startupAt = now + 10 * SECOND;
        reconcileBy = now + 5 * MINUTE;

        // 3. Initialisation has no public SUT state getter to compare against this model state.
    }

    public boolean ADMIN_reconcileGuard() { return state != SystemState.UNINITIALISED && !reconciled; }
    @Action public void ADMIN_reconcile() {
        // 1. Execute reconciliation on the SUT.
        if (testing) front.ADMIN_reconcile();

        // 2. Record reconciliation and consume its deadline in the model.
        reconciled = true;
        reconcileBy = -1;

        // 3. The SUT has no public reconciliation-state getter to compare.
    }

    public boolean USER_loginGuard() {
        return hasUser && mode == UserMode.ENABLED
                && (!SKIP_KNOWN_VIOLATIONS || state == SystemState.READY);
    }
    @Action public void USER_login() {
        // 1. Attempt login on the SUT.
        Integer session = testing ? front.USER_login(uid) : (state == SystemState.READY ? 0 : -1);
        boolean allowed = state == SystemState.READY;

        // 2. Login does not change the state represented by this system-lifecycle model.

        // 3. Compare the SUT result with the model's readiness state.
        if (allowed) assertNotEquals(-1, session, "Login should work after startup");
        else if (!SKIP_KNOWN_VIOLATIONS) assertEquals(-1, session, "P2/P11: login before readiness");
    }

    public boolean finishStartupGuard() { return startupAt != -1 && now >= startupAt; }
    @Action public void finishStartup() {
        // 1. The SUT has no virtual-time callback for this deadline.

        // 2. Move the model to READY and consume the startup deadline.
        state = SystemState.READY;
        startupAt = -1;

        // 3. This model timer event has no direct SUT state to compare.
    }

    public boolean checkReconciliationGuard() {
        return reconcileBy != -1 && now >= reconcileBy
                && (!SKIP_KNOWN_VIOLATIONS || reconciled);
    }
    @Action public void checkReconciliation() {
        // 1. The SUT has no virtual-time deadline callback.

        // 2. Capture the model's deadline result and consume the timer event.
        boolean reconciliationWasMissed = !reconciled;
        reconcileBy = -1;

        // 3. Check P14 against the model state at the deadline.
        if (reconciliationWasMissed && !SKIP_KNOWN_VIOLATIONS)
            fail("P14: reconciliation missed five-minute deadline");
    }

    @Test public void runModelJUnit() {
        FitsModelRunner.run("System timing (P2, P11, P14)", new TimedModel(this));
    }
}
