@file:UseSerializers(InstantAsText::class)

package io.github.dgproman.pihome.widget

import io.github.dgproman.pihome.house.Freshness
import io.github.dgproman.pihome.house.freshnessOf
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Reading
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Sensor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Everything the widget shows, kept on the phone between the moments it is
 * drawn: Android draws a widget whenever it likes, long after the app that
 * read the hub has gone.
 *
 * Times rather than ages throughout. A widget is not drawn again as the
 * minutes pass, so "2 minutes ago" would still be on the screen an hour
 * later, and "at 14:05" stays true.
 */
@Serializable
data class WidgetHouse(
    /** Why the widget cannot show the house, or null when it can. Nothing else is kept while it cannot. */
    val blocked: Blocked? = null,
    /** The origin of the hub the rest came from. */
    val hub: String? = null,
    val relays: List<WidgetRelay> = emptyList(),
    val sensors: List<WidgetSensor> = emptyList(),
    /** When the hub last answered. Null before it ever has. */
    val asOf: Instant? = null,
    /** Asking the hub for the whole house now. */
    val reading: Boolean = false,
    /** Why the last read failed. What is shown is from [asOf]. */
    val failure: HubErrorKind? = null,
) {
    /** Why the widget cannot act. A tap on it opens the app, where each is dealt with. */
    enum class Blocked { NOT_SIGNED_IN, READ_ONLY, NEEDS_PERMISSION }
}

/** One relay on the widget, and what became of the last press on it there. */
@Serializable
data class WidgetRelay(
    val id: String,
    val label: String,
    /** As the hub last said, or, while [press] is under way or unsure, as the press would leave it. */
    val on: Boolean,
    val press: Press? = null,
) {
    enum class Press {
        SWITCHING,

        /** No usable answer came back: the relay may well be as pressed. */
        UNSURE,

        /** The hub could not be reached, or refused. [on] is back to what it was. */
        FAILED,
    }
}

/** A sensor, said in one line. */
@Serializable
data class WidgetSensor(
    val label: String,
    /** What it reports: temperature, humidity, then motion. Empty when it has never reported anything. */
    val readings: List<WidgetReading>,
    /** When its newest reading was taken, if the hub said. */
    val at: Instant?,
)

/** One value: a temperature, a humidity, or whether there is motion. */
@Serializable
data class WidgetReading(
    val quantity: Quantity,
    /** Degrees, percent, or 1 for motion and 0 for none. */
    val value: Double,
    /** Older than the sensor's window when the hub answered. */
    val stale: Boolean,
) {
    enum class Quantity { MOTION, TEMPERATURE, HUMIDITY }
}

fun widgetRelayOf(relay: Relay): WidgetRelay = WidgetRelay(relay.id, relay.label, relay.on)

/**
 * A sensor, as the widget says it: each value it has reported, each judged on
 * its own time, as the house screen judges them. What it has never reported,
 * or does not report at all, is left out: a line has no room for absences.
 */
fun widgetSensorOf(
    sensor: Sensor,
    asOf: Instant,
): WidgetSensor {
    fun <T> reading(
        quantity: WidgetReading.Quantity,
        reading: Reading<T>,
        value: (T) -> Double,
    ): Pair<WidgetReading, Instant?>? {
        if (reading !is Reading.Value) return null
        val stale = freshnessOf(reading, sensor.staleAfterSeconds, asOf) == Freshness.STALE
        return WidgetReading(quantity, value(reading.value), stale) to reading.at
    }
    val found =
        listOfNotNull(
            reading(WidgetReading.Quantity.TEMPERATURE, sensor.temperature) { it },
            reading(WidgetReading.Quantity.HUMIDITY, sensor.humidity) { it },
            reading(WidgetReading.Quantity.MOTION, sensor.motion) { if (it) 1.0 else 0.0 },
        )
    return WidgetSensor(
        label = sensor.label,
        readings = found.map { it.first },
        at = found.mapNotNull { it.second }.maxOrNull() ?: sensor.lastSeen,
    )
}

/** Times as ISO-8601 text, as the hub sends them. */
internal object InstantAsText : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
