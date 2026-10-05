package io.github.dgproman.pihome

import android.content.Context
import io.github.dgproman.pihome.connect.InvitationScanner
import io.github.dgproman.pihome.connect.LocalNetwork
import io.github.dgproman.pihome.connect.Scan
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
 * Only the calls the app makes so far are answered; anything else fails the test.
 */
class FakeHubs : Hubs {
    var health: suspend (HubAddress) -> Unit = {}
    var readSession: suspend (SessionToken?) -> Session? = { null }
    var logIn: suspend (String, String) -> SignedIn = { _, _ -> throw failure(HubErrorKind.UNAUTHORIZED) }
    var join: suspend (InvitationToken) -> SignedIn = { throw failure(HubErrorKind.UNAUTHORIZED) }
    var logOut: suspend (SessionToken?) -> Unit = {}

    /** Every call, in order, as `what address`. */
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

            override suspend fun relays(): List<Relay> = unexpected()

            override suspend fun setRelay(
                id: String,
                on: Boolean,
            ): Relay = unexpected()

            override suspend fun setAllRelays(on: Boolean): List<Relay> = unexpected()

            override suspend fun sensors(): List<Sensor> = unexpected()

            override suspend fun devices(): List<Device> = unexpected()

            override suspend fun rules(): List<AutomationRule> = unexpected()

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

/** A scanner that reads whatever the test holds up to it. */
class FakeScanner(
    var next: Scan = Scan.Cancelled,
) : InvitationScanner {
    override suspend fun scan(context: Context): Scan = next
}

/** A clock a test moves by hand. */
class TestClock(
    var now: Instant = Instant.parse("2026-10-05T12:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}
