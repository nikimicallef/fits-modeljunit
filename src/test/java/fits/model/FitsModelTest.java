package fits.model;

import fits.FrontEnd;
import fits.TransactionSystem;
import nz.ac.waikato.modeljunit.Action;
import nz.ac.waikato.modeljunit.GreedyTester;
import nz.ac.waikato.modeljunit.GraphListener;
import nz.ac.waikato.modeljunit.Transition;
import nz.ac.waikato.modeljunit.StopOnFailureListener;
import nz.ac.waikato.modeljunit.VerboseListener;
import nz.ac.waikato.modeljunit.coverage.ActionCoverage;
import nz.ac.waikato.modeljunit.coverage.TransitionCoverage;
import nz.ac.waikato.modeljunit.coverage.TransitionPairCoverage;
import nz.ac.waikato.modeljunit.timing.Time;
import nz.ac.waikato.modeljunit.timing.Timeout;
import nz.ac.waikato.modeljunit.timing.TimedFsmModel;
import nz.ac.waikato.modeljunit.timing.TimedModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A shared FSM for FITS, with one runner at the bottom of this file.
 * Enums describe the objects' states; maps keep a separate state for each account and session.
 * Actions call the real system, assert conformance, then update the expected model.
 * FITS is intentionally buggy: the runner stops and fails at the first counterexample.
 */
public class FitsModelTest implements TimedFsmModel {
    /** Initialisation starts a ten-second waiting period before login is permitted. */
    private enum SystemState { UNINITIALISED, STARTING, READY }
    /** Each user has an independent enabled, disabled or frozen mode. */
    private enum UserMode { DISABLED, ENABLED, FROZEN }
    /** Listing status controls the greylisting and post-blacklisting rules. */
    private enum UserStatus { WHITELISTED, GREYLISTED, BLACKLISTED }
    /** Membership determines the outgoing payment fee. */
    private enum UserType { NORMAL, SILVER, GOLD }
    /** Each requested money account has its own lifecycle. */
    private enum AccountState { REQUESTED, APPROVED, REJECTED, CLOSED }
    /** Every session retains its own state even after logout. */
    private enum SessionState { OPEN, CLOSED }

    /** Set to true to skip known failure-producing actions during exploration. */
    private static boolean SKIP_KNOWN_VIOLATIONS = true;

    private TransactionSystem system;
    private FrontEnd front;
    private boolean testing;
    private SystemState systemState;
    private boolean reconciled;
    private final Random data = new Random(1);
    private final Map<Integer, ExpectedUser> users = new LinkedHashMap<>();
    private ExpectedUser user;

    /** Simple expected data for one user; never derived from the real system's state. */
    private static class ExpectedUser {
        Integer uid;
        UserMode mode = UserMode.DISABLED;
        UserStatus status = UserStatus.WHITELISTED;
        UserType type = UserType.NORMAL;
        boolean transferRestricted;
        int incomingTransfers;
        int restrictionUntil = -1;
        final Map<String, AccountState> accounts = new LinkedHashMap<>();
        final Map<String, Double> balances = new LinkedHashMap<>();
        final Map<String, Integer> createdAt = new LinkedHashMap<>();
        final Map<Integer, SessionState> sessions = new LinkedHashMap<>();
        final Map<Integer, Integer> requests = new LinkedHashMap<>();
        final Map<Integer, Integer> lastActivity = new LinkedHashMap<>();
        final ArrayList<Integer> recentCreations = new ArrayList<>();

        /** Snapshot of this user's FSMs and counters for ModelJUnit's state description. */
        @Override public String toString() {
            return mode + ":" + status + ":" + type + ":" + accounts + ":" + sessions
                    + ":" + requests + ":" + incomingTransfers + ":" + recentCreations.size()
                    + ":" + transferRestricted;
        }
    }

