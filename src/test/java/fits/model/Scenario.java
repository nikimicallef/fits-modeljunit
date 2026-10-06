package fits.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static fits.model.FitsFixture.*;

/**
 * Immutable timed test plan: property, human-readable name, expected failing event (null for safe
 * plans), and ordered event list. Offsets are milliseconds relative to initialisation at the
 * scenario epoch.
 */
record Scenario(Property property, String name, String failureEvent, List<Event> events) {
    /**
     * One scheduled operation with a millisecond offset and diagnostic label. Its callback
     * receives the fixture that drives and observes FITS.
     */
    record Event(int offset, String label, Consumer<FitsFixture> operation) {}
    /**
     * Returns whether this plan names an expected failing event.
     */
    boolean violates() { return failureEvent != null; }

    /**
     * Starts a readable scenario builder for the supplied property and name.
     */
    static Builder plan(Property p, String name) { return new Builder(p, name); }

    /**
     * Builds an ordered scenario and automatically inserts initialisation at offset zero.
     * Equal-time events retain their insertion order.
     */
    static final class Builder {
        private final Property property;
        private final String name;
        private final List<Event> events = new ArrayList<>();
        private String failureEvent;
        /**
         * Creates a builder and inserts administrator initialisation at offset zero.
         */
        Builder(Property property, String name) {
            this.property = property;
            this.name = name;
            at(0, "initialise", FitsFixture::initialise);
        }
        /**
         * Appends a labeled callback at a nondecreasing millisecond offset. Equal offsets preserve
         * event order; decreasing offsets are rejected.
         */
        Builder at(int time, String label, Consumer<FitsFixture> action) {
            if (!events.isEmpty() && events.getLast().offset() > time) {
                throw new IllegalArgumentException("Events must be ordered");
            }
            events.add(new Event(time, label, action));
            return this;
        }
        /**
         * Names the event at which a characterization test expects the property assertion to fail.
         */
        Builder failure(String label) { failureEvent = label; return this; }
        /**
         * Returns an immutable scenario with a copied event list.
         */
        Scenario build() {
            return new Scenario(property, name, failureEvent, List.copyOf(events));
        }
    }

    /** Reusable setup operation creating user slot zero and logging into session slot zero. */
    static Consumer<FitsFixture> userAndLogin = f -> { f.user(0); f.login(0, 0); };
    /** Creates an enabled logged-in user with approved account a and $10,000 of fixture funds. */
    static Consumer<FitsFixture> funded = f -> {
        userAndLogin.accept(f);
        f.request(0, 0, "a"); f.approve(0, "a"); f.deposit(0, 0, "a", 10_000);
    };

