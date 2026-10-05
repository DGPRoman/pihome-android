package io.github.dgproman.pihome.hub

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The house in src/contractTest/hub, read and switched as the app does. */
class HouseContractTest {
    private val hub = ContractHub.current

    @Test
    fun `relays switch one at a time and all together`() =
        runTest {
            val client = hub.signIn()

            assertEquals(listOf("porch-light", "gate-light"), client.relays().map { it.id })

            val porch = client.setRelay("porch-light", on = true)
            assertEquals(Relay("porch-light", "Porch light", on = true), porch)
            assertTrue(client.relays().first { it.id == "porch-light" }.on)

            val all = client.setAllRelays(on = false)
            assertEquals(listOf("porch-light", "gate-light"), all.map { it.id })
            assertTrue(all.none { it.on })
            assertTrue(client.relays().none { it.on })

            val failure = assertFailsWith<HubException> { client.setRelay("cellar-light", on = true) }
            assertEquals(HubErrorKind.NOT_FOUND, failure.kind)

            client.logOut()
        }

    @Test
    fun `a reading shows on its sensor, and the rule it matches switches its relay for a while`() =
        runTest {
            // Motion changing to true is what fires the rule, so it starts from false.
            hub.pushReading("porch-motion", """{"motion":false}""")
            hub.pushReading("porch-motion", """{"motion":true,"temperature":21.5,"humidity":40.0}""")
            val client = hub.signIn()

            val sensor = client.sensors().single()
            assertEquals("porch-motion", sensor.id)
            assertEquals("Porch motion sensor", sensor.label)
            assertFalse(sensor.stale)
            assertEquals(300.0, sensor.staleAfterSeconds)
            assertNotNull(sensor.lastSeen)
            val motion = assertIs<Reading.Value<Boolean>>(sensor.motion)
            assertTrue(motion.value)
            assertNotNull(motion.at)
            val temperature = assertIs<Reading.Value<Double>>(sensor.temperature)
            assertEquals(21.5, temperature.value)
            assertNotNull(temperature.at)
            assertEquals(40.0, assertIs<Reading.Value<Double>>(sensor.humidity).value)

            val gate = client.relays().first { it.id == "gate-light" }
            assertTrue(gate.on)
            assertTrue(assertNotNull(gate.holdExpiresAt) > Instant.now())

            assertEquals(
                listOf(
                    AutomationRule(
                        id = "porch-motion-light",
                        enabled = true,
                        onlyAfterDark = false,
                        trigger = AutomationRule.Trigger(device = "porch-motion", motion = true),
                        action = AutomationRule.Action(relay = "gate-light", state = SwitchState.ON, holdSeconds = 600.0),
                    ),
                ),
                client.rules(),
            )

            client.logOut()
        }

    @Test
    fun `a device that has not announced itself is listed with nothing known about it`() =
        runTest {
            val client = hub.signIn()

            val device = client.devices().single()
            assertEquals("workshop-pc", device.id)
            assertEquals("Workshop PC", device.label)
            assertEquals("pc-power", device.kind)
            assertNull(device.address)
            assertNull(device.announcedAt)
            assertNull(device.state)

            client.logOut()
        }
}
