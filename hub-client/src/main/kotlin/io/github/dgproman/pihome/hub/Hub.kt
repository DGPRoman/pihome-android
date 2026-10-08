package io.github.dgproman.pihome.hub

/**
 * Everything the app asks of one hub, as the person signed in with one token.
 *
 * Every call suspends, can be cancelled, and fails only with [HubException].
 * The routes a sensor or a device reports to, and the hub's API keys, are not
 * here and never will be. This is a client for people.
 *
 * [HubClient] is the one that talks to a hub. An interface so that the app can
 * be tested against a hub that answers whatever a test needs.
 */
interface Hub {
    val address: HubAddress

    /**
     * Whether a hub answers at [address]: `GET /health`, asked once.
     *
     * Asked before anything with a secret in it is sent, so that a typing mistake
     * or a network's sign-in page is caught before an invitation or a password
     * goes to it. [HubErrorKind.NOT_THE_HUB] for anything that answers and is not
     * a hub. Not asked again on failure: the person is waiting, and decides.
     */
    suspend fun checkHealth()

    /**
     * Who the token belongs to, or null when the hub does not accept it.
     *
     * Null rather than a failure, because being signed out is an ordinary answer
     * to this question. Any other failure still raises: a hub that is not
     * answering has not said that nobody is signed in.
     *
     * Called when the app starts and when it comes back, and that is what renews
     * the session: asked from the home network, and a day or more since it was
     * last renewed, the hub moves the expiry a full lifetime on. The token stays
     * the same, and the new expiry is in what this returns.
     */
    suspend fun readSession(): Session?

    /**
     * Open a session with a username and password.
     *
     * [HubErrorKind.UNAUTHORIZED] for every wrong answer alike, because the hub
     * does not say whether the name, the password or the account was the problem.
     */
    suspend fun logIn(
        username: String,
        password: String,
    ): SignedIn

    /**
     * Open a session with an invitation, which spends it.
     *
     * Sent once and never again, whatever happens: the hub spends an invitation
     * even on an attempt it refuses. A [HubErrorKind.TIMEOUT] here means the hub
     * may have taken it, and the person will need a new one rather than another
     * try. A refusal is [HubErrorKind.UNAUTHORIZED] for every reason, as on the
     * hub, including a token too mangled to read.
     */
    suspend fun join(invitation: InvitationToken): SignedIn

    /** End this session on the hub. Done already, when the hub no longer knows it. */
    suspend fun logOut()

    suspend fun relays(): List<Relay>

    /**
     * Drive one relay to a state, and return it as the hub reports it afterwards.
     *
     * The state is named rather than toggled, so a request that arrives twice
     * leaves the circuit where it was asked to be.
     */
    suspend fun setRelay(
        id: String,
        on: Boolean,
    ): Relay

    /** Drive every relay to one state. Named rather than toggled, as for one. */
    suspend fun setAllRelays(on: Boolean): List<Relay>

    /**
     * Let the hub's automation switch one relay, or stop it from doing so, and
     * return the relay as the hub reports it afterwards.
     *
     * Turning it off also switches the relay off and cancels any rule's timer on
     * it, and lasts until it is turned back on. Turning it on switches nothing.
     * [HubErrorKind.NOT_FOUND] from a hub too old to have the route, as well as
     * for a relay it does not know.
     */
    suspend fun setAutomatic(
        id: String,
        automatic: Boolean,
    ): Relay

    suspend fun sensors(): List<Sensor>

    suspend fun devices(): List<Device>

    suspend fun rules(): List<AutomationRule>

    /** Every account. Admins only, by session only. */
    suspend fun accounts(): List<Account>

    /**
     * Make an account with no password, for somebody to join by invitation.
     *
     * [HubErrorKind.CONFLICT] when the name is taken, ignoring case, and
     * [HubErrorKind.MALFORMED] when the hub does not accept it as a name.
     */
    suspend fun createAccount(
        username: String,
        role: ManagedRole,
    ): Account

    /** Move an account between roles, or block or unblock it. Not for an admin account. */
    suspend fun changeAccount(
        username: String,
        change: AccountChange,
    ): Account

    /** Delete an account. Its sessions and its invitation go with it. */
    suspend fun deleteAccount(username: String)

    /**
     * Issue a one-time invitation to an account, replacing any it had.
     *
     * [HubErrorKind.CONFLICT] when the account is disabled.
     */
    suspend fun issueInvitation(username: String): Invitation

    /** Withdraw an account's invitation. There being none is not a failure. */
    suspend fun revokeInvitation(username: String)
}
