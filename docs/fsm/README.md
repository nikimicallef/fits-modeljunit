# FITS FSM diagrams

These diagrams show the expected object lifecycles and property rules represented
by the separate ModelJUnit tests. They are conceptual views; they do not depict
one composed graph containing every object at once. The files that test each area
are listed in the main [README](../../README.md#the-separate-fsm-models).

Solid blue arrows show successful expected transitions. Labels use exact action
names and are placed beside their arrows. Bracketed conditions are important
acceptance conditions, not every part of the Java guard. Dashed red arrows
identify an original implementation defect or a test failure. A TEST FAILS box
is an outcome, never an enum state.

The split is deliberate:

- `01-system.png`: `SystemTimingModelTest` checks initialisation, the ten-second
  startup period and reconciliation. It uses one user's enabled state to make
  login attempts; it does not model account or session collections.
- `02-user-mode.png` and `04-user-membership.png`: `DisabledPaymentModelTest`
  checks the disabled-user payment rule and fee selection. Freeze and unfreeze
  appear in the conceptual mode diagram but are not actions in this test.
- `03-user-status.png`: a new user starts `WHITELISTED` through
  `ADMIN_createUser`. `ListingPolicyModelTest` checks the three-transfer
  greylisting rule and the temporary restriction after blacklisting then
  whitelisting. It caps the transfer counter at three because higher values are
  equivalent for P6, and models one restriction period.
- `05-accounts.png`: `AccountRequestModelTest` checks per-session request limits,
  the rolling creation window and the decision deadline. It holds one request
  pending at a time; approved or rejected requests are no longer tracked as
  pending obligations.
- `06-sessions.png`: `SessionModelTest` gives each session its own `OPEN` or
  `CLOSED` state and inactivity deadline. Requests through an open session refresh
  only that session's timer. It retains closed sessions to try an operation that
  should not append to their log.

The test models use virtual time through ModelJUnit; they do not sleep. Exact
balances and absolute clock values are action data and are omitted from state
labels. The models therefore remain small but can merge concrete FITS situations
that would differ in an unrestricted system. Each file's `SKIP_KNOWN_VIOLATIONS`
setting controls whether its known failure-producing attempts are guarded out.

FITS itself has no persistent active-session state or automatic timeout scheduler.
Some expected lifecycle states and deadlines are therefore richer than the
implementation. Account rejection and reconciliation are stubs; the model records
the API event as completion for this tutorial example.

All six diagram files use PNG.
