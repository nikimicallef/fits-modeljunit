# FITS FSM diagrams

These diagrams are derived from the current `FitsModelTest.java` guards and actions.
They are projections of the composed ModelJUnit model, not an exhaustive generated graph.

Solid blue arrows show successful expected enum transitions. Labels use exact action names;
bracketed conditions are acceptance conditions or additional guards, not the full Java predicate.
Dashed red arrows identify an original implementation defect or a test failure. A TEST FAILS box
is an outcome, never an additional enum value. Data-only self-loops are explained in the notes.

Each user owns its own mode, status, membership, account states and session states. Operations
may change several parts of the combined state. `ADMIN_createUser` creates DISABLED + WHITELISTED
+ NORMAL classifications together. `selectUser` chooses which user's FSMs to address without
changing its classifications. `reset` resets the entire model; `ADMIN_initialise` clears ordinary
objects and restarts system timers. These global effects are described on the system sheet.

Virtual time is advanced by TimedModel after actions. Besides `finishStartup`, the timer actions
are `finishRestriction`, `expireCreations`, `checkReconciliation`, `checkAccountDecisions` and
`checkInactivity`. `expireCreations` removes timestamps at 24 hours for the rolling creation count;
it does not delete accounts or change AccountState. The diagrams do not enumerate balances,
request counts, timestamps or every rejected-attempt self-loop.

For account creation, the model bounds allocated accounts below eleven, permits at most ten
requests per session and at most three creations within its rolling window. Known CLOSED sessions
can still be selected to test P10. For login, the generation guard requires ENABLED mode and fewer
than four allocated sessions; acceptance additionally requires READY and fewer than three OPEN
sessions. Freeze/unfreeze generation requires a known session, which can include CLOSED.

Original FITS directly changes user classifications, stores a boolean initialised flag and an
account open flag, and has no persistent active-session state. The expected lifecycle FSMs and
temporal restrictions are partly richer than that implementation; differences are called out
on each sheet. Some transitions fail before the model can update its expected state.

Files 01–06 are available as PNG images.
