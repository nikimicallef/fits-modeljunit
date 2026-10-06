# FITS ModelJUnit

## What FITS does

FITS (Financial Transaction System) is a small, in-memory banking simulation used
by the book to teach verification. It has no database, web interface or real payment
network. Clients and administrators interact with Java methods on `FrontEnd`.
The word “frontend” here means an API that a user interface could call.

There are three main domain objects:

* **User:** a registered person with a name, country, membership type (gold, silver,
  normal), listing status (white-, grey-, blacklisted), and mode (enabled, disabled,
  frozen). Membership type determines outgoing payment charges.
* **Money account:** an account belonging to a user, with an account number,
  balance and an open flag. This is distinct from the person's user record.
* **Session:** a particular login belonging to a user. Its ID is supplied to user
  operations, and its log records those operations. One user can have several sessions.

A typical interaction is:

1. An administrator initialises the system, creates a user and enables that user.
2. The user logs in and receives a session ID.
3. The user requests a money account and receives its account number.
4. An administrator approves that account.
5. The user deposits money, pays an external bill, or transfers funds to another
   account. External payments and transfers to other users incur membership-based
   charges; transfers between the same user's accounts do not.
6. The user logs out of the session.

These are operations the code exposes, not a guarantee that it enforces that order
or every associated policy. For example, it can log a request after logout, accept
an eleventh account request in one session, or let a disabled user make a payment.
The tests demonstrate those violations. The core implementation is deliberately
preserved as a verification teaching subject.

### Implementation files

All six files are in `src/main/java/fits`.

| File | Responsibility |
|---|---|
| `TransactionSystem.java` | Creates and connects the frontend and backend. `setup()` replaces both with fresh instances. Constructing this object does **not** perform administrator initialisation. |
| `FrontEnd.java` | The administrator/user API. `ADMIN_*` methods manage users and approve accounts; `USER_*` methods log in, request accounts and move money. It delegates to the other classes, logs user activity, and applies payment charges. |
| `BackEnd.java` | Stores users in memory, allocates user IDs and looks up users. `initialise()` clears the user list and creates the enabled administrator “Clark Kent”. |
| `UserInfo.java` | Holds one user's attributes, money accounts and sessions. Creates and looks up those objects, changes the user's classification, moves funds and calculates charges. |
| `BankAccount.java` | Holds one money account's owner, number, open flag and balance. Deposits add to the balance; withdrawals subtract from it. These methods do not themselves enforce the verification policies. |
| `UserSession.java` | Holds a session ID, owner and text log. `log()` appends text. `openSession()` and `closeSession()` are empty; there is no stored active-session flag. |

`ADMIN_reconcile()` and `ADMIN_rejectOpenAccount()` are also stubs. A test can observe
when they are called, but cannot establish that they perform financial reconciliation
or persist a rejection. The code has no automatic inactivity scheduler.

There is currently **no application `main()` runner**. Maven/JUnit runs the tests,
which construct `TransactionSystem` instances and drive the frontend. The book's
old `Main` and `Scenarios` drivers depended on its runtime-monitoring framework and
were replaced by the test runners below.

## Test files and how they fit together

Every test file is in `src/test/java/fits/model`. “SUT” means the **system under test**:
the real FITS implementation. An **oracle** is the independent logic that decides
whether the observed operations satisfy a requirement.

There are two families: untimed models for event ordering/counters, and timed
models for timestamped events and deadlines. The classes ending in `Test` are
JUnit entry points. Models describe the paths; the `Runs` helpers configure
ModelJUnit and execute them.

### Untimed FSM tests

