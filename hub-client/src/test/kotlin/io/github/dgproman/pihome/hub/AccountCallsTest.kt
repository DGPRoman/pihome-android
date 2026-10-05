package io.github.dgproman.pihome.hub

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountCallsTest {
    @get:Rule val hub = FakeHub()

    private val olyaJson =
        """{"username":"olya","role":"viewer","disabled":false,"created_at":"2026-10-05T14:48:46.298851Z","invitation_expires_at":null}"""

    private val olya = Account("olya", Role.VIEWER, disabled = false, Instant.parse("2026-10-05T14:48:46.298851Z"), null)

    @Test
    fun `every account is read, with any invitation outstanding`() =
        runTest {
            hub.answer(
                200,
                """
                {"users":[
                  {"username":"boss","role":"admin","disabled":false,"created_at":"2026-10-05T14:48:46Z","invitation_expires_at":null},
                  {"username":"olya","role":"viewer","disabled":false,"created_at":"2026-10-05T14:48:46Z","invitation_expires_at":"2026-10-05T15:03:46Z"}
                ]}
                """,
            )

            val (boss, invited) = hub.client().accounts()

            assertEquals(Account("boss", Role.ADMIN, false, Instant.parse("2026-10-05T14:48:46Z"), null), boss)
            assertEquals(Instant.parse("2026-10-05T15:03:46Z"), invited.invitationExpiresAt)
            val request = hub.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/users", request.target)
        }

    @Test
    fun `an account the hub describes without its invitation field is unreadable`() =
        runTest {
            hub.answer(200, """{"users":[{"username":"olya","role":"viewer","disabled":false,"created_at":"2026-10-05T14:48:46Z"}]}""")

            val failure = assertFailsWith<HubException> { hub.client().accounts() }

            assertEquals(HubErrorKind.UNREADABLE, failure.kind)
        }

    @Test
    fun `an account is created with a name and a role`() =
        runTest {
            hub.answer(201, olyaJson)

            assertEquals(olya, hub.client().createAccount("olya", ManagedRole.VIEWER))
            val request = hub.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/users", request.target)
            assertEquals(Json.parseToJsonElement("""{"username":"olya","role":"viewer"}"""), Json.parseToJsonElement(request.text))
        }

    @Test
    fun `a name that is taken or not accepted says which`() =
        runTest {
            hub.answer(409, """{"detail":"username 'Olya' is taken"}""")
            hub.answer(422, """{"detail":"usernames are letters, digits, and . _ -"}""")
            val client = hub.client()

            assertEquals(HubErrorKind.CONFLICT, assertFailsWith<HubException> { client.createAccount("Olya", ManagedRole.VIEWER) }.kind)
            assertEquals(HubErrorKind.MALFORMED, assertFailsWith<HubException> { client.createAccount("o l", ManagedRole.VIEWER) }.kind)
        }

    @Test
    fun `a change sends only what changes`() =
        runTest {
            hub.answer(200, olyaJson.replace("viewer", "operator"))
            hub.answer(200, olyaJson.replace("false", "true"))
            val client = hub.client()

            assertEquals(Role.OPERATOR, client.changeAccount("olya", AccountChange(role = ManagedRole.OPERATOR)).role)
            assertEquals(true, client.changeAccount("olya", AccountChange(disabled = true)).disabled)

            val promoted = hub.takeRequest()
            assertEquals("PATCH", promoted.method)
            assertEquals("/v1/users/olya", promoted.target)
            assertEquals(Json.parseToJsonElement("""{"role":"operator"}"""), Json.parseToJsonElement(promoted.text))
            assertEquals(Json.parseToJsonElement("""{"disabled":true}"""), Json.parseToJsonElement(hub.takeRequest().text))
        }

    @Test
    fun `a change that names nothing is not one`() {
        assertFailsWith<IllegalArgumentException> { AccountChange() }
    }

    @Test
    fun `an account is deleted by name`() =
        runTest {
            hub.answer(204)

            hub.client().deleteAccount("olya")

            val request = hub.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/v1/users/olya", request.target)
        }

    @Test
    fun `an invitation is issued with an empty post, and its token returned`() =
        runTest {
            hub.answer(201, """{"token":"haCY1GiHstPgx6NmKIANrIW9y98ZDq4Bk3c7FChPsVU","expires_at":"2026-10-05T15:03:46.397053Z"}""")

            val invitation = hub.client().issueInvitation("olya")

            assertEquals(
                Invitation(InvitationToken("haCY1GiHstPgx6NmKIANrIW9y98ZDq4Bk3c7FChPsVU"), Instant.parse("2026-10-05T15:03:46.397053Z")),
                invitation,
            )
            val request = hub.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/users/olya/invitation", request.target)
            assertEquals(0L, request.bodySize)
        }

    @Test
    fun `an invitation with an empty token would open nothing, so is unreadable`() =
        runTest {
            hub.answer(201, """{"token":"","expires_at":"2026-10-05T15:03:46Z"}""")

            val failure = assertFailsWith<HubException> { hub.client().issueInvitation("olya") }

            assertEquals(HubErrorKind.UNREADABLE, failure.kind)
        }

    @Test
    fun `a disabled account cannot be invited`() =
        runTest {
            hub.answer(409, """{"detail":"The account is disabled. Enable it before inviting anyone to it"}""")

            val failure = assertFailsWith<HubException> { hub.client().issueInvitation("olya") }

            assertEquals(HubErrorKind.CONFLICT, failure.kind)
        }

    @Test
    fun `an invitation is withdrawn by the account's name`() =
        runTest {
            hub.answer(204)

            hub.client().revokeInvitation("olya")

            val request = hub.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/v1/users/olya/invitation", request.target)
        }
}
