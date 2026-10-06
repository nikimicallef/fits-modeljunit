package fits;

/**
 * Owns the in-memory FITS backend and the frontend API connected to it. Construction resets the
 * objects but does not perform administrator initialisation.
 */
public class TransactionSystem {

	private FrontEnd frontend;
	private BackEnd backend;

	/**
	 * Creates a fresh backend and connected frontend by calling setup().
	 */
	public TransactionSystem() {
		setup();
	}

	/**
	 * Returns the administrator/user API connected to this system.
	 */
	public FrontEnd getFrontEnd() {
		return frontend;
	}

	/**
	 * Returns the in-memory user store owned by this system.
	 */
	public BackEnd getBackEnd() {
		return backend;
	}

	/**
	 * Replaces both components with empty instances; callers must initialise the backend separately.
	 */
	public void setup() {
		backend = new BackEnd();
		frontend = new FrontEnd(backend);
	}
}
