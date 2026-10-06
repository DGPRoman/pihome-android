package io.github.dgproman.pihome.ui

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeLightsChoices
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.quick.LightsChoice
import io.github.dgproman.pihome.quick.LightsSettingsViewModel
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.ui.screens.AccountScreen
import io.github.dgproman.pihome.ui.screens.LightsContent
import io.github.dgproman.pihome.ui.screens.LightsScreen
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.time.Instant

/** Choosing the All lights shortcut's relays, in both languages. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class LightsTest(
    qualifiers: String,
) {
    @get:Rule(order = 0)
    val config = Qualifiers(qualifiers)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val porch = Relay("porch", "Porch light", on = false)
    private val gate = Relay("gate", "Gate light", on = true)
    private val pump = Relay("pump", "Pump", on = false)
    private val asked = mutableListOf<String>()

    private fun text(
        @StringRes id: Int,
    ): String = compose.activity.getString(id)

    private fun show(
        chosen: Set<String>? = null,
        mayChange: Boolean = true,
    ) {
        compose.setContent {
            PihomeTheme {
                LightsContent(
                    relays = Section(listOf(porch, gate), now),
                    chosen = chosen,
                    mayChange = mayChange,
                    onInclude = { relay, included -> asked += "${relay.id} $included" },
                    onRetry = {},
                    onBack = {},
                )
            }
        }
    }

    private fun seen(text: String) {
        compose.onNode(hasText(text)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the account screen leads to the shortcut's relays`() {
        var opened = false
        compose.setContent {
            PihomeTheme { AccountScreen(HOME, "0.1.0", TestClock(now), onBack = {}, onSignOut = {}, onLights = { opened = true }) }
        }

        compose.onNode(hasText(text(R.string.lights_title)) and hasClickAction()).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun `with nothing chosen every relay is ticked, and that is said`() {
        show()

        seen(text(R.string.lights_body))
        compose.onNodeWithText("Porch light").assertIsOn()
        compose.onNodeWithText("Gate light").assertIsOn().performClick()
        seen(text(R.string.lights_every_relay))

        assertEquals(listOf("gate false"), asked)
    }

    @Test
    fun `the chosen relays are ticked, and the last one cannot be cleared`() {
        show(chosen = setOf("gate"))

        compose.onNodeWithText("Gate light").assertIsOn().assertIsNotEnabled()
        compose
            .onNodeWithText("Porch light")
            .assertIsOff()
            .assertIsEnabled()
            .performClick()
        seen(text(R.string.lights_some_relays))

        assertEquals(listOf("porch true"), asked)
    }

    @Test
    fun `a viewer is told the shortcut would only open the app`() {
        show(mayChange = false)

        seen(text(R.string.lights_viewer))
    }

    @Test
    fun `a relay left out is kept out on this hub, and putting it back means every relay again`() {
        val hubs = FakeHubs()
        hubs.relays = { listOf(porch, gate, pump) }
        val choices = FakeLightsChoices()
        val model = LightsSettingsViewModel(hubs.at(HOME.address, HOME.token), choices, TestClock(now), onRefused = {})
        compose.setContent { PihomeTheme { LightsScreen(model, mayChange = true, onBack = {}) } }
        compose.waitUntilAtLeastOneExists(hasText("Pump"), timeoutMillis = 5_000)

        compose.onNodeWithText("Pump").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { choices.current != null }
        assertEquals(LightsChoice(HOME.address.origin, setOf("porch", "gate")), choices.current)
        compose.onNodeWithText("Pump").assertIsOff()

        compose.onNodeWithText("Pump").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { choices.current == null }
        compose.onNodeWithText("Pump").assertIsOn()
    }

    @Test
    fun `a choice made on another hub is not shown as this one's`() {
        val hubs = FakeHubs()
        hubs.relays = { listOf(porch, gate) }
        val choices = FakeLightsChoices(LightsChoice("http://192.168.1.50:5002", setOf("porch")))
        val model = LightsSettingsViewModel(hubs.at(HOME.address, HOME.token), choices, TestClock(now), onRefused = {})
        compose.setContent { PihomeTheme { LightsScreen(model, mayChange = true, onBack = {}) } }
        compose.waitUntilAtLeastOneExists(hasText("Gate light"), timeoutMillis = 5_000)

        compose.onNodeWithText("Gate light").assertIsOn()
        compose.onNodeWithText("Porch light").assertIsOn()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("uk"))
    }
}
