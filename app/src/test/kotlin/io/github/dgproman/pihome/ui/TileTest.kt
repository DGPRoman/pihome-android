package io.github.dgproman.pihome.ui

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.FakeTileChoices
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.quick.TileChoice
import io.github.dgproman.pihome.quick.TileSettingsViewModel
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.ui.screens.AccountScreen
import io.github.dgproman.pihome.ui.screens.TileContent
import io.github.dgproman.pihome.ui.screens.TileScreen
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.time.Instant

/** Choosing the tile's relay, in both languages. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class TileTest(
    qualifiers: String,
) {
    @get:Rule(order = 0)
    val config = Qualifiers(qualifiers)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val porch = Relay("porch", "Porch light", on = false)
    private val gate = Relay("gate", "Gate light", on = true)
    private val chosen = mutableListOf<String>()

    private fun text(
        @StringRes id: Int,
    ): String = compose.activity.getString(id)

    private fun show(
        chosenId: String? = null,
        mayChange: Boolean = true,
        canAddTile: Boolean = true,
    ) {
        compose.setContent {
            PihomeTheme {
                TileContent(
                    relays = Section(listOf(porch, gate), now),
                    chosen = chosenId,
                    mayChange = mayChange,
                    canAddTile = canAddTile,
                    onChoose = { chosen += it.id },
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
    fun `the account screen leads to the tile`() {
        var opened = false
        compose.setContent {
            PihomeTheme { AccountScreen(HOME, "0.1.0", TestClock(now), onBack = {}, onSignOut = {}, onTile = { opened = true }) }
        }

        compose.onNode(hasText(text(R.string.tile_title)) and hasClickAction()).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun `the relays are offered one at a time, with the chosen one marked`() {
        show(chosenId = "gate")

        seen(text(R.string.tile_body))
        compose.onNodeWithText("Gate light").assertIsSelected()
        compose.onNodeWithText("Porch light").assertIsNotSelected().performClick()

        assertEquals(listOf("porch"), chosen)
    }

    @Test
    fun `a viewer is told the tile would only open the app`() {
        show(mayChange = false)

        seen(text(R.string.tile_viewer))
    }

    @Test
    fun `android 13 and later are asked to add the tile`() {
        show(canAddTile = true)

        compose.onNode(hasText(text(R.string.tile_add)) and hasClickAction()).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `earlier android is told how to add it by hand`() {
        show(canAddTile = false)

        seen(text(R.string.tile_add_by_hand))
    }

    @Test
    fun `a relay chosen from the hub's list is kept, for this hub, and the tile is told`() {
        val hubs = FakeHubs()
        hubs.relays = { listOf(porch, gate) }
        val choices = FakeTileChoices()
        var told = 0
        val model = TileSettingsViewModel(hubs.at(HOME.address, HOME.token), choices, TestClock(now), onRefused = {}, onChosen = { told++ })
        compose.setContent { PihomeTheme { TileScreen(model, mayChange = true, onBack = {}) } }
        compose.waitUntilAtLeastOneExists(hasText("Gate light"), timeoutMillis = 5_000)

        compose.onNodeWithText("Gate light").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { told == 1 }

        assertEquals(TileChoice(HOME.address.origin, "gate", "Gate light"), runBlocking { choices.choice.first() })
        compose.onNodeWithText("Gate light").assertIsSelected()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("uk"))
    }
}
