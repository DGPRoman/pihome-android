package io.github.dgproman.pihome.quick

import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeLocalNetwork
import io.github.dgproman.pihome.FakeShortcutShelf
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.session.FakeCipher
import io.github.dgproman.pihome.session.Gate
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.session.SessionGate
import io.github.dgproman.pihome.session.SessionStore
import io.github.dgproman.pihome.session.sessionData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShortcutModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { SessionStore(folder.sessionData(), FakeCipher()) }
    private val hubs = FakeHubs()
    private val network = FakeLocalNetwork()
    private val shelf = FakeShortcutShelf()
    private val porch = Relay("porch", "Porch light", on = false)
    private val gateLight = Relay("gate", "Gate", on = true)
    private val porchShortcut = ShortcutRequest.Switch(RelayShortcut(HOME.address.origin, "porch", "Porch light"))

    /** What the hub holds: each write changes it, each read reports it. */
    private var onHub = listOf(porch, gateLight)

    private lateinit var gate: SessionGate

    init {
        hubs.relays = { onHub }
        hubs.setRelay = { id, on ->
            onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
            onHub.first { it.id == id }
        }
        hubs.setAllRelays = { on ->
            onHub = onHub.map { it.copy(on = on) }
            onHub
        }
    }

    private suspend fun TestScope.model(saved: SavedSession? = HOME): ShortcutModel {
        if (saved != null) store.save(saved)
        gate = SessionGate(store, backgroundScope, hubs)
        gate.state.first { it != Gate.Loading }
        return ShortcutModel(QuickActions(gate, network, hubs), RelayShortcuts(shelf))
    }

    @Test
    fun `a relay's shortcut switches it to the state it is not in`() =
        runTest {
            val model = model()

            assertEquals(ShortcutResult.Switched("Porch light", on = true), model.run(porchShortcut))
            assertEquals(ShortcutResult.Switched("Porch light", on = false), model.run(porchShortcut))
            assertEquals(listOf("relays", "set porch true", "relays", "set porch false"), hubs.calls)
        }

    @Test
    fun `the relays it reads keep the launcher's list in step`() =
        runTest {
            val model = model()

            model.run(porchShortcut)

            assertEquals(listOf("porch", "gate"), shelf.shown.map { it.relayId })
        }

    @Test
    fun `a shortcut to a relay that has gone says so, switches nothing, and is no longer offered`() =
        runTest {
            onHub = listOf(gateLight)
            val model = model()

            assertEquals(ShortcutResult.Gone("Porch light"), model.run(porchShortcut))
            assertEquals(listOf("relays"), hubs.calls)
            assertEquals(listOf("gate"), shelf.shown.map { it.relayId })
        }

    @Test
    fun `a shortcut made for another hub switches nothing on this one`() =
        runTest {
            val model = model()
            val elsewhere = ShortcutRequest.Switch(RelayShortcut("http://192.168.1.50:5002", "porch", "Porch light"))

            assertEquals(ShortcutResult.Gone("Porch light"), model.run(elsewhere))
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `a relay removed between the read and the switch has gone`() =
        runTest {
            hubs.setRelay = { _, _ -> throw failure(HubErrorKind.NOT_FOUND) }
            val model = model()

            assertEquals(ShortcutResult.Gone("Porch light"), model.run(porchShortcut))
        }

    @Test
    fun `all off switches every relay off`() =
        runTest {
            val model = model()

            assertEquals(ShortcutResult.AllOff, model.run(ShortcutRequest.AllOff))
            assertEquals(listOf("set all false"), hubs.calls)
            assertEquals(listOf("porch", "gate"), shelf.shown.map { it.relayId })
        }

    @Test
    fun `a write with no answer in time may have happened, and says so`() =
        runTest {
            hubs.setRelay = { _, _ -> awaitCancellation() }
            hubs.setAllRelays = { awaitCancellation() }
            val model = model()

            val switch = async { model.run(porchShortcut) }
            val allOff = async { model.run(ShortcutRequest.AllOff) }
            advanceTimeBy(QuickActions.DEADLINE.toMillis() * 2 + 1)

            assertEquals(ShortcutResult.Unsure("Porch light"), switch.await())
            assertEquals(ShortcutResult.Unsure(null), allOff.await())
        }

    @Test
    fun `a hub that cannot be read is a failure, and nothing is switched`() =
        runTest {
            hubs.relays = { throw failure(HubErrorKind.OFFLINE) }
            val model = model()

            assertEquals(ShortcutResult.Failed(HubErrorKind.OFFLINE), model.run(porchShortcut))
            assertEquals(listOf("relays"), hubs.calls)
        }

    @Test
    fun `a refused switch is a failure`() =
        runTest {
            hubs.setAllRelays = { throw failure(HubErrorKind.SERVER) }
            val model = model()

            assertEquals(ShortcutResult.Failed(HubErrorKind.SERVER), model.run(ShortcutRequest.AllOff))
        }

    @Test
    fun `with no session, a viewer's or no leave to reach the network, the app opens and nothing is sent`() =
        runTest {
            assertEquals(ShortcutResult.OpenApp, model(saved = null).run(ShortcutRequest.AllOff))
            assertEquals(ShortcutResult.OpenApp, model(HOME.copy(session = HOME.session.copy(role = Role.VIEWER))).run(porchShortcut))
            network.local = true
            assertEquals(ShortcutResult.OpenApp, model().run(porchShortcut))
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `a refused session ends it for the whole app, and the app opens to say so`() =
        runTest {
            hubs.relays = { throw failure(HubErrorKind.UNAUTHORIZED) }
            val model = model()

            assertEquals(ShortcutResult.OpenApp, model.run(porchShortcut))
            assertEquals(Gate.Ended, gate.state.value)
        }

    @Test
    fun `an account no longer allowed to switch opens the app`() =
        runTest {
            hubs.setRelay = { _, _ -> throw failure(HubErrorKind.FORBIDDEN) }
            val model = model()

            assertEquals(ShortcutResult.OpenApp, model.run(porchShortcut))
        }
}