| File | What it does |
|---|---|
| `FitsFsmModel.java` | Implements ModelJUnit's `FsmModel` for P2, P5, P6, P7, P9 and P10, one selected rule at a time. Maintains independent state such as enabled status, login status, incoming-transfer count and account-request count. Its `@Action` methods call the actual FITS API and assert the selected rule; matching `*Guard()` methods decide which actions ModelJUnit can select. `getState()` exposes a finite description, and `reset()` resets the model and, during testing, the SUT. |
| `FsmRuns.java` | Wraps the FSM in ModelJUnit's `Model`, installs `StopOnFailureListener`, and uses a seeded `GreedyTester` to generate paths. Builds graphs, writes coverage reports, and supplies minimal counterexample sequences. P7 also uses deterministic graph discovery and boundary paths so the ten-request limit is reached reliably. |
| `FitsFsmTest.java` | The default untimed JUnit runner. Confirms the intended counterexamples, checks P6 with zero through three incoming transfers, and generates safe paths for each rule. Expected violations are caught and checked for the correct property ID. |
| `FitsFsmConformanceTest.java` | The strict untimed JUnit runner, enabled by `-Pconformance`. Allows violating paths and lets their assertion failures reach JUnit instead of treating them as expected counterexamples. |

For example, the P10 counterexample flows through
`FitsFsmTest → FsmRuns → FitsFsmModel → FrontEnd`:
login, logout, then request an account using the old session. FITS actually appends
to that session's log. The model's independent login state says the session is
closed, so the P10 assertion fails. The default runner checks that this precise
property violation occurred; the strict runner reports it as a failing test.

### Timed tests

| File | What it does |
|---|---|
| `Property.java` | Defines the six timed property identifiers, P11–P16. The untimed identifiers are separately declared by `FitsFsmModel.Rule`. |
| `Scenario.java` | Defines the timed event catalog. Each scenario has a property, name, ordered events with time offsets, and an expected failing event when applicable. Includes safe, violating, boundary and isolation cases. The small builder makes plans readable; it is not the book's EGCL DSL. |
| `FitsTimedModel.java` | Implements `TimedFsmModel`. Holds the `@Time` clock and an `@Timeout` for the next event, selects safe/boundary/violating paths, and executes their events. Its abstract state is the selected scenario and current event index. It delegates real FITS interaction and temporal assertions to `FitsFixture`. |
| `FitsFixture.java` | The timed SUT adapter and oracle. Calls actual frontend methods, keeps IDs and account numbers, checks results/balances, and independently tracks initialisation, whitelisting, account creations, decision deadlines and session activity. `checkpoint()` detects missing deadline events. A test-only session subclass observes actual log/close calls. It adds no policy enforcement. |
| `TimedRuns.java` | Wraps the timed FSM in ModelJUnit's `TimedModel`, configures fast-forwarding and failure handling, replays individual scenarios, and generates seeded paths with `GreedyTester`. Writes graphs and four coverage metrics; checks reachable state/transition coverage. |
| `Chapter10TimedTest.java` | The default timed JUnit runner. Replays every catalog scenario and verifies safe outcomes or the expected property violation at the named event. Also generates safe and boundary paths for each timed property. |
| `Chapter10ConformanceTest.java` | The strict timed JUnit runner, enabled by `-Pconformance`. Generates paths that include violations and lets those failures propagate to JUnit. |

For P11, the flow is
`Chapter10TimedTest → TimedRuns → FitsTimedModel → FitsFixture → FrontEnd`.
`Scenario` supplies a plan that initialises FITS and attempts login after 9,999 ms.
ModelJUnit advances virtual time to the event, the fixture invokes the real login,
and its independent timestamp check detects that ten seconds have not elapsed.
The corresponding login at 10,000 ms is a safe boundary case. Both execute without
waiting ten real seconds.

Generated timed paths choose the first safe case, second safe case, and (in strict
mode) first violating case for the selected property. Deterministic replay covers
the rest of the catalog. Thus graph coverage applies to those selected model paths;
it does not mean random generation explores every catalog case or every FITS behaviour.

### Documentation in the Java source

Every handwritten class, constructor and method has Javadoc explaining its role.
The implementation comments describe actual behaviour, including retained defects
and empty observation points. Model comments explain guards, independent state,
reset semantics and assertions; timing comments explain millisecond offsets,
deadline ordering and per-object histories. Hover over methods in IntelliJ to read
their documentation while following a test path. Executable behaviour remains the
same as before this documentation pass.