    /**
     * Builds the complete timed catalog covering safe, violating, boundary and isolation cases for
     * P11 through P16.
     */
    static List<Scenario> all() {
        List<Scenario> result = new ArrayList<>();
        // P11: compare immediate, just-before, exact-boundary and later login events.
        for (int t : new int[]{0, 10 * SECOND - 1, 10 * SECOND, 21 * SECOND}) {
            Builder b = plan(Property.P11, "login-at-" + t)
                    .at(t, "login", userAndLogin);
            if (t < 10 * SECOND) b.failure("login");
            result.add(b.build());
        }
        result.add(plan(Property.P11, "reinitialisation-restarts-wait")
                .at(10 * SECOND, "first-login", userAndLogin)
                .at(20 * SECOND, "initialise-again", FitsFixture::initialise)
                .at(30 * SECOND, "new-login", userAndLogin).build());

        // P12: check expiration, transfer principal, user isolation and whitelist history.
        for (int delay : new int[]{12 * HOUR - 1, 12 * HOUR, 12 * HOUR + 1}) {
            Builder b = plan(Property.P12, "pay-101-after-" + delay)
                    .at(10 * SECOND, "fund-account", funded)
                    .at(20 * SECOND, "blacklist-whitelist", f -> { f.blacklist(0); f.whitelist(0); })
                    .at(20 * SECOND + delay, "pay", f -> f.pay(0, 0, "a", 101));
            if (delay < 12 * HOUR) b.failure("pay");
            result.add(b.build());
        }
        result.add(plan(Property.P12, "100-principal-plus-fees-and-own-transfer")
                .at(10 * SECOND, "fund-account", funded)
                .at(11 * SECOND, "second-account", f -> { f.request(0, 0, "b"); f.approve(0, "b"); })
                .at(20 * SECOND, "blacklist-whitelist", f -> { f.blacklist(0); f.whitelist(0); })
                .at(21 * SECOND, "pay-100", f -> f.pay(0, 0, "a", 100))
                .at(22 * SECOND, "own-transfer", f -> f.ownTransfer(0, 0, "a", "b", 120)).build());
        result.add(plan(Property.P12, "restriction-is-per-user")
                .at(10 * SECOND, "fund-user-zero", funded)
                .at(11 * SECOND, "fund-user-one", f -> {
                    f.user(1); f.login(1, 0); f.request(1, 0, "b");
                    f.approve(1, "b"); f.deposit(1, 0, "b", 1_000);
                })
                .at(20 * SECOND, "restrict-user-zero", f -> { f.blacklist(0); f.whitelist(0); })
                .at(21 * SECOND, "user-one-pays", f -> f.pay(1, 0, "b", 101)).build());
        result.add(plan(Property.P12, "repeated-whitelist-does-not-restart-window")
                .at(10 * SECOND, "fund-account", funded)
                .at(20 * SECOND, "blacklist-whitelist", f -> { f.blacklist(0); f.whitelist(0); })
                .at(20 * SECOND + 11 * HOUR, "whitelist-again", f -> f.whitelist(0))
                .at(20 * SECOND + 12 * HOUR, "pay", f -> f.pay(0, 0, "a", 101)).build());
        result.add(plan(Property.P12, "new-blacklist-whitelist-restarts-window")
                .at(10 * SECOND, "fund-account", funded)
                .at(20 * SECOND, "first-cycle", f -> { f.blacklist(0); f.whitelist(0); })
                .at(20 * SECOND + 12 * HOUR, "second-cycle", f -> { f.blacklist(0); f.whitelist(0); })
                .at(21 * SECOND + 12 * HOUR, "pay", f -> f.pay(0, 0, "a", 101))
                .failure("pay").build());
        result.add(plan(Property.P12, "incoming-external-transfer")
                .at(10 * SECOND, "fund-account", funded)
                .at(20 * SECOND, "blacklist-whitelist", f -> { f.blacklist(0); f.whitelist(0); })
                .at(21 * SECOND, "deposit", f -> f.deposit(0, 0, "a", 101))
                .failure("deposit").build());

        // P13: distinguish a rolling creation window from lifetime or calendar-day counts.
        for (int delta : new int[]{DAY - 1, DAY, DAY + 1}) {
            Builder b = plan(Property.P13, "rolling-window-fourth-at-" + delta)
                    .at(10 * SECOND, "first-three", f -> {
                        userAndLogin.accept(f);
                        f.request(0, 0, "a"); f.request(0, 0, "b"); f.request(0, 0, "c");
                    })
                    .at(10 * SECOND + delta, "fourth", f -> f.request(0, 0, "d"));
            if (delta < DAY) b.failure("fourth");
            result.add(b.build());
        }
        result.add(plan(Property.P13, "accounts-counted-per-user")
                .at(10 * SECOND, "three-each", f -> {
                    for (int u = 0; u < 2; u++) {
                        f.user(u); f.login(u, 0);
                        for (int a = 0; a < 3; a++) f.request(u, 0, "u" + u + "a" + a);
                    }
                }).build());
        result.add(plan(Property.P13, "rolling-not-calendar-day")
                .at(23 * HOUR, "first-account", f -> { userAndLogin.accept(f); f.request(0, 0, "a"); })
                .at(26 * HOUR, "second", f -> f.request(0, 0, "b"))
                .at(38 * HOUR + 10 * MINUTE, "third", f -> f.request(0, 0, "c"))
                .at(39 * HOUR + 20 * MINUTE, "fourth", f -> f.request(0, 0, "d"))
                .failure("fourth").build());

        // P14: reconciliation has both a time deadline and a reinitialisation obligation.
        for (int time : new int[]{5 * MINUTE - 1, 5 * MINUTE, 5 * MINUTE + 1}) {
            Builder b = plan(Property.P14, "reconcile-at-" + time)
                    .at(time, "reconcile", FitsFixture::reconcile)
                    .at(time, "checkpoint", FitsFixture::checkpoint);
            if (time > 5 * MINUTE) b.failure("reconcile");
            result.add(b.build());
        }
        result.add(plan(Property.P14, "missing-reconciliation")
                .at(5 * MINUTE, "deadline", FitsFixture::checkpoint).failure("deadline").build());
        result.add(plan(Property.P14, "reinitialise-before-reconcile")
                .at(MINUTE, "reinitialise", FitsFixture::initialise).failure("reinitialise").build());
        result.add(plan(Property.P14, "reconcile-before-reinitialise")
                .at(MINUTE, "reconcile", FitsFixture::reconcile)
                .at(2 * MINUTE, "reinitialise", FitsFixture::initialise)
                .at(7 * MINUTE, "reconcile-at-new-deadline", FitsFixture::reconcile)
                .at(7 * MINUTE, "checkpoint", FitsFixture::checkpoint).build());

        // P15: approval and rejection both satisfy the obligation, independently per account.
        for (boolean approve : new boolean[]{true, false}) {
            for (int delta : new int[]{DAY - 1, DAY, DAY + 1}) {
                Builder b = plan(Property.P15, (approve ? "approve" : "reject") + "-at-" + delta)
                        .at(10 * SECOND, "request", f -> { userAndLogin.accept(f); f.request(0, 0, "a"); })
                        .at(10 * SECOND + delta, "decision", f -> {
                            if (approve) f.approve(0, "a"); else f.reject(0, "a");
                        })
                        .at(10 * SECOND + delta, "checkpoint", FitsFixture::checkpoint);
                if (delta > DAY) b.failure("decision");
                result.add(b.build());
            }
        }
        result.add(plan(Property.P15, "missing-account-decision")
                .at(10 * SECOND, "request", f -> { userAndLogin.accept(f); f.request(0, 0, "a"); })
                .at(10 * SECOND + DAY, "deadline", FitsFixture::checkpoint).failure("deadline").build());
        result.add(plan(Property.P15, "decision-does-not-clear-another-account")
                .at(10 * SECOND, "two-requests", f -> {
                    userAndLogin.accept(f); f.request(0, 0, "a"); f.request(0, 0, "b");
                })
                .at(MINUTE, "approve-one", f -> f.approve(0, "a"))
                .at(10 * SECOND + DAY, "other-deadline", FitsFixture::checkpoint)
                .failure("other-deadline").build());
        result.add(plan(Property.P15, "staggered-decisions-for-independent-accounts")
                .at(10 * SECOND, "first-request", f -> { userAndLogin.accept(f); f.request(0, 0, "a"); })
                .at(20 * SECOND, "second-request", f -> f.request(0, 0, "b"))
                .at(10 * SECOND + DAY, "first-decision", f -> { f.approve(0, "a"); f.checkpoint(); })
                .at(20 * SECOND + DAY, "second-decision", f -> { f.reject(0, "b"); f.checkpoint(); }).build());

        // P16: only activity in that session resets its idle deadline; logout cancels it.
        for (int delta : new int[]{15 * MINUTE - 1, 15 * MINUTE, 15 * MINUTE + 1}) {
            Builder b = plan(Property.P16, "logout-after-" + delta)
                    .at(10 * SECOND, "login", userAndLogin)
                    .at(10 * SECOND + delta, "logout", f -> f.logout(0, 0))
                    .at(10 * SECOND + delta, "checkpoint", FitsFixture::checkpoint);
            if (delta > 15 * MINUTE) b.failure("logout");
            result.add(b.build());
        }
        result.add(plan(Property.P16, "inactive-session-not-closed")
                .at(10 * SECOND, "login", userAndLogin)
                .at(10 * SECOND + 15 * MINUTE, "deadline", FitsFixture::checkpoint)
                .failure("deadline").build());
        result.add(plan(Property.P16, "user-log-resets-idle-deadline")
                .at(10 * SECOND, "login", userAndLogin)
                .at(10 * SECOND + 14 * MINUTE, "activity", f -> f.request(0, 0, "a"))
                .at(10 * SECOND + 15 * MINUTE, "old-deadline", FitsFixture::checkpoint)
                .at(10 * SECOND + 29 * MINUTE, "logout", f -> { f.logout(0, 0); f.checkpoint(); }).build());
        result.add(plan(Property.P16, "admin-approval-does-not-reset-user-inactivity")
                .at(10 * SECOND, "request", f -> { userAndLogin.accept(f); f.request(0, 0, "a"); })
                .at(10 * SECOND + 14 * MINUTE, "approval", f -> f.approve(0, "a"))
                .at(10 * SECOND + 15 * MINUTE, "deadline", FitsFixture::checkpoint)
                .failure("deadline").build());
        result.add(plan(Property.P16, "session-timers-are-independent")
                .at(10 * SECOND, "first-login", userAndLogin)
                .at(10 * SECOND + MINUTE, "second-login", f -> f.login(0, 1))
                .at(10 * SECOND + 14 * MINUTE, "first-activity", f -> f.request(0, 0, "a"))
                .at(10 * SECOND + 16 * MINUTE, "second-deadline", FitsFixture::checkpoint)
                .failure("second-deadline").build());
        result.add(plan(Property.P16, "logout-cancels-only-own-timer")
                .at(10 * SECOND, "two-sessions", f -> { userAndLogin.accept(f); f.login(0, 1); })
                .at(10 * SECOND + MINUTE, "logout-first", f -> f.logout(0, 0))
                .at(10 * SECOND + 15 * MINUTE, "other-deadline", FitsFixture::checkpoint)
                .failure("other-deadline").build());
        result.add(plan(Property.P16, "logged-out-session-does-not-time-out")
                .at(10 * SECOND, "login", userAndLogin)
                .at(MINUTE, "logout", f -> f.logout(0, 0))
                .at(DAY, "long-after-logout", FitsFixture::checkpoint).build());
        return List.copyOf(result);
    }
}
