package io.github.dgproman.pihome.ui

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.dgproman.pihome.AppGraph
import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeLocalNetwork
import io.github.dgproman.pihome.FakeTileChoices
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.connect.Incoming
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.session.FakeCipher
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.session.HOME_OPENED
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration

/** The ways in, from the first screen to the house, in both languages. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class ConnectTest(
    qualifiers: String,
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
    private val network = FakeLocalNetwork()
    private val clock = TestClock()
    private lateinit var graph: AppGraph

    private val invitation = "http://192.168.1.20:5002/join#test-invitation"

    /** What the hub's join page hands the app, as it arrives. */
    private fun arrive(text: String = "pihome://join?hub=http%3A%2F%2F192.168.1.20%3A5002&token=test-invitation") {
        compose.runOnUiThread {
            graph.incoming.offer(
                when (val link = InvitationLink.parse(text)) {
                    is Parsed.Valid -> Incoming.Invitation(link.value, clock.now)
                    is Parsed.Invalid -> Incoming.Broken(link.problem)
                },
            )
        }
    }

    @After
    fun stop() {
        scope.cancel()
    }

    private fun show() {
        graph = AppGraph(store, scope, hubs, network, clock, FakeTileChoices())
        compose.setContent { PihomeTheme { PihomeApp(graph, version = "0.1.0") } }
    }

    private fun text(
        @StringRes id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    private fun waitFor(
        @StringRes id: Int,
        vararg args: Any,
    ) {
        compose.waitUntilAtLeastOneExists(hasText(text(id, *args), substring = true), timeoutMillis = 5_000)
    }

    private fun press(
        @StringRes id: Int,
    ) {
        // The control, not a title that may say the same.
        compose.onNode(hasText(text(id)) and hasClickAction()).performScrollTo().performClick()
    }

    /** A button in a dialog, which sits over the screen rather than in its scrolling column. */
    private fun tap(
        @StringRes id: Int,
    ) {
        compose.onNode(hasText(text(id)) and hasClickAction()).performClick()
    }

    @Test
    fun `an invitation handed over by the join page names its hub, and joining it opens the house`() {
        hubs.join = { HOME_OPENED }
        show()
        waitFor(R.string.paste_invitation)

        arrive()
        waitFor(R.string.join_body, HOME.address.origin)
        waitFor(R.string.join_button)
        assertEquals(listOf("health ${HOME.address}"), hubs.calls)

        press(R.string.join_button)
        waitFor(R.string.house_title)
        assertEquals(HOME, runBlocking { store.read() })
    }

    @Test
    fun `a link that is not a whole invitation is said on the first screen`() {
        show()
        waitFor(R.string.paste_invitation)

        arrive("pihome://join?hub=http%3A%2F%2F192.168.1.20%3A5002")

        waitFor(R.string.not_an_invitation)
        assertEquals(emptyList<String>(), hubs.calls)
    }

    @Test
    fun `an invitation arriving while signed in is asked about, and kept out unless used`() {
        runBlocking { store.save(HOME) }
        show()
        waitFor(R.string.house_title)

        arrive()
        waitFor(R.string.incoming_body, HOME.address.origin, HOME.session.username)
        tap(R.string.incoming_keep)
        compose.waitUntil(timeoutMillis = 5_000) { graph.incoming.next.value == null }
        assertEquals(HOME, runBlocking { store.read() })

        arrive()
        waitFor(R.string.incoming_title)
        tap(R.string.incoming_use)
        waitFor(R.string.join_body, HOME.address.origin)
        assertEquals(null, runBlocking { store.read() })
    }

    @Test
    fun `a pasted link is read before anything is sent`() {
        show()
        waitFor(R.string.paste_invitation)
        press(R.string.paste_invitation)
        waitFor(R.string.invitation_link)

        press(R.string.continue_button)
        waitFor(R.string.paste_blank)

        compose.onNode(hasSetTextAction()).performTextInput(invitation)
        press(R.string.continue_button)
        waitFor(R.string.join_body, HOME.address.origin)
    }

    @Test
    fun `a refused invitation says to ask for a new one, and offers no second try`() {
        hubs.join = { throw failure(HubErrorKind.UNAUTHORIZED) }
        show()
        waitFor(R.string.paste_invitation)
        arrive()
        waitFor(R.string.join_button)

        press(R.string.join_button)
        waitFor(R.string.join_refused)

        compose.onNodeWithText(text(R.string.join_button)).assertDoesNotExist()
        press(R.string.back_to_start)
        waitFor(R.string.paste_invitation)
    }

    @Test
    fun `a hub on the local network is explained before Android is asked`() {
        network.local = true
        show()
        waitFor(R.string.paste_invitation)

        arrive()

        waitFor(R.string.permission_body)
        compose.onNodeWithText(text(R.string.continue_button)).assertIsDisplayed()
        assertEquals(emptyList<String>(), hubs.calls)
    }

    @Test
    fun `a wrong password says so on the form`() {
        hubs.logIn = { _, _ -> throw failure(HubErrorKind.UNAUTHORIZED) }
        show()
        waitFor(R.string.log_in_with_password)
        press(R.string.log_in_with_password)
        waitFor(R.string.hub_address)

        compose.onNode(hasSetTextAction() and hasText(text(R.string.hub_address))).performTextInput("192.168.1.20:5002")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.username))).performTextInput("olya")
        compose.onNode(hasSetTextAction() and hasText(text(R.string.password))).performTextInput("wrong")
        press(R.string.log_in_button)

        waitFor(R.string.log_in_refused)
    }

    @Test
    fun `the account says when the session ends, and warns in its last days`() {
        clock.now = HOME.session.expiresAt.minus(Duration.ofDays(3).plusHours(2))
        runBlocking { store.save(HOME) }
        show()
        waitFor(R.string.house_title)

        compose
            .onNode(
                hasText(text(R.string.account_title)) or
                    androidx.compose.ui.test
                        .hasContentDescription(text(R.string.account_title)),
            ).performClick()

        val warning = compose.activity.resources.getQuantityString(R.plurals.session_ends_in_days, 3, 3)
        compose.waitUntilAtLeastOneExists(hasText(warning), timeoutMillis = 5_000)
        compose.onNodeWithText(text(R.string.role_operator)).assertIsDisplayed()
    }

    @Test
    fun `a session the hub no longer takes is found when the app checks, and explained`() {
        runBlocking { store.save(HOME) }
        hubs.readSession = { null }
        show()
        waitFor(R.string.house_title)

        graph.gate.check()

        waitFor(R.string.session_ended_title)
    }

    @Test
    fun `at twice the text size, the whole login form can still be reached`() {
        RuntimeEnvironment.setFontScale(2f)
        show()
        waitFor(R.string.log_in_with_password)
        press(R.string.log_in_with_password)
        waitFor(R.string.hub_address)

        compose.onNode(hasText(text(R.string.log_in_button)) and hasClickAction()).performScrollTo().assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("uk"))
    }
}
