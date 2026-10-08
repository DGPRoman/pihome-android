package io.github.dgproman.pihome.house

import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.MainDispatcherRule
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Reading
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Sensor
import io.github.dgproman.pihome.session.HOME
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class HouseViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val hubs = FakeHubs()
    private val clock = TestClock()
    private var refused = 0
    private var forbidden = 0

    /** Every list of relays the model reported, in order. */
    private val reported = mutableListOf<List<Relay>>()

    private val porch = Relay("porch", "Porch light", on = false)
    private val gate = Relay("gate", "Gate light", on = true)

    /** What the hub holds: each write changes it, each read reports it. */
    private var onHub = listOf(porch, gate)

    private val yard =
        Sensor("yard", "Yard", stale = false, staleAfterSeconds = 300.0, lastSeen = null, Reading.Never, Reading.Never, Reading.Never)

    init {
        hubs.relays = { onHub }
        hubs.sensors = { listOf(yard) }
        hubs.setRelay = { id, on ->
            onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
            onHub.first { it.id == id }
        }
        hubs.setAllRelays = { on ->
            onHub = onHub.map { it.copy(on = on) }
            onHub
        }
        // As the hub does it: off switches the relay off and drops any timer, on switches nothing.
        hubs.setAutomatic = { id, automatic ->
            onHub =
                onHub.map {
                    when {
                        it.id != id -> it
                        automatic -> it.copy(automatic = true)
                        else -> it.copy(automatic = false, on = false, holdExpiresAt = null)
                    }
                }
            onHub.first { it.id == id }
        }
    }

    private fun model() =
        HouseViewModel(
            hub = hubs.at(HOME.address, HOME.token),
            clock = clock,
            onRefused = { refused++ },
            onForbidden = { forbidden++ },
            onRelays = { reported += it },
        )

    private fun HouseViewModel.relay(id: String): RelayRow =
        state.value.relays.data!!
            .first { it.relay.id == id }

    @Test
    fun `the relays are read first, alone, then the rest together, each with the time it arrived`() =
        runTest {
            val model = model()
            assertTrue(model.state.value.relays.loading)

            model.refresh()
            advanceUntilIdle()

            assertEquals("relays", hubs.calls.first())
            assertEquals(setOf("sensors", "devices", "rules"), hubs.calls.drop(1).toSet())
            val house = model.state.value
            assertEquals(listOf(porch, gate), house.relays.data!!.map { it.relay })
            assertEquals(listOf(yard), house.sensors.data)
            assertEquals(emptyList<Any>(), house.devices.data)
            assertEquals(clock.now, house.sensors.asOf)
            assertFalse(house.rules.loading)
        }

    @Test
    fun `every list of relays the hub sends is reported, for the launcher's shortcuts`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            assertEquals(listOf(listOf(porch, gate)), reported)

            model.allOff()
            advanceUntilIdle()

            // The answer to all off, then the read that follows it.
            val off = listOf(porch, gate.copy(on = false))
            assertEquals(listOf(listOf(porch, gate), off, off), reported)
        }

    @Test
    fun `a refused session costs one request, ends the session once, and nothing more is sent`() =
        runTest {
            hubs.relays = { throw failure(HubErrorKind.UNAUTHORIZED) }
            val model = model()
            // In the test's own scope, which runs to idle: a poll that never ends would hang it.
            val polling = launch { model.poll() }

            advanceUntilIdle()

            assertEquals(listOf("relays"), hubs.calls)
            assertEquals(1, refused)
            assertTrue(polling.isCompleted)

            model.refresh()
            model.pull()
            advanceUntilIdle()
            assertEquals(listOf("relays"), hubs.calls)
        }

    @Test
    fun `a failed read keeps the last answer under the failure, and the next one clears it`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val asOf = clock.now

            clock.now = clock.now.plusSeconds(10)
            hubs.sensors = { throw failure(HubErrorKind.TIMEOUT) }
            model.refresh()
            advanceUntilIdle()

            assertEquals(Section(listOf(yard), asOf, HubErrorKind.TIMEOUT), model.state.value.sensors)

            hubs.sensors = { listOf(yard) }
            model.refresh()
            advanceUntilIdle()
            assertEquals(Section(listOf(yard), clock.now), model.state.value.sensors)
        }

    @Test
    fun `a read that fails before anything arrived is a failure with nothing to show`() =
        runTest {
            hubs.relays = { throw failure(HubErrorKind.OFFLINE) }
            val model = model()
            model.refresh()
            advanceUntilIdle()

            assertEquals(Section<List<RelayRow>>(failure = HubErrorKind.OFFLINE), model.state.value.relays)
            assertEquals(0, refused)
        }

    @Test
    fun `the house is read every ten seconds while it is watched, and not after`() =
        runTest {
            val model = model()
            val polling = backgroundScope.launch { model.poll() }
            runCurrent()
            assertEquals(1, hubs.calls.count { it == "relays" })

            advanceTimeBy(Duration.ofSeconds(9).toMillis())
            assertEquals(1, hubs.calls.count { it == "relays" })
            advanceTimeBy(Duration.ofSeconds(1).toMillis() + 1)
            assertEquals(2, hubs.calls.count { it == "relays" })

            polling.cancel()
            advanceTimeBy(Duration.ofMinutes(1).toMillis())
            assertEquals(2, hubs.calls.count { it == "relays" })
        }

    @Test
    fun `asked again while a read is under way, it is that read`() =
        runTest {
            val model = model()
            model.refresh()
            model.refresh()
            model.pull()
            advanceUntilIdle()

            assertEquals(1, hubs.calls.count { it == "relays" })
            assertFalse(model.state.value.refreshing)
        }

    @Test
    fun `a press shows at once, takes the hub's answer, and is read back`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val answer = CompletableDeferred<Unit>()
            val write = hubs.setRelay
            hubs.setRelay = { id, on ->
                answer.await()
                write(id, on)
            }
            hubs.calls.clear()

            model.setRelay("porch", true)
            runCurrent()
            assertEquals(RelayRow(porch.copy(on = true), pending = true), model.relay("porch"))

            // A second press on a switch already moving is not sent.
            model.setRelay("porch", false)
            answer.complete(Unit)
            advanceUntilIdle()

            assertEquals(RelayRow(porch.copy(on = true)), model.relay("porch"))
            assertEquals(listOf("set porch true", "relays"), hubs.calls)
        }

    @Test
    fun `a refused press goes back, and says why until the switch is pressed again`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.setRelay = { _, _ -> throw failure(HubErrorKind.SERVER) }

            model.setRelay("porch", true)
            advanceUntilIdle()

            assertEquals(RelayRow(porch, failure = HubErrorKind.SERVER), model.relay("porch"))

            model.refresh()
            advanceUntilIdle()
            assertEquals(HubErrorKind.SERVER, model.relay("porch").failure)
        }

    @Test
    fun `a press refused as not allowed has the session checked, since the role may have changed`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.setRelay = { _, _ -> throw failure(HubErrorKind.FORBIDDEN) }

            model.setRelay("porch", true)
            advanceUntilIdle()

            assertEquals(1, forbidden)
            assertEquals(0, refused)
            assertFalse(model.relay("porch").relay.on)
        }

    @Test
    fun `a press whose answer was lost stays where it was pressed, in doubt, until the hub is read`() =
        runTest {
            for (kind in listOf(HubErrorKind.UNREADABLE, HubErrorKind.TIMEOUT)) {
                onHub = listOf(porch, gate)
                val model = model()
                model.refresh()
                advanceUntilIdle()
                // The hub switched it, and the answer never arrived.
                hubs.setRelay = { id, on ->
                    onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
                    throw failure(kind)
                }
                // The read that settles it does not come at once.
                val read = CompletableDeferred<Unit>()
                hubs.relays = {
                    read.await()
                    onHub
                }

                model.setRelay("porch", true)
                runCurrent()

                assertEquals(kind.name, RelayRow(porch.copy(on = true), failure = kind, unconfirmed = true), model.relay("porch"))

                read.complete(Unit)
                advanceUntilIdle()
                assertEquals(kind.name, RelayRow(porch.copy(on = true)), model.relay("porch"))
                hubs.relays = { onHub }
            }
        }

    @Test
    fun `a poll that started before a press cannot undo it`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            // A poll goes out, and its answer, from before the press, is slow to come.
            val slow = CompletableDeferred<List<Relay>>()
            hubs.relays = { slow.await() }
            model.refresh()
            runCurrent()

            hubs.relays = { onHub }
            model.setRelay("porch", true)
            slow.complete(listOf(porch, gate))
            advanceUntilIdle()

            assertTrue(model.relay("porch").relay.on)
        }

    @Test
    fun `a poll that ends while a press is under way is dropped`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val answer = CompletableDeferred<Unit>()
            hubs.setRelay = { id, on ->
                answer.await()
                onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
                onHub.first { it.id == id }
            }

            model.setRelay("porch", true)
            runCurrent()
            // Read while the write is still on its way: the hub has not switched it yet.
            model.refresh()
            runCurrent()
            assertEquals(RelayRow(porch.copy(on = true), pending = true), model.relay("porch"))

            answer.complete(Unit)
            advanceUntilIdle()
            assertEquals(RelayRow(porch.copy(on = true)), model.relay("porch"))
        }

    @Test
    fun `a poll that starts during a press, and ends after it, cannot undo it`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val answer = CompletableDeferred<Unit>()
            hubs.setRelay = { id, on ->
                answer.await()
                onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
                onHub.first { it.id == id }
            }
            model.setRelay("porch", true)
            runCurrent()

            // Served by the hub before the write, and slow to come back.
            val slow = CompletableDeferred<List<Relay>>()
            hubs.relays = { slow.await() }
            model.refresh()
            runCurrent()
            hubs.relays = { onHub }
            answer.complete(Unit)
            runCurrent()
            slow.complete(listOf(porch, gate))
            advanceUntilIdle()

            assertEquals(RelayRow(porch.copy(on = true)), model.relay("porch"))
            assertEquals(
                listOf("relays", "sensors", "devices", "rules", "set porch true", "sensors", "devices", "rules", "relays"),
                hubs.calls,
            )
        }

    @Test
    fun `turning automation off shows the relay off and left alone at once, and is read back`() =
        runTest {
            val held = gate.copy(automatic = true, holdExpiresAt = clock.now.plusSeconds(60))
            onHub = listOf(porch.copy(automatic = true), held)
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val answer = CompletableDeferred<Unit>()
            val write = hubs.setAutomatic
            hubs.setAutomatic = { id, automatic ->
                answer.await()
                write(id, automatic)
            }
            hubs.calls.clear()

            model.setAutomatic("gate", false)
            runCurrent()
            val off = gate.copy(on = false, automatic = false)
            assertEquals(RelayRow(off, pending = true, pressed = Control.AUTOMATION), model.relay("gate"))

            // The relay takes no other press until the hub answers, not even its switch.
            model.setRelay("gate", true)
            model.setAutomatic("gate", true)
            answer.complete(Unit)
            advanceUntilIdle()

            assertEquals(RelayRow(off), model.relay("gate"))
            assertEquals(listOf("automatic gate false", "relays"), hubs.calls)
        }

    @Test
    fun `turning automation back on switches nothing`() =
        runTest {
            // Switched on by hand while automation was off.
            onHub = listOf(porch.copy(automatic = true), gate.copy(automatic = false))
            val model = model()
            model.refresh()
            advanceUntilIdle()

            hubs.calls.clear()

            model.setAutomatic("gate", true)
            advanceUntilIdle()

            assertEquals(RelayRow(gate.copy(automatic = true)), model.relay("gate"))
            assertEquals(listOf("automatic gate true", "relays"), hubs.calls)
        }

    @Test
    fun `a refused automation press puts the relay back as it was, and says why beside it`() =
        runTest {
            val held = gate.copy(automatic = true, holdExpiresAt = clock.now.plusSeconds(60))
            onHub = listOf(porch.copy(automatic = true), held)
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.setAutomatic = { _, _ -> throw failure(HubErrorKind.SERVER) }

            model.setAutomatic("gate", false)
            advanceUntilIdle()

            assertEquals(RelayRow(held, failure = HubErrorKind.SERVER, pressed = Control.AUTOMATION), model.relay("gate"))

            model.refresh()
            advanceUntilIdle()
            assertEquals(RelayRow(held, failure = HubErrorKind.SERVER, pressed = Control.AUTOMATION), model.relay("gate"))
        }

    @Test
    fun `an automation press whose answer was lost stays as pressed, in doubt, until the hub is read`() =
        runTest {
            onHub = listOf(porch.copy(automatic = true), gate.copy(automatic = true))
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val write = hubs.setAutomatic
            hubs.setAutomatic = { id, automatic ->
                write(id, automatic)
                throw failure(HubErrorKind.TIMEOUT)
            }
            val read = CompletableDeferred<Unit>()
            hubs.relays = {
                read.await()
                onHub
            }

            model.setAutomatic("gate", false)
            runCurrent()

            val off = gate.copy(on = false, automatic = false)
            assertEquals(
                RelayRow(off, failure = HubErrorKind.TIMEOUT, unconfirmed = true, pressed = Control.AUTOMATION),
                model.relay("gate"),
            )

            read.complete(Unit)
            advanceUntilIdle()
            assertEquals(RelayRow(off), model.relay("gate"))
        }

    @Test
    fun `an automation press refused as not allowed has the session checked`() =
        runTest {
            onHub = listOf(porch.copy(automatic = true), gate.copy(automatic = true))
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.setAutomatic = { _, _ -> throw failure(HubErrorKind.FORBIDDEN) }

            model.setAutomatic("gate", false)
            advanceUntilIdle()

            assertEquals(1, forbidden)
            assertEquals(0, refused)
            assertEquals(gate.copy(automatic = true), model.relay("gate").relay)
        }

    @Test
    fun `a relay whose hub does not say whether it is automatic has no automation to press`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.calls.clear()

            model.setAutomatic("gate", false)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), hubs.calls)
            assertEquals(RelayRow(gate), model.relay("gate"))
        }

    @Test
    fun `all off switches everything off at once, and only while something is on`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.calls.clear()

            model.allOff()
            runCurrent()
            assertTrue(
                model.state.value.relays.data!!
                    .none { it.relay.on },
            )
            advanceUntilIdle()

            assertEquals(listOf("set all false", "relays"), hubs.calls)
            assertEquals(AllOff(), model.state.value.allOff)

            hubs.calls.clear()
            model.allOff()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `a refused all off puts each relay back`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.setAllRelays = { throw failure(HubErrorKind.SERVER) }
            // So the read after it is not what puts them back.
            val read = CompletableDeferred<Unit>()
            hubs.relays = {
                read.await()
                onHub
            }

            model.allOff()
            runCurrent()

            assertEquals(
                listOf(false, true),
                model.state.value.relays.data!!
                    .map { it.relay.on },
            )
            assertEquals(AllOff(failure = HubErrorKind.SERVER), model.state.value.allOff)
            read.complete(Unit)
        }

    @Test
    fun `an all off whose answer was lost leaves the relays off, in doubt, until the hub is read`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.setAllRelays = {
                onHub = onHub.map { it.copy(on = false) }
                throw failure(HubErrorKind.UNREADABLE)
            }
            val read = CompletableDeferred<Unit>()
            hubs.relays = {
                read.await()
                onHub
            }

            model.allOff()
            runCurrent()
            assertEquals(AllOff(failure = HubErrorKind.UNREADABLE, unconfirmed = true), model.state.value.allOff)
            assertTrue(
                model.state.value.relays.data!!
                    .none { it.relay.on },
            )

            read.complete(Unit)
            advanceUntilIdle()
            assertEquals(AllOff(), model.state.value.allOff)
        }

    @Test
    fun `a refusal of the session during a press ends it, and nothing more is sent`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.setRelay = { _, _ -> throw failure(HubErrorKind.UNAUTHORIZED) }
            hubs.calls.clear()

            model.setRelay("porch", true)
            advanceUntilIdle()
            model.setRelay("gate", false)
            model.refresh()
            advanceUntilIdle()

            assertEquals(listOf("set porch true"), hubs.calls)
            assertEquals(1, refused)
            assertNull(model.relay("gate").failure)
        }
}
