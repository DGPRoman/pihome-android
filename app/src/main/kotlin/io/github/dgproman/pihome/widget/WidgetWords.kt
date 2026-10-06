package io.github.dgproman.pihome.widget

import android.content.Context
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.ui.connect.messageFor
import io.github.dgproman.pihome.widget.WidgetHouse.Blocked
import io.github.dgproman.pihome.widget.WidgetReading.Quantity
import io.github.dgproman.pihome.widget.WidgetRelay.Press
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the widget says, in the phone's language, apart from Glance so it can
 * be tested. Every state is said in words, never by a colour alone.
 *
 * [now] is when the widget is drawn, and decides only whether a time needs
 * its date: nothing here says how long ago, which would go stale on the
 * screen (see [WidgetHouse]).
 */
class WidgetWords(
    private val context: Context,
    private val now: Instant,
    private val zone: ZoneId,
) {
    private val locale = context.resources.configuration.locales[0]
    private val numbers = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }

    /** The line under the title: whether the house shown is current, and from when. */
    fun status(house: WidgetHouse): String {
        val asOf = house.asOf
        val failure = house.failure
        return when {
            house.reading -> context.getString(R.string.widget_reading)
            failure != null && asOf != null -> context.getString(R.string.widget_failed_since, time(asOf))
            failure != null -> context.getString(messageFor(failure))
            asOf != null -> context.getString(R.string.widget_updated, time(asOf))
            else -> context.getString(R.string.widget_not_read)
        }
    }

    /** Why the widget cannot show the house, and what to do about it. */
    fun blocked(blocked: Blocked): String =
        context.getString(
            when (blocked) {
                Blocked.NOT_SIGNED_IN -> R.string.tile_not_connected
                Blocked.READ_ONLY -> R.string.widget_read_only
                Blocked.NEEDS_PERMISSION -> R.string.tile_needs_permission
            },
        )

    /** A relay's state, and what became of the last press on it. */
    fun state(relay: WidgetRelay): String =
        context.getString(
            when (relay.press) {
                null -> if (relay.on) R.string.relay_on else R.string.relay_off
                Press.SWITCHING -> if (relay.on) R.string.tile_switching_on else R.string.tile_switching_off
                Press.UNSURE -> if (relay.on) R.string.relay_probably_on else R.string.relay_probably_off
                Press.FAILED -> if (relay.on) R.string.widget_not_switched_on else R.string.widget_not_switched_off
            },
        )

    /** A sensor's values, each stale one marked, and the time of the newest. */
    fun sensor(sensor: WidgetSensor): String {
        if (sensor.readings.isEmpty()) return context.getString(R.string.no_readings)
        val values =
            sensor.readings.map { reading ->
                val value =
                    when (reading.quantity) {
                        Quantity.TEMPERATURE -> context.getString(R.string.temperature_value, numbers.format(reading.value))
                        Quantity.HUMIDITY -> context.getString(R.string.humidity_value, numbers.format(reading.value))
                        Quantity.MOTION -> context.getString(if (reading.value > 0) R.string.widget_motion else R.string.widget_no_motion)
                    }
                if (reading.stale) context.getString(R.string.widget_stale_value, value) else value
            }
        val at = sensor.at ?: return values.joinToString(SEPARATOR)
        return context.getString(R.string.widget_sensor_line, values.joinToString(SEPARATOR), time(at))
    }

    /** A time of day, with its date when it was not today. */
    fun time(at: Instant): String {
        val sameDay = at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
        val style =
            if (sameDay) {
                DateTimeFormatter.ofLocalizedTime(
                    FormatStyle.SHORT,
                )
            } else {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            }
        return style.withLocale(locale).withZone(zone).format(at)
    }

    private companion object {
        const val SEPARATOR = " · "
    }
}
