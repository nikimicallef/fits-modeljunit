package fits;

// The methods called by the user interface for (i) the ADMINistrator; and (ii) normal USERs
/**
 * Java API that a client or administrator interface could call. Delegates to domain objects and
 * logs user operations. The teaching implementation intentionally omits several policy checks, and
 * some administrator methods are stubs.
 */
public class FrontEnd {
	protected BackEnd backend;

	// Constructor of the interface
	/**
	 * Connects this frontend API to the supplied backend.
	 */
	public FrontEnd(BackEnd be) {
		backend = be;
	}

	/**
	 * Returns the backend to which operations are delegated.
	 */
	public BackEnd getBackEnd() {
		return backend;
	}

	// ADMINistrator methods
	// * Initialise the transaction system
	/**
	 * Initialises the backend, clearing existing users and installing the default administrator.
	 */
	public void ADMIN_initialise() {
		backend.initialise();
	}

	// * Reconcile the transaction system
	/**
	 * Administrator reconciliation observation point. This stub performs no accounting work.
	 */
	public void ADMIN_reconcile() {
		// Here the developers would have added code to reconcile the user accounts
	}

	// * Create a new user
	/**
	 * Creates a disabled user and returns the user ID; an explicit enable operation is needed for
	 * login.
	 */
	public Integer ADMIN_createUser(String name, String country) {
		Integer uid = backend.addUser(name, country);
		backend.getUserInfo(uid).makeDisabled();
		return uid;
	}

	// * Enable a user
	/**
	 * Sets the selected user's mode to enabled, allowing subsequent logins.
	 */
	public void ADMIN_enableUser(Integer uid) {
		backend.getUserInfo(uid).makeEnabled();
	}

	// * Disable a user (initially disabled)
	/**
	 * Sets the selected user's mode to disabled. Existing session objects are retained, and payment
	 * methods do not enforce this restriction.
	 */
	public void ADMIN_disableUser(Integer uid) {
		backend.getUserInfo(uid).makeDisabled();
	}

	// * Black/grey or whitelist a user
	/**
	 * Changes the selected user's listing status to blacklisted; it does not itself block transfers.
	 */
	public void ADMIN_blacklistUser(Integer uid) {
		backend.getUserInfo(uid).makeBlacklisted();
	}

	/**
	 * Changes the listing status to greylisted without counting or restricting incoming transfers.
	 */
	public void ADMIN_greylistUser(Integer uid) {
		backend.getUserInfo(uid).makeGreylisted();
	}

	/**
	 * Changes the listing status to whitelisted without enforcing the book's transfer-count or timing
	 * conditions.
	 */
	public void ADMIN_whitelistUser(Integer uid) {
		backend.getUserInfo(uid).makeWhitelisted();
	}

	// * Change user type (Gold, Silver or Normal User)
	/**
	 * Selects gold payment fees without checking the country restriction in P1.
	 */
	public void ADMIN_makeGoldUser(Integer uid) {
		backend.getUserInfo(uid).makeGoldUser();
	}

	/**
	 * Selects silver payment fees for the user.
	 */
	public void ADMIN_makeSilverUser(Integer uid) {
		backend.getUserInfo(uid).makeSilverUser();
	}

	/**
	 * Selects normal payment fees for the user.
	 */
	public void ADMIN_makeNormalUser(Integer uid) {
		backend.getUserInfo(uid).makeNormalUser();
	}

	// * Approve the opening of an account
	/**
	 * Marks the selected money account open without checking global account-number uniqueness.
	 */
	public void ADMIN_approveOpenAccount(Integer uid, String accnum) {
		backend.getUserInfo(uid).getAccount(accnum).enableAccount();
	}

	// * Reject the opening of an account
	/**
	 * Administrator rejection observation point. This stub neither deletes the account nor records a
	 * rejected state.
	 */
	public void ADMIN_rejectOpenAccount(Integer uid, String accnum) {
		// nothing to do here
	}

	// USER methods
	// * Login into the system (allows only ENABLED users to login)
	/**
	 * Creates and returns a session ID for an enabled user, or returns -1 otherwise. Initialisation,
	 * concurrent-session and timing requirements are not checked.
	 */
	public Integer USER_login(Integer uid) {
		UserInfo u = backend.getUserInfo(uid);

		if (u.isEnabled()) {
			return (u.openSession());
		} else {
			return -1;
		}
	}

	// * Logout of the chosen session
	/**
	 * Delegates to the selected session's close observation point. The original session remains in
	 * the user's list.
	 */
	public void USER_logout(Integer uid, Integer sid) {
		backend.getUserInfo(uid).closeSession(sid);
	}

	// * Freeze his/her own user account
	/**
	 * Logs the request, sets the user to frozen and returns true. Transfer methods do not
	 * consistently enforce the mode.
	 */
	public Boolean USER_freezeUser(Integer uid, Integer sid) {
		UserInfo u = backend.getUserInfo(uid);
		u.getSession(sid).log("Freeze account");
		u.makeFrozen();
		return true;
	}

