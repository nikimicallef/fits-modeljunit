package fits.model;

import fits.FrontEnd;
import fits.TransactionSystem;
import fits.UserInfo;
import fits.UserSession;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives real FITS operations and independently checks one timed requirement. Scenario slots map
 * to actual IDs. Virtual timestamps and observed events form the oracle; this adapter never
 * enforces a policy or schedules automatic system work.
 */
final class FitsFixture {
    static final int SECOND = 1_000;
    static final int MINUTE = 60 * SECOND;
    static final int HOUR = 60 * MINUTE;
    static final int DAY = 24 * HOUR;

    final TransactionSystem sut = new TransactionSystem();
    final FrontEnd front = sut.getFrontEnd();
    private final Property property;
    private int now;
    private Integer initialisedAt;
    private Integer reconciliationDue;
    /** Observed blacklisting history keyed by scenario user slot, independent of SUT status. */
    private final Map<Integer, Boolean> blacklisted = new HashMap<>();
    /** Start of each user's P12 restriction window, measured on the virtual clock. */
    private final Map<Integer, Integer> whitelistedAt = new HashMap<>();
    /** Per-user creation timestamps retained within P13's rolling 24-hour window. */
    private final Map<Integer, ArrayDeque<Integer>> creations = new HashMap<>();
    /** Outstanding P15 deadlines keyed by scenario account slot. */
    private final Map<String, Integer> approvalsDue = new HashMap<>();
    /** Last observed activity of each open session; absence means its close event was observed. */
    private final Map<SessionKey, Integer> activity = new HashMap<>();
    /** Maps readable scenario user slots to the actual Integer ID objects returned by FITS. */
    private final Map<Integer, Integer> users = new HashMap<>();
    /** Maps scenario session slots to actual returned IDs, retaining closed sessions for lookup. */
    private final Map<SessionKey, Integer> sessions = new HashMap<>();
    /** Maps account aliases such as a and b to the actual returned account-number objects. */
    private final Map<String, String> accounts = new HashMap<>();

    /**
     * Identifies a scenario session by both user slot and session slot, keeping inactivity timers
     * independent across users and sessions.
     */
    record SessionKey(int user, int session) {}

    /**
     * Creates a fresh transaction system and an oracle for one selected timed property.
     */
    FitsFixture(Property property) {
        this.property = property;
    }

    /**
     * Advances the oracle to a monotonic absolute virtual timestamp and checks deadlines strictly
     * before it. Equal-time system events may still satisfy a deadline.
     */
    void at(int time) {
        assertTrue(time >= now, "Virtual time must be monotonic");
        now = time;
        // Equal-time events are processed before the deadline checkpoint. Past deadlines
        // always fail before a late approval/reconciliation/logout can erase the failure.
        deadlines(false);
    }

    /**
     * Checks missing events at or before now, including exact deadlines. Place equal-time
     * satisfying events before this call.
     */
    void checkpoint() {
        deadlines(true);
    }

    /**
     * Checks pending reconciliation, account-decision or session-inactivity deadlines for the
     * selected property. inclusive controls whether equality counts as expiration.
     */
    private void deadlines(boolean inclusive) {
        if (property == Property.P14 && reconciliationDue != null) {
            require(!expired(reconciliationDue, inclusive), "reconciliation not observed by five-minute deadline");
        }
        if (property == Property.P15) {
            approvalsDue.forEach((account, due) ->
                    require(!expired(due, inclusive), "account " + account + " undecided at 24-hour deadline"));
        }
        if (property == Property.P16) {
            activity.forEach((session, last) -> require(!expired(last + 15 * MINUTE, inclusive),
                    "session " + session + " has no close event after 15 minutes of inactivity"));
        }
    }

    /**
     * Returns whether a deadline has passed, optionally treating equality as expiration.
     */
    private boolean expired(int due, boolean inclusive) {
        return inclusive ? now >= due : now > due;
    }

