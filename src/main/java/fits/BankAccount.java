package fits;

/**
 * Mutable money account with an owner, number, balance and open flag. Deposits and withdrawals
 * perform arithmetic directly without enforcing the book's financial policies.
 */
public class BankAccount {
	protected Boolean opened;
	protected String account_number;
	protected Double balance;
	protected Integer owner;

	/**
	 * Creates a closed, zero-balance money account belonging to uid and identified by accnum.
	 */
	public BankAccount(Integer uid, String accnum) {
		account_number = accnum;
		balance = 0.00;
		opened = false;
		owner = uid;
	}

	/**
	 * Returns the stored open flag; this flag is not consulted by deposit or withdraw.
	 */
	public Boolean isOpen() {
		return opened;
	}

	/**
	 * Returns the account-number object originally supplied at construction.
	 */
	public String getAccountNumber() {
		return account_number;
	}

	/**
	 * Returns the current balance, including any negative balance permitted by the implementation.
	 */
	public Double getBalance() {
		return balance;
	}

	/**
	 * Returns the owning user's ID.
	 */
	public Integer getOwner() {
		return owner;
	}

	/**
	 * Marks the money account open after administrator approval.
	 */
	public void enableAccount() {
		opened = true;
	}

	/**
	 * Clears the open flag without deleting the account or moving its balance.
	 */
	public void closeAccount() {
		opened = false;
	}

	/**
	 * Subtracts amount directly, without checking funds, sign, account status or user permissions.
	 */
	public void withdraw(Double amount) {
		balance -= amount;
	}

	/**
	 * Adds amount directly, without validating its sign or account status.
	 */
	public void deposit(Double amount) {
		balance += amount;
	}
}
