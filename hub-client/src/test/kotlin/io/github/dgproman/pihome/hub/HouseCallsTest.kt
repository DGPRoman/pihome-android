package io.github.dgproman.pihome.hub

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HouseCallsTest {
    @get:Rule val hub = FakeHub()

    @Test
    fun `relays are read with any automation hold on them`() =
        runTest {
            hub.answer(
                200,
                """
                {"relays":[
                  {"id":"porch-light","label":"Porch light","on":true,"hold_expires_at":"2026-10-05T18:00:00Z"},
                  {"id":"gate-light","label":"Gate light","on":false,"hold_expires_at":null}
                ]}
                """,
            )

            val relays = hub.client().relays()

            assertEquals(
                listOf(
                    Relay("porch-light", "Porch light", on = true, holdExpiresAt = Instant.parse("2026-10-05T18:00:00Z")),
                    Relay("gate-light", "Gate light", on = false),
                ),
                relays,
            )
            val request = hub.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/relays", request.target)
        }

    @Test
    fun `one relay is put in the state asked for`() =
        runTest {
            hub.answer(200, """{"id":"porch-light","label":"Porch light","on":true,"hold_expires_at":null}""")

            assertEquals(Relay("porch-light", "Porch light", on = true), hub.client().setRelay("porch-light", on = true))
            val request = hub.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/v1/relays/porch-light", request.target)
            assertEquals(Json.parseToJsonElement("""{"on":true}"""), Json.parseToJsonElement(request.text))
        }

    @Test
    fun `every relay is put in one state`() =
        runTest {
            hub.answer(200, """{"relays":[{"id":"porch-light","label":"Porch light","on":false,"hold_expires_at":null}]}""")

            assertEquals(listOf(Relay("porch-light", "Porch light", on = false)), hub.client().setAllRelays(on = false))
            val request = hub.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/v1/relays", request.target)
            assertEquals(Json.parseToJsonElement("""{"on":false}"""), Json.parseToJsonElement(request.text))
        }

    @Test
    fun `a sensor reading that is missing, never sent, or sent is told apart`() =
        runTest {
            hub.answer(
                200,
                """
                {"sensors":[
                  {"id":"hall","label":"Hall","stale":false,"stale_after_seconds":900.0,
                   "last_seen":"2026-10-05T14:40:00Z",
                   "motion":true,"motion_updated_at":"2026-10-05T14:40:00Z",
                   "temperature":null,"climate_updated_at":null},
                  {"id":"porch","label":"Porch","stale":true}
                ]}
                """,
            )

            val (hall, porch) = hub.client().sensors()

            assertEquals(
                Sensor(
                    id = "hall",
                    label = "Hall",
                    stale = false,
                    staleAfterSeconds = 900.0,
                    lastSeen = Instant.parse("2026-10-05T14:40:00Z"),
                    motion = Reading.Value(true, Instant.parse("2026-10-05T14:40:00Z")),
                    temperature = Reading.Never,
                    humidity = Reading.Unsupported,
                ),
                hall,
            )
            assertEquals(
                Sensor("porch", "Porch", true, null, null, Reading.Unsupported, Reading.Unsupported, Reading.Unsupported),
                porch,
            )
            assertEquals("/v1/sensors", hub.takeRequest().target)
        }

    @Test
    fun `temperature and humidity share the time they were taken`() =
        runTest {
            hub.answer(
                200,
                """
                {"sensors":[{"id":"hall","label":"Hall","stale":false,"stale_after_seconds":900,
                  "motion":null,"temperature":21.5,"humidity":0,"climate_updated_at":"2026-10-05T14:30:00Z"}]}
                """,
            )

            val hall = hub.client().sensors().single()

            val taken = Instant.parse("2026-10-05T14:30:00Z")
            assertEquals(Reading.Never, hall.motion)
            assertEquals(Reading.Value(21.5, taken), hall.temperature)
            // Zero is a reading, not the absence of one.
            assertEquals(Reading.Value(0.0, taken), hall.humidity)
        }

    @Test
    fun `a sensor reading of the wrong type is unreadable`() =
        runTest {
            hub.answer(200, """{"sensors":[{"id":"hall","label":"Hall","stale":false,"motion":"yes"}]}""")

            val failure = assertFailsWith<HubException> { hub.client().sensors() }

            assertEquals(HubErrorKind.UNREADABLE, failure.kind)
        }

    @Test
    fun `a device is read with its own state as it served it`() =
        runTest {
            hub.answer(
                200,
                """
                {"devices":[
                  {"id":"desk-pc","label":"Desk PC","kind":"pc-power","address":"http://192.168.1.50",
                   "firmware":"1.4.0","announced_at":"2026-10-05T08:00:00Z","reachable":false,
                   "last_polled_at":"2026-10-05T14:48:00Z","last_seen_at":"2026-10-05T14:00:00Z",
                   "unreachable_since":"2026-10-05T14:01:00Z","last_error":"timed out",
                   "state":{"power":"on","uptime_s":3600}},
                  {"id":"lamp","label":"Lamp","kind":"smart-lamp"}
                ]}
                """,
            )

            val (desk, lamp) = hub.client().devices()

            assertEquals(
                Device(
                    id = "desk-pc",
                    label = "Desk PC",
                    kind = "pc-power",
                    address = "http://192.168.1.50",
                    firmware = "1.4.0",
                    announcedAt = Instant.parse("2026-10-05T08:00:00Z"),
                    reachable = false,
                    lastPolledAt = Instant.parse("2026-10-05T14:48:00Z"),
                    lastSeenAt = Instant.parse("2026-10-05T14:00:00Z"),
                    unreachableSince = Instant.parse("2026-10-05T14:01:00Z"),
                    lastError = "timed out",
                    state =
                        buildJsonObject {
                            put("power", "on")
                            put("uptime_s", JsonPrimitive(3600))
                        },
                ),
                desk,
            )
            // Never polled is null, not false.
            assertEquals(Device("lamp", "Lamp", "smart-lamp"), lamp)
            assertEquals("/v1/devices", hub.takeRequest().target)
        }

    @Test
    fun `automation rules are read with what sets them off and what they do`() =
        runTest {
            hub.answer(
                200,
                """
                {"rules":[
                  {"id":"porch-motion-light","when":{"device":"porch","motion":true},
                   "then":{"relay":"porch-light","state":"on","hold_seconds":120.0},
                   "only_after_dark":true,"enabled":true},
                  {"id":"hall-off","when":{"device":"hall","motion":false},
                   "then":{"relay":"hall-light","state":"off","hold_seconds":null},
                   "only_after_dark":false,"enabled":false}
                ]}
                """,
            )

            val rules = hub.client().rules()

            assertEquals(
                listOf(
                    AutomationRule(
                        id = "porch-motion-light",
                        enabled = true,
                        onlyAfterDark = true,
                        trigger = AutomationRule.Trigger("porch", motion = true),
                        action = AutomationRule.Action("porch-light", SwitchState.ON, holdSeconds = 120.0),
                    ),
                    AutomationRule(
                        id = "hall-off",
                        enabled = false,
                        onlyAfterDark = false,
                        trigger = AutomationRule.Trigger("hall", motion = false),
                        action = AutomationRule.Action("hall-light", SwitchState.OFF),
                    ),
                ),
                rules,
            )
            assertEquals("/v1/automation/rules", hub.takeRequest().target)
        }

    @Test
    fun `a rule that switches a relay to something but on or off is unreadable`() =
        runTest {
            hub.answer(
                200,
                """{"rules":[{"id":"r","when":{"device":"d","motion":true},"then":{"relay":"x","state":"dim"},"only_after_dark":false,"enabled":true}]}""",
            )

            val failure = assertFailsWith<HubException> { hub.client().rules() }

            assertEquals(HubErrorKind.UNREADABLE, failure.kind)
        }
}
