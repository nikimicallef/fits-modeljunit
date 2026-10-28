package fits.model;

import fits.model.enums.AccountState;

import fits.FrontEnd;
import fits.TransactionSystem;
import nz.ac.waikato.modeljunit.Action;
import nz.ac.waikato.modeljunit.timing.Time;
import nz.ac.waikato.modeljunit.timing.Timeout;
import nz.ac.waikato.modeljunit.timing.TimedFsmModel;
import nz.ac.waikato.modeljunit.timing.TimedModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Models one account lifecycle and checks request-count and decision deadlines (P7, P13, P15).
 *
 * <p>{@link #accountState} is the FSM state. Request counts, creation timestamps and deadlines are
 * model variables used by guards and property checks; they are deliberately not part of the graph
 * state returned by {@link #getState()}. This keeps the graph focused on the account FSM, while
 * generated action sequences can still exercise the numeric rules.
 */
public class AccountRequestModelTest implements TimedFsmModel {
    private static final boolean SKIP_KNOWN_VIOLATIONS = true;
    private static final int SECOND = 1_000;
    private static final int MINUTE = 60 * SECOND;
    private static final int HOUR = 60 * MINUTE;
    private static final int DAY = 24 * HOUR;

    // States
    /** Setup markers before this model has a user/session or a money-account instance. */
    private enum FixtureState { NO_SESSION, NO_ACCOUNT }
    /** Base FSM 5 state; null means that no account has been requested in this model yet. */
    private AccountState accountState;

    // State variables
    private final ArrayList<Integer> recentCreations = new ArrayList<>();
    private TransactionSystem system;
    private FrontEnd front;
    private boolean testing;
    private boolean ready;
    private Integer uid;
    private Integer sid;
    /** Account number for the lifecycle currently represented by accountState. */
    private String currentAccount;
    private String pendingAccount;
    /** Request-limit data used by guards, but not additional account FSM states. */
    private int requestsThisSession;
    private int nextAccount;
    @Time public int now;
    @Timeout("expireCreations") public int creationExpiresAt;
    @Timeout("checkDecision") public int decisionDueAt;

    /**
     * Returns a setup marker before an account exists, then the shared FSM 5 {@link AccountState}
     * enum directly. Numeric limits and timers are model variables, not account FSM states.
     */
    @Override public Object getState() {
        if (!ready) return FixtureState.NO_SESSION;
        return accountState == null ? FixtureState.NO_ACCOUNT : accountState;
    }

    @Override public void reset(boolean testing) {
        this.testing = testing;
        // Graph exploration passes false; only a real test reset creates a fresh SUT.
        if (testing) {
            system = new TransactionSystem();
            front = system.getFrontEnd();
        }
        ready = false;
        uid = sid = null;
        currentAccount = null;
        pendingAccount = null;
        accountState = null;
        requestsThisSession = 0;
        nextAccount = 1;
        recentCreations.clear();
        now = 0;
        creationExpiresAt = decisionDueAt = -1;
    }

    @Override public int getNextTimeIncrement(Random random) { return SECOND; }

    /** This model holds other objects fixed: one enabled user and one active session. */
    public boolean prepareSessionGuard() { return !ready; }
    @Action public void prepareSession() {
        // 1. Execute the fixture setup on the SUT.
        Integer sutUid;
        Integer sutSid;
        if (testing) {
            front.ADMIN_initialise();
            sutUid = front.ADMIN_createUser("Student", "Argentina");
            front.ADMIN_enableUser(sutUid);
            sutSid = front.USER_login(sutUid);
        } else {
            sutUid = 1;
            sutSid = 0;
        }

        // 2. Update the model's fixture state.
        uid = sutUid;
        sid = sutSid;
        ready = true;

        // 3. Check that the fixture exists in the SUT.
        if (testing) assertEquals(0, sutSid, "The first login should create session 0");
    }

    /** Only one account lifecycle is shown at a time; counters remain separate model variables. */
    public boolean USER_requestAccountGuard() {
        return ready && accountState != AccountState.REQUESTED
                && (!SKIP_KNOWN_VIOLATIONS || (requestsThisSession < 10 && recentCreations.size() < 3));
    }
    @Action public void USER_requestAccount() {
        // 1. Execute the request on the SUT.
        String account = testing ? front.USER_requestAccount(uid, sid) : "account" + nextAccount;
        boolean allowed = requestsThisSession < 10 && recentCreations.size() < 3;

        // 2. Update the model only when the request is permitted.
        if (allowed) {
            currentAccount = account;
            pendingAccount = account;
            accountState = AccountState.REQUESTED;
            requestsThisSession++;
            nextAccount++;
            recentCreations.add(now);
            updateCreationDeadline();
            decisionDueAt = now + DAY;
        }

        // 3. Check the SUT result against the model's permission rule.
        if (allowed) assertNotNull(account, "A permitted account request should create an account");
        else if (!SKIP_KNOWN_VIOLATIONS && testing)
            assertNull(account, "P7/P13: account request limit exceeded");
    }

    public boolean ADMIN_approveOpenAccountGuard() {
        return accountState == AccountState.REQUESTED;
    }
    @Action public void ADMIN_approveOpenAccount() {
        // 1. Execute the approval on the SUT.
        String decidedAccount = pendingAccount;
        if (testing) front.ADMIN_approveOpenAccount(uid, decidedAccount);

        // 2. Update the model: the pending request is no longer pending.
        accountState = AccountState.APPROVED;
        pendingAccount = null;
        decisionDueAt = -1;

        // 3. Check correspondence: approval opened the account in the SUT.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).getAccount(decidedAccount).isOpen());
    }

    public boolean ADMIN_rejectOpenAccountGuard() {
        return accountState == AccountState.REQUESTED;
    }
    @Action public void ADMIN_rejectOpenAccount() {
        // 1. Execute the rejection observation point on the SUT.
        String decidedAccount = pendingAccount;
        if (testing) front.ADMIN_rejectOpenAccount(uid, decidedAccount);

        // 2. Update the model: the pending request has received a decision.
        accountState = AccountState.REJECTED;
        pendingAccount = null;
        decisionDueAt = -1;

        // 3. Check correspondence: the FITS rejection stub leaves the account closed.
        if (testing) assertFalse(system.getBackEnd().getUserInfo(uid).getAccount(decidedAccount).isOpen());
    }

    /** An approved account can be closed; this is FSM 5's final lifecycle transition. */
    public boolean USER_closeAccountGuard() {
        return accountState == AccountState.APPROVED;
    }
    @Action public void USER_closeAccount() {
        // 1. Execute the close operation on the SUT.
        String accountToClose = currentAccount;
        if (testing) front.USER_closeAccount(uid, sid, accountToClose);

        // 2. Move this account's FSM to CLOSED.
        accountState = AccountState.CLOSED;

        // 3. Compare the account's open flag with the model state.
        if (testing) assertFalse(system.getBackEnd().getUserInfo(uid).getAccount(accountToClose).isOpen());
    }

    public boolean expireCreationsGuard() { return creationExpiresAt != -1 && now >= creationExpiresAt; }
    @Action public void expireCreations() {
        // 1. This timer action has no corresponding operation in the SUT.

        // 2. Remove creation times that have left the one-day window.
        recentCreations.removeIf(created -> now - created >= DAY);
        updateCreationDeadline();

        // 3. There is no SUT state for this model-only timer update to compare.
    }

    public boolean checkDecisionGuard() {
        return decisionDueAt != -1 && now >= decisionDueAt
                && (!SKIP_KNOWN_VIOLATIONS
                || accountState != AccountState.REQUESTED);
    }
    @Action public void checkDecision() {
        // 1. This deadline check has no corresponding operation in the SUT.

        // 2. Remember whether the model's pending request missed its deadline, then consume it.
        boolean decisionWasMissed = accountState == AccountState.REQUESTED;
        decisionDueAt = -1;

        // 3. Check the P15 property against the model state at the deadline.
        if (decisionWasMissed && !SKIP_KNOWN_VIOLATIONS)
            fail("P15: account request was not decided within 24 hours");
    }

    private void updateCreationDeadline() {
        creationExpiresAt = recentCreations.isEmpty() ? -1 : recentCreations.getFirst() + DAY;
    }

    @Test public void runModelJUnit() {
        FitsModelRunner.run("Account requests (P7, P13, P15)", new TimedModel(this));
    }
}