    private static final int SECOND = 1000;
    private static final int MINUTE = 60 * SECOND;
    private static final int HOUR = 60 * MINUTE;
    private static final int DAY = 24 * HOUR;
    /** Virtual milliseconds; there are no wall-clock sleeps. */
    @Time public int now;
    @Timeout("finishStartup") public int startupDeadline;
    @Timeout("checkReconciliation") public int reconciliationDeadline;
    @Timeout("finishRestriction") public int restrictionDeadline;
    @Timeout("expireCreations") public int creationDeadline;
    @Timeout("checkAccountDecisions") public int decisionDeadline;
    @Timeout("checkInactivity") public int inactivityDeadline;

    /** Combines all users' enum FSMs and relevant counters into one immutable snapshot. */
    @Override public Object getState() {
        return systemState + ":" + reconciled + ":" + users + ":selected="
                + (user == null ? "none" : user.uid);
    }

    /** Resets every object FSM and timer, and creates a fresh real system during testing. */
    @Override public void reset(boolean testing) {
        this.testing = testing;
        systemState = SystemState.UNINITIALISED;
        users.clear(); user = null;
        reconciled = false;
        now = 0;
        startupDeadline = reconciliationDeadline = restrictionDeadline = -1;
        creationDeadline = decisionDeadline = inactivityDeadline = -1;
        data.setSeed(1);
        if (testing) {
            system = new TransactionSystem();
            front = system.getFrontEnd();
        }
    }

    /** Normal clock steps are one second; TimedModel also selects the next timeout. */
    @Override public int getNextTimeIncrement(Random random) { return SECOND; }

    /**
     * Takes an idle step without calling FITS. TimedModel advances @Time after actions,
     * using getNextTimeIncrement() or jumping to the next timeout; do not increment now here.
     */
    @Action public void waitForTime() {}

    /**
     * Optional exploration mode: omit actions that are expected to reveal known rule violations.
     * The default run keeps these attempts enabled and stops at its first conformance failure.
     */
    private boolean skipKnownViolations() {
        return SKIP_KNOWN_VIOLATIONS;
    }

    /** Initialisation is legal initially, or after the preceding reconciliation. */
    public boolean ADMIN_initialiseGuard() { return systemState == SystemState.UNINITIALISED || reconciled; }

    /** Calls the frontend initialisation API, clears object FSMs and starts both system timers. */
    @Action public void ADMIN_initialise() {
        if (testing) front.ADMIN_initialise();
        // Initialisation removes ordinary users and all their accounts and sessions.
        systemState = SystemState.STARTING;
        users.clear(); user = null;
        reconciled = false;
        startupDeadline = now + 10 * SECOND;
        reconciliationDeadline = now + 5 * MINUTE;
        restrictionDeadline = creationDeadline = decisionDeadline = inactivityDeadline = -1;
    }

    /** The startup timeout moves the system FSM from STARTING to READY. */
    public boolean finishStartupGuard() { return systemState == SystemState.STARTING && now >= startupDeadline; }
    /** Completes the ten-second waiting period. */
    @Action public void finishStartup() { systemState = SystemState.READY; startupDeadline = -1; }

    /** At most two ordinary users keep the tutorial's search space small. */
    public boolean ADMIN_createUserGuard() { return users.size() < 2; }
    /** Creates another user through the API and checks all three initial classifications. */
    @Action public void ADMIN_createUser() {
        user = new ExpectedUser();
        user.uid = testing ? front.ADMIN_createUser("Student " + users.size(), "Argentina") : users.size();
        users.put(user.uid, user);
        if (testing) {
            assertTrue(system.getBackEnd().getUserInfo(user.uid).isDisabled());
            assertTrue(system.getBackEnd().getUserInfo(user.uid).isWhitelisted());
            assertTrue(system.getBackEnd().getUserInfo(user.uid).isNormalUser());
        }
    }

    /** Selection is available once two ordinary users exist. */
    public boolean selectUserGuard() { return users.size() > 1; }
    /** Changes which user subsequent actions address; this is model selection, not a FITS operation. */
    @Action public void selectUser() {
        for (ExpectedUser other : users.values()) {
            if (other != user) { user = other; break; }
        }
    }

