package io.github.dgproman.pihome

import io.github.dgproman.pihome.connect.LocalNetwork
import io.github.dgproman.pihome.hub.Account
import io.github.dgproman.pihome.hub.AccountChange
import io.github.dgproman.pihome.hub.AutomationRule
import io.github.dgproman.pihome.hub.Device
import io.github.dgproman.pihome.hub.Hub
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.HubException
import io.github.dgproman.pihome.hub.Invitation
import io.github.dgproman.pihome.hub.InvitationToken
import io.github.dgproman.pihome.hub.ManagedRole
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Sensor
import io.github.dgproman.pihome.hub.Session
import io.github.dgproman.pihome.hub.SessionToken
import io.github.dgproman.pihome.hub.SignedIn
import io.github.dgproman.pihome.quick.LightsChoice
import io.github.dgproman.pihome.quick.LightsChoices
import io.github.dgproman.pihome.quick.RelayNews
import io.github.dgproman.pihome.quick.RelayShortcut
import io.github.dgproman.pihome.quick.ShortcutShelf
import io.github.dgproman.pihome.quick.TileChoice
import io.github.dgproman.pihome.quick.TileChoices
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.widget.WidgetHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A failure as the hub client raises it. */
fun failure(kind: HubErrorKind): HubException = HubException(kind, "a failure made up by a test")

/**
 * Hubs that answer what a test sets, and remember what they were asked.
 *
 * Each answer is a function, so a test can fail it, hold it back, or count it.
 * The house and the list of accounts are empty until a test says otherwise,
 * and a write the test has not set up fails as the hub would fail it.
 */
class FakeHubs : Hubs {
    var health: suspend (HubAddress) -> Unit = {}
    var readSession: suspend (SessionToken?) -> Session? = { null }
    var logIn: suspend (String, String) -> SignedIn = { _, _ -> throw failure(HubErrorKind.UNAUTHORIZED) }
    var join: suspend (InvitationToken) -> SignedIn = { throw failure(HubErrorKind.UNAUTHORIZED) }
    var logOut: suspend (SessionToken?) -> Unit = {}
    var relays: suspend () -> List<Relay> = { emptyList() }
    var setRelay: suspend (String, Boolean) -> Relay = { _, _ -> throw failure(HubErrorKind.NOT_FOUND) }
    var setAllRelays: suspend (Boolean) -> List<Relay> = { emptyList() }
    var sensors: suspend () -> List<Sensor> = { emptyList() }
    var devices: suspend () -> List<Device> = { emptyList() }
    var rules: suspend () -> List<AutomationRule> = { emptyList() }
    var accounts: suspend () -> List<Account> = { emptyList() }
    var createAccount: suspend (String, ManagedRole) -> Account = { _, _ -> throw failure(HubErrorKind.CONFLICT) }
    var changeAccount: suspend (String, AccountChange) -> Account = { _, _ -> throw failure(HubErrorKind.NOT_FOUND) }
    var deleteAccount: suspend (String) -> Unit = { throw failure(HubErrorKind.NOT_FOUND) }
    var issueInvitation: suspend (String) -> Invitation = { throw failure(HubErrorKind.NOT_FOUND) }
    var revokeInvitation: suspend (String) -> Unit = {}

    /** Every call, in order: `what address` for the ways in, and `what` for the house and the people. */
    val calls = mutableListOf<String>()

    override fun at(
        address: HubAddress,
        token: SessionToken?,
    ): Hub =
        object : Hub {
            override val address = address

            override suspend fun checkHealth() {
                calls += "health $address"
                health(address)
            }

            override suspend fun readSession(): Session? {
                calls += "session $address"
                return this@FakeHubs.readSession(token)
            }

            override suspend fun logIn(
                username: String,
                password: String,
            ): SignedIn {
                calls += "login $address"
                return this@FakeHubs.logIn(username, password)
            }

            override suspend fun join(invitation: InvitationToken): SignedIn {
                calls += "join $address"
                return this@FakeHubs.join(invitation)
            }

            override suspend fun logOut() {
                calls += "logout $address"
                this@FakeHubs.logOut(token)
            }

            override suspend fun relays(): List<Relay> {
                calls += "relays"
                return this@FakeHubs.relays()
            }

