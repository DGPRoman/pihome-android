package io.github.dgproman.pihome.quick

import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeLocalNetwork
import io.github.dgproman.pihome.FakeTileChoices
import io.github.dgproman.pihome.HeardNews
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TileModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { SessionStore(folder.sessionData(), FakeCipher()) }
    private val hubs = FakeHubs()
    private val network = FakeLocalNetwork()
    private val porch = Relay("porch", "Porch light", on = false)
    private val choices = FakeTileChoices(TileChoice(HOME.address.origin, "porch", "Porch light"))
    private var redrawn = 0
    private val news = HeardNews()

    /** What the hub holds: each write changes it, each read reports it. */
    private var onHub = listOf(porch)

    private lateinit var gate: SessionGate

    init {
        hubs.relays = { onHub }
        hubs.setRelay = { id, on ->
            onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
            onHub.first { it.id == id }
        }
    }

    private suspend fun TestScope.model(saved: SavedSession? = HOME): TileModel {
        if (saved != null) store.save(saved)
        gate = SessionGate(store, backgroundScope, hubs)
        gate.state.first { it != Gate.Loading }
        return TileModel(QuickActions(gate, network, hubs), choices, backgroundScope, news) { redrawn++ }
    }

    @Test
    fun `opening the shade reads the chosen relay`() =
        runTest {
            val model = model()

            model.refresh()
            runCurrent()

            assertEquals(TileLook.Showing("Porch light", on = false), model.state.value)
            assertEquals(listOf("relays"), hubs.calls)
        }

    @Test
    fun `a tap switches the relay to the other state, and shows what the hub answered`() =
        runTest {
            val model = model()
            model.refresh()
            runCurrent()
            val answer = CompletableDeferred<Unit>()
            hubs.setRelay = { id, on ->
                answer.await()
                onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
                onHub.first { it.id == id }
            }

            assertEquals(Tap.HANDLED, model.tap())
            runCurrent()
            assertEquals(TileLook.Switching("Porch light", to = true), model.state.value)
            // Pressed again while it is switching: nothing more is sent.
            model.tap()

            answer.complete(Unit)
            runCurrent()
            assertEquals(TileLook.Showing("Porch light", on = true), model.state.value)
            assertEquals(listOf("relays", "set porch true"), hubs.calls)
            assertEquals(1, redrawn)
        }

    @Test
    fun `a refused switch says so, and a tap tries it again`() =
        runTest {
            val model = model()
            model.refresh()
            runCurrent()
            val set = hubs.setRelay
            hubs.setRelay = { _, _ -> throw failure(HubErrorKind.SERVER) }

            model.tap()
            runCurrent()
            assertEquals(TileLook.Failed("Porch light", on = false, HubErrorKind.SERVER), model.state.value)

            hubs.setRelay = set
            model.tap()
            runCurrent()
            assertEquals(TileLook.Showing("Porch light", on = true), model.state.value)
        }

    @Test
    fun `a switch with no answer in time is in doubt, and a tap reads the hub rather than guessing`() =
        runTest {
            val model = model()
            model.refresh()
            runCurrent()
            hubs.setRelay = { _, _ -> awaitCancellation() }

            model.tap()
            advanceTimeBy(QuickActions.DEADLINE.toMillis() + 1)
            runCurrent()
            assertEquals(TileLook.Unsure("Porch light", to = true), model.state.value)

            model.tap()
            runCurrent()
            assertEquals("relays", hubs.calls.last())
            assertEquals(TileLook.Showing("Porch light", on = false), model.state.value)
        }

    @Test
    fun `a hub that cannot be read says so, and a tap reads it again`() =
        runTest {
            hubs.relays = { throw failure(HubErrorKind.OFFLINE) }
            val model = model()

            model.refresh()
            runCurrent()
            assertEquals(TileLook.Failed("Porch light", on = null, HubErrorKind.OFFLINE), model.state.value)

            hubs.relays = { onHub }
            model.tap()
            runCurrent()
            assertEquals(TileLook.Showing("Porch light", on = false), model.state.value)
            assertEquals(listOf("relays", "relays"), hubs.calls)
        }

    @Test
    fun `with no session the tile cannot act, and a tap opens the app`() =
        runTest {
            val model = model(saved = null)

            model.refresh()
            runCurrent()

            assertEquals(TileLook.CannotAct("Porch light", TileLook.Reason.NOT_SIGNED_IN), model.state.value)
            assertEquals(Tap.OPEN_APP, model.tap())
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `a viewer's tile cannot act, and asks the hub nothing`() =
        runTest {
            val model = model(HOME.copy(session = HOME.session.copy(role = Role.VIEWER)))

            model.refresh()
            runCurrent()

            assertEquals(TileLook.CannotAct("Porch light", TileLook.Reason.READ_ONLY), model.state.value)
            assertEquals(Tap.OPEN_APP, model.tap())
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `without leave to reach the network the tile sends nothing, since the request would only hang`() =
        runTest {
            network.local = true
            val model = model()

            model.refresh()
            runCurrent()

            assertEquals(TileLook.CannotAct("Porch light", TileLook.Reason.NEEDS_PERMISSION), model.state.value)
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `with no relay chosen, or one chosen on another hub, the tile asks for one`() =
        runTest {
            choices.choose(TileChoice("http://192.168.1.50:5002", "porch", "Porch light"))
            val model = model()

            model.refresh()
            runCurrent()

            assertEquals(TileLook.CannotAct(null, TileLook.Reason.NOT_CHOSEN), model.state.value)
            assertEquals(Tap.OPEN_APP, model.tap())
        }

    @Test
    fun `a relay gone from the hub says so, and one renamed takes its new name`() =
        runTest {
            val model = model()
            onHub = listOf(porch.copy(label = "Front door light"))

            model.refresh()
            runCurrent()
            assertEquals(TileLook.Showing("Front door light", on = false), model.state.value)
            assertEquals("Front door light", choices.choice.first()!!.relayName)

            onHub = emptyList()
            model.refresh()
            runCurrent()
            assertEquals(TileLook.CannotAct("Front door light", TileLook.Reason.NOT_FOUND), model.state.value)
        }

    @Test
    fun `a refused session ends it for the whole app, and the tile then opens it`() =
        runTest {
            hubs.relays = { throw failure(HubErrorKind.UNAUTHORIZED) }
            val model = model()

            // Joined, since ending the session writes to disk on another thread.
            model.refresh().join()

            assertEquals(Gate.Ended, gate.state.value)
            assertEquals(TileLook.CannotAct("Porch light", TileLook.Reason.NOT_SIGNED_IN), model.state.value)
            assertEquals(Tap.OPEN_APP, model.tap())
        }

    @Test
    fun `what the hub says goes to the rest of the app, so the widget shows a switch made here`() =
        runTest {
            val model = model()
            model.refresh()
            runCurrent()

            model.tap()
            runCurrent()

            assertEquals(listOf("all porch=false", "one porch=true"), news.heard)
        }
}
