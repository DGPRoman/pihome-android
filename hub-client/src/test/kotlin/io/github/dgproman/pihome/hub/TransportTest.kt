package io.github.dgproman.pihome.hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okhttp3.Dns
import org.junit.Rule
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportTest {
    @get:Rule val hub = FakeHub()

    private val relay = """{"id":"porch-light","label":"Porch light","on":true,"hold_expires_at":null}"""

    private fun raw(
        status: Int,
        contentType: String?,
        body: String = "",
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .apply { if (contentType != null) setHeader("Content-Type", contentType) }
            .body(body)
            .build()

    @Test
    fun `a read asks for JSON, sends the session as the cookie, and no CSRF header`() =
        runTest {
            hub.answer(200, """{"relays":[]}""")

            hub.client().relays()

            val request = hub.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("application/json", request.headers["Accept"])
            assertEquals("pihome_session=test-session-token", request.headers["Cookie"])
            assertNull(request.headers["X-Pihome-CSRF"])
        }

    @Test
    fun `every write carries the CSRF header`() =
        runTest {
            val client = hub.client()
            hub.answer(200, relay)
            client.setRelay("porch-light", on = true)
            hub.answer(201, """{"token":"t","expires_at":"2026-10-05T15:03:46Z"}""")
            client.issueInvitation("olya")
            hub.answer(204)
            client.deleteAccount("olya")
            hub.answer(
                200,
                """{"username":"olya","role":"viewer","disabled":true,"created_at":"2026-10-05T14:48:46Z","invitation_expires_at":null}""",
            )
            client.changeAccount("olya", AccountChange(disabled = true))

            for (method in listOf("PUT", "POST", "DELETE", "PATCH")) {
                val request = hub.takeRequest()
                assertEquals(method, request.method)
                assertEquals("1", request.headers["X-Pihome-CSRF"], method)
            }
        }

    @Test
    fun `without a token no cookie is sent`() =
        runTest {
            hub.answer(200, """{"relays":[]}""")

            hub.client(token = null).relays()

            assertNull(hub.takeRequest().headers["Cookie"])
        }

    @Test
    fun `each status means one kind of failure`() =
        runTest {
            val table =
                listOf(
                    Triple(401, "application/json", HubErrorKind.UNAUTHORIZED),
                    Triple(403, "application/json", HubErrorKind.FORBIDDEN),
                    Triple(404, "application/json", HubErrorKind.NOT_FOUND),
                    Triple(409, "application/json", HubErrorKind.CONFLICT),
                    Triple(422, "application/json", HubErrorKind.MALFORMED),
                    Triple(429, "application/json", HubErrorKind.RATE_LIMITED),
                    Triple(400, "application/json", HubErrorKind.MALFORMED),
                    Triple(418, "application/json", HubErrorKind.MALFORMED),
                    Triple(500, "application/json", HubErrorKind.SERVER),
                    // The hub's own "not now", when a relay or its storage fails.
                    Triple(503, "application/json", HubErrorKind.SERVER),
                    // A gateway in front of the hub, saying it could not reach it.
                    Triple(502, "text/html", HubErrorKind.OFFLINE),
                    Triple(503, "text/plain", HubErrorKind.OFFLINE),
                    Triple(504, null, HubErrorKind.OFFLINE),
                    Triple(500, "text/html", HubErrorKind.NOT_THE_HUB),
                    Triple(511, "text/html", HubErrorKind.NOT_THE_HUB),
                    Triple(200, "text/html", HubErrorKind.NOT_THE_HUB),
                    Triple(200, null, HubErrorKind.NOT_THE_HUB),
                    Triple(301, "application/json", HubErrorKind.NOT_THE_HUB),
                    Triple(307, "text/html", HubErrorKind.NOT_THE_HUB),
                )
            val client = hub.client()

            for ((status, contentType, kind) in table) {
                hub.answer(raw(status, contentType, "{}"))

                // A write, so that nothing is asked twice and each row is one request.
                val failure = assertFailsWith<HubException> { client.setRelay("porch-light", on = true) }

                assertEquals(kind, failure.kind, "$status $contentType")
                assertEquals(status, failure.status, "$status $contentType")
            }
            assertEquals(table.size, hub.requestCount)
        }

    @Test
    fun `any JSON media type is the hub's`() =
        runTest {
            hub.answer(raw(200, "application/vnd.pihome+json; charset=utf-8", relay))

            assertEquals("porch-light", hub.client().setRelay("porch-light", on = true).id)
        }

    @Test
    fun `a redirect is not followed`() =
        runTest {
            hub.answer(
                MockResponse
                    .Builder()
                    .code(302)
                    .setHeader("Location", hub.server.url("/login").toString())
                    .build(),
            )

            val failure = assertFailsWith<HubException> { hub.client().relays() }

            assertEquals(HubErrorKind.NOT_THE_HUB, failure.kind)
            assertEquals(1, hub.requestCount)
        }

    @Test
    fun `a reply of the wrong shape is unreadable`() =
        runTest {
            val bodies =
                listOf(
                    """{"relays":[{"id":"porch-light"}]}""",
                    """{"relays":{}}""",
                    """{"relays":[{"id":"porch-light","label":"Porch light","on":"yes"}]}""",
                    """{"relays":[{"id":"porch-light","label":"Porch light","on":true,"hold_expires_at":"tomorrow"}]}""",
                    // A time with no offset could be in any zone.
                    """{"relays":[{"id":"porch-light","label":"Porch light","on":true,"hold_expires_at":"2026-10-05T14:48:46"}]}""",
                    "{",
                    "<html>sign in to continue</html>",
                    "",
                )
            val client = hub.client()

            for (body in bodies) {
                hub.answer(200, body)

                val failure = assertFailsWith<HubException>(body) { client.relays() }

                assertEquals(HubErrorKind.UNREADABLE, failure.kind, body)
            }
        }

    @Test
    fun `fields the hub adds later are passed over`() =
        runTest {
            hub.answer(200, """{"relays":[{"id":"porch-light","label":"Porch light","on":true,"watts":40}],"count":1}""")

            assertEquals(listOf(Relay("porch-light", "Porch light", on = true)), hub.client().relays())
        }

    @Test
    fun `a reply too large to be the hub's is unreadable`() =
        runTest {
            hub.answer(200, """{"relays":[],"padding":"${"x".repeat(2 shl 20)}"}""")

            val failure = assertFailsWith<HubException> { hub.client().relays() }

            assertEquals(HubErrorKind.UNREADABLE, failure.kind)
        }

    @Test
    fun `a hub that is not there is offline`() =
        runTest {
            val address = hub.address
            hub.server.close()

            val failure = assertFailsWith<HubException> { hub.client(address = address).relays() }

            assertEquals(HubErrorKind.OFFLINE, failure.kind)
            assertNull(failure.status)
        }

    @Test
    fun `a hub that takes the request and does not answer is a timeout`() =
        runTest {
            hub.answer(
                MockResponse
                    .Builder()
                    .headersDelay(5, TimeUnit.SECONDS)
                    .body(relay)
                    .build(),
            )

            val failure = assertFailsWith<HubException> { hub.client().setRelay("porch-light", on = true) }

            assertEquals(HubErrorKind.TIMEOUT, failure.kind)
        }

    @Test
    fun `a read is asked again after a failure that might pass, at most twice more`() =
        runTest {
            hub.answer(500, """{"detail":"Internal Server Error"}""")
            hub.answer(raw(503, "text/html"))
            hub.answer(200, """{"relays":[]}""")

            assertEquals(emptyList(), hub.client().relays())
            assertEquals(3, hub.requestCount)

            repeat(3) { hub.answer(500, """{"detail":"Internal Server Error"}""") }

            val failure = assertFailsWith<HubException> { hub.client().relays() }

            assertEquals(HubErrorKind.SERVER, failure.kind)
            assertEquals(6, hub.requestCount)
        }

    @Test
    fun `a read the hub refused is not asked again`() =
        runTest {
            hub.answer(401, """{"detail":"Not authenticated"}""")

            val failure = assertFailsWith<HubException> { hub.client().relays() }

            assertEquals(HubErrorKind.UNAUTHORIZED, failure.kind)
            assertEquals(1, hub.requestCount)
        }

    @Test
    fun `a write is sent once whatever happens to it`() =
        runTest {
            hub.answer(500, """{"detail":"Internal Server Error"}""")
            hub.answer(200, relay)

            val failure = assertFailsWith<HubException> { hub.client().setRelay("porch-light", on = true) }

            assertEquals(HubErrorKind.SERVER, failure.kind)
            assertEquals(1, hub.requestCount)
        }

    @Test
    fun `a POST whose reply is lost is a timeout, and OkHttp does not send it again`() =
        runTest {
            val client = hub.client()
            // A connection already used once, which is the kind OkHttp would quietly
            // retry a failure on.
            hub.answer(200, """{"relays":[]}""")
            client.relays()
            hub.answer(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            hub.answer(201, """{"token":"second","expires_at":"2026-10-05T15:03:46Z"}""")

            val failure = assertFailsWith<HubException> { client.issueInvitation("olya") }

            assertEquals(HubErrorKind.TIMEOUT, failure.kind)
            assertEquals(2, hub.requestCount)
        }

    @Test
    fun `a PUT whose reply is lost on a reused connection may be sent again, since it names its state`() =
        runTest {
            val client = hub.client()
            hub.answer(200, """{"relays":[]}""")
            client.relays()
            hub.answer(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            hub.answer(200, relay)

            assertEquals("porch-light", client.setRelay("porch-light", on = true).id)
            assertEquals(3, hub.requestCount)
        }

    @Test
    fun `plain http to a name that resolves outside the home network is refused before sending`() =
        runTest {
            val dns =
                Dns { name ->
                    assertEquals("hub.test", name)
                    listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("203.0.113.9"))
                }
            val address = HubAddress.parse("hub.test:${hub.server.port}").valid()

            val failure = assertFailsWith<HubException> { hub.client(dns = dns, address = address).relays() }

            assertEquals(HubErrorKind.INSECURE, failure.kind)
            assertEquals(0, hub.requestCount)
        }

    @Test
    fun `plain http to a name that resolves to the home network goes ahead`() =
        runTest {
            hub.answer(200, """{"relays":[]}""")
            val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
            val address = HubAddress.parse("hub.test:${hub.server.port}").valid()

            hub.client(dns = dns, address = address).relays()

            assertEquals("hub.test", hub.takeRequest().url.host)
        }

    @Test
    fun `a call that is cancelled ends as cancelled, not as a failure of the hub`() =
        runTest {
            hub.answer(
                MockResponse
                    .Builder()
                    .headersDelay(5, TimeUnit.SECONDS)
                    .body("""{"relays":[]}""")
                    .build(),
            )
            var thrown: Throwable? = null

            val call =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        hub.client().relays()
                    } catch (e: Throwable) {
                        thrown = e
                        throw e
                    }
                }
            hub.takeRequest()
            call.cancelAndJoin()

            assertTrue(call.isCancelled)
            assertIs<CancellationException>(thrown)
        }
}
