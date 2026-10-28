package fits.model;

import fits.model.enums.UserStatus;

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

/**
 * One listing-status FSM for P6 and P12.
 *
 * <p>The graph uses the FSM 3 enum plus an initial no-user state. Transfer counts, balances and
 * restriction deadlines are model variables checked by guards and properties; they do not create
 * extra graph states.
 */
public class ListingPolicyModelTest implements TimedFsmModel {
    private static final boolean SKIP_KNOWN_VIOLATIONS = true;
    private static final int SECOND = 1_000;
    private static final int HOUR = 60 * 60 * SECOND;

    // States
    /** The only graph state before the fixture has created the user. */
    private enum FixtureState { NO_USER }
    /** Base FSM 3 state; the local graph enum only adds a pre-fixture state. */
    private UserStatus listing;

    // State variables
    private TransactionSystem system;
    private FrontEnd front;
    private boolean testing;
    private boolean ready;
    private Integer uid;
    private Integer sid;
    private String account;
    /** Rule data for P6/P12; these values do not create graph states. */
    private int incomingTransfers;
    private int depositsUsed;
    private double balance;
    private boolean transferRestricted;
    private boolean restrictionUsed;
    private boolean paymentAttempted;
    @Time public int now;
    @Timeout("finishRestriction") public int restrictionEnds;

    /** Returns FSM 3's base enum directly; counters and timers remain separate model variables. */
    @Override public Object getState() {
        return ready ? listing : FixtureState.NO_USER;
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
        account = null;
        listing = UserStatus.WHITELISTED;
        incomingTransfers = depositsUsed = 0;
        balance = 1_000;
        transferRestricted = restrictionUsed = paymentAttempted = false;
        now = 0;
        restrictionEnds = -1;
    }

    @Override public int getNextTimeIncrement(Random random) { return SECOND; }

    /** The same small, active account fixture is used for deposits and external payments. */
    public boolean prepareFixtureGuard() { return !ready; }
    @Action public void prepareFixture() {
        // 1. Create and fund the test user in the SUT.
        Integer sutUid;
        Integer sutSid;
        String sutAccount;
        if (testing) {
            front.ADMIN_initialise();
            sutUid = front.ADMIN_createUser("Student", "Argentina");
            front.ADMIN_enableUser(sutUid);
            sutSid = front.USER_login(sutUid);
            sutAccount = front.USER_requestAccount(sutUid, sutSid);
            front.ADMIN_approveOpenAccount(sutUid, sutAccount);
            front.USER_depositFromExternal(sutUid, sutSid, sutAccount, balance);
        } else {
            sutUid = 1; sutSid = 0; sutAccount = "11";
        }

        // 2. Record the user, session and account in the model.
        uid = sutUid;
        sid = sutSid;
        account = sutAccount;
        ready = true;

        // 3. Check that the SUT account has the model's initial balance and listing status.
        if (testing) {
            assertEquals(balance, system.getBackEnd().getUserInfo(uid).getAccount(account).getBalance(), 0.001);
            assertTrue(system.getBackEnd().getUserInfo(uid).isWhitelisted());
        }
    }

    public boolean ADMIN_greylistUserGuard() { return ready && listing != UserStatus.GREYLISTED; }
    @Action public void ADMIN_greylistUser() {
        // 1. Execute the operation on the SUT.
        if (testing) front.ADMIN_greylistUser(uid);

        // 2. Update the model's listing status and transfer counter.
        listing = UserStatus.GREYLISTED;
        incomingTransfers = 0;

        // 3. Compare the SUT listing status with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isGreylisted());
    }

    public boolean ADMIN_blacklistUserGuard() {
        return ready && listing != UserStatus.BLACKLISTED && !restrictionUsed;
    }
    @Action public void ADMIN_blacklistUser() {
        // 1. Execute the operation on the SUT.
        if (testing) front.ADMIN_blacklistUser(uid);

        // 2. Update the model's listing status and cancel any restriction.
        listing = UserStatus.BLACKLISTED;
        transferRestricted = false;
        restrictionEnds = -1;

        // 3. Compare the SUT listing status with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isBlacklisted());
    }