	// * Unfreeze his/her own user account
	/**
	 * If frozen, logs the request and invokes makeUnFrozen(), returning true. That method incorrectly
	 * makes the user disabled. Otherwise logs a failed request and returns false.
	 */
	public Boolean USER_unfreezeUser(Integer uid, Integer sid) {
		UserInfo u = backend.getUserInfo(uid);
		UserSession s = u.getSession(sid);
		if (u.isFrozen()) {
			s.log("Unfreeze account");
			u.makeUnFrozen();
			return true;
		}
		s.log("FAILED (user account not frozen): Unfreeze account");
		return false;
	}

	// * Open a new money account
	/**
	 * Creates an unapproved money account, logs its number to the supplied session and returns the
	 * number. Session activity and request limits are not checked.
	 */
	public String USER_requestAccount(Integer uid, Integer sid) {
		UserInfo u = backend.getUserInfo(uid);
		UserSession s = u.getSession(sid);
		String account_number = u.createAccount(sid);
		s.log("Request new account with number <" + account_number + ">");

		return (account_number);

	}

	// * Close an existing money account
	/**
	 * Logs the request and clears the account's open flag; the account stays in the user's list.
	 */
	public void USER_closeAccount(Integer uid, Integer sid, String accnum) {
		UserInfo u = backend.getUserInfo(uid);
		UserSession s = u.getSession(sid);
		s.log("Close account number <" + accnum + ">");
		u.deleteAccount(accnum);
	}

	// * Deposit money from an external source (e.g. from a credit card)
	/**
	 * Logs an incoming external deposit and credits its amount directly to the destination account.
	 */
	public void USER_depositFromExternal(Integer uid, Integer sid, String accnumDst, Double amount) {
		UserInfo u = backend.getUserInfo(uid);
		UserSession s = u.getSession(sid);
		s.log("Deposit $" + amount + "to account <" + accnumDst + ">");
		u.depositTo(accnumDst, amount);
	}

	// * Pay a bill (i.e. an external money account) - charges apply
	/**
	 * Attempts an external payment, debiting principal plus the membership fee and returning success.
	 * The funds check compares only principal, so fees can produce a negative balance. Missing
	 * sessions return false; disabled or closed sessions are not rejected.
	 */
	public Boolean USER_payToExternal(Integer uid, Integer sid, String accnumSrc, Double amount) {
		UserInfo u = backend.getUserInfo(uid);
		UserSession s = u.getSession(sid);

		if (s == null)
			return false;

		Double total_amount = amount + backend.getUserInfo(uid).getChargeRate(amount);
		if (u.getAccount(accnumSrc).getBalance() >= amount) {
			s.log("Payment of $" + amount + " from account <" + accnumSrc + ">");
			u.withdrawFrom(accnumSrc, total_amount);
			return true;
		}
		s.log("FAILED (not enough funds): Payment of $" + amount + " from account <" + accnumSrc + ">");
		return false;
	}

	// * Transfer money to another user's account - charges apply
	/**
	 * Transfers principal to another user's account and charges the sender a fee if enough funds
	 * cover both. Returns false for a missing source session or insufficient funds; policy and
	 * activity checks are absent.
	 */
	public Boolean USER_transferToOtherAccount(Integer uidSrc, Integer sidSrc, String accnumSrc, Integer uidDst,
			String accnumDst, Double amount) {
		UserInfo from_u = backend.getUserInfo(uidSrc);
		UserSession s = from_u.getSession(sidSrc);

		if (s == null)
			return false;

		Double total_amount = amount + backend.getUserInfo(uidSrc).getChargeRate(amount);

		if (from_u.getAccount(accnumSrc).getBalance() >= total_amount) {
			from_u.withdrawFrom(accnumSrc, total_amount);
			backend.getUserInfo(uidDst).depositTo(accnumDst, amount);
			s.log("Payment of $" + amount + " from account <" + accnumSrc + "> to account " + "<" + accnumDst
					+ " of user " + uidDst);
			return true;
		}
		s.log("FAILED (not enough funds): " + "Payment of $" + amount + " from account <" + accnumSrc + "> to account "
				+ "<" + accnumDst + " of user " + uidDst);
		return false;
	}

	// * Transfer money across own accounts - charges do not apply
	/**
	 * Moves funds between this user's money accounts without a fee, logs the attempt and returns
	 * whether funds were sufficient. No active-session check is performed.
	 */
	public Boolean USER_transferOwnAccounts(Integer uid, Integer sid, String from_account_number,
			String to_account_number, Double amount) {
		UserInfo u = backend.getUserInfo(uid);
		UserSession s = u.getSession(sid);
		BankAccount from_a = backend.getUserInfo(uid).getAccount(from_account_number),
				to_a = backend.getUserInfo(uid).getAccount(to_account_number);

		if (from_a.getBalance() >= amount) {
			from_a.withdraw(amount);
			to_a.deposit(amount);
			s.log("Transfer of $" + amount + " from account <" + from_account_number + "> to own account <"
					+ to_account_number);
			return true;
		}
		s.log("FAILED (not enough funds)" + "Transfer of $" + amount + " from account <" + from_account_number
				+ "> to own account <" + to_account_number);
		return false;
	}

}
