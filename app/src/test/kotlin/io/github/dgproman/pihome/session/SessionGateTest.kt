package io.github.dgproman.pihome.session

import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.hub.Session
import io.github.dgproman.pihome.hub.SessionToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SessionGateTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { SessionStore(folder.sessionData(), FakeCipher()) }
    private val hubs = FakeHubs()

    private suspend fun TestScope.gate(): SessionGate {
        val gate = SessionGate(store, backgroundScope, hubs)
        gate.state.first { it != Gate.Loading }
        return gate
    }

    @Test
    fun `starts signed out with nothing saved`() =
        runTest {
            assertEquals(Gate.SignedOut, gate().state.value)
        }

    @Test
    fun `starts signed in with a session saved`() =
        runTest {
            store.save(HOME)

            assertEquals(Gate.SignedIn(HOME), gate().state.value)
        }

    @Test
    fun `signing in saves the session, and who it belongs to`() =
        runTest {
            val gate = gate()

            gate.signIn(HOME.address, HOME_OPENED).join()

            assertEquals(Gate.SignedIn(HOME), gate.state.value)
            assertEquals(HOME, store.read())
        }

    @Test
    fun `signing out forgets the session, then ends it on the hub`() =
        runTest {
            store.save(HOME)
            val gate = gate()
            var ended: SessionToken? = null
            hubs.logOut = { ended = it }

            gate.signOut().join()
            runCurrent()

            assertEquals(Gate.SignedOut, gate.state.value)
            assertNull(store.read())
            assertEquals(HOME.token, ended)
        }

    @Test
    fun `signing out does not wait for a hub that cannot be reached`() =
        runTest {
            store.save(HOME)
            val gate = gate()
            hubs.logOut = { throw failure(HubErrorKind.OFFLINE) }

            gate.signOut().join()
            runCurrent()

            assertEquals(Gate.SignedOut, gate.state.value)
            assertNull(store.read())
        }

    @Test
    fun `a refusal of the session held ends it, says so once, then signs out`() =
        runTest {
            store.save(HOME)
            val gate = gate()

            gate.refused(HOME.token).join()

            assertEquals(Gate.Ended, gate.state.value)
            assertNull(store.read())

            gate.acknowledgeEnded().join()

            assertEquals(Gate.SignedOut, gate.state.value)
        }

    @Test
    fun `a late refusal of an earlier session leaves the current one alone`() =
        runTest {
            store.save(HOME)
            val gate = gate()

            gate.refused(SessionToken("an-earlier-token")).join()

            assertEquals(Gate.SignedIn(HOME), gate.state.value)
            assertEquals(HOME, store.read())
        }

    @Test
    fun `acknowledging is nothing unless a session has ended`() =
        runTest {
            store.save(HOME)
            val gate = gate()

            gate.acknowledgeEnded().join()

            assertEquals(Gate.SignedIn(HOME), gate.state.value)
        }

    @Test
    fun `a check the hub refuses ends the session`() =
        runTest {
            store.save(HOME)
            val gate = gate()
            hubs.readSession = { null }

            gate.check().join()

            assertEquals(Gate.Ended, gate.state.value)
            assertNull(store.read())
        }

    @Test
    fun `a check keeps what the hub says now, a renewal or a new role`() =
        runTest {
            store.save(HOME)
            val gate = gate()
            val renewed = Session("olya", Role.VIEWER, HOME.session.expiresAt.plus(Duration.ofDays(30)))
            hubs.readSession = { token -> renewed.takeIf { token == HOME.token } }

            gate.check().join()

            val now = HOME.copy(session = renewed)
            assertEquals(Gate.SignedIn(now), gate.state.value)
            assertEquals(now, store.read())
        }

    @Test
    fun `a check that cannot reach the hub changes nothing`() =
        runTest {
            store.save(HOME)
            val gate = gate()
            hubs.readSession = { throw failure(HubErrorKind.TIMEOUT) }

            gate.check().join()

            assertEquals(Gate.SignedIn(HOME), gate.state.value)
            assertEquals(HOME, store.read())
        }

    @Test
    fun `nothing is checked with nobody signed in`() =
        runTest {
            val gate = gate()

            gate.check().join()

            assertEquals(emptyList<String>(), hubs.calls)
        }

    @Test
    fun `a check asked for while one is under way is that one`() =
        runTest {
            store.save(HOME)
            val gate = gate()
            val answer = CompletableDeferred<Session?>()
            hubs.readSession = { answer.await() }

            val first = gate.check()
            runCurrent()
            val second = gate.check()
            answer.complete(HOME.session)
            first.join()

            assertSame(first, second)
            assertEquals(1, hubs.calls.size)
        }

    @Test
    fun `a refusal that arrives after signing out and back in leaves the new session alone`() =
        runTest {
            store.save(HOME)
            val gate = gate()
            val answer = CompletableDeferred<Session?>()
            hubs.readSession = { answer.await() }
            val newer = HOME.copy(token = SessionToken("a-newer-token"))

            val check = gate.check()
            runCurrent()
            gate.signOut().join()
            gate.signIn(newer.address, HOME_OPENED.copy(token = newer.token)).join()
            answer.complete(null)
            check.join()

            assertEquals(Gate.SignedIn(newer), gate.state.value)
        }
}
