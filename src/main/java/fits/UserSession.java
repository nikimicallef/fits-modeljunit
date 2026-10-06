package fits;

/**
 * A user-owned login identifier and append-only operation log. Opening and closing are observation
 * points only: this implementation stores no active-session state.
 */
public class UserSession {
	protected Integer sid;
	protected String log;
	protected Integer owner;

	/**
	 * Allocates a session record for uid and sid with an empty log; does not open it.
	 */
	public UserSession(Integer uid, Integer sid) {
		this.sid = sid;
		owner = uid;
		log = "";
	}

	/**
	 * Returns this session's ID.
	 */
	public Integer getId() {
		return sid;
	}

	/**
	 * Returns the owning user's ID.
	 */
	public Integer getOwner() {
		return owner;
	}

	/**
	 * Returns accumulated operation messages separated by newlines.
	 */
	public String getLog() {
		return log;
	}

	/**
	 * Session-opening observation point. The original method is empty and records no active state.
	 */
	public void openSession() {
	}

	/**
	 * Appends a message and newline, even if the session has previously received a close call.
	 */
	public void log(String l) {
		log += l + "\n";
	}

	/**
	 * Session-closing observation point. The original method is empty and does not prevent later
	 * logging.
	 */
	public void closeSession() {
	}

}