    /** DISABLED users can be enabled by the administrator. */
    public boolean ADMIN_enableUserGuard() { return user != null && user.mode == UserMode.DISABLED; }
    /** Checks the real enable operation before transitioning to ENABLED. */
    @Action public void ADMIN_enableUser() {
        if (testing) { front.ADMIN_enableUser(user.uid); assertTrue(system.getBackEnd().getUserInfo(user.uid).isEnabled()); }
        user.mode = UserMode.ENABLED;
    }

    /** ENABLED users can be disabled while retaining their existing sessions. */
    public boolean ADMIN_disableUserGuard() { return user != null && user.mode == UserMode.ENABLED; }
    /** Checks the real disable operation before transitioning to DISABLED. */
    @Action public void ADMIN_disableUser() {
        if (testing) { front.ADMIN_disableUser(user.uid); assertTrue(system.getBackEnd().getUserInfo(user.uid).isDisabled()); }
        user.mode = UserMode.DISABLED;
    }

    /** A session lets an enabled user request freezing. */
    public boolean USER_freezeUserGuard() { return user != null && user.mode == UserMode.ENABLED && !user.sessions.isEmpty() && (!skipKnownViolations() || hasOpenSession()); }
    /** Calls the frontend freeze operation, then checks and records FROZEN mode. */
    @Action public void USER_freezeUser() {
        Integer sid = chooseSession();
        String before = testing ? system.getBackEnd().getUserInfo(user.uid).getSession(sid).getLog() : "";
        if (testing) {
            assertTrue(front.USER_freezeUser(user.uid, sid));
            assertTrue(system.getBackEnd().getUserInfo(user.uid).isFrozen());
        }
        observeActivity(sid, before);
        user.mode = UserMode.FROZEN;
    }

    /** A frozen user may attempt unfreezing unless skip mode omits this known FITS defect. */
    public boolean USER_unfreezeUserGuard() {
        return user != null && user.mode == UserMode.FROZEN && !user.sessions.isEmpty()
                && !skipKnownViolations();
    }
    /** Checks that unfreezing restores ENABLED; the original FITS incorrectly disables the user. */
    @Action public void USER_unfreezeUser() {
        Integer sid = chooseSession();
        String before = testing ? system.getBackEnd().getUserInfo(user.uid).getSession(sid).getLog() : "";
        if (testing) {
            assertTrue(front.USER_unfreezeUser(user.uid, sid));
            assertTrue(system.getBackEnd().getUserInfo(user.uid).isEnabled(), "Unfreeze must restore ENABLED");
        }
        observeActivity(sid, before);
        user.mode = UserMode.ENABLED;
    }

    /** The administrator can change an existing user's membership to gold. */
    public boolean ADMIN_makeGoldUserGuard() { return user != null && user.type != UserType.GOLD; }
    /** Checks the gold transition; this tutorial uses Argentinian users, as required by P1. */
    @Action public void ADMIN_makeGoldUser() {
        if (testing) { front.ADMIN_makeGoldUser(user.uid); assertTrue(system.getBackEnd().getUserInfo(user.uid).isGoldUser()); }
        user.type = UserType.GOLD;
    }

    /** The administrator can change an existing user's membership to silver. */
    public boolean ADMIN_makeSilverUserGuard() { return user != null && user.type != UserType.SILVER; }
    /** Checks the silver transition before recording the expected membership. */
    @Action public void ADMIN_makeSilverUser() {
        if (testing) { front.ADMIN_makeSilverUser(user.uid); assertTrue(system.getBackEnd().getUserInfo(user.uid).isSilverUser()); }
        user.type = UserType.SILVER;
    }

    /** The administrator can restore normal membership. */
    public boolean ADMIN_makeNormalUserGuard() { return user != null && user.type != UserType.NORMAL; }
    /** Checks the normal transition before recording the expected membership. */
    @Action public void ADMIN_makeNormalUser() {
        if (testing) { front.ADMIN_makeNormalUser(user.uid); assertTrue(system.getBackEnd().getUserInfo(user.uid).isNormalUser()); }
        user.type = UserType.NORMAL;
    }

