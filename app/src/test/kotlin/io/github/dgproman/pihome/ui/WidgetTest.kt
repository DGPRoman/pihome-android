package io.github.dgproman.pihome.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.isChecked
import androidx.glance.appwidget.testing.unit.isNotChecked
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasNoClickAction
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import io.github.dgproman.pihome.MainActivity
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.widget.HouseWidgetContent
import io.github.dgproman.pihome.widget.RefreshHouse
import io.github.dgproman.pihome.widget.WidgetHouse
import io.github.dgproman.pihome.widget.WidgetReading
import io.github.dgproman.pihome.widget.WidgetReading.Quantity
import io.github.dgproman.pihome.widget.WidgetRelay
import io.github.dgproman.pihome.widget.WidgetRelay.Press
import io.github.dgproman.pihome.widget.WidgetSensor
import io.github.dgproman.pihome.widget.WidgetWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/** The home-screen widget, and every word it says, in both languages. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class WidgetTest(
    qualifiers: String,
) {
    @get:Rule
    val config = Qualifiers(qualifiers)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val words get() = WidgetWords(context, now, ZoneOffset.UTC)

    private fun text(
        @StringRes id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    private val house =
        WidgetHouse(
            hub = HOME.address.origin,
            relays =
                listOf(
                    WidgetRelay("porch", "Porch light", on = true),
                    WidgetRelay("gate", "Gate", on = false, press = Press.SWITCHING),
                    WidgetRelay("yard", "Yard", on = false, press = Press.FAILED),
                ),
            sensors =
                listOf(
                    WidgetSensor(
                        "Hall",
                        listOf(WidgetReading(Quantity.TEMPERATURE, 21.0, stale = false), WidgetReading(Quantity.MOTION, 0.0, stale = true)),
                        now.minusSeconds(120),
                    ),
                    WidgetSensor("Attic", emptyList(), null),
                ),
            asOf = now,
        )

    private fun draw(
        shown: WidgetHouse,
        checks: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit,
    ) = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { HouseWidgetContent(shown, words) }
        checks()
    }

    @Test
    fun `each relay is a switch with its name and its state in words`() =
        draw(house) {
            onNode(hasText("Porch light")).assert(isChecked())
            onNode(hasText("Gate")).assert(isNotChecked())
            onNode(hasTextEqualTo(text(R.string.relay_on))).assertExists()
            onNode(hasTextEqualTo(text(R.string.tile_switching_off))).assertExists()
            onNode(hasTextEqualTo(text(R.string.widget_not_switched_off))).assertExists()
        }

    @Test
    fun `a switch takes no press while one is under way`() =
        draw(house) {
            onNode(hasText("Porch light")).assert(hasClickAction())
            onNode(hasText("Yard")).assert(hasClickAction())
            onNode(hasText("Gate")).assert(hasNoClickAction())
        }

    @Test
    fun `the title opens the app, and refresh reads the hub`() =
        draw(house) {
            onNode(hasStartActivityClickAction<MainActivity>()).assert(hasAnyDescendant(hasTextEqualTo(text(R.string.house_title))))
            onNode(
                hasRunCallbackClickAction<RefreshHouse>(),
            ).assert(hasAnyDescendant(hasContentDescriptionEqualTo(text(R.string.widget_refresh))))
            onNode(hasTextEqualTo(text(R.string.widget_updated, words.time(now)))).assertExists()
        }

    @Test
    fun `each sensor says what it reported, and when`() =
        draw(house) {
            onNode(hasTextEqualTo(text(R.string.sensors_title))).assertExists()
            val values =
                listOf(
                    text(R.string.temperature_value, "21"),
                    text(R.string.widget_stale_value, text(R.string.widget_no_motion)),
                ).joinToString(" · ")
            onNode(hasTextEqualTo(text(R.string.widget_sensor_line, values, words.time(now.minusSeconds(120))))).assertExists()
            onNode(hasTextEqualTo(text(R.string.no_readings))).assertExists()
        }

    @Test
    fun `a widget that cannot act says why, shows no switch, and opens the app`() =
        draw(house.copy(blocked = WidgetHouse.Blocked.NEEDS_PERMISSION)) {
            onNode(hasTextEqualTo(text(R.string.tile_needs_permission))).assert(hasStartActivityClickAction<MainActivity>())
            onAllNodes(hasText("Porch light")).assertCountEquals(0)
            onAllNodes(hasContentDescriptionEqualTo(text(R.string.widget_refresh))).assertCountEquals(0)
        }

    @Test
    fun `a hub with no relays says so`() =
        draw(WidgetHouse(hub = HOME.address.origin, asOf = now)) {
            onNode(hasTextEqualTo(text(R.string.relays_empty))).assertExists()
            onAllNodes(hasClickAction()).assertCountEquals(2)
        }

    @Test
    fun `the line under the title says how current the house is`() {
        assertEquals(text(R.string.widget_reading), words.status(house.copy(reading = true)))
        assertEquals(text(R.string.widget_failed_since, words.time(now)), words.status(house.copy(failure = HubErrorKind.OFFLINE)))
        assertEquals(text(R.string.error_offline), words.status(WidgetHouse(failure = HubErrorKind.OFFLINE)))
        assertEquals(text(R.string.widget_not_read), words.status(WidgetHouse()))
    }

    @Test
    fun `a time from another day has its date`() {
        val today = words.time(Instant.parse("2026-10-05T09:03:00Z"))
        val earlier = words.time(Instant.parse("2026-10-03T09:03:00Z"))

        assertTrue(today, "9:03" in today && "26" !in today)
        assertTrue(earlier, "9:03" in earlier && "26" in earlier)
        assertFalse(today == earlier)
    }

    @Test
    fun `a press that may have happened says so`() {
        assertEquals(text(R.string.relay_probably_on), words.state(WidgetRelay("porch", "Porch light", on = true, press = Press.UNSURE)))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("uk"))
    }
}
