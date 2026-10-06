package fits.model;

import fits.FrontEnd;
import fits.TransactionSystem;
import nz.ac.waikato.modeljunit.Action;
import nz.ac.waikato.modeljunit.FsmModel;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Untimed ModelJUnit model for one selected requirement. Guards expose safe inputs or deliberate
 * forbidden inputs, actions drive the real system, and independent state supplies the oracle.
 * Graph discovery resets abstract state without invoking FITS.
 */
public final class FitsFsmModel implements FsmModel {
    /**
     * Untimed requirements: initialisation (P2), disabled withdrawals (P5), greylisting (P6),
     * account requests (P7), concurrent sessions (P9), and active-session logging (P10).
     */
    public enum Rule { P2, P5, P6, P7, P9, P10 }

    private final Rule rule;
    private final boolean allowViolations;
    private boolean testing;
    private TransactionSystem sut;
    private FrontEnd front;
    private Integer uid;
    private Integer sid;
    private String account;
    private boolean initialised;
    private boolean enabled;
    private boolean loggedIn;
    private boolean hasSession;
    private boolean greylisted;
    private int incoming;
    private int requests;
    private int active;
    private final ArrayDeque<Integer> liveSessions = new ArrayDeque<>();

    /**
     * Selects one untimed rule and whether deliberately violating inputs may be generated.
     */
    FitsFsmModel(Rule rule, boolean allowViolations) {
        this.rule = rule;
        this.allowViolations = allowViolations;
    }

    /**
     * Returns only the state relevant to the selected rule, keeping IDs and unrelated data out of
     * the finite graph.
     */
    @Override public Object getState() {
        // Project onto the state relevant to this rule; IDs/balances do not enlarge
        // the finite graph. Counters saturate where the requirement stops counting.
        return switch (rule) {
            case P2 -> "initialised=" + initialised + ",loggedIn=" + loggedIn;
            case P5 -> "enabled=" + enabled + ",loggedIn=" + loggedIn;
            case P6 -> "greylisted=" + greylisted + ",incoming=" + incoming + ",loggedIn=" + loggedIn;
            case P7 -> "requests=" + requests + ",loggedIn=" + loggedIn;
            case P9 -> "active=" + active;
            case P10 -> "hasSession=" + hasSession + ",loggedIn=" + loggedIn;
        };
    }

    /**
     * Resets all abstract state. With testing=true, creates the SUT and prepares a funded account
     * when needed; with false, performs no system operations.
     */
    @Override public void reset(boolean testing) {
        this.testing = testing;
        initialised = rule != Rule.P2;
        enabled = true;
        loggedIn = hasSession = greylisted = false;
        incoming = requests = active = 0;
        liveSessions.clear();
        uid = sid = null;
        account = null;
        sut = null;
        front = null;
        if (testing) {
            sut = new TransactionSystem();
            front = sut.getFrontEnd();
            if (initialised) front.ADMIN_initialise();
            createUser();
            if (rule != Rule.P2) {
                // Fixture setup is outside the generated trace. Open and fund an
                // account, then close this preparation session before generation.
                Integer setupSession = front.USER_login(uid);
                account = front.USER_requestAccount(uid, setupSession);
                front.ADMIN_approveOpenAccount(uid, account);
                front.USER_depositFromExternal(uid, setupSession, account, 10_000.0);
                front.USER_logout(uid, setupSession);
            }
        }
    }

    /**
     * Creates an enabled Argentinian fixture user and stores its returned ID.
     */
    private void createUser() {
        uid = front.ADMIN_createUser("Tutorial user", "Argentina");
        front.ADMIN_enableUser(uid);
    }

    /**
     * Fails with the selected property ID so characterization tests can distinguish policy
     * violations from unrelated errors.
     */
    private void require(boolean condition, String message) {
        assertTrue(condition, rule + " violated: " + message);
    }

    /**
     * Enables initialisation only for P2 while the abstract system is uninitialised.
     */
    public boolean initialiseGuard() { return rule == Rule.P2 && !initialised; }
    /**
     * Initialises the real backend during testing, recreates its fixture user and resets abstract
     * session state.
     */
    @Action public void initialise() {
        if (testing) { front.ADMIN_initialise(); createUser(); }
        initialised = true;
        loggedIn = hasSession = false;
        active = 0;
        liveSessions.clear();
        sid = null;
    }

    /**
     * Allows login in the states explored by this rule. Safe mode respects P2 and P9; violation
     * mode admits a premature or fourth login.
     */
    public boolean loginGuard() {
        if (!enabled) return false;
        if (rule == Rule.P9) return active < (allowViolations ? 4 : 3);
        return !loggedIn && (rule != Rule.P2 || initialised || allowViolations);
    }
    /**
     * Calls real login and verifies the returned session. Checks P2 or P9 before recording the new
     * abstract active session and resetting its request count.
     */
    @Action public void login() {
        if (testing) {
            sid = front.USER_login(uid);
            assertNotEquals(-1, sid);
            assertNotNull(sut.getBackEnd().getUserInfo(uid).getSession(sid));
            if (rule == Rule.P2) require(initialised, "login before initialisation");
            if (rule == Rule.P9) require(active < 3, "fourth concurrent session opened");
            liveSessions.addLast(sid);
        }
        loggedIn = hasSession = true;
        active++;
        requests = 0;
    }

