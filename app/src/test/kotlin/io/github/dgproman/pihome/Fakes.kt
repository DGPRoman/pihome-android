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
 * The house is empty until a test says otherwise. Only the calls the app makes
 * so far are answered; anything else fails the test.
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

    /** Every call, in order: `what address` for the ways in, and `what` for the house. */
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

            override suspend fun accounts(): List<Account> = unexpected()

            override suspend fun createAccount(
                username: String,
                role: ManagedRole,
            ): Account = unexpected()

            override suspend fun changeAccount(
                username: String,
                change: AccountChange,
            ): Account = unexpected()

            override suspend fun deleteAccount(username: String) = unexpected()

            override suspend fun issueInvitation(username: String): Invitation = unexpected()

            override suspend fun revokeInvitation(username: String) = unexpected()

            private fun unexpected(): Nothing = throw AssertionError("the app asked the hub something this test does not expect")
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

/** A clock a test moves by hand. */
class TestClock(
    var now: Instant = Instant.parse("2026-10-05T12:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}
