package io.github.dgproman.pihome.hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the app asks of one hub, as the person signed in with [token].
 *
 * Holds no state between calls: a new token means a new client. Every call
 * suspends, can be cancelled, and fails only with [HubException]. The routes a
 * sensor or a device reports to, and the hub's API keys, are not here and never
 * will be. This is a client for people.
 *
 * [http] is shared across the app so connections are reused. The client derives
 * its own settings from it and never changes it.
 */
class HubClient internal constructor(
    val address: HubAddress,
    private val token: SessionToken?,
    http: OkHttpClient,
    timeouts: Timeouts,
) {
    constructor(address: HubAddress, token: SessionToken?, http: OkHttpClient) :
        this(address, token, http, Timeouts())

    private val transport = Transport(address, http, timeouts)

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
    suspend fun readSession(): Session? =
        try {
            read(SESSION) { it.decode(Session.serializer()) }
        } catch (e: HubException) {
            if (e.kind == HubErrorKind.UNAUTHORIZED) null else throw e
        }

    /**
     * Open a session with a username and password.
     *
     * [HubErrorKind.UNAUTHORIZED] for every wrong answer alike, because the hub
     * does not say whether the name, the password or the account was the problem.
     */
    suspend fun logIn(
        username: String,
        password: String,
    ): SignedIn =
        write(
            Method.POST,
            SESSION,
            buildJsonObject {
                put("username", username)
                put("password", password)
            },
        ) { it.signedIn() }

    /**
     * Open a session with an invitation, which spends it.
     *
     * Sent once and never again, whatever happens: the hub spends an invitation
     * even on an attempt it refuses. A [HubErrorKind.TIMEOUT] here means the hub
     * may have taken it, and the person will need a new one rather than another
     * try. A refusal is [HubErrorKind.UNAUTHORIZED] for every reason, as on the
     * hub, including a token too mangled to read.
     */
    suspend fun join(invitation: InvitationToken): SignedIn =
        try {
            write(Method.POST, SESSION, buildJsonObject { put("invitation", invitation.value) }) {
                it.signedIn()
            }
        } catch (e: HubException) {
            // The hub reads no token longer than it could have issued, so a 422 is a
            // link damaged on its way here: to whoever holds it, one that ran out.
            if (e.kind == HubErrorKind.MALFORMED) {
                throw HubException(HubErrorKind.UNAUTHORIZED, "the hub refused the invitation as malformed", e.status, e)
            }
            throw e
        }

    /** End this session on the hub. Done already, when the hub no longer knows it. */
    suspend fun logOut() {
        try {
            write(Method.DELETE, SESSION, null) {}
        } catch (e: HubException) {
            if (e.kind != HubErrorKind.UNAUTHORIZED) throw e
        }
    }

    suspend fun relays(): List<Relay> = read(RELAYS) { it.decode(RelayList.serializer()).relays }

    /**
     * Drive one relay to a state, and return it as the hub reports it afterwards.
     *
     * The state is named rather than toggled, so a request that arrives twice
     * leaves the circuit where it was asked to be.
     */
    suspend fun setRelay(
        id: String,
        on: Boolean,
    ): Relay = write(Method.PUT, RELAYS + id, buildJsonObject { put("on", on) }) { it.decode(Relay.serializer()) }

    /** Drive every relay to one state. Named rather than toggled, as for one. */
    suspend fun setAllRelays(on: Boolean): List<Relay> =
        write(Method.PUT, RELAYS, buildJsonObject { put("on", on) }) {
            it.decode(RelayList.serializer()).relays
        }

    suspend fun sensors(): List<Sensor> =
        read(listOf("v1", "sensors")) { reply ->
            reply.parse { text -> HubJson.decodeFromString(SensorList.serializer(), text).sensors.map(::sensorFrom) }
        }

    suspend fun devices(): List<Device> = read(listOf("v1", "devices")) { it.decode(DeviceList.serializer()).devices }

    suspend fun rules(): List<AutomationRule> = read(listOf("v1", "automation", "rules")) { it.decode(RuleList.serializer()).rules }

    /** Every account. Admins only, by session only. */
    suspend fun accounts(): List<Account> = read(USERS) { it.decode(AccountList.serializer()).users }

    /**
     * Make an account with no password, for somebody to join by invitation.
     *
     * [HubErrorKind.CONFLICT] when the name is taken, ignoring case, and
     * [HubErrorKind.MALFORMED] when the hub does not accept it as a name.
     */
    suspend fun createAccount(
        username: String,
        role: ManagedRole,
    ): Account =
        write(
            Method.POST,
            USERS,
            buildJsonObject {
                put("username", username)
                put("role", role.wire)
            },
        ) { it.decode(Account.serializer()) }

    /** Move an account between roles, or block or unblock it. Not for an admin account. */
    suspend fun changeAccount(
        username: String,
        change: AccountChange,
    ): Account =
        write(
            Method.PATCH,
            USERS + username,
            buildJsonObject {
                change.role?.let { put("role", it.wire) }
                change.disabled?.let { put("disabled", it) }
            },
        ) { it.decode(Account.serializer()) }

    /** Delete an account. Its sessions and its invitation go with it. */
    suspend fun deleteAccount(username: String) {
        write(Method.DELETE, USERS + username, null) {}
    }

    /**
     * Issue a one-time invitation to an account, replacing any it had.
     *
     * [HubErrorKind.CONFLICT] when the account is disabled.
     */
    suspend fun issueInvitation(username: String): Invitation =
        write(Method.POST, USERS + username + INVITATION, null) { it.decode(Invitation.serializer()) }

    /** Withdraw an account's invitation. There being none is not a failure. */
    suspend fun revokeInvitation(username: String) {
        write(Method.DELETE, USERS + username + INVITATION, null) {}
    }

    /**
     * A read, asked again if it failed in a way that might pass.
     *
     * At most twice more, a second and then two apart, as in the web client. A
     * session refused is not asked again: the hub counts each refusal against
     * this address and locks it out after a few.
     */
    private suspend fun <T> read(
        path: List<String>,
        decode: (Reply) -> T,
    ): T =
        guarded {
            for (pause in RETRY_DELAYS) {
                try {
                    return@guarded decode(transport.send(Method.GET, address.url(path), null, token))
                } catch (e: HubException) {
                    if (!e.kind.worthRetrying) throw e
                }
                delay(pause)
            }
            decode(transport.send(Method.GET, address.url(path), null, token))
        }

    /** A write, sent once. See [Transport.send]. */
    private suspend fun <T> write(
        method: Method,
        path: List<String>,
        body: JsonObject?,
        decode: (Reply) -> T,
    ): T = guarded { decode(transport.send(method, address.url(path), body, token)) }

    /**
     * Hold every call to one failure type, so the app has one thing to catch.
     *
     * Anything else thrown in here is a fault in this client, and says so.
     * Cancellation passes untouched: it is the caller's own doing.
     */
    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block()
        } catch (e: HubException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HubException(HubErrorKind.UNEXPECTED, "fault in the hub client", cause = e)
        }

    private companion object {
        val SESSION = listOf("v1", "session")
        val RELAYS = listOf("v1", "relays")
        val USERS = listOf("v1", "users")
        const val INVITATION = "invitation"
        val RETRY_DELAYS = listOf(1.seconds, 2.seconds)
    }
}

