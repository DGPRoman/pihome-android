package io.github.dgproman.pihome.hub

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An admin bringing someone in by invitation, and taking them out again. */
class AccountsContractTest {
    private val hub = ContractHub.current

    @Test
    fun `an invitation lets a second phone in, under whatever role the admin gives it`() =
        runTest {
            val admin = hub.signIn()

            val created = admin.createAccount("olya", ManagedRole.VIEWER)
            assertEquals("olya", created.username)
            assertEquals(Role.VIEWER, created.role)
            assertFalse(created.disabled)
            assertNull(created.invitationExpiresAt)
            assertEquals(HubErrorKind.CONFLICT, failureOf { admin.createAccount("OLYA", ManagedRole.OPERATOR) })
            assertEquals(HubErrorKind.MALFORMED, failureOf { admin.createAccount("not a name", ManagedRole.VIEWER) })

            val invitation = admin.issueInvitation("olya")
            assertTrue(invitation.expiresAt > Instant.now())
            assertNotNull(admin.accounts().single { it.username == "olya" }.invitationExpiresAt)

            val joined = hub.anonymous().join(invitation.token)
            assertEquals("olya", joined.session.username)
            assertEquals(Role.VIEWER, joined.session.role)
            assertNull(admin.accounts().single { it.username == "olya" }.invitationExpiresAt)

            val phone = hub.client(joined.token)
            assertEquals(joined.session, phone.readSession())
            assertEquals(2, phone.relays().size)
            assertEquals(HubErrorKind.FORBIDDEN, failureOf { phone.setRelay("porch-light", on = true) })
            assertEquals(HubErrorKind.FORBIDDEN, failureOf { phone.accounts() })

            assertEquals(Role.OPERATOR, admin.changeAccount("olya", AccountChange(role = ManagedRole.OPERATOR)).role)
            assertEquals(Role.OPERATOR, phone.readSession()?.role)
            assertTrue(phone.setRelay("porch-light", on = true).on)

            assertTrue(admin.changeAccount("olya", AccountChange(disabled = true)).disabled)
            assertNull(phone.readSession())
            assertEquals(HubErrorKind.CONFLICT, failureOf { admin.issueInvitation("olya") })

            // Disabling pauses a session rather than ending it.
            assertFalse(admin.changeAccount("olya", AccountChange(disabled = false)).disabled)
            assertEquals("olya", phone.readSession()?.username)

            admin.deleteAccount("olya")
            assertNull(phone.readSession())
            assertTrue(admin.accounts().none { it.username == "olya" })
            assertEquals(HubErrorKind.NOT_FOUND, failureOf { admin.deleteAccount("olya") })

            admin.logOut()
        }

    @Test
    fun `a revoked invitation lets nobody in`() =
        runTest {
            val admin = hub.signIn()
            admin.createAccount("taras", ManagedRole.OPERATOR)
            val invitation = admin.issueInvitation("taras")

            admin.revokeInvitation("taras")
            // Revoking what is already gone is not a failure.
            admin.revokeInvitation("taras")

            assertNull(admin.accounts().single { it.username == "taras" }.invitationExpiresAt)
            assertEquals(HubErrorKind.UNAUTHORIZED, failureOf { hub.anonymous().join(invitation.token) })

            admin.deleteAccount("taras")
            admin.logOut()
        }

    @Test
    fun `an invitation is spent by its first use`() =
        runTest {
            val admin = hub.signIn()
            admin.createAccount("marta", ManagedRole.VIEWER)
            val invitation = admin.issueInvitation("marta")

            hub.anonymous().join(invitation.token)

            assertEquals(HubErrorKind.UNAUTHORIZED, failureOf { hub.anonymous().join(invitation.token) })

            admin.deleteAccount("marta")
            admin.logOut()
        }

    private suspend fun failureOf(block: suspend () -> Unit): HubErrorKind = assertFailsWith<HubException> { block() }.kind
}
