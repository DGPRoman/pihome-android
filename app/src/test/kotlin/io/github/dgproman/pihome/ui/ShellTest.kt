package io.github.dgproman.pihome.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.dgproman.pihome.AppGraph
import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeLocalNetwork
import io.github.dgproman.pihome.FakeTileChoices
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.session.FakeCipher
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.session.SessionStore
import io.github.dgproman.pihome.session.sessionData
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What the shell says, in one language, for the tests to look for. */
data class Words(
    val notConnected: String,
    val house: String,
    /** What the house says when the hub has no relays, as the fake hub has none. */
    val noRelays: String,
    val account: String,
    val hub: String,
    val signOut: String,
    val operator: String,
    val back: String,
    val ended: String,
    val ok: String,
)

private val ENGLISH =
    Words(
        notConnected = "Not connected to a hub yet",
        house = "House",
        noRelays = "No relays are set up on the hub.",
        account = "Account",
        hub = "Hub",
        signOut = "Sign out",
        operator = "Operator",
        back = "Back",
        ended = "Your session has ended",
        ok = "OK",
    )

private val UKRAINIAN =
    Words(
        notConnected = "Ще не підключено до хаба",
        house = "Будинок",
        noRelays = "На хабі не налаштовано жодного реле.",
        account = "Акаунт",
        hub = "Хаб",
        signOut = "Вийти",
        operator = "Оператор",
        back = "Назад",
        ended = "Сеанс завершено",
        ok = "Зрозуміло",
    )

/** Sets the device configuration before the activity under test is made. */
class Qualifiers(
    private val qualifiers: String,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                RuntimeEnvironment.setQualifiers(qualifiers)
                base.evaluate()
            }
        }
}

/** Every state of the shell, in both languages and both themes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class ShellTest(
    private val qualifiers: String,
    private val words: Words,
) {
    @get:Rule(order = 0)
    val config = Qualifiers(qualifiers)

    @get:Rule(order = 1)
    val folder = TemporaryFolder()

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { SessionStore(folder.sessionData(), FakeCipher()) }
    private val hubs = FakeHubs()
    private val night get() = "night" in qualifiers

    @After
    fun stop() {
        scope.cancel()
    }

    private fun show() {
        val graph = AppGraph(store, scope, hubs, FakeLocalNetwork(), TestClock(), FakeTileChoices())
        var surface = Color.Unspecified
        compose.setContent {
            PihomeTheme {
                surface = MaterialTheme.colorScheme.surface
                PihomeApp(graph, version = "0.1.0")
            }
        }
        compose.waitForIdle()
        assertEquals(if (night) Color(0xFF14161A) else Color(0xFFFFFFFF), surface)
        this.graph = graph
    }

    private lateinit var graph: AppGraph

    private fun waitFor(text: String) {
        compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMillis = 5_000)
    }

    @Test
    fun `a phone that is not signed in is told how to connect`() {
        show()

        waitFor(words.notConnected)
        compose.onNodeWithText(words.notConnected).assertIsDisplayed()
    }

    @Test
    fun `a signed-in phone sees the house, then its account, and can sign out`() {
        runBlocking { store.save(HOME) }
        show()

        waitFor(words.house)
        waitFor(words.noRelays)

        compose.onNodeWithContentDescription(words.account).performClick()
        waitFor(words.hub)
        compose.onNodeWithText(HOME.address.origin).assertIsDisplayed()
        compose.onNodeWithText(HOME.session.username).assertIsDisplayed()
        compose.onNodeWithText(words.operator).assertIsDisplayed()
        compose.onNodeWithText("0.1.0").assertIsDisplayed()

        compose.onNodeWithText(words.signOut).performScrollTo().performClick()
        waitFor(words.notConnected)
        assertNull(runBlocking { store.read() })
        compose.waitUntil(timeoutMillis = 5_000) { "logout ${HOME.address}" in hubs.calls }
    }

    @Test
    fun `back from the account, by the bar or by the system, is the house`() {
        runBlocking { store.save(HOME) }
        show()
        waitFor(words.house)

        compose.onNodeWithContentDescription(words.account).performClick()
        waitFor(words.signOut)
        compose.onNodeWithContentDescription(words.back).performClick()
        waitFor(words.noRelays)

        compose.onNodeWithContentDescription(words.account).performClick()
        waitFor(words.signOut)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(words.noRelays)
    }

    @Test
    fun `a session the hub refused is explained, then the phone is signed out`() {
        runBlocking { store.save(HOME) }
        show()
        waitFor(words.house)

        graph.gate.refused(HOME.token)
        waitFor(words.ended)

        compose.onNodeWithText(words.ok).performClick()
        waitFor(words.notConnected)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configurations(): List<Array<Any>> =
            listOf(
                arrayOf("en", ENGLISH),
                arrayOf("uk", UKRAINIAN),
                arrayOf("en-night", ENGLISH),
                arrayOf("uk-night", UKRAINIAN),
            )
    }
}
