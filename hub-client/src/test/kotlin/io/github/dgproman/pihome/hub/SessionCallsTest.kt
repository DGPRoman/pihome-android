package io.github.dgproman.pihome.hub

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import org.junit.Rule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SessionCallsTest {
    @get:Rule val hub = FakeHub()

    private val viewer = """{"username":"olya","role":"viewer","expires_at":"2026-11-04T14:48:46.344933Z"}"""

    private val olya = Session("olya", Role.VIEWER, Instant.parse("2026-11-04T14:48:46.344933Z"))

    /** What the hub sends with a new session. */
    private fun opened(cookie: String = "pihome_session=new-token; HttpOnly; Max-Age=2592000; Path=/; SameSite=strict"): MockResponse =
        MockResponse
            .Builder()
            .code(201)
            .setHeader("Content-Type", "application/json")
            .setHeader("Set-Cookie", cookie)
            .body(viewer)
            .build()

    @Test
    fun `the health check asks once, with no session, and takes the hub's answer`() =
        runTest {
            hub.answer(200, """{"status":"ok"}""")

            hub.client().checkHealth()

            val request = hub.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/health", request.target)
            assertNull(request.headers["Cookie"])
        }

    @Test
    fun `anything else answering the health check is not the hub`() =
        runTest {
            val others =
                listOf(
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Type", "text/html")
                        .body("<p>Sign in to this Wi-Fi</p>")
                        .build(),
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body("""{"status":"up"}""")
                        .build(),
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body("""{"ok":true}""")
                        .build(),
                    MockResponse
                        .Builder()
                        .code(
                            404,
                        ).setHeader("Content-Type", "application/json")
                        .body("""{"detail":"Not Found"}""")
                        .build(),
                    MockResponse
                        .Builder()
                        .code(401)
                        .setHeader("Content-Type", "application/json")
                        .body("""{}""")
                        .build(),
                    MockResponse
                        .Builder()
                        .code(302)
                        .setHeader("Location", "http://portal.example/")
                        .build(),
                )
            for (other in others) {
                hub.answer(other)

                val failure = assertFailsWith<HubException> { hub.client().checkHealth() }

                assertEquals(HubErrorKind.NOT_THE_HUB, failure.kind, other.toString())
            }
        }

    @Test
    fun `a failing health check is not asked again`() =
        runTest {
            hub.answer(500, """{"detail":"Internal Server Error"}""")
            hub.answer(200, """{"status":"ok"}""")

            val failure = assertFailsWith<HubException> { hub.client().checkHealth() }

            assertEquals(HubErrorKind.SERVER, failure.kind)
            assertEquals(1, hub.requestCount)
        }

    @Test
    fun `logging in posts the name and password, and keeps the token from the cookie`() =
        runTest {
            hub.answer(opened())

            val signedIn = hub.client(token = null).logIn("olya", "correct horse")

            assertEquals(SignedIn(olya, SessionToken("new-token")), signedIn)
            val request = hub.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/session", request.target)
            assertEquals("application/json", request.headers["Content-Type"])
            assertEquals(
                Json.parseToJsonElement("""{"username":"olya","password":"correct horse"}"""),
                Json.parseToJsonElement(request.text),
            )
        }

    @Test
    fun `a refused login is unauthorized`() =
        runTest {
            hub.answer(401, """{"detail":"Invalid username or password"}""")

            val failure = assertFailsWith<HubException> { hub.client(token = null).logIn("olya", "wrong") }

            assertEquals(HubErrorKind.UNAUTHORIZED, failure.kind)
        }

    @Test
    fun `a session opened without a cookie cannot be used`() =
        runTest {
            hub.answer(201, viewer)

            val failure = assertFailsWith<HubException> { hub.client(token = null).logIn("olya", "correct horse") }

            assertEquals(HubErrorKind.UNREADABLE, failure.kind)
        }

    @Test
    fun `a cookie with another name, or empty, is not the session`() =
        runTest {
            for (cookie in listOf("other=new-token; Path=/", "pihome_session=; Path=/")) {
                hub.answer(opened(cookie))

                val failure = assertFailsWith<HubException>(cookie) { hub.client(token = null).logIn("olya", "correct horse") }

                assertEquals(HubErrorKind.UNREADABLE, failure.kind, cookie)
            }
        }

    @Test
    fun `joining posts the invitation, and keeps the token from the cookie`() =
        runTest {
            hub.answer(opened())

            val signedIn = hub.client(token = null).join(InvitationToken("haCY1GiHstPg"))

            assertEquals(SignedIn(olya, SessionToken("new-token")), signedIn)
            val request = hub.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/session", request.target)
            // Required by the hub on this request in particular.
            assertEquals("1", request.headers["X-Pihome-CSRF"])
            assertEquals(Json.parseToJsonElement("""{"invitation":"haCY1GiHstPg"}"""), Json.parseToJsonElement(request.text))
        }

    @Test
    fun `an invitation refused for any reason is unauthorized, a mangled one included`() =
        runTest {
            for (status in listOf(401, 422)) {
                hub.answer(status, """{"detail":"The invitation is not valid"}""")

                val failure = assertFailsWith<HubException> { hub.client(token = null).join(InvitationToken("spent")) }

                assertEquals(HubErrorKind.UNAUTHORIZED, failure.kind, "$status")
                assertEquals(status, failure.status)
            }
        }

    /**
     * A client whose next request goes over a connection already used once: the
     * case where OkHttp would quietly send a failed request again if it could.
     */
    private suspend fun clientOnAUsedConnection(): HubClient {
        val client = hub.client(token = null)
        hub.answer(401, """{"detail":"Not authenticated"}""")
        client.readSession()
        return client
    }

    @Test
    fun `a join whose reply is lost may have spent the invitation, and is not sent again`() =
        runTest {
            val client = clientOnAUsedConnection()
            hub.answer(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            hub.answer(opened())

            val failure = assertFailsWith<HubException> { client.join(InvitationToken("once")) }

            assertEquals(HubErrorKind.TIMEOUT, failure.kind)
            assertEquals(2, hub.requestCount)
        }

    @Test
    fun `a login whose reply is lost is not sent again either`() =
        runTest {
            val client = clientOnAUsedConnection()
            hub.answer(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            hub.answer(opened())

            val failure = assertFailsWith<HubException> { client.logIn("olya", "correct horse") }

            assertEquals(HubErrorKind.TIMEOUT, failure.kind)
            assertEquals(2, hub.requestCount)
        }

    @Test
    fun `reading the session sends the token and returns who it belongs to`() =
        runTest {
            hub.answer(200, viewer)

            assertEquals(olya, hub.client().readSession())
            val request = hub.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/session", request.target)
            assertEquals("pihome_session=test-session-token", request.headers["Cookie"])
        }

    @Test
    fun `a renewal is read from the body, and the token stays as it was`() =
        runTest {
            hub.answer(
                MockResponse
                    .Builder()
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "pihome_session=test-session-token; HttpOnly; Max-Age=2592000; Path=/")
                    .body("""{"username":"olya","role":"viewer","expires_at":"2026-12-04T09:00:00Z"}""")
                    .build(),
            )

            assertEquals(Instant.parse("2026-12-04T09:00:00Z"), hub.client().readSession()?.expiresAt)
        }

    @Test
    fun `a session the hub does not accept reads as nobody`() =
        runTest {
            hub.answer(401, """{"detail":"Not authenticated"}""")

            assertNull(hub.client().readSession())
            assertEquals(1, hub.requestCount)
        }

    @Test
    fun `a hub that cannot be reached is not the same as nobody`() =
        runTest {
            val address = hub.address
            hub.server.close()

            val failure = assertFailsWith<HubException> { hub.client(address = address).readSession() }

            assertEquals(HubErrorKind.OFFLINE, failure.kind)
        }

    @Test
    fun `a role this app does not know is unreadable, not a guess`() =
        runTest {
            hub.answer(200, """{"username":"olya","role":"owner","expires_at":"2026-11-04T14:48:46Z"}""")

            val failure = assertFailsWith<HubException> { hub.client().readSession() }

            assertEquals(HubErrorKind.UNREADABLE, failure.kind)
        }

    @Test
    fun `logging out deletes the session the token belongs to`() =
        runTest {
            hub.answer(204)

            hub.client().logOut()

            val request = hub.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/v1/session", request.target)
            assertEquals("pihome_session=test-session-token", request.headers["Cookie"])
            assertEquals("1", request.headers["X-Pihome-CSRF"])
        }

    @Test
    fun `logging out of a session the hub has forgotten is done already`() =
        runTest {
            hub.answer(401, """{"detail":"Not authenticated"}""")

            hub.client().logOut()
        }

    @Test
    fun `tokens never print`() {
        val signedIn = SignedIn(olya, SessionToken("new-token"))

        assertFalse("new-token" in signedIn.toString())
        assertFalse("haCY1" in InvitationToken("haCY1GiHstPg").toString())
    }
}