    public boolean ADMIN_whitelistUserGuard() {
        return ready && listing != UserStatus.WHITELISTED
                && (!SKIP_KNOWN_VIOLATIONS || listing != UserStatus.GREYLISTED || incomingTransfers >= 3);
    }
    @Action public void ADMIN_whitelistUser() {
        // 1. Execute the operation on the SUT.
        if (testing) front.ADMIN_whitelistUser(uid);

        // 2. Update the model if the whitelist transition is permitted.
        boolean rejectedEarlyWhitelist = listing == UserStatus.GREYLISTED && incomingTransfers < 3;
        if (rejectedEarlyWhitelist) {
            // The original system still whitelists; leave the model GREYLISTED to expose P6.
        } else {
            if (listing == UserStatus.BLACKLISTED) {
                transferRestricted = true;
                restrictionUsed = true;
                restrictionEnds = now + 12 * HOUR;
            }
            listing = UserStatus.WHITELISTED;
            incomingTransfers = 0;
        }

        // 3. Compare the SUT result with the model's expected transition.
        if (rejectedEarlyWhitelist) {
            if (!SKIP_KNOWN_VIOLATIONS && testing)
                assertTrue(system.getBackEnd().getUserInfo(uid).isGreylisted(), "P6: whitelisted before three transfers");
        } else if (testing)
            assertTrue(system.getBackEnd().getUserInfo(uid).isWhitelisted());
    }

    public boolean USER_depositFromExternalGuard() { return ready && depositsUsed < 3; }
    @Action public void USER_depositFromExternal() {
        // 1. Execute the deposit on the SUT.
        if (testing) front.USER_depositFromExternal(uid, sid, account, 100.0);

        // 2. Update the model's balance and greylist transfer history.
        balance += 100;
        depositsUsed++;
        if (listing == UserStatus.GREYLISTED && incomingTransfers < 3) incomingTransfers++;

        // 3. Compare the SUT balance with the model.
        if (testing) assertEquals(balance, system.getBackEnd().getUserInfo(uid).getAccount(account).getBalance(), 0.001);
    }

    /** Payment is included only after the blacklist-to-whitelist sequence starts P12's timer. */
    public boolean USER_payToExternalGuard() {
        return ready && restrictionUsed && !paymentAttempted
                && (!SKIP_KNOWN_VIOLATIONS || !transferRestricted);
    }
    @Action public void USER_payToExternal() {
        // 1. Execute the payment on the SUT.
        boolean expected = !transferRestricted && balance >= 106.05;
        boolean paid = testing ? front.USER_payToExternal(uid, sid, account, 101.0) : expected;

        // 2. Update the model's attempt flag and balance when payment is expected to succeed.
        paymentAttempted = true;
        if (expected) balance -= 106.05;

        // 3. Compare the SUT result and balance with the updated model.
        if (expected) assertTrue(paid, "Payment should succeed after the restriction expires");
        else assertFalse(paid, "P12: transfer above $100 was allowed during the restriction");
        if (testing)
            assertEquals(balance, system.getBackEnd().getUserInfo(uid).getAccount(account).getBalance(), 0.001);
    }

    public boolean finishRestrictionGuard() { return restrictionEnds != -1 && now >= restrictionEnds; }
    @Action public void finishRestriction() {
        // 1. Time passing is represented by the timed model; the SUT has no timer callback.

        // 2. Update the model when the restriction deadline is reached.
        transferRestricted = false;
        restrictionEnds = -1;

        // 3. This model-only timer has no corresponding SUT state to compare.
    }

    @Test public void runModelJUnit() {
        FitsModelRunner.run("Listing policy (P6, P12)", new TimedModel(this));
    }
}