### Suggested reading order

Start with `FitsFsmTest.java` and the P10 branches of `FitsFsmModel.java`, then read
`FsmRuns.java` to see ModelJUnit configuration. For timing, start with one P11 plan
in `Scenario.java`, follow it through `FitsTimedModel.java` and `FitsFixture.java`,
then examine `TimedRuns.java` and `Chapter10TimedTest.java`.

Read the conformance runners last: they reuse the same models but change how
violations are reported. [TUTORIAL.md](TUTORIAL.md) provides a longer lesson sequence
and extension exercises.

## Run

Use JDK 25 and Maven 3.9 or later:

```sh
mvn clean verify
mvn -Pconformance test
mvn -Dfits.seed=42 -Dfits.steps=5000 test
```

**The default build is a characterization run, not a claim that FITS conforms.** It
checks safe traces and confirms that deliberately violating traces fail at the
specified event with the correct property ID. It also generates safe and boundary
paths using `GreedyTester`, asserting complete reachable state and transition
coverage of the selected safe graph. P7 also receives deterministic threshold
paths to ensure the deep ten-request boundary is exercised. Its small abstract
graph is also discovered deterministically; the other graphs use `buildGraph()`. Each strict assertion is delivered through
`StopOnFailureListener`; unrelated errors cannot count as an expected violation.

**The `conformance` profile additionally runs uncaught property assertions.** The
unchanged implementation is expected to produce twelve generated failing tests:
six untimed properties and six timed properties. P7 includes a deterministic
counterexample fallback if random generation does not reach its eleventh request. This profile intentionally exits with a failing Maven status. P14 and P15
include obligations of the administrator/environment: missing those events violates
the trace; it does not mean FITS should autonomously reconcile or decide accounts.
The same distinction applies to explicit user logout traces versus automatic session
closure for P16. Strict tests are enabled only by the explicit conformance profile; all known
counterexamples are checked by the default characterization run.

Import `pom.xml` into IntelliJ and select JDK 25 for both the project and Maven runner.
Build output, IntelliJ metadata and other editor/OS noise are ignored by `.gitignore`.

## Requirements and coverage

| Property | Requirement | Cases |
|---|---|---|
| P11 | No session opened in the first 10 seconds after initialisation | Immediate login, 1 ms before, exactly at, after; reinitialisation restarts the window |
| P12 | After blacklisting then whitelisting, no single external transfer above $100 for 12 hours | 1 ms before/at/after 12h; $100 principal plus fees; own-account transfers; per-user isolation; repeated whitelist; renewed blacklist/whitelist; incoming external deposit |
| P13 | At most 3 accounts created per user in any rolling 24 hours | Fourth request before/at/after expiry; per-user isolation; rolling window across calendar days |
| P14 | Reconcile within 5 minutes of initialisation or before the next initialisation, whichever occurs first | Before/at/after deadline; missing reconciliation; early reinitialisation; reconciliation before reinitialisation |
| P15 | Approve or reject each new account within 24 hours | Both outcomes before/at/after deadline; missing decision; independent and staggered account deadlines |
| P16 | Close each session within 15 minutes of user inactivity | Before/at/after logout; missing closure; activity resets timer; admin actions do not; independent sessions; logout cancellation |

The untimed `FitsFsmModel` also covers P2, P5, P6, P7, P9 and P10 from earlier
chapters. P1 (country/type), P3 (balance), P4 (unique account IDs), and P8 (aggregate
reconciliation thresholds) are not included; they are useful extension exercises.
The P8 implementation is an extended FSM with aggregate counters rather than a
simple lifecycle FSM. See [TUTORIAL.md](TUTORIAL.md) for a suggested lesson sequence.
The book contains additional advanced timer exercises; this suite covers the
implemented chapter10-solutions rules, including Exercise 10.2, rather than adding
new policies from exercises absent from that solution.