    /** An enabled user can attempt login; four allocated sessions bound this teaching example. */
    public boolean USER_loginGuard() {
        return user != null && user.mode == UserMode.ENABLED && user.sessions.size() < 4
                && (!skipKnownViolations() || (systemState == SystemState.READY && openSessions() < 3));
    }
    /** Checks whether login should succeed or be rejected under P2, P11 and P9. */
    @Action public void USER_login() {
        boolean allowed = systemState == SystemState.READY && openSessions() < 3;
        String rule = systemState == SystemState.UNINITIALISED ? "P2" :
                systemState == SystemState.STARTING ? "P11" : "P9";
        Integer sid = testing ? front.USER_login(user.uid) : user.sessions.size();
        if (!allowed) {
            if (testing) assertEquals(-1, sid, rule + " violated: forbidden login accepted");
            return; // Rejection is a self-loop in the expected FSM.
        }
        if (testing) assertNotEquals(-1, sid, "Valid login rejected");
        user.sessions.put(sid, SessionState.OPEN);
        user.requests.put(sid, 0);
        user.lastActivity.put(sid, now);
        updateInactivityDeadline();
    }

    /** Logout is available while at least one session FSM is OPEN. */
    public boolean USER_logoutGuard() { return user != null && user.sessions.containsValue(SessionState.OPEN); }
    /** Closes one OPEN session and updates only that session's FSM and timer. */
    @Action public void USER_logout() {
        Integer sid = null;
        for (Integer id : user.sessions.keySet()) {
            if (user.sessions.get(id) == SessionState.OPEN) { sid = id; break; }
        }
        if (testing) front.USER_logout(user.uid, sid);
        user.sessions.put(sid, SessionState.CLOSED);
        updateInactivityDeadline();
    }

    /** A known session can attempt a request; eleven accounts bound the example. */
    public boolean USER_requestAccountGuard() {
        return user != null && !user.sessions.isEmpty() && user.accounts.size() < 11
                && (!skipKnownViolations() || (hasOpenSession() && canRequestAnotherAccount()));
    }
    /** Checks active-session logging, per-session request count and rolling creation limits. */
    @Action public void USER_requestAccount() {
        Integer sid = chooseRequestSession();
        String before = testing ? system.getBackEnd().getUserInfo(user.uid).getSession(sid).getLog() : "";
        int beforeCount = testing ? system.getBackEnd().getUserInfo(user.uid).getAccounts().size() : 0;
        String number = testing ? front.USER_requestAccount(user.uid, sid) : "account" + user.accounts.size();
        observeActivity(sid, before);
        if (user.requests.get(sid) >= 10 || user.recentCreations.size() >= 3) {
            String rule = user.requests.get(sid) >= 10 ? "P7" : "P13";
            if (testing) {
                assertNull(number, rule + " violated: forbidden account creation accepted");
                assertEquals(beforeCount, system.getBackEnd().getUserInfo(user.uid).getAccounts().size());
            }
            return;
        }
        if (testing) assertNotNull(number, "Valid request rejected");
        user.accounts.put(number, AccountState.REQUESTED);
        user.balances.put(number, 0.0);
        user.createdAt.put(number, now);
        user.requests.put(sid, user.requests.get(sid) + 1);
        user.recentCreations.add(now);
        updateCreationDeadline();
        updateDecisionDeadline();
    }

    /** An administrator may approve any account FSM in REQUESTED. */
    public boolean ADMIN_approveOpenAccountGuard() { return user != null && user.accounts.containsValue(AccountState.REQUESTED); }
    /** Approves one requested account and checks its observable open flag. */
    @Action public void ADMIN_approveOpenAccount() {
        String number = findAccount(AccountState.REQUESTED);
        if (testing) { front.ADMIN_approveOpenAccount(user.uid, number); assertTrue(system.getBackEnd().getUserInfo(user.uid).getAccount(number).isOpen()); }
        user.accounts.put(number, AccountState.APPROVED);
        updateDecisionDeadline();
    }

