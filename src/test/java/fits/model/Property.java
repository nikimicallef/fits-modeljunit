package fits.model;

/**
 * Identifiers for chapter 10's six timed requirements. Untimed identifiers are declared separately
 * in FitsFsmModel.Rule.
 */
public enum Property {
    /** No login in the first ten seconds after initialisation. */
    P11,
    /** No single external transfer above $100 for twelve hours after blacklisting then whitelisting. */
    P12,
    /** At most three account creations per user in any rolling 24-hour window. */
    P13,
    /** Reconcile within five minutes of initialisation or before the next initialisation. */
    P14,
    /** Approve or reject each requested account within 24 hours. */
    P15,
    /** Observe session closure within fifteen minutes of its last user activity. */
    P16
}
