package io.github.dgproman.pihome.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionGateTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { SessionStore(folder.sessionData(), FakeCipher()) }

    private suspend fun TestScope.gate(): SessionGate {
        val gate = SessionGate(store, backgroundScope)
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
    fun `signing in saves the session`() =
        runTest {
            val gate = gate()

            gate.signIn(HOME).join()

            assertEquals(Gate.SignedIn(HOME), gate.state.value)
            assertEquals(HOME, store.read())
        }

    @Test
    fun `signing out forgets the session`() =
        runTest {
            store.save(HOME)
            val gate = gate()

            gate.signOut().join()

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

            gate.refused("an-earlier-token").join()

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
}
