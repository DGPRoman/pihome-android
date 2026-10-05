package io.github.dgproman.pihome.connect

import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeLocalNetwork
import io.github.dgproman.pihome.MainDispatcherRule
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.hub.InvitationToken
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.session.FakeCipher
import io.github.dgproman.pihome.session.Gate
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.session.HOME_OPENED
import io.github.dgproman.pihome.session.SessionGate
import io.github.dgproman.pihome.session.SessionStore
import io.github.dgproman.pihome.session.sessionData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class JoinViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val folder = TemporaryFolder()

    private val hubs = FakeHubs()
    private val network = FakeLocalNetwork()
    private val clock = TestClock()
    private val link = (InvitationLink.parse("http://192.168.1.20:5002/join#test-invitation") as Parsed.Valid).value
    private val health = "health ${HOME.address}"
    private val join = "join ${HOME.address}"

    private lateinit var gate: SessionGate

    private suspend fun TestScope.model(receivedAgo: Duration = Duration.ofMinutes(1)): JoinViewModel {
        // One gate for the test, as there is one for the app: two over one file would clash.
        if (!::gate.isInitialized) {
            gate = SessionGate(SessionStore(folder.sessionData(), FakeCipher()), backgroundScope, hubs)
            gate.state.first { it != Gate.Loading }
        }
        // The test's own scope for the join, so stepping the test steps it too.
        return JoinViewModel(link, clock.now.minus(receivedAgo), hubs, network, gate, this, clock)
    }

    @Test
    fun `the hub is checked first, and the invitation goes only when the person says join`() =
        runTest {
            var presented: InvitationToken? = null
            hubs.join = {
                presented = it
                HOME_OPENED
            }
            val model = model()
            assertEquals(JoinState.Preparing(Reach.Checking), model.state.value)

            advanceUntilIdle()

            assertEquals(JoinState.Preparing(Reach.Reached), model.state.value)
            assertEquals(listOf(health), hubs.calls)

            model.join()
            assertEquals(JoinState.Joining, model.state.value)
            gate.state.first { it is Gate.SignedIn }

            assertEquals(Gate.SignedIn(HOME), gate.state.value)
            assertEquals(link.token, presented)
            assertEquals(listOf(health, join), hubs.calls)
        }

    @Test
    fun `join pressed again while joining sends nothing more`() =
        runTest {
            hubs.join = { HOME_OPENED }
            val model = model()
            advanceUntilIdle()

            model.join()
            model.join()
            advanceUntilIdle()

            assertEquals(1, hubs.calls.count { it == join })
        }

    @Test
    fun `a refused invitation says so, and cannot be sent again`() =
        runTest {
            hubs.join = { throw failure(HubErrorKind.UNAUTHORIZED) }
            val model = model()
            advanceUntilIdle()

            model.join()
            advanceUntilIdle()
            model.join()
            model.reach()
            advanceUntilIdle()

            assertEquals(JoinState.Over(Spent.REFUSED), model.state.value)
            assertEquals(listOf(health, join), hubs.calls)
        }

    @Test
    fun `a join whose reply was lost may have spent the invitation, and is not sent again`() =
        runTest {
            for (kind in listOf(HubErrorKind.TIMEOUT, HubErrorKind.UNREADABLE, HubErrorKind.SERVER)) {
                hubs.calls.clear()
                hubs.join = { throw failure(kind) }
                val model = model()
                advanceUntilIdle()

                model.join()
                advanceUntilIdle()
                model.join()
                advanceUntilIdle()

                assertEquals(kind.name, JoinState.Over(Spent.LOST), model.state.value)
                assertEquals(kind.name, 1, hubs.calls.count { it == join })
            }
        }

    @Test
    fun `an invitation older than any lasts is not sent at all`() =
        runTest {
            val model = model(receivedAgo = Duration.ofMinutes(16))
            advanceUntilIdle()

            model.join()
            advanceUntilIdle()

            assertEquals(JoinState.Over(Spent.EXPIRED), model.state.value)
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `an invitation that runs out while the screen is open is not sent`() =
        runTest {
            val model = model(receivedAgo = Duration.ofMinutes(14))
            advanceUntilIdle()

            clock.now = clock.now.plus(Duration.ofMinutes(2))
            model.join()
            advanceUntilIdle()

            assertEquals(JoinState.Over(Spent.EXPIRED), model.state.value)
            assertEquals(listOf(health), hubs.calls)
        }

    @Test
    fun `a join that never reached the hub leaves the invitation as it was, to try again`() =
        runTest {
            var attempts = 0
            hubs.join = {
                attempts++
                if (attempts == 1) throw failure(HubErrorKind.OFFLINE) else HOME_OPENED
            }
            val model = model()
            advanceUntilIdle()

            model.join()
            advanceUntilIdle()
            assertEquals(JoinState.Preparing(Reach.Failed(HubErrorKind.OFFLINE)), model.state.value)

            model.reach()
            advanceUntilIdle()
            model.join()
            gate.state.first { it is Gate.SignedIn }

            assertEquals(listOf(health, join, health, join), hubs.calls)
        }

    @Test
    fun `a hub that does not answer its health check is not offered the invitation`() =
        runTest {
            hubs.health = { throw failure(HubErrorKind.NOT_THE_HUB) }
            val model = model()
            advanceUntilIdle()

            model.join()
            advanceUntilIdle()

            assertEquals(JoinState.Preparing(Reach.Failed(HubErrorKind.NOT_THE_HUB)), model.state.value)
            assertEquals(listOf(health), hubs.calls)
        }

    @Test
    fun `a hub on the local network waits for the permission, then is checked`() =
        runTest {
            network.local = true
            val model = model()
            advanceUntilIdle()

            assertEquals(JoinState.Preparing(Reach.NeedsPermission), model.state.value)
            assertEquals(emptyList<String>(), hubs.calls)

            network.granted = true
            model.permissionAnswered(granted = true)
            advanceUntilIdle()

            assertEquals(JoinState.Preparing(Reach.Reached), model.state.value)
            assertEquals(listOf(health), hubs.calls)
        }

    @Test
    fun `a refused permission says so, and stays said until it is given in the settings`() =
        runTest {
            network.local = true
            val model = model()
            advanceUntilIdle()

            model.permissionAnswered(granted = false)
            model.resumed()
            advanceUntilIdle()

            assertEquals(JoinState.Preparing(Reach.PermissionRefused), model.state.value)
            assertEquals(emptyList<String>(), hubs.calls)

            network.granted = true
            model.resumed()
            advanceUntilIdle()

            assertEquals(JoinState.Preparing(Reach.Reached), model.state.value)
        }

    @Test
    fun `a hub that needs no permission is checked straight away`() =
        runTest {
            network.local = false
            val model = model()
            advanceUntilIdle()

            assertEquals(JoinState.Preparing(Reach.Reached), model.state.value)
        }
}
