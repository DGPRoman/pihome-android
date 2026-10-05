package io.github.dgproman.pihome.connect

import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeLocalNetwork
import io.github.dgproman.pihome.MainDispatcherRule
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.InputProblem
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

@RunWith(RobolectricTestRunner::class)
class LogInViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @get:Rule val folder = TemporaryFolder()

    private val hubs = FakeHubs()
    private val network = FakeLocalNetwork()
    private val health = "health ${HOME.address}"
    private val login = "login ${HOME.address}"

    private lateinit var gate: SessionGate

    private suspend fun TestScope.model(): LogInViewModel {
        gate = SessionGate(SessionStore(folder.sessionData(), FakeCipher()), backgroundScope, hubs)
        gate.state.first { it != Gate.Loading }
        return LogInViewModel(hubs, network, gate, this).apply {
            address = " 192.168.1.20:5002 "
            username = " olya "
            password = "correct horse"
        }
    }

    @Test
    fun `a password goes to a hub that answered its health check, and signs in`() =
        runTest {
            var offered: Pair<String, String>? = null
            hubs.logIn = { name, password ->
                offered = name to password
                HOME_OPENED
            }
            val model = model()

            model.logIn()
            assertEquals(LogInState.Reaching(Reach.Checking), model.state.value)
            advanceUntilIdle()
            gate.state.first { it is Gate.SignedIn }

            assertEquals(Gate.SignedIn(HOME), gate.state.value)
            assertEquals("olya" to "correct horse", offered)
            assertEquals(listOf(health, login), hubs.calls)
        }

    @Test
    fun `the password is being checked while the hub thinks about it`() =
        runTest {
            val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
            hubs.logIn = { _, _ ->
                answer.await()
                HOME_OPENED
            }
            val model = model()

            model.logIn()
            advanceUntilIdle()

            assertEquals(LogInState.Checking, model.state.value)
            answer.complete(Unit)
            gate.state.first { it is Gate.SignedIn }
        }

    @Test
    fun `what is missing or wrong in the form is said before anything is sent`() =
        runTest {
            val model = model()

            model.address = "ftp://hub.local"
            model.logIn()
            assertEquals(LogInState.Editing(LogInProblem.Address(InputProblem.NOT_HTTP)), model.state.value)

            model.address = "hub.local:5002"
            model.username = "  "
            model.logIn()
            assertEquals(LogInState.Editing(LogInProblem.NoUsername), model.state.value)

            model.username = "olya"
            model.password = ""
            model.logIn()
            assertEquals(LogInState.Editing(LogInProblem.NoPassword), model.state.value)

            advanceUntilIdle()
            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `a wrong password and too many attempts each say so, after one attempt`() =
        runTest {
            for (kind in listOf(HubErrorKind.UNAUTHORIZED, HubErrorKind.RATE_LIMITED)) {
                hubs.calls.clear()
                hubs.logIn = { _, _ -> throw failure(kind) }
                val model = LogInViewModel(hubs, network, gateFor(), this)
                model.address = "192.168.1.20:5002"
                model.username = "olya"
                model.password = "wrong"

                model.logIn()
                advanceUntilIdle()
                model.state.first { it is LogInState.Editing }

                assertEquals(LogInState.Editing(LogInProblem.Hub(kind)), model.state.value)
                assertEquals(listOf(health, login), hubs.calls)
            }
        }

    @Test
    fun `something that is not a hub is never sent the password`() =
        runTest {
            hubs.health = { throw failure(HubErrorKind.NOT_THE_HUB) }
            val model = model()

            model.logIn()
            advanceUntilIdle()

            assertEquals(LogInState.Editing(LogInProblem.Hub(HubErrorKind.NOT_THE_HUB)), model.state.value)
            assertEquals(listOf(health), hubs.calls)
        }

    @Test
    fun `a hub on the local network waits for the permission, then the login carries on`() =
        runTest {
            network.local = true
            hubs.logIn = { _, _ -> HOME_OPENED }
            val model = model()

            model.logIn()
            advanceUntilIdle()
            assertEquals(LogInState.Reaching(Reach.NeedsPermission), model.state.value)
            assertEquals(emptyList<String>(), hubs.calls)

            network.granted = true
            model.permissionAnswered(granted = true)
            advanceUntilIdle()
            gate.state.first { it is Gate.SignedIn }

            assertEquals(listOf(health, login), hubs.calls)
        }

    @Test
    fun `a refused permission sends nothing, and the form is there to go back to`() =
        runTest {
            network.local = true
            val model = model()

            model.logIn()
            advanceUntilIdle()
            model.permissionAnswered(granted = false)
            model.resumed()
            advanceUntilIdle()

            assertEquals(LogInState.Reaching(Reach.PermissionRefused), model.state.value)

            model.dismiss()

            assertEquals(LogInState.Editing(), model.state.value)
            assertEquals(emptyList<String>(), hubs.calls)
        }

    private var shared: SessionGate? = null

    /** One gate across a loop, as two over one file would clash. */
    private suspend fun TestScope.gateFor(): SessionGate =
        shared ?: SessionGate(SessionStore(folder.sessionData(), FakeCipher()), backgroundScope, hubs).also {
            it.state.first { state -> state != Gate.Loading }
            shared = it
        }
}