    /** An administrator may reject any account FSM in REQUESTED. */
    public boolean ADMIN_rejectOpenAccountGuard() { return user != null && user.accounts.containsValue(AccountState.REQUESTED); }
    /** Records the rejection event; FITS has no persistent rejected-state getter. */
    @Action public void ADMIN_rejectOpenAccount() {
        String number = findAccount(AccountState.REQUESTED);
        if (testing) front.ADMIN_rejectOpenAccount(user.uid, number);
        user.accounts.put(number, AccountState.REJECTED);
        updateDecisionDeadline();
    }

    /** A known session may close an APPROVED account. */
    public boolean USER_closeAccountGuard() {
        return user != null && !user.sessions.isEmpty() && user.accounts.containsValue(AccountState.APPROVED)
                && (!skipKnownViolations() || hasOpenSession());
    }
    /** Checks the close operation before moving that account FSM to CLOSED. */
    @Action public void USER_closeAccount() {
        Integer sid = chooseSession();
        String number = findAccount(AccountState.APPROVED);
        String before = testing ? system.getBackEnd().getUserInfo(user.uid).getSession(sid).getLog() : "";
        if (testing) {
            front.USER_closeAccount(user.uid, sid, number);
            assertFalse(system.getBackEnd().getUserInfo(user.uid).getAccount(number).isOpen());
        }
        observeActivity(sid, before);
        user.accounts.put(number, AccountState.CLOSED);
    }

    /** Deposits require an APPROVED account and a known session. */
    public boolean USER_depositFromExternalGuard() {
        return user != null && !user.sessions.isEmpty() && user.accounts.containsValue(AccountState.APPROVED)
                && (!skipKnownViolations() || hasOpenSession());
    }
    /** Deposits $100, compares balances and counts incoming transfers for P6. */
    @Action public void USER_depositFromExternal() {
        Integer sid = chooseSession();
        String number = findAccount(AccountState.APPROVED);
        String before = testing ? system.getBackEnd().getUserInfo(user.uid).getSession(sid).getLog() : "";
        if (testing) front.USER_depositFromExternal(user.uid, sid, number, 100.0);
        observeActivity(sid, before);
        user.balances.put(number, user.balances.get(number) + 100);
        if (testing) assertEquals(user.balances.get(number), system.getBackEnd().getUserInfo(user.uid).getAccount(number).getBalance(), 0.001);
        if (user.status == UserStatus.GREYLISTED && user.incomingTransfers < 3) user.incomingTransfers++;
    }

    /** A payment attempt needs a known session and an APPROVED account. */
    public boolean USER_payToExternalGuard() {
        return user != null && !user.sessions.isEmpty() && user.accounts.containsValue(AccountState.APPROVED)
                && (!skipKnownViolations() || (hasOpenSession() && user.mode == UserMode.ENABLED && !user.transferRestricted));
    }
    /** Attempts $101, checking P5/P12 and the selected membership fee and balance. */
    @Action public void USER_payToExternal() {
        Integer sid = chooseSession();
        String number = findAccount(AccountState.APPROVED);
        double fee = switch (user.type) {
            case NORMAL -> 5.05;
            case SILVER -> 3.03;
            case GOLD -> 2.02;
        };
        double total = 101 + fee;
        boolean allowed = user != null && user.mode == UserMode.ENABLED && !user.transferRestricted && user.balances.get(number) >= total;
        String before = testing ? system.getBackEnd().getUserInfo(user.uid).getSession(sid).getLog() : "";
        boolean paid = testing ? front.USER_payToExternal(user.uid, sid, number, 101.0) : allowed;
        observeActivity(sid, before);
        if (!allowed) {
            String rule = user.mode != UserMode.ENABLED ? "P5" : user.transferRestricted ? "P12" : "Funds";
            if (testing) assertFalse(paid, rule + " violated: forbidden payment accepted");
        } else {
            if (testing) assertTrue(paid, "Valid payment rejected");
            user.balances.put(number, user.balances.get(number) - total);
        }
        if (testing) assertEquals(user.balances.get(number), system.getBackEnd().getUserInfo(user.uid).getAccount(number).getBalance(), 0.001);
    }