    /**
     * Reports an assertion failure containing the property ID, virtual timestamp and explanatory
     * detail.
     */
    private void require(boolean valid, String detail) {
        assertTrue(valid, property + " violated at " + now + "ms: " + detail);
    }

    /**
     * Calls real administrator initialisation, checks P14's outstanding reconciliation obligation,
     * then resets oracle histories and scenario ID mappings.
     */
    void initialise() {
        boolean outstanding = reconciliationDue != null;
        front.ADMIN_initialise();
        if (property == Property.P14) {
            require(!outstanding, "reinitialisation before reconciliation (Exercise 10.2)");
        }
        initialisedAt = now;
        reconciliationDue = now + 5 * MINUTE;
        blacklisted.clear();
        whitelistedAt.clear();
        creations.clear();
        approvalsDue.clear();
        activity.clear();
        users.clear();
        sessions.clear();
        accounts.clear();
    }

    /**
     * Calls the reconciliation stub and records that its required event occurred; does not claim
     * that financial reconciliation was implemented.
     */
    void reconcile() {
        front.ADMIN_reconcile();
        reconciliationDue = null; // Observed call; no assertion about the stub's financial work.
    }

    /**
     * Creates and enables a real user, storing the returned ID under a scenario-local slot.
     */
    void user(int slot) {
        Integer uid = front.ADMIN_createUser("User " + slot, "Malta");
        front.ADMIN_enableUser(uid);
        users.put(slot, uid);
    }

    /**
     * Resolves a scenario user slot to its actual backend user record.
     */
    private UserInfo info(int slot) {
        return sut.getBackEnd().getUserInfo(users.get(slot));
    }

    /**
     * Performs a real login, checks P11 when selected, and maps the session slot. Replaces the
     * session record with a delegating test spy that observes logging and closing for P16.
     */
    void login(int user, int session) {
        Integer sid = front.USER_login(users.get(user));
        assertNotEquals(-1, sid, "Enabled fixture user should be able to login");
        assertNotNull(info(user).getSession(sid));
        sessions.put(new SessionKey(user, session), sid);
        if (property == Property.P11) {
            require(initialisedAt != null && now - initialisedAt >= 10 * SECOND,
                    "session opened during initial ten seconds");
        }
        // UserSession exposes neither open/closed state nor callbacks. A test-only spy
        // observes log/close calls, equivalent to the original AspectJ event points.
        // The original session open call above executed on the unchanged SUT.
        UserSession original = info(user).getSession(sid);
        SessionKey key = new SessionKey(user, session);
        UserSession observed = new UserSession(users.get(user), sid) {
            /**
             * Delegates the real log append and observes activity at the current virtual time for
             * this session.
             */
            @Override public void log(String message) {
                super.log(message);
                activity.put(key, now);
            }
            /**
             * Delegates the close observation point and cancels only this session's inactivity
             * deadline.
             */
            @Override public void closeSession() {
                super.closeSession();
                activity.remove(key);
            }
        };
        int index = info(user).getSessions().indexOf(original);
        info(user).getSessions().set(index, observed);
        activity.put(key, now);
    }

    /**
     * Calls real logout using mapped IDs. The session spy removes that session's pending
     * inactivity obligation.
     */
    void logout(int user, int session) {
        front.USER_logout(users.get(user), sessions.get(new SessionKey(user, session)));
    }

    /**
     * Calls real blacklisting and clears any previous post-whitelist restriction history for this
     * user.
     */
    void blacklist(int user) {
        front.ADMIN_blacklistUser(users.get(user));
        blacklisted.put(user, true);
        whitelistedAt.remove(user);
        assertTrue(info(user).isBlacklisted());
    }

    /**
     * Calls real whitelisting. Starts the P12 window only after an observed blacklisting; repeated
     * whitelisting alone does not restart it.
     */
    void whitelist(int user) {
        front.ADMIN_whitelistUser(users.get(user));
        if (Boolean.TRUE.equals(blacklisted.remove(user))) {
            whitelistedAt.put(user, now);
        }
        assertTrue(info(user).isWhitelisted());
    }

