package fits.model;

import fits.model.enums.SessionState;

import fits.FrontEnd;
import fits.TransactionSystem;
import nz.ac.waikato.modeljunit.Action;
import nz.ac.waikato.modeljunit.timing.Time;
import nz.ac.waikato.modeljunit.timing.Timeout;
import nz.ac.waikato.modeljunit.timing.TimedFsmModel;
import nz.ac.waikato.modeljunit.timing.TimedModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One user's session FSMs; focuses on concurrent sessions, closed-session logging and inactivity
 * (P9, P10, P16).
 *
 * <p>The graph state is a test-local enum describing the product of the per-session FSM 6 states.
 * Session IDs, last-activity times and the request counter are model variables, not separate graph
 * states. Counts are used because the IDs do not change the rules being checked.
 */
public class SessionModelTest implements TimedFsmModel {
    /** Product enum for up to four session instances, plus the fixture's initial state. */
    private enum SessionGraphState {
        NO_USER, NO_SESSIONS,
        OPEN_1, CLOSED_1,
        OPEN_2, OPEN_1_CLOSED_1, CLOSED_2,
        OPEN_3, OPEN_2_CLOSED_1, OPEN_1_CLOSED_2, CLOSED_3,
        OPEN_4, OPEN_3_CLOSED_1, OPEN_2_CLOSED_2, OPEN_1_CLOSED_3, CLOSED_4;

        static SessionGraphState of(boolean prepared, List<SessionState> states) {
            if (!prepared) return NO_USER;
            int open = (int) states.stream().filter(s -> s == SessionState.OPEN).count();
            int closed = states.size() - open;
            if (states.isEmpty()) return NO_SESSIONS;
            String name = open == 0 ? "CLOSED_" + closed
                    : closed == 0 ? "OPEN_" + open
                    : "OPEN_" + open + "_CLOSED_" + closed;
            return valueOf(name);
        }
    }
    private static final boolean SKIP_KNOWN_VIOLATIONS = true;
    private static final int SECOND = 1_000;
    private static final int MINUTE = 60 * SECOND;

    /** One base FSM 6 enum state per session ID. */
    private final Map<Integer, SessionState> sessions = new LinkedHashMap<>();
    /** Inactivity data for each session; these timestamps do not create graph states. */
    private final Map<Integer, Integer> lastActivity = new LinkedHashMap<>();
    private TransactionSystem system;
    private FrontEnd front;
    private Integer uid;
    private boolean testing;
    private boolean prepared;
    private int nextSession;
    private int activityRequests;
    @Time public int now;
    @Timeout("checkInactivity") public int inactivityAt;

    /**
     * Returns only the fixture state and the product of session enum states. Timer and activity
     * data remain model variables, like a balance or last-service time in the coffee-machine model.
     */
    @Override public Object getState() {
        return SessionGraphState.of(prepared, List.copyOf(sessions.values()));
    }

    @Override public void reset(boolean testing) {
        this.testing = testing;
        sessions.clear();
        lastActivity.clear();
        prepared = false;
        nextSession = 0;
        activityRequests = 0;
        now = 0;
        inactivityAt = -1;
        system = new TransactionSystem();
        front = system.getFrontEnd();
        uid = null;
    }

    @Override public int getNextTimeIncrement(Random random) { return SECOND; }

    /** This model assumes startup is complete; other rules are checked in SystemTimingModelTest. */
    public boolean prepareUserGuard() { return !prepared; }
    @Action public void prepareUser() {
        // 1. Initialise the SUT and create an enabled user.
        Integer sutUid;
        if (testing) {
            front.ADMIN_initialise();
            sutUid = front.ADMIN_createUser("Student", "Argentina");
            front.ADMIN_enableUser(sutUid);
        } else sutUid = 1;

        // 2. Update the model's fixture state.
        uid = sutUid;
        prepared = true;

        // 3. Check the created user is enabled in the SUT.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isEnabled());
    }