    /** An existing user may enter GREYLISTED. */
    public boolean ADMIN_greylistUserGuard() { return user != null && user.status != UserStatus.GREYLISTED; }
    /** Checks greylisting and starts a new incoming-transfer count. */
    @Action public void ADMIN_greylistUser() {
        if (testing) { front.ADMIN_greylistUser(user.uid); assertTrue(system.getBackEnd().getUserInfo(user.uid).isGreylisted()); }
        user.status = UserStatus.GREYLISTED;
        user.incomingTransfers = 0;
    }

    /** An existing user may enter BLACKLISTED. */
    public boolean ADMIN_blacklistUserGuard() { return user != null && user.status != UserStatus.BLACKLISTED; }
    /** Checks blacklisting and cancels any old whitelist restriction. */
    @Action public void ADMIN_blacklistUser() {
        if (testing) { front.ADMIN_blacklistUser(user.uid); assertTrue(system.getBackEnd().getUserInfo(user.uid).isBlacklisted()); }
        user.status = UserStatus.BLACKLISTED;
        user.transferRestricted = false;
        user.restrictionUntil = -1;
        updateRestrictionDeadline();
    }

    /** A GREYLISTED or BLACKLISTED user can attempt whitelisting. */
    public boolean ADMIN_whitelistUserGuard() {
        return user != null && user.status != UserStatus.WHITELISTED
                && (!skipKnownViolations() || user.status != UserStatus.GREYLISTED || user.incomingTransfers >= 3);
    }
    /** Checks P6 before transitioning; blacklisting followed by whitelisting starts P12's timer. */
    @Action public void ADMIN_whitelistUser() {
        if (testing) front.ADMIN_whitelistUser(user.uid);
        if (user.status == UserStatus.GREYLISTED && user.incomingTransfers < 3) {
            if (testing) assertTrue(system.getBackEnd().getUserInfo(user.uid).isGreylisted(), "P6 violated: early whitelisting accepted");
            return;
        }
        if (testing) assertTrue(system.getBackEnd().getUserInfo(user.uid).isWhitelisted());
        if (user.status == UserStatus.BLACKLISTED) {
            user.transferRestricted = true;
            user.restrictionUntil = now + 12 * HOUR;
            updateRestrictionDeadline();
        }
        user.status = UserStatus.WHITELISTED;
        user.incomingTransfers = 0;
    }

    /** Reconciliation is an administrator transition after initialisation. */
    public boolean ADMIN_reconcileGuard() { return systemState != SystemState.UNINITIALISED && !reconciled; }
    /** Records the reconciliation event; the actual method is a stub. */
    @Action public void ADMIN_reconcile() {
        if (testing) front.ADMIN_reconcile();
        reconciled = true;
        reconciliationDeadline = -1;
    }

    /** Enables an outstanding reconciliation timeout. */
    public boolean checkReconciliationGuard() { return reconciliationDeadline != -1 && now >= reconciliationDeadline; }
    /** Reports P14 if the administrator did not reconcile in time. */
    @Action public void checkReconciliation() {
        if (testing && !skipKnownViolations()) assertTrue(reconciled, "P14 violated: no reconciliation within five minutes");
        reconciliationDeadline = -1;
    }

    /** Enables the post-whitelist restriction timeout. */
    public boolean finishRestrictionGuard() { return restrictionDeadline != -1 && now >= restrictionDeadline; }
    /** Changes only the restriction flag; the user remains WHITELISTED. */
    @Action public void finishRestriction() {
        for (ExpectedUser current : users.values()) {
            if (current.restrictionUntil != -1 && now >= current.restrictionUntil) {
                current.transferRestricted = false;
                current.restrictionUntil = -1;
            }
        }
        updateRestrictionDeadline();
    }