    /**
     * Calls real account creation and records its number, P15 decision deadline and per-user P13
     * creation history. Accounts exactly 24 hours old expire from the rolling count.
     */
    void request(int user, int session, String slot) {
        String account = front.USER_requestAccount(users.get(user),
                sessions.get(new SessionKey(user, session)));
        assertNotNull(info(user).getAccount(account));
        accounts.put(slot, account);
        approvalsDue.put(slot, now + DAY);
        ArrayDeque<Integer> times = creations.computeIfAbsent(user, ignored -> new ArrayDeque<>());
        // Half-open rolling window: an account exactly 24h old is no longer counted.
        while (!times.isEmpty() && now - times.peekFirst() >= DAY) {
            times.removeFirst();
        }
        times.addLast(now);
        if (property == Property.P13) {
            require(times.size() <= 3, "four accounts created in a rolling 24-hour window for user " + user);
        }
    }

    /**
     * Calls real account approval, checks the open flag and clears that account's decision
     * obligation.
     */
    void approve(int user, String slot) {
        front.ADMIN_approveOpenAccount(users.get(user), accounts.get(slot));
        assertTrue(info(user).getAccount(accounts.get(slot)).isOpen());
        approvalsDue.remove(slot);
    }

    /**
     * Calls the rejection stub and clears the decision obligation based on the observed
     * administrator event, without inventing rejection state.
     */
    void reject(int user, String slot) {
        front.ADMIN_rejectOpenAccount(users.get(user), accounts.get(slot));
        // P15 concerns the administrator's decision event; rejection is a SUT stub.
        approvalsDue.remove(slot);
    }

    /**
     * Calls an incoming external deposit, checks the balance increase and applies the P12
     * principal limit if selected.
     */
    void deposit(int user, int session, String slot, double amount) {
        double before = info(user).getAccount(accounts.get(slot)).getBalance();
        front.USER_depositFromExternal(users.get(user), sessions.get(new SessionKey(user, session)),
                accounts.get(slot), amount);
        assertEquals(before + amount, info(user).getAccount(accounts.get(slot)).getBalance(), 1e-9);
        externalTransfer(user, amount);
    }

    /**
     * Performs a funded external payment, checks the fee-inclusive debit and applies the P12 limit
     * to principal only.
     */
    void pay(int user, int session, String slot, double amount) {
        double before = info(user).getAccount(accounts.get(slot)).getBalance();
        boolean paid = front.USER_payToExternal(users.get(user), sessions.get(new SessionKey(user, session)),
                accounts.get(slot), amount);
        assertTrue(paid, "Fixture payment must have sufficient funds");
        assertEquals(before - amount - info(user).getChargeRate(amount),
                info(user).getAccount(accounts.get(slot)).getBalance(), 1e-9);
        externalTransfer(user, amount);
    }

    /**
     * Checks P12 for one user: principal above $100 is forbidden until twelve hours after
     * blacklisting followed by whitelisting.
     */
    private void externalTransfer(int user, double amount) {
        Integer since = whitelistedAt.get(user);
        if (property == Property.P12 && since != null) {
            require(now - since >= 12 * HOUR || amount <= 100,
                    "external transfer principal exceeds $100 within 12 hours after whitelisting");
        }
    }

    /**
     * Calls a fee-free transfer between the same user's accounts and checks both balances. Such
     * transfers are excluded from P12's external-transfer limit.
     */
    void ownTransfer(int user, int session, String from, String to, double amount) {
        double source = info(user).getAccount(accounts.get(from)).getBalance();
        double destination = info(user).getAccount(accounts.get(to)).getBalance();
        assertTrue(front.USER_transferOwnAccounts(users.get(user), sessions.get(new SessionKey(user, session)),
                accounts.get(from), accounts.get(to), amount));
        assertEquals(source - amount, info(user).getAccount(accounts.get(from)).getBalance(), 1e-9);
        assertEquals(destination + amount, info(user).getAccount(accounts.get(to)).getBalance(), 1e-9);
        // Own-account transfers are not external transfers, even above $100.
    }
}
