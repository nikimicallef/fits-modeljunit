package fits;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Mutable record for one user, including classification, money accounts and login sessions.
 * Membership determines payment fees. Original lookup and lifecycle defects are retained for
 * verification exercises.
 */
public class UserInfo {
	/**
	 * Whether a user is enabled, disabled or temporarily frozen. The original unfreeze operation
	 * incorrectly chooses DISABLED.
	 */
	protected enum UserMode {
		ENABLED, DISABLED, FROZEN;
	}

	/**
	 * White-, grey- or blacklisting classification, independent of membership type and enabled mode.
	 */
	public enum UserStatus {
		WHITELISTED, GREYLISTED, BLACKLISTED;
	}

	/**
	 * Membership tier used to calculate outgoing payment fees.
	 */
	public enum UserType {
		GOLD, SILVER, NORMAL
	}

	protected Integer uid;
	protected String name;
	protected UserMode mode;
	protected UserStatus status;
	protected UserType type;
	protected ArrayList<UserSession> sessions;
	protected ArrayList<BankAccount> accounts;
	protected Integer next_session_id;
	protected Integer next_account_number;
	protected String country;

	/**
	 * Creates a disabled, whitelisted, normal user with empty account/session lists and initial ID
	 * counters.
	 */
	public UserInfo(Integer uid, String name, String country) {
		this.uid = uid;
		this.name = name;

		makeDisabled();
		makeWhitelisted();
		makeNormalUser();

		sessions = new ArrayList<UserSession>();
		accounts = new ArrayList<BankAccount>();

		next_session_id = 0;
		next_account_number = 1;

		this.country = country;
	}

	// Basic information
	/**
	 * Returns this user's ID object.
	 */
	public Integer getId() {
		return uid;
	}

	/**
	 * Returns the registered display name.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the registered country used by the book's membership rules.
	 */
	public String getCountry() {
		return country;
	}

	/**
	 * Returns the live mutable money-account list, including accounts whose open flag is false.
	 */
	public ArrayList<BankAccount> getAccounts() {
		return accounts;
	}

	/**
	 * Returns the live mutable session list, including records that have received close calls.
	 */
	public ArrayList<UserSession> getSessions() {
		return sessions;
	}

	// User type (Gold/Silver/Normal)
	/**
	 * Returns whether this user's type is gold.
	 */
	public Boolean isGoldUser() {
		return (type == UserType.GOLD);
	}

	/**
	 * Returns whether this user's type is silver.
	 */
	public Boolean isSilverUser() {
		return (type == UserType.SILVER);
	}

	/**
	 * Returns whether this user's type is normal.
	 */
	public Boolean isNormalUser() {
		return (type == UserType.NORMAL);
	}

	/**
	 * Sets this user's type to gold without enforcing additional history or timing requirements.
	 */
	public void makeGoldUser() {
		type = UserType.GOLD;
	}

	/**
	 * Sets this user's type to silver without enforcing additional history or timing requirements.
	 */
	public void makeSilverUser() {
		type = UserType.SILVER;
	}

	/**
	 * Sets this user's type to normal without enforcing additional history or timing requirements.
	 */
	public void makeNormalUser() {
		type = UserType.NORMAL;
	}

	// Status (White/Black/Greylisted)
	/**
	 * Returns whether this user's status is whitelisted.
	 */
	public Boolean isWhitelisted() {
		return (status == UserStatus.WHITELISTED);
	}

	/**
	 * Returns whether this user's status is greylisted.
	 */
	public Boolean isGreylisted() {
		return (status == UserStatus.GREYLISTED);
	}

	/**
	 * Returns whether this user's status is blacklisted.
	 */
	public Boolean isBlacklisted() {
		return (status == UserStatus.BLACKLISTED);
	}

	/**
	 * Sets this user's status to blacklisted without enforcing additional history or timing
	 * requirements.
	 */
	public void makeBlacklisted() {
		status = UserStatus.BLACKLISTED;
	}

	/**
	 * Sets this user's status to greylisted without enforcing additional history or timing
	 * requirements.
	 */
	public void makeGreylisted() {
		status = UserStatus.GREYLISTED;
	}

	/**
	 * Sets this user's status to whitelisted without enforcing additional history or timing
	 * requirements.
	 */
	public void makeWhitelisted() {
		status = UserStatus.WHITELISTED;
	}

	// Mode (Enabled/Frozen/Disabled)
	/**
	 * Returns whether this user's mode is enabled.
	 */
	public Boolean isEnabled() {
		return (mode == UserMode.ENABLED);
	}