    /** Enables expiry of the oldest creation in P13's rolling window. */
    public boolean expireCreationsGuard() { return creationDeadline != -1 && now >= creationDeadline; }
    /** Removes expired timestamps, retaining all account FSMs. */
    @Action public void expireCreations() {
        for (ExpectedUser current : users.values()) current.recentCreations.removeIf(time -> now - time >= DAY);
        updateCreationDeadline();
    }

    /** Enables the earliest requested account's decision timeout. */
    public boolean checkAccountDecisionsGuard() { return decisionDeadline != -1 && now >= decisionDeadline; }
    /** Checks each account FSM independently for an overdue REQUESTED state (P15). */
    @Action public void checkAccountDecisions() {
        for (ExpectedUser current : users.values()) {
            for (String number : current.accounts.keySet()) {
                if (current.accounts.get(number) == AccountState.REQUESTED && now >= current.createdAt.get(number) + DAY) {
                    if (testing && !skipKnownViolations()) fail("P15 violated: account " + number + " still REQUESTED after 24 hours");
                }
            }
        }
        decisionDeadline = -1;
    }

    /** Enables the earliest OPEN session's inactivity timeout. */
    public boolean checkInactivityGuard() { return inactivityDeadline != -1 && now >= inactivityDeadline; }
    /** Checks each session FSM independently for a missing close event (P16). */
    @Action public void checkInactivity() {
        for (ExpectedUser current : users.values()) {
            for (Integer sid : current.sessions.keySet()) {
                if (current.sessions.get(sid) == SessionState.OPEN && now >= current.lastActivity.get(sid) + 15 * MINUTE) {
                    if (testing && !skipKnownViolations()) fail("P16 violated: session " + sid + " still OPEN after fifteen idle minutes");
                }
            }
        }
        inactivityDeadline = -1;
    }

    /** Counts OPEN session FSMs, independently of the real system's retained session objects. */
    private int openSessions() {
        int count = 0;
        for (SessionState state : user.sessions.values()) if (state == SessionState.OPEN) count++;
        return count;
    }

    /** Chooses a known session, including CLOSED ones to test forbidden operations. */
    private boolean hasOpenSession() { return user.sessions.containsValue(SessionState.OPEN); }

    /** In skip mode choose only active sessions, so user actions cannot violate P10. */
    private Integer chooseSession() {
        ArrayList<Integer> choices = new ArrayList<>();
        for (Integer sid : user.sessions.keySet()) {
            if (!skipKnownViolations() || user.sessions.get(sid) == SessionState.OPEN) choices.add(sid);
        }
        return choices.get(data.nextInt(choices.size()));
    }

    /** Selects a session that still has request capacity when failure cases are skipped. */
    private Integer chooseRequestSession() {
        if (!skipKnownViolations()) return chooseSession();
        ArrayList<Integer> choices = new ArrayList<>();
        for (Integer sid : user.sessions.keySet()) {
            if (user.sessions.get(sid) == SessionState.OPEN && user.requests.get(sid) < 10) choices.add(sid);
        }
        return choices.get(data.nextInt(choices.size()));
    }

    /** Checks the rolling and per-session limits for at least one open session. */
    private boolean canRequestAnotherAccount() {
        if (user.recentCreations.size() >= 3) return false;
        for (Integer sid : user.sessions.keySet()) {
            if (user.sessions.get(sid) == SessionState.OPEN && user.requests.get(sid) < 10) return true;
        }
        return false;
    }

    /** Finds the first account in the requested FSM state. */
    private String findAccount(AccountState state) {
        for (String number : user.accounts.keySet()) if (user.accounts.get(number) == state) return number;
        throw new IllegalStateException("No account in state " + state);
    }

    /** Compares CLOSED-session logging with P10; activity in an OPEN session resets its own timer. */
    private void observeActivity(Integer sid, String before) {
        if (user.sessions.get(sid) == SessionState.CLOSED) {
            if (testing) assertEquals(before, system.getBackEnd().getUserInfo(user.uid).getSession(sid).getLog(), "P10 violated: logging to a CLOSED session");
        } else {
            user.lastActivity.put(sid, now);
            updateInactivityDeadline();
        }
    }