private fun <T> Reply.decode(deserializer: DeserializationStrategy<T>): T = parse { HubJson.decodeFromString(deserializer, it) }

/** Read a successful reply with [read], or raise [HubErrorKind.UNREADABLE]. */
private fun <T> Reply.parse(read: (String) -> T): T {
    val text = body ?: throw HubException(HubErrorKind.UNREADABLE, "${url.encodedPath} sent no body", status)
    return try {
        read(text)
    } catch (cause: IllegalArgumentException) {
        // Serialization's own failures are among these, and so is a model
        // refusing a value it was built with.
        throw HubException(HubErrorKind.UNREADABLE, "${url.encodedPath} sent a reply of the wrong shape", status, cause)
    }
}

/** A session just opened: who it is from the body, and the token from the cookie. */
private fun Reply.signedIn(): SignedIn {
    val session = decode(Session.serializer())
    val cookie =
        Cookie.parseAll(url, headers).lastOrNull { it.name == SESSION_COOKIE && it.value.isNotBlank() }
            ?: throw HubException(HubErrorKind.UNREADABLE, "${url.encodedPath} set no session cookie", status)
    return SignedIn(session, SessionToken(cookie.value))
}

@Serializable
private class RelayList(
    val relays: List<Relay>,
)

@Serializable
private class SensorList(
    val sensors: List<JsonObject>,
)

@Serializable
private class DeviceList(
    val devices: List<Device>,
)

@Serializable
private class RuleList(
    val rules: List<AutomationRule>,
)

@Serializable
private class AccountList(
    val users: List<Account>,
)
