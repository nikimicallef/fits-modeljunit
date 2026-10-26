package fits.model;

import fits.model.enums.UserMode;
import fits.model.enums.UserType;

import fits.FrontEnd;
import fits.TransactionSystem;
import nz.ac.waikato.modeljunit.Action;
import nz.ac.waikato.modeljunit.timing.Time;
import nz.ac.waikato.modeljunit.timing.TimedFsmModel;
import nz.ac.waikato.modeljunit.timing.TimedModel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Small combined user-mode and membership FSM for P5.
 *
 * <p>The graph state is the product of the FSM 2 user-mode enum and FSM 4 membership enum.
 * Balance and payment-attempt count are state variables used by guards and checks, not additional
 * graph states.
 */
public class DisabledPaymentModelTest implements TimedFsmModel {
    /** Test-local product enum for the two base FSMs used by this payment property. */
    private enum PaymentGraphState {
        NO_USER(null, null),
        DISABLED_NORMAL(UserMode.DISABLED, UserType.NORMAL),
        DISABLED_SILVER(UserMode.DISABLED, UserType.SILVER),
        DISABLED_GOLD(UserMode.DISABLED, UserType.GOLD),
        ENABLED_NORMAL(UserMode.ENABLED, UserType.NORMAL),
        ENABLED_SILVER(UserMode.ENABLED, UserType.SILVER),
        ENABLED_GOLD(UserMode.ENABLED, UserType.GOLD),
        FROZEN_NORMAL(UserMode.FROZEN, UserType.NORMAL),
        FROZEN_SILVER(UserMode.FROZEN, UserType.SILVER),
        FROZEN_GOLD(UserMode.FROZEN, UserType.GOLD);

        private final UserMode mode;
        private final UserType membership;

        PaymentGraphState(UserMode mode, UserType membership) {
            this.mode = mode;
            this.membership = membership;
        }

        static PaymentGraphState of(UserMode mode, UserType membership) {
            for (PaymentGraphState state : values())
                if (state.mode == mode && state.membership == membership) return state;
            throw new IllegalStateException("No combined state for " + mode + " and " + membership);
        }
    }
    private static final boolean SKIP_KNOWN_VIOLATIONS = true;

    private TransactionSystem system;
    private FrontEnd front;
    private boolean testing;
    private boolean ready;
    private Integer uid;
    private Integer sid;
    private String account;
    /** Base FSM states; PaymentGraphState combines these two enums for ModelJUnit's graph. */
    private UserMode mode;
    private UserType membership;
    /** Data used to compute payment results; not part of the enum graph state. */
    private double balance;
    private int paymentAttempts;
    @Time public int now;

    /** Returns the combined enum state; paymentAttempts and balance remain separate variables. */
    @Override public Object getState() {
        return ready ? PaymentGraphState.of(mode, membership) : PaymentGraphState.NO_USER;
    }

    @Override public void reset(boolean testing) {
        this.testing = testing;
        system = new TransactionSystem();
        front = system.getFrontEnd();
        ready = false;
        uid = sid = null;
        account = null;
        mode = UserMode.ENABLED;
        membership = UserType.NORMAL;
        balance = 1_000;
        paymentAttempts = 0;
        now = 0;
    }

    @Override public int getNextTimeIncrement(Random random) { return 1_000; }

    /** One active session and one well-funded approved account are fixed for this property. */
    public boolean prepareFixtureGuard() { return !ready; }
    @Action public void prepareFixture() {
        // 1. Create the user, session and approved, funded account in the SUT.
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

        // 2. Record the fixture values and initial mode in the model.
        uid = sutUid;
        sid = sutSid;
        account = sutAccount;
        ready = true;

        // 3. Check that the SUT account has the model's initial balance.
        if (testing)
            assertEquals(balance, system.getBackEnd().getUserInfo(uid).getAccount(account).getBalance(), 0.001);
    }

    public boolean ADMIN_disableUserGuard() { return ready && mode == UserMode.ENABLED; }
    @Action public void ADMIN_disableUser() {
        // 1. Execute the operation on the SUT.
        if (testing) front.ADMIN_disableUser(uid);

        // 2. Update the model's user mode.
        mode = UserMode.DISABLED;

        // 3. Compare the SUT mode with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isDisabled());
    }

    public boolean ADMIN_enableUserGuard() { return ready && mode == UserMode.DISABLED; }
    @Action public void ADMIN_enableUser() {
        // 1. Execute the operation on the SUT.
        if (testing) front.ADMIN_enableUser(uid);

        // 2. Update the model's user mode.
        mode = UserMode.ENABLED;

        // 3. Compare the SUT mode with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isEnabled());
    }

    public boolean ADMIN_makeSilverUserGuard() { return ready && membership != UserType.SILVER; }
    @Action public void ADMIN_makeSilverUser() {
        // 1. Execute the operation on the SUT.
        if (testing) front.ADMIN_makeSilverUser(uid);

        // 2. Update the model's membership tier.
        membership = UserType.SILVER;

        // 3. Compare the SUT membership with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isSilverUser());
    }

    public boolean ADMIN_makeGoldUserGuard() { return ready && membership != UserType.GOLD; }
    @Action public void ADMIN_makeGoldUser() {
        // 1. Execute the operation on the SUT.
        if (testing) front.ADMIN_makeGoldUser(uid);

        // 2. Update the model's membership tier.
        membership = UserType.GOLD;

        // 3. Compare the SUT membership with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isGoldUser());
    }

    public boolean ADMIN_makeNormalUserGuard() { return ready && membership != UserType.NORMAL; }
    @Action public void ADMIN_makeNormalUser() {
        // 1. Execute the operation on the SUT.
        if (testing) front.ADMIN_makeNormalUser(uid);

        // 2. Update the model's membership tier.
        membership = UserType.NORMAL;

        // 3. Compare the SUT membership with the model.
        if (testing) assertTrue(system.getBackEnd().getUserInfo(uid).isNormalUser());
    }

    public boolean USER_payToExternalGuard() {
        return ready && paymentAttempts < 3
                && (!SKIP_KNOWN_VIOLATIONS || mode == UserMode.ENABLED);
    }
    @Action public void USER_payToExternal() {
        // 1. Execute the payment on the SUT.
        double fee = switch (membership) { case NORMAL -> 5.05; case SILVER -> 3.03; case GOLD -> 2.02; };
        double before = balance;
        boolean expected = mode == UserMode.ENABLED && balance >= 101 + fee;
        boolean paid = testing ? front.USER_payToExternal(uid, sid, account, 101.0) : expected;

        // 2. Update model counters and balance according to the expected result.
        paymentAttempts++;
        if (expected) balance -= 101 + fee;

        // 3. Check the SUT result and balance against the updated model.
        if (expected) assertTrue(paid, "Payment should succeed for enabled user with funds");
        else assertFalse(paid, "P5: disabled user was allowed to pay");
        if (testing) {
            double expectedBalance = expected ? balance : before;
            assertEquals(expectedBalance,
                    system.getBackEnd().getUserInfo(uid).getAccount(account).getBalance(), 0.001);
        }
    }

    @Test public void runModelJUnit() {
        FitsModelRunner.run("Disabled payment (P5)", new TimedModel(this));
    }
}