## Timing and modelling

`FitsTimedModel` implements `TimedFsmModel`. Its public `@Time int now` measures
milliseconds; `@Timeout("executeEvent")` schedules the next event. The model is
wrapped in `TimedModel`, as in the supplied BadLogin `TimedTest.java` example.
Timeout probability 1 makes ModelJUnit fast-forward directly to the next scheduled
event. `elapse()` is an unguarded action so the model can advance when no ordinary
transition is enabled. Inactive timeouts use `TIMEOUT_DISABLED` (-1).

The guarded actions `chooseSafe`, `chooseBoundary` and `chooseViolation` form
alternative paths. `GreedyTester` chooses paths and resets using a fixed,
configurable seed. Every catalog case also receives a deterministic replay through
ModelJUnit's timed action API so boundary coverage does not depend on chance.
Equal-time events execute in declared order. Absolute time is excluded from
`getState()`; time alone cannot mutate the abstract model state. The first event is
scheduled at a positive epoch because ModelJUnit 2.5 ignores timeouts at zero.

`FitsFixture` invokes the real FITS frontend, checks SUT results/balances, and
maintains an independent temporal oracle for the property under test. The original
FITS has no wall-clock reads, scheduler or injected clock; virtual time belongs to
the trace oracle. The adapter never prevents forbidden calls, automatically approves
accounts, reconciles or closes sessions. At a deadline, `checkpoint()` reports a
missing event even when no further system action occurs.

The deadline convention is explicit: an event at the deadline is accepted if it is
ordered before the checkpoint at that same timestamp. Once virtual time passes a
deadline, failure is reported before a late event can clear it. For P13, the window
is half-open: a creation exactly 24 hours old expires. Times in this bounded catalog
fit ModelJUnit's signed 32-bit integer clock.

`reset(false)` resets only abstract model fields and creates no SUT. Graph discovery
therefore cannot operate on or assert against a stale SUT. `reset(true)` creates a
fresh transaction system and oracle. Both path and timeout RNGs are reseeded after
graph discovery for reproducible generation.

`UserSession.openSession()` and `closeSession()` are empty and expose no open-state
query. After the real login call, the fixture installs a test-only `UserSession`
subclass in the session list to observe actual `log` and `closeSession` calls,
matching the original AspectJ observation points. It delegates to the original
methods and records events; it adds no timeout enforcement. Similarly,
`ADMIN_reconcile` and `ADMIN_rejectOpenAccount` are stubs: these tests verify their
call timing, not financial reconciliation correctness or persistent rejection state.

## Differences from the supplied solution

* P13's solution compares the oldest creation to `now + 24h`, which never expires
  old entries correctly. The oracle implements the book's rolling-window requirement:
  expire entries when `now - created >= 24h`.
* P12 checks the external transfer's principal, excluding charges, and includes
  incoming external deposits as external transfers. The solution observes
  `UserInfo.withdrawFrom`, which sees fee-inclusive debits, misses deposits, and also
  sees transfers to other FITS users. This suite follows the stated external-transfer
  criterion and explicitly excludes own-account transfers. Inter-user transfer
  classification is not tested; the external API paths are deposit and pay.
* P14 includes the solution's prohibition on reinitialisation before reconciliation.
* P15 observes the returned account identifier directly instead of parsing a log
  string (a workaround for the EGCL DSL's inability to capture return values).
* Properties are tested independently. The book's P12/P13 scenarios leave sessions
  idle for hours and would otherwise trigger P16 first, hiding the target property.
* Equal-time deadline ordering is deterministic, unlike the book's background timer
  thread. These tests verify logical timing and event criteria, not scheduler latency.

DOT graphs and coverage summaries are written to `target/modeljunit/P<property-number>`
for all twelve included properties. Surefire reports are under `target/surefire-reports`. A strict run stops each
property at its first violation, so its coverage is intentionally partial.
