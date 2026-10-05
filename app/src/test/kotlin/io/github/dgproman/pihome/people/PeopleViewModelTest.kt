package io.github.dgproman.pihome.people

import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.MainDispatcherRule
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.hub.Account
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Invitation
import io.github.dgproman.pihome.hub.InvitationToken
import io.github.dgproman.pihome.hub.ManagedRole
import io.github.dgproman.pihome.hub.Role
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
class PeopleViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val hubs = FakeHubs()
    private val clock = TestClock()
    private var refused = 0
    private var forbidden = 0
    private var issued = 0

    private fun account(
        username: String,
        role: Role,
    ) = Account(username, role, disabled = false, createdAt = clock.now.minusSeconds(86_400), invitationExpiresAt = null)

    /**
     * What the hub holds: each write changes it, each read reports it. A stub that
     * answered a write and left the list as it was would have the row spring back
     * on the next read: a test of the stub, not of the screen.
     */
    private var onHub = listOf(account("admin", Role.ADMIN), account("olya", Role.OPERATOR), account("taras", Role.VIEWER))

    private fun update(
        username: String,
        change: (Account) -> Account,
    ): Account {
        val found = onHub.find { it.username == username } ?: throw failure(HubErrorKind.NOT_FOUND)
        onHub = onHub.map { if (it == found) change(it) else it }
        return onHub.first { it.username == username }
    }

    init {
        hubs.accounts = { onHub }
        hubs.createAccount = { username, role ->
            if (onHub.any { it.username.equals(username, ignoreCase = true) }) throw failure(HubErrorKind.CONFLICT)
            account(username, role.role).also { onHub = onHub + it }
        }
        hubs.changeAccount = { username, change ->
            update(username) { it.copy(role = change.role?.role ?: it.role, disabled = change.disabled ?: it.disabled) }
        }
        hubs.deleteAccount = { username ->
            if (onHub.none { it.username == username }) throw failure(HubErrorKind.NOT_FOUND)
            onHub = onHub.filterNot { it.username == username }
        }
        hubs.issueInvitation = { username ->
            if (onHub.find { it.username == username }?.disabled == true) throw failure(HubErrorKind.CONFLICT)
            issued++
            val invitation = Invitation(InvitationToken("made-up-$issued"), clock.now.plus(Duration.ofMinutes(15)))
            update(username) { it.copy(invitationExpiresAt = invitation.expiresAt) }
            invitation
        }
        hubs.revokeInvitation = { username -> update(username) { it.copy(invitationExpiresAt = null) } }
    }

    /** Somebody used olya's invitation on another phone. */
    private fun redeem(username: String) {
        update(username) { it.copy(invitationExpiresAt = null) }
    }

    private fun model() =
        PeopleViewModel(
            hub = hubs.at(HOME.address, HOME.token),
            clock = clock,
            onRefused = { refused++ },
            onForbidden = { forbidden++ },
        )

    private fun PeopleViewModel.account(username: String): Account? =
        state.value.accounts.data!!
            .find { it.username == username }

    @Test
    fun `the accounts are read, with the time they arrived, every ten seconds while watched`() =
        runTest {
            val model = model()
            val watching = launch { model.poll() }
            runCurrent()

            assertEquals(onHub, model.state.value.accounts.data)
            assertEquals(clock.now, model.state.value.accounts.asOf)
            advanceTimeBy(PeopleViewModel.POLL_EVERY.toMillis() + 1)
            assertEquals(listOf("accounts", "accounts"), hubs.calls)

            watching.cancel()
            advanceTimeBy(60_000)
            assertEquals(2, hubs.calls.size)
        }

    @Test
    fun `a refused session costs one request, ends the session once, and nothing more is sent`() =
        runTest {
            hubs.accounts = { throw failure(HubErrorKind.UNAUTHORIZED) }
            val model = model()

            launch { model.poll() }
            advanceTimeBy(60_000)
            model.invite("olya")
            model.add("bohdana", ManagedRole.VIEWER)
            advanceUntilIdle()

            assertEquals(listOf("accounts"), hubs.calls)
            assertEquals(1, refused)
        }

    @Test
    fun `a failed read keeps the last list under the failure`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.accounts = { throw failure(HubErrorKind.OFFLINE) }

            model.refresh()
            advanceUntilIdle()

            assertEquals(
                3,
                model.state.value.accounts.data!!
                    .size,
            )
            assertEquals(HubErrorKind.OFFLINE, model.state.value.accounts.failure)
        }

    @Test
    fun `a role is changed by the hub, shown as it answered, and read back`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val answer = CompletableDeferred<Unit>()
            val change = hubs.changeAccount
            hubs.changeAccount = { username, c ->
                answer.await()
                change(username, c)
            }

            model.setRole("olya", ManagedRole.VIEWER)
            runCurrent()
            // Not shown before the hub has said so.
            assertEquals(Role.OPERATOR, model.account("olya")!!.role)
            assertEquals(setOf("olya"), model.state.value.busy)

            answer.complete(Unit)
            advanceUntilIdle()
            assertEquals(Role.VIEWER, model.account("olya")!!.role)
            assertEquals(emptySet<String>(), model.state.value.busy)
            assertEquals(listOf("accounts", "change olya VIEWER", "accounts"), hubs.calls)
        }

    @Test
    fun `a disabled account cannot be invited, and says why`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()

            model.setDisabled("taras", true)
            advanceUntilIdle()
            assertTrue(model.account("taras")!!.disabled)

            model.invite("taras")
            advanceUntilIdle()
            assertEquals(Failure(Action.INVITE, HubErrorKind.CONFLICT), model.state.value.failures["taras"])
            assertNull(model.state.value.shown)

            model.setDisabled("taras", false)
            advanceUntilIdle()
            // A new change clears the last one's failure.
            assertNull(model.state.value.failures["taras"])
            assertFalse(model.account("taras")!!.disabled)
        }

    @Test
    fun `a change whose answer was lost says it may have happened, and the list is read`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.changeAccount = { username, _ ->
                update(username) { it.copy(role = Role.VIEWER) }
                throw failure(HubErrorKind.TIMEOUT)
            }

            model.setRole("olya", ManagedRole.VIEWER)
            advanceUntilIdle()

            assertEquals(Failure(Action.ROLE, HubErrorKind.TIMEOUT), model.state.value.failures["olya"])
            assertEquals(Role.VIEWER, model.account("olya")!!.role)
        }

    @Test
    fun `a change refused as not allowed has the session checked, since the role may have changed`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            hubs.deleteAccount = { throw failure(HubErrorKind.FORBIDDEN) }

            model.delete("olya")
            advanceUntilIdle()

            assertEquals(1, forbidden)
            assertEquals(Failure(Action.DELETE, HubErrorKind.FORBIDDEN), model.state.value.failures["olya"])
        }

    @Test
    fun `a deleted account leaves the list, and one already gone says so`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()

            model.delete("taras")
            advanceUntilIdle()
            assertNull(model.account("taras"))

            onHub = onHub.filterNot { it.username == "olya" }
            model.delete("olya")
            advanceUntilIdle()
            assertEquals(Failure(Action.DELETE, HubErrorKind.NOT_FOUND), model.state.value.failures["olya"])
            // And the read after it shows the list as the hub has it.
            assertNull(model.account("olya"))
        }

    @Test
    fun `an outstanding invitation is withdrawn from its row`() =
        runTest {
            hubs.issueInvitation("olya")
            val model = model()
            model.refresh()
            advanceUntilIdle()

            model.withdraw("olya")
            advanceUntilIdle()

            assertNull(model.account("olya")!!.invitationExpiresAt)
            assertTrue("withdraw olya" in hubs.calls)
        }

    @Test
    fun `an invitation is shown, held only in memory, and replaced by the next one`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()

            model.invite("olya")
            assertEquals("olya", model.state.value.inviting)
            advanceUntilIdle()

            val shown = model.state.value.shown!!
            assertEquals("olya", shown.username)
            assertEquals(InvitationToken("made-up-1"), shown.invitation.token)
            assertFalse(shown.gone)
            assertNull(model.state.value.inviting)
            assertFalse("made-up" in model.state.value.toString())

            model.invite("olya")
            advanceUntilIdle()
            assertEquals(
                InvitationToken("made-up-2"),
                model.state.value.shown!!
                    .invitation.token,
            )
        }

    @Test
    fun `one invitation at a time`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val answer = CompletableDeferred<Unit>()
            val issue = hubs.issueInvitation
            hubs.issueInvitation = {
                answer.await()
                issue(it)
            }

            model.invite("olya")
            model.invite("taras")
            answer.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("invite olya"), hubs.calls.filter { it.startsWith("invite") })
        }

    @Test
    fun `a used invitation is noticed on the next read`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            model.invite("olya")
            advanceUntilIdle()

            clock.now = clock.now.plusSeconds(10)
            redeem("olya")
            model.refresh()
            advanceUntilIdle()

            assertTrue(
                model.state.value.shown!!
                    .gone,
            )
        }

    @Test
    fun `it is still used once it would have run out as well`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            model.invite("olya")
            advanceUntilIdle()
            redeem("olya")
            clock.now = clock.now.plusSeconds(10)
            model.refresh()
            advanceUntilIdle()

            clock.now = clock.now.plus(Duration.ofMinutes(20))
            model.refresh()
            advanceUntilIdle()

            assertTrue(
                model.state.value.shown!!
                    .gone,
            )
        }

    @Test
    fun `one that ran out is not called used`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            model.invite("olya")
            advanceUntilIdle()

            clock.now = clock.now.plus(Duration.ofMinutes(16))
            // The hub leaves an invitation that ran out off the list, as it does one that was used.
            redeem("olya")
            model.refresh()
            advanceUntilIdle()

            assertFalse(
                model.state.value.shown!!
                    .gone,
            )
        }

    @Test
    fun `a read that went out before the invitation was issued says nothing about it`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            // A poll goes out, and the hub's answer, from before the invitation, is slow to come.
            val before = onHub
            val slow = CompletableDeferred<Unit>()
            hubs.accounts = {
                slow.await()
                before
            }
            model.refresh()
            runCurrent()

            hubs.accounts = { onHub }
            model.invite("olya")
            runCurrent()
            slow.complete(Unit)
            advanceUntilIdle()

            assertFalse(
                model.state.value.shown!!
                    .gone,
            )
            assertTrue(model.account("olya")!!.invitationExpiresAt != null)
        }

    @Test
    fun `no read starts while a change is under way, and the change ends with one`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            val answer = CompletableDeferred<Unit>()
            val change = hubs.changeAccount
            hubs.changeAccount = { username, c ->
                answer.await()
                change(username, c)
            }

            model.setRole("olya", ManagedRole.VIEWER)
            runCurrent()
            model.refresh()
            runCurrent()
            answer.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("accounts", "change olya VIEWER", "accounts"), hubs.calls)
        }

    @Test
    fun `the invitation on screen is withdrawn and closed, or stays up and says why`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            model.invite("olya")
            advanceUntilIdle()

            hubs.revokeInvitation = { throw failure(HubErrorKind.SERVER) }
            model.withdrawShown()
            advanceUntilIdle()
            assertEquals(
                HubErrorKind.SERVER,
                model.state.value.shown!!
                    .withdrawFailure,
            )
            assertFalse(
                model.state.value.shown!!
                    .withdrawing,
            )

            hubs.revokeInvitation = { username -> update(username) { it.copy(invitationExpiresAt = null) } }
            model.withdrawShown()
            advanceUntilIdle()
            assertNull(model.state.value.shown)
            assertNull(model.account("olya")!!.invitationExpiresAt)
        }

    @Test
    fun `closing the invitation lets go of it and withdraws nothing`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()
            model.invite("olya")
            advanceUntilIdle()

            model.close()
            advanceUntilIdle()

            assertNull(model.state.value.shown)
            assertFalse(hubs.calls.any { it.startsWith("withdraw") })
        }

    @Test
    fun `a person is added and invited at once`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()

            model.add("bohdana", ManagedRole.OPERATOR)
            advanceUntilIdle()

            assertEquals(listOf("accounts", "create bohdana OPERATOR", "invite bohdana", "accounts"), hubs.calls)
            assertEquals(Role.OPERATOR, model.account("bohdana")!!.role)
            assertEquals(
                "bohdana",
                model.state.value.shown!!
                    .username,
            )
            assertEquals(Adding(added = 1), model.state.value.adding)
        }

    @Test
    fun `a name that breaks the rule is not sent`() =
        runTest {
            val model = model()

            model.add("Оля", ManagedRole.VIEWER)
            advanceUntilIdle()

            assertFalse(hubs.calls.any { it.startsWith("create") })
        }

    @Test
    fun `a name that is taken says so, and nobody is invited`() =
        runTest {
            val model = model()
            model.refresh()
            advanceUntilIdle()

            model.add("Olya", ManagedRole.VIEWER)
            advanceUntilIdle()

            assertEquals(Adding(failure = HubErrorKind.CONFLICT), model.state.value.adding)
            assertFalse(hubs.calls.any { it.startsWith("invite") })
            assertNull(model.state.value.shown)
        }

    @Test
    fun `a name the hub refuses although the rule here takes it says so`() =
        runTest {
            hubs.createAccount = { _, _ -> throw failure(HubErrorKind.MALFORMED) }
            val model = model()

            model.add("bohdana", ManagedRole.VIEWER)
            advanceUntilIdle()

            assertEquals(Adding(failure = HubErrorKind.MALFORMED), model.state.value.adding)
        }

    @Test
    fun `an account made whose invitation failed says why on its row`() =
        runTest {
            hubs.issueInvitation = { throw failure(HubErrorKind.SERVER) }
            val model = model()
            model.refresh()
            advanceUntilIdle()

            model.add("bohdana", ManagedRole.VIEWER)
            advanceUntilIdle()

            assertEquals(Failure(Action.INVITE, HubErrorKind.SERVER), model.state.value.failures["bohdana"])
            assertTrue(model.account("bohdana") != null)
        }
}