    /** Schedules the earliest decision deadline among REQUESTED accounts. */
    private void updateDecisionDeadline() {
        decisionDeadline = -1;
        for (ExpectedUser current : users.values()) {
            for (String number : current.accounts.keySet()) {
                if (current.accounts.get(number) == AccountState.REQUESTED) {
                    int due = current.createdAt.get(number) + DAY;
                    if (decisionDeadline == -1 || due < decisionDeadline) decisionDeadline = due;
                }
            }
        }
    }

    /** Schedules the earliest inactivity deadline among OPEN sessions. */
    private void updateInactivityDeadline() {
        inactivityDeadline = -1;
        for (ExpectedUser current : users.values()) {
            for (Integer sid : current.sessions.keySet()) {
                if (current.sessions.get(sid) == SessionState.OPEN) {
                    int due = current.lastActivity.get(sid) + 15 * MINUTE;
                    if (inactivityDeadline == -1 || due < inactivityDeadline) inactivityDeadline = due;
                }
            }
        }
    }

    /** Schedules the earliest rolling-window expiry across users. */
    private void updateCreationDeadline() {
        creationDeadline = -1;
        for (ExpectedUser current : users.values()) {
            if (!current.recentCreations.isEmpty()) {
                int due = current.recentCreations.getFirst() + DAY;
                if (creationDeadline == -1 || due < creationDeadline) creationDeadline = due;
            }
        }
    }

    /** Schedules the earliest post-whitelist restriction expiry across users. */
    private void updateRestrictionDeadline() {
        restrictionDeadline = -1;
        for (ExpectedUser current : users.values()) {
            int due = current.restrictionUntil;
            if (due != -1 && (restrictionDeadline == -1 || due < restrictionDeadline)) restrictionDeadline = due;
        }
    }

    /** Prints each generated transition as a readable state/action/state block. */
    private static class ReadableTransitionListener extends VerboseListener {
        @Override public void doneTransition(int action, Transition transition) {
            String timedTransition = transition.toString();
            String time = timedTransition.substring(0, timedTransition.indexOf(':'));
            model_.printMessage("Time: " + time + " ms"
                    + "\nState: " + transition.getStartState()
                    + "\nAction: " + transition.getAction()
                    + "\nState: " + transition.getEndState() + "\n");
        }

        @Override public void failure(nz.ac.waikato.modeljunit.TestFailureException failure) {
            String reason = failure.getMessage();
            int detail = reason == null ? -1 : reason.indexOf(" due to ");
            if (detail >= 0) reason = reason.substring(detail + " due to ".length());
            int time = ((TimedModel) model_).getTime();
            model_.printMessage("FAILURE\nTime: " + time + " ms"
                    + "\nState: " + failure.getState()
                    + "\nAction: " + failure.getActionName()
                    + "\nReason: " + reason);
        }
    }

    /** The only runner: build the FSM graph, then generate guarded conformance actions. */
    @Test public void runModelJUnit() {
        TimedModel model = new TimedModel(new FitsModelTest());
        model.setRandom(new Random(1));
        model.setTimeoutProbability(0.1);
        GreedyTester tester = new GreedyTester(model);

        // Build immediately after creating the tester so coverage metrics can count transition pairs.
        GraphListener graph = tester.buildGraph();
        model.printMessage("Model graph complete: " + graph.isComplete()
                + "; unexplored branches: " + graph.numTodo());
        tester.setRandom(new Random(1));

        tester.addListener(new StopOnFailureListener());
        tester.addListener(new ReadableTransitionListener());
        tester.addCoverageMetric(new ActionCoverage());
        tester.addCoverageMetric(new TransitionCoverage());
        tester.addCoverageMetric(new TransitionPairCoverage());
        try {
            tester.generate(1000);
        } finally {
            tester.printCoverage();
        }
    }
}