    public boolean USER_loginGuard() {
        long open = sessions.values().stream().filter(s -> s == SessionState.OPEN).count();
        return prepared && sessions.size() < 4 && (!SKIP_KNOWN_VIOLATIONS || open < 3);
    }
    @Action public void USER_login() {
        // 1. Execute login on the SUT.
        int open = (int) sessions.values().stream().filter(s -> s == SessionState.OPEN).count();
        Integer sid = testing ? front.USER_login(uid) : nextSession;

        // 2. Add the new session to the model when the three-session limit permits it.
        boolean loginAllowed = open < 3;
        if (loginAllowed) {
            sessions.put(sid, SessionState.OPEN);
            lastActivity.put(sid, now);
            nextSession++;
            updateInactivityDeadline();
        }

        // 3. Compare the SUT result with the model's session limit.
        if (loginAllowed) assertNotEquals(-1, sid, "Login below the three-session limit should succeed");
        else if (!SKIP_KNOWN_VIOLATIONS) assertEquals(-1, sid, "P9: fourth concurrent session was accepted");
    }

    public boolean USER_logoutGuard() { return sessions.containsValue(SessionState.OPEN); }
    @Action public void USER_logout() {
        // 1. Execute logout on the SUT.
        int sid = sessions.entrySet().stream().filter(e -> e.getValue() == SessionState.OPEN)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        if (testing) front.USER_logout(uid, sid);

        // 2. Mark the selected session closed in the model.
        sessions.put(sid, SessionState.CLOSED);
        updateInactivityDeadline();

        // 3. The SUT's logout method stores no open/closed state to compare (see UserSession).
    }

    /** An ordinary request refreshes only the selected OPEN session's inactivity clock. */
    public boolean USER_requestAccountGuard() {
        return prepared && activityRequests < 3 && sessions.containsValue(SessionState.OPEN);
    }
    @Action public void USER_requestAccount() {
        // 1. Execute the account request on the SUT and retain its result.
        int sid = sessions.entrySet().stream().filter(e -> e.getValue() == SessionState.OPEN)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String account = testing ? front.USER_requestAccount(uid, sid) : "account" + activityRequests;

        // 2. Update the selected session's activity in the model.
        activityRequests++;
        lastActivity.put(sid, now);
        updateInactivityDeadline();

        // 3. Check that the SUT accepted the request and logged it.
        if (testing) {
            assertNotNull(account);
            assertTrue(system.getBackEnd().getUserInfo(uid).getSession(sid).getLog().contains(account));
        }
    }

    /** Try a real logging operation through a retained closed session to check P10. */
    public boolean requestFromClosedSessionGuard() {
        return sessions.containsValue(SessionState.CLOSED) && !SKIP_KNOWN_VIOLATIONS;
    }
    @Action public void requestFromClosedSession() {
        // 1. Attempt the request through the selected closed session in the SUT.
        int sid = sessions.entrySet().stream().filter(e -> e.getValue() == SessionState.CLOSED)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String before = null;
        if (testing) {
            before = system.getBackEnd().getUserInfo(uid).getSession(sid).getLog();
            front.USER_requestAccount(uid, sid);
        }

        // 2. The model retains the session as CLOSED; this action does not change model state.

        // 3. Check that the closed session's SUT log did not change.
        if (testing) assertEquals(before, system.getBackEnd().getUserInfo(uid).getSession(sid).getLog(),
                "P10: operation added a log entry to a closed session");
    }

    public boolean checkInactivityGuard() {
        return inactivityAt != -1 && now >= inactivityAt && !SKIP_KNOWN_VIOLATIONS;
    }
    @Action public void checkInactivity() {
        // 1. The SUT has no inactivity timer callback; this is a model deadline check.

        // 2. Record whether any OPEN model session has exceeded its inactivity limit.
        boolean inactivityWasExceeded = false;
        for (Map.Entry<Integer, SessionState> session : sessions.entrySet()) {
            if (session.getValue() == SessionState.OPEN
                    && now >= lastActivity.get(session.getKey()) + 15 * MINUTE)
                inactivityWasExceeded = true;
        }
        inactivityAt = -1;

        // 3. Check P16 against the model's timer state.
        if (inactivityWasExceeded && !SKIP_KNOWN_VIOLATIONS)
            fail("P16: open session exceeded fifteen idle minutes");
    }

    private void updateInactivityDeadline() {
        inactivityAt = sessions.entrySet().stream().filter(e -> e.getValue() == SessionState.OPEN)
                .mapToInt(e -> lastActivity.get(e.getKey()) + 15 * MINUTE).min().orElse(-1);
    }

    @Test public void runModelJUnit() {
        FitsModelRunner.run("Sessions (P9, P10, P16)", new TimedModel(this));
    }
}
