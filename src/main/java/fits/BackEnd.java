package fits;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Stores registered users and allocates their IDs. Administrator initialisation clears existing
 * users and installs a default administrator; no database is used.
 */
public class BackEnd {
	protected Boolean initialised;
	protected ArrayList<UserInfo> users;
	protected Integer next_user_id;

	// Constructor
	/**
	 * Creates an empty, uninitialised user store with its first user ID set to one.
	 */
	public BackEnd() {
		users = new ArrayList<UserInfo>();
		next_user_id = 1;
		initialised = false;
	}

	// Get the users currently registered in the system
	/**
	 * Returns the live mutable user list, not a defensive copy.
	 */
	public ArrayList<UserInfo> getUsers() {
		return users;
	}

	// Initialise the transaction system with a single admin user
	// called Clark Kent from Malta
	/**
	 * Clears users, restarts IDs at zero and creates enabled silver administrator Clark Kent from
	 * Malta.
	 */
	public void initialise() {
		users = new ArrayList<UserInfo>();
		next_user_id = 0;
		initialised = true;

		Integer admin_uid = addUser("Clark Kent", "Malta");
		UserInfo admin = getUserInfo(admin_uid);

		admin.makeSilverUser();
		admin.makeEnabled();
	}

	// Lookup a user by user-id
	/**
	 * Returns the user whose ID object is identical to uid, or null. The original reference
	 * comparison is retained; equal numeric values are not always sufficient.
	 */
	public UserInfo getUserInfo(Integer uid) {
		UserInfo u;

		Iterator<UserInfo> iterator = users.iterator();
		while (iterator.hasNext()) {
			u = iterator.next();
			if (u.getId() == uid)
				return u;
		}
		return null;
	}

	// Add a user to the system
	/**
	 * Adds a newly constructed user and returns its allocated ID. The user initially starts disabled,
	 * whitelisted and normal.
	 */
	public Integer addUser(String name, String country) {
		Integer uid = next_user_id;
		next_user_id++;

		users.add(new UserInfo(uid, name, country));
		return uid;
	}
}
