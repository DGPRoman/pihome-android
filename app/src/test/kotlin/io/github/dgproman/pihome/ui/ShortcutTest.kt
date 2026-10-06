package io.github.dgproman.pihome.ui

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.quick.RelayShortcut
import io.github.dgproman.pihome.quick.ShortcutRequest
import io.github.dgproman.pihome.quick.ShortcutResult
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.ui.screens.ShortcutDialog
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/** The window a launcher shortcut opens, in both languages. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ShortcutTest(
    qualifiers: String,
) {
    @get:Rule(order = 0)
    val config = Qualifiers(qualifiers)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val porch = ShortcutRequest.Switch(RelayShortcut(HOME.address.origin, "porch", "Porch light"))
    private var closed = 0
    private var opened = 0
    private var result by mutableStateOf<ShortcutResult?>(null)

    private fun text(
        @StringRes id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    private fun show(request: ShortcutRequest = porch) {
        compose.setContent {
            PihomeTheme {
                ShortcutDialog(request, result, onClose = { closed++ }, onOpenApp = { opened++ })
            }
        }
    }

    @Test
    fun `it says it is asking, then what came of it, and closes on its own a moment later`() {
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Porch light").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shortcut_asking)).assertIsDisplayed()

        result = ShortcutResult.Switched("Porch light", on = true)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(text(R.string.shortcut_now_on, "Porch light")).assertIsDisplayed()
        assertEquals(0, closed)

        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(1, closed)
    }

    @Test
    fun `all off says every relay is off`() {
        result = ShortcutResult.AllOff
        show(ShortcutRequest.AllOff)

        compose.onNodeWithText(text(R.string.all_off)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shortcut_all_off_done)).assertIsDisplayed()
    }

    @Test
    fun `a failure stays until it is closed, and offers the app`() {
        result = ShortcutResult.Failed(HubErrorKind.OFFLINE)
        show()

        compose.onNodeWithText(text(R.string.error_offline)).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(0, closed)

        compose.onNodeWithText(text(R.string.shortcut_open_app)).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(text(R.string.shortcut_close)).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `a switch that may have happened says so`() {
        result = ShortcutResult.Unsure("Porch light")
        show()

        compose.onNodeWithText(text(R.string.shortcut_unsure, "Porch light")).assertIsDisplayed()
    }

    @Test
    fun `all off that may have happened says so`() {
        result = ShortcutResult.Unsure(null)
        show(ShortcutRequest.AllOff)

        compose.onNodeWithText(text(R.string.shortcut_all_off_unsure)).assertIsDisplayed()
    }

    @Test
    fun `all lights says which way they went, and closes on its own`() {
        compose.mainClock.autoAdvance = false
        result = ShortcutResult.Lights(on = true)
        show(ShortcutRequest.Lights)
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText(text(R.string.shortcut_lights)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shortcut_lights_on)).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_000)
        assertEquals(1, closed)
    }

    @Test
    fun `all lights that may have happened says so`() {
        result = ShortcutResult.Unsure(null)
        show(ShortcutRequest.Lights)

        compose.onNodeWithText(text(R.string.shortcut_lights_unsure)).assertIsDisplayed()
    }

    @Test
    fun `all lights with none of its relays on the hub says so, and offers the app`() {
        result = ShortcutResult.NoLights
        show(ShortcutRequest.Lights)

        compose.onNodeWithText(text(R.string.shortcut_no_lights)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shortcut_open_app)).performClick()
        assertEquals(1, opened)
        assertEquals(0, closed)
    }

    @Test
    fun `a relay that has gone says so`() {
        result = ShortcutResult.Gone("Porch light")
        show()

        compose.onNodeWithText(text(R.string.shortcut_gone, "Porch light")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shortcut_open_app)).assertDoesNotExist()
    }

    @Test
    fun `a shortcut that cannot act opens the app`() {
        show()
        result = ShortcutResult.OpenApp
        compose.waitForIdle()

        assertEquals(1, opened)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("uk"))
    }
}