    /**
     * Allows logout when the model records at least one active session.
     */
    public boolean logoutGuard() { return loggedIn; }
    /**
     * Calls real close for the latest live session and decrements the independent active-session
     * count.
     */
    @Action public void logout() {
        if (testing) front.USER_logout(uid, liveSessions.removeLast());
        active--;
        loggedIn = active > 0;
    }

    /**
     * Allows P5's administrator disable operation when the user is currently enabled.
     */
    public boolean disableGuard() { return rule == Rule.P5 && enabled; }
    /**
     * Disables the real user, checks its mode and records the independent disabled state.
     */
    @Action public void disable() {
        if (testing) { front.ADMIN_disableUser(uid); assertTrue(sut.getBackEnd().getUserInfo(uid).isDisabled()); }
        enabled = false;
    }
    /**
     * Allows P5's administrator enable operation when the user is disabled.
     */
    public boolean enableGuard() { return rule == Rule.P5 && !enabled; }
    /**
     * Enables the real user, checks its mode and records the independent enabled state.
     */
    @Action public void enable() {
        if (testing) { front.ADMIN_enableUser(uid); assertTrue(sut.getBackEnd().getUserInfo(uid).isEnabled()); }
        enabled = true;
    }

    /**
     * Allows P5 payments in a logged-in session. Safe mode requires enabled status; violation mode
     * also tries disabled withdrawals.
     */
    public boolean payGuard() { return rule == Rule.P5 && loggedIn && (enabled || allowViolations); }
    /**
     * Adds sufficient fixture funds, performs a real payment and checks that a debit happened
     * while the model says the user is enabled.
     */
    @Action public void pay() {
        if (testing) {
            // Keep sufficient funds across arbitrarily many generated payments.
            front.USER_depositFromExternal(uid, sid, account, 10.0);
            double before = sut.getBackEnd().getUserInfo(uid).getAccount(account).getBalance();
            assertTrue(front.USER_payToExternal(uid, sid, account, 1.0));
            double after = sut.getBackEnd().getUserInfo(uid).getAccount(account).getBalance();
            assertTrue(after < before, "Payment should withdraw from the real account");
            require(enabled, "disabled user withdrew before being enabled again");
        }
    }

    /**
     * Allows a new P6 greylisting cycle when the user is not already greylisted.
     */
    public boolean greylistGuard() { return rule == Rule.P6 && !greylisted; }
    /**
     * Grey-lists the real user, checks its status and restarts the independent incoming-transfer
     * count.
     */
    @Action public void greylist() {
        if (testing) { front.ADMIN_greylistUser(uid); assertTrue(sut.getBackEnd().getUserInfo(uid).isGreylisted()); }
        greylisted = true;
        incoming = 0;
    }
    /**
     * Allows incoming transfers for a logged-in greylisted P6 user until the relevant count
     * reaches three.
     */
    public boolean incomingTransferGuard() { return rule == Rule.P6 && greylisted && loggedIn && incoming < 3; }
    /**
     * Calls a real incoming deposit, checks its balance effect and increments the independent P6
     * count.
     */
    @Action public void incomingTransfer() {
        if (testing) {
            double before = sut.getBackEnd().getUserInfo(uid).getAccount(account).getBalance();
            front.USER_depositFromExternal(uid, sid, account, 10.0);
            assertEquals(before + 10, sut.getBackEnd().getUserInfo(uid).getAccount(account).getBalance(), 1e-9);
        }
        incoming++;
    }
    /**
     * Allows P6 whitelisting after three transfers in safe mode, or earlier in violation mode.
     */
    public boolean whitelistGuard() { return rule == Rule.P6 && greylisted && (incoming >= 3 || allowViolations); }
    /**
     * Whitelists the real user and asserts that three incoming transfers were observed since
     * greylisting before ending the abstract cycle.
     */
    @Action public void whitelist() {
        if (testing) {
            front.ADMIN_whitelistUser(uid);
            assertTrue(sut.getBackEnd().getUserInfo(uid).isWhitelisted());
            require(incoming >= 3, "whitelisted before three incoming transfers since greylisting");
        }
        greylisted = false;
        incoming = 0;
    }

    /**
     * For P7, allows ten safe requests or an eleventh negative request. For P10, safe mode
     * requires an active session; violation mode permits a retained closed session.
     */
    public boolean requestAccountGuard() {
        if (rule == Rule.P7) return loggedIn && requests < (allowViolations ? 11 : 10);
        return rule == Rule.P10 && hasSession && (loggedIn || allowViolations);
    }
    /**
     * Requests a real account, verifies its creation and log entry, then checks P7's count or
     * P10's active-session requirement.
     */
    @Action public void requestAccount() {
        if (testing) {
            String created = front.USER_requestAccount(uid, sid);
            assertNotNull(sut.getBackEnd().getUserInfo(uid).getAccount(created));
            String log = sut.getBackEnd().getUserInfo(uid).getSession(sid).getLog();
            assertTrue(log.contains(created), "Request must be logged by the real session");
            if (rule == Rule.P7) require(requests < 10, "eleventh account requested in one session");
            if (rule == Rule.P10) require(loggedIn, "session logged a request after logout");
        }
        if (rule == Rule.P7) requests++;
    }
}
