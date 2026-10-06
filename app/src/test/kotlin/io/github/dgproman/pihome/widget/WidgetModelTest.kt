package io.github.dgproman.pihome.widget

import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeLocalNetwork
import io.github.dgproman.pihome.FakeWidgetHost
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Reading
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.hub.Sensor
import io.github.dgproman.pihome.quick.QuickActions
import io.github.dgproman.pihome.session.FakeCipher
import io.github.dgproman.pihome.session.Gate
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.session.SessionGate
import io.github.dgproman.pihome.session.SessionStore
import io.github.dgproman.pihome.session.address
import io.github.dgproman.pihome.session.sessionData
import io.github.dgproman.pihome.widget.WidgetHouse.Blocked
import io.github.dgproman.pihome.widget.WidgetRelay.Press
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class WidgetModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val sessions by lazy { SessionStore(folder.sessionData(), FakeCipher()) }
    private val hubs = FakeHubs()
    private val network = FakeLocalNetwork()
    private val host = FakeWidgetHost()
    private val clock = TestClock()
    private val store = MemoryWidgetStore()
    private val porch = Relay("porch", "Porch light", on = false)
    private val gateLight = Relay("gate", "Gate", on = true)
    private val hall =
        Sensor(
            "hall",
            "Hall",
            stale = false,
            staleAfterSeconds = 300.0,
            lastSeen = clock.now,
            motion = Reading.Unsupported,
            temperature = Reading.Value(21.5, clock.now),
            humidity = Reading.Never,
        )

    /** What the hub holds: each write changes it, each read reports it. */
    private var onHub = listOf(porch, gateLight)

    private lateinit var gate: SessionGate

    init {
        hubs.relays = { onHub }
        hubs.sensors = { listOf(hall) }
        hubs.setRelay = { id, on ->
            onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
            onHub.first { it.id == id }
        }
    }

    private suspend fun TestScope.model(saved: SavedSession? = HOME): WidgetModel {
        if (saved != null) sessions.save(saved)
        gate = SessionGate(sessions, backgroundScope, hubs)
        gate.state.first { it != Gate.Loading }
        return WidgetModel(QuickActions(gate, network, hubs), store, host, clock, backgroundScope)
    }

    private suspend fun shown(): WidgetHouse = store.house.first()

    private val read =
        WidgetHouse(
            hub = HOME.address.origin,
            relays = listOf(WidgetRelay("porch", "Porch light", on = false), WidgetRelay("gate", "Gate", on = true)),
            sensors =
                listOf(
                    WidgetSensor("Hall", listOf(WidgetReading(WidgetReading.Quantity.TEMPERATURE, 21.5, stale = false)), clock.now),
                ),
            asOf = clock.now,
        )

    @Test
    fun `a refresh reads the relays and the sensors, and draws them`() =
        runTest {
            val model = model()

            model.refresh()

            assertEquals(read, shown())
            assertEquals(listOf("relays", "sensors"), hubs.calls)
            assertTrue(host.redraws > 0)
        }

    @Test
    fun `a moved switch sets the relay that way, says so while it waits, and shows what the hub answered`() =
        runTest {
            val model = model()
            model.refresh()
            val answer = CompletableDeferred<Unit>()
            hubs.setRelay = { id, on ->
                answer.await()
                onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
                onHub.first { it.id == id }
            }

            val switching = launch { model.switch("porch", to = true) }
            runCurrent()
            assertEquals(WidgetRelay("porch", "Porch light", on = true, press = Press.SWITCHING), shown().relays.first())

            answer.complete(Unit)
            switching.join()
            assertEquals(WidgetRelay("porch", "Porch light", on = true), shown().relays.first())
            assertEquals(listOf("relays", "sensors", "set porch true"), hubs.calls)
        }

    @Test
    fun `a switch with no answer in time may have happened, and stays where it was moved`() =
        runTest {
            val model = model()
            model.refresh()
            hubs.setRelay = { _, _ -> awaitCancellation() }

            val switching = launch { model.switch("porch", to = true) }
            advanceTimeBy(QuickActions.DEADLINE.toMillis() + 1)
            switching.join()

            assertEquals(WidgetRelay("porch", "Porch light", on = true, press = Press.UNSURE), shown().relays.first())
        }

    @Test
    fun `a refused switch goes back, and says it was not switched`() =
        runTest {
            val model = model()
            model.refresh()
            hubs.setRelay = { _, _ -> throw failure(HubErrorKind.SERVER) }

            model.switch("porch", to = true)

            assertEquals(WidgetRelay("porch", "Porch light", on = false, press = Press.FAILED), shown().relays.first())
        }

    @Test
    fun `a relay gone from the hub is read away`() =
        runTest {
            val model = model()
            model.refresh()
            onHub = listOf(gateLight)
            hubs.setRelay = { _, _ -> throw failure(HubErrorKind.NOT_FOUND) }

            model.switch("porch", to = true)

            assertEquals(listOf("gate"), shown().relays.map { it.id })
        }

    @Test
    fun `a switch drawn from another hub's answer reads this one instead of switching`() =
        runTest {
            store.change { read.copy(hub = address("http://192.168.1.50:5002").origin) }
            val model = model()

            model.switch("porch", to = true)

            assertEquals(listOf("relays", "sensors"), hubs.calls)
            assertEquals(read, shown())
        }

    @Test
    fun `a failed read keeps what was shown, and says the hub did not answer`() =
        runTest {
            val model = model()
            model.refresh()
            hubs.sensors = { throw failure(HubErrorKind.OFFLINE) }

            model.refresh()

            assertEquals(read.copy(failure = HubErrorKind.OFFLINE), shown())
        }

    @Test
    fun `with no session, a viewer's or no leave to reach the network, it says why, shows nothing and sends nothing`() =
        runTest {
            store.change { read }
            model(saved = null).refresh()
            assertEquals(WidgetHouse(blocked = Blocked.NOT_SIGNED_IN), shown())

            model(HOME.copy(session = HOME.session.copy(role = Role.VIEWER))).switch("porch", to = true)
            assertEquals(WidgetHouse(blocked = Blocked.READ_ONLY), shown())

            network.local = true
            model().refresh()
            assertEquals(WidgetHouse(blocked = Blocked.NEEDS_PERMISSION), shown())
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `a refused session ends it for the whole app`() =
        runTest {
            hubs.relays = { throw failure(HubErrorKind.UNAUTHORIZED) }
            val model = model()

            model.refresh()

            assertEquals(WidgetHouse(blocked = Blocked.NOT_SIGNED_IN), shown())
            assertEquals(Gate.Ended, gate.state.value)
        }

    @Test
    fun `relays the app heard are shown without asking the hub`() =
        runTest {
            val model = model()
            model.refresh()
            hubs.calls.clear()
            clock.now = clock.now.plusSeconds(60)

            model.all(HOME, listOf(porch.copy(on = true), gateLight))
            model.one(HOME, gateLight.copy(on = false))
            runCurrent()

            assertEquals(listOf(true, false), shown().relays.map { it.on })
            assertEquals(clock.now, shown().asOf)
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `the same relays heard again change nothing, so the house screen's reads do not redraw it`() =
        runTest {
            val model = model()
            model.refresh()
            val redraws = host.redraws
            clock.now = clock.now.plusSeconds(10)

            model.all(HOME, listOf(porch, gateLight))
            runCurrent()

            assertEquals(read, shown())
            assertEquals(redraws, host.redraws)
        }

    @Test
    fun `with no widget on the home screen, what the app hears is not kept`() =
        runTest {
            host.placed = false
            val model = model()

            model.all(HOME, listOf(porch))
            runCurrent()

            assertEquals(WidgetHouse(), shown())
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `a widget that could not act reads the hub itself once the app is heard from it`() =
        runTest {
            store.change { WidgetHouse(blocked = Blocked.NOT_SIGNED_IN) }
            val model = model()

            model.all(HOME, listOf(porch))
            runCurrent()

            assertEquals(read, shown())
        }

    @Test
    fun `a viewer heard from is told the widget cannot switch for it`() =
        runTest {
            // Shown while the account could still switch; an admin has since made it a viewer.
            store.change { read }
            val viewer = HOME.copy(session = HOME.session.copy(role = Role.VIEWER))
            val model = model(viewer)

            model.all(viewer, listOf(porch))
            runCurrent()

            assertEquals(WidgetHouse(blocked = Blocked.READ_ONLY), shown())
        }

    @Test
    fun `signing out takes the house off it`() =
        runTest {
            val model = model()
            model.refresh()

            model.signedOut()
            runCurrent()

            assertEquals(WidgetHouse(blocked = Blocked.NOT_SIGNED_IN), shown())
        }

    @Test
    fun `a sensor is said by what it reported, each value judged on its own time`() {
        val asOf = Instant.parse("2026-10-05T12:00:00Z")
        val sensor =
            Sensor(
                "yard",
                "Yard",
                stale = false,
                staleAfterSeconds = 300.0,
                lastSeen = asOf.minusSeconds(30),
                motion = Reading.Value(true, asOf.minusSeconds(30)),
                temperature = Reading.Value(4.0, asOf.minusSeconds(3_600)),
                humidity = Reading.Never,
            )

        assertEquals(
            WidgetSensor(
                "Yard",
                listOf(
                    WidgetReading(WidgetReading.Quantity.TEMPERATURE, 4.0, stale = true),
                    WidgetReading(WidgetReading.Quantity.MOTION, 1.0, stale = false),
                ),
                at = asOf.minusSeconds(30),
            ),
            widgetSensorOf(sensor, asOf),
        )
        val quiet = sensor.copy(lastSeen = null, motion = Reading.Never, temperature = Reading.Unsupported)
        assertEquals(WidgetSensor("Yard", emptyList(), at = null), widgetSensorOf(quiet, asOf))
    }
}