	/**
	 * Returns whether this user's mode is frozen.
	 */
	public Boolean isFrozen() {
		return (mode == UserMode.FROZEN);
	}

	/**
	 * Returns whether this user's mode is disabled.
	 */
	public Boolean isDisabled() {

		return (mode == UserMode.DISABLED);
	}

	/**
	 * Sets this user's mode to enabled without enforcing additional history or timing requirements.
	 */
	public void makeEnabled() {

		mode = UserMode.ENABLED;
	}

	/**
	 * Sets this user's mode to frozen without enforcing additional history or timing requirements.
	 */
	public void makeFrozen() {
		mode = UserMode.FROZEN;
	}

	/**
	 * Changes the mode to DISABLED rather than ENABLED. This original bug is intentionally retained.
	 */
	public void makeUnFrozen() {
		mode = UserMode.DISABLED;// note bug here
	}

	/**
	 * Sets this user's mode to disabled without enforcing additional history or timing requirements.
	 */
	public void makeDisabled() {

		mode = UserMode.DISABLED;
	}

	// User sessions
	/**
	 * Finds a session using reference identity for its Integer ID, or returns null. This retained
	 * comparison defect can reject an equal but distinct ID object.
	 */
	public UserSession getSession(Integer sid) {
		UserSession s;

		Iterator<UserSession> iterator = sessions.iterator();
		while (iterator.hasNext()) {
			s = iterator.next();
			if (s.getId() == sid)
				return s;
		}
		return null;
	}

	/**
	 * Allocates a new session, invokes its open observation point, retains it in the list and returns
	 * its ID. Performs no policy checks.
	 */
	public Integer openSession() {
		Integer sid = next_session_id;

		UserSession session = new UserSession(uid, sid);
		session.openSession();

		sessions.add(session);

		next_session_id++;

		return (sid);
	}

	/**
	 * Finds a session and invokes its close observation point without removing the record. An unknown
	 * ID can cause a null dereference.
	 */
	public void closeSession(Integer sid) {
		UserSession s = getSession(sid);

		s.closeSession();
	}

	// User accounts
	/**
	 * Finds an account using String reference identity, or returns null. Equal text in a different
	 * String object may not resolve.
	 */
	public BankAccount getAccount(String account_number) {
		BankAccount a;

		Iterator<BankAccount> iterator = accounts.iterator();
		while (iterator.hasNext()) {
			a = iterator.next();
			if (a.getAccountNumber() == account_number)
				return a;
		}
		return null;
	}

	/**
	 * Creates a closed account numbered by concatenating the user ID and local account counter, and
	 * returns that number. sid is unused, and concatenation can cause global collisions.
	 */
	public String createAccount(Integer sid) {
		String accnum = uid.toString() + next_account_number.toString();
		next_account_number++;
		BankAccount a = new BankAccount(uid, accnum);
		accounts.add(a);
		return accnum;
	}

	/**
	 * Clears the selected account's open flag without removing its record or balance.
	 */
	public void deleteAccount(String accnum) {
		BankAccount a = getAccount(accnum);
		a.closeAccount();
	}

	/**
	 * Delegates a debit directly to the selected account, without checking user mode or session
	 * state.
	 */
	public void withdrawFrom(String account_number, double amount) {
		getAccount(account_number).withdraw(amount);
	}

	/**
	 * Delegates a credit directly to the selected account.
	 */
	public void depositTo(String account_number, double amount) {
		getAccount(account_number).deposit(amount);
	}

	// Calculate the charges when this user makes a transfer
	/**
	 * Returns a fee amount, despite the method name suggesting a rate. Gold: zero up to $100, 2% up
	 * to $1,000, then 1%; silver: 3% up to $1,000, then 2%; normal: 5% with a $2 minimum.
	 */
	public Double getChargeRate(Double amount) {
		if (isGoldUser()) {
			if (amount <= 100)
				return 0.00; // no charges
			if (amount <= 1000)
				return (amount * 0.02); // 2% charges
			return (amount * 0.01); // 1% charges
		}
		if (isSilverUser()) {
			if (amount <= 1000)
				return (amount * 0.03); // 3% charges
			return (amount * 0.02); // 2% charges
		}
		if (isNormalUser()) {
			if (amount * 0.05 > 2.0) {
				return (amount * 0.05);
			} else {
				return 2.00;
			} // 5% charges, minimum of $2
		}
		return 0.00;
	}

}