            override suspend fun setRelay(
                id: String,
                on: Boolean,
            ): Relay {
                calls += "set $id $on"
                return this@FakeHubs.setRelay(id, on)
            }

            override suspend fun setAllRelays(on: Boolean): List<Relay> {
                calls += "set all $on"
                return this@FakeHubs.setAllRelays(on)
            }

            override suspend fun sensors(): List<Sensor> {
                calls += "sensors"
                return this@FakeHubs.sensors()
            }

            override suspend fun devices(): List<Device> {
                calls += "devices"
                return this@FakeHubs.devices()
            }

            override suspend fun rules(): List<AutomationRule> {
                calls += "rules"
                return this@FakeHubs.rules()
            }

            override suspend fun accounts(): List<Account> {
                calls += "accounts"
                return this@FakeHubs.accounts()
            }

            override suspend fun createAccount(
                username: String,
                role: ManagedRole,
            ): Account {
                calls += "create $username $role"
                return this@FakeHubs.createAccount(username, role)
            }

            override suspend fun changeAccount(
                username: String,
                change: AccountChange,
            ): Account {
                calls +=
                    "change $username ${listOfNotNull(
                        change.role,
                        change.disabled?.let { if (it) "disabled" else "enabled" },
                    ).joinToString(" ")}"
                return this@FakeHubs.changeAccount(username, change)
            }

            override suspend fun deleteAccount(username: String) {
                calls += "delete $username"
                this@FakeHubs.deleteAccount(username)
            }

            override suspend fun issueInvitation(username: String): Invitation {
                calls += "invite $username"
                return this@FakeHubs.issueInvitation(username)
            }

            override suspend fun revokeInvitation(username: String) {
                calls += "withdraw $username"
                this@FakeHubs.revokeInvitation(username)
            }
        }
}

/** Android's local network rule, with the answers a test sets. */
class FakeLocalNetwork(
    /** Whether the hubs in the test are on the local network. */
    var local: Boolean = false,
    var granted: Boolean = false,
) : LocalNetwork {
    override suspend fun mustAsk(address: HubAddress): Boolean = local && !granted
}

/** The tile's relay, kept in memory. */
class FakeTileChoices(
    initial: TileChoice? = null,
) : TileChoices {
    private val held = MutableStateFlow(initial)

    override val choice: Flow<TileChoice?> = held

    override suspend fun choose(choice: TileChoice?) {
        held.value = choice
    }
}

/** The All lights shortcut's relays, kept in memory. */
class FakeLightsChoices(
    initial: LightsChoice? = null,
) : LightsChoices {
    private val held = MutableStateFlow(initial)

    val current: LightsChoice? get() = held.value

    override val choice: Flow<LightsChoice?> = held

    override suspend fun choose(choice: LightsChoice?) {
        held.value = choice
    }
}

/** The launcher's shortcuts, kept in memory. */
class FakeShortcutShelf : ShortcutShelf {
    /** Android would not change them, as when an app changes them too often. */
    var refuse = false
    var shown: List<RelayShortcut> = emptyList()
    var puts = 0

    override fun put(shortcuts: List<RelayShortcut>): Boolean {
        puts++
        if (refuse) return false
        shown = shortcuts
        return true
    }
}

/** The home-screen widgets, with the answers a test sets. */
class FakeWidgetHost(
    /** Whether a widget is on the home screen. */
    var placed: Boolean = true,
) : WidgetHost {
    var redraws = 0

    override suspend fun placed(): Boolean = placed

    override suspend fun redraw() {
        redraws++
    }
}

/** Everything the hub was heard to say of its relays, in order. */
class HeardNews : RelayNews {
    val heard = mutableListOf<String>()

    override fun all(
        saved: SavedSession,
        relays: List<Relay>,
    ) {
        heard += "all " + relays.joinToString(" ") { "${it.id}=${it.on}" }
    }

    override fun one(
        saved: SavedSession,
        relay: Relay,
    ) {
        heard += "one ${relay.id}=${relay.on}"
    }
}

/** A clock a test moves by hand. */
class TestClock(
    var now: Instant = Instant.parse("2026-10-05T12:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}
