package io.github.dgproman.pihome.hub

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionContractTest {
    private val hub = ContractHub.current

    @Test
    fun `a password opens a session, which reads itself back until it is closed`() =
        runTest {
            val signedIn = hub.anonymous().logIn(hub.admin, hub.password)

            assertEquals(hub.admin, signedIn.session.username)
            assertEquals(Role.ADMIN, signedIn.session.role)
            assertTrue(signedIn.session.expiresAt > Instant.now())

            val client = hub.client(signedIn.token)
            assertEquals(signedIn.session, client.readSession())

            client.logOut()

            assertNull(client.readSession())
            assertEquals(HubErrorKind.UNAUTHORIZED, assertFailsWith<HubException> { client.relays() }.kind)
            // A session the hub no longer knows is closed already.
            client.logOut()
        }

    @Test
    fun `a wrong password is refused`() =
        runTest {
            val failure = assertFailsWith<HubException> { hub.anonymous().logIn(hub.admin, "not the password at all") }

            assertEquals(HubErrorKind.UNAUTHORIZED, failure.kind)
        }

    @Test
    fun `a write without the CSRF header is refused, which is why the client always sends it`() =
        runTest {
            val signedIn = hub.anonymous().logIn(hub.admin, hub.password)
            val request =
                Request
                    .Builder()
                    .url("${hub.address.origin}/v1/relays/porch-light")
                    .header("Cookie", "pihome_session=${signedIn.token.value}")
                    .put("""{"on":true}""".toRequestBody(ContractHub.JSON))
                    .build()

            hub.send(request).use { assertEquals(403, it.code) }

            // The same write through the client goes ahead.
            val client = hub.client(signedIn.token)
            assertTrue(client.setRelay("porch-light", on = true).on)
            client.logOut()
        }
}
