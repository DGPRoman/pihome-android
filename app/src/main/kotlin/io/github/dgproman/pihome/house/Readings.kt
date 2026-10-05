package io.github.dgproman.pihome.house

import io.github.dgproman.pihome.hub.Device
import io.github.dgproman.pihome.hub.Reading
import java.time.Duration
import java.time.Instant

/** How long ago something happened, in the largest unit that fits, rounded down. */
sealed interface Age {
    /** Under a second, or a time the hub's clock put slightly ahead of this one. */
    data object JustNow : Age

    data class Seconds(
        val count: Int,
    ) : Age

    data class Minutes(
        val count: Int,
    ) : Age

    data class Hours(
        val count: Int,
    ) : Age

    data class Days(
        val count: Int,
    ) : Age
}

/**
 * How long before [asOf] the moment [at] was.
 *
 * Measured from when the data arrived rather than from now, so what the screen
 * says stays true of the data it shows. A time ahead of [asOf] is the hub's
 * clock running a little fast, not a reading from the future, so it is just now.
 */
fun ageOf(
    at: Instant,
    asOf: Instant,
): Age {
    val seconds = Duration.between(at, asOf).seconds
    return when {
        seconds < 1 -> Age.JustNow
        seconds < 60 -> Age.Seconds(seconds.toInt())
        seconds < 3_600 -> Age.Minutes((seconds / 60).toInt())
        seconds < 86_400 -> Age.Hours((seconds / 3_600).toInt())
        else -> Age.Days((seconds / 86_400).toInt())
    }
}

/** How far one reading can be trusted to describe the present. */
enum class Freshness {
    CURRENT,
    STALE,

    /**
     * Its age cannot be told: the hub sent no time for it, or no window to judge
     * it by. Shown as neither current nor stale, because it might be either.
     */
    UNKNOWN,
}

/**
 * Judge one reading by its own time, against the window of the sensor it came from.
 *
 * Not by the sensor's `stale` flag: the hub moves `last_seen` on any reading, so a
 * sensor sending temperature every minute is not stale while its motion is hours
 * old. Exactly at the window is still current; past it is stale.
 */
fun freshnessOf(
    reading: Reading<*>,
    staleAfterSeconds: Double?,
    asOf: Instant,
): Freshness {
    if (reading !is Reading.Value || reading.at == null || staleAfterSeconds == null) return Freshness.UNKNOWN
    val age = Duration.between(reading.at, asOf).toMillis()
    return if (age > staleAfterSeconds * 1_000) Freshness.STALE else Freshness.CURRENT
}

/**
 * Where a device stands with the hub. Four answers rather than two, because
 * three of them are ordinary and only [SILENT] is a fault.
 */
enum class Standing {
    /** Set up on the hub, and it has never said where it is. Nothing to poll yet. */
    NEVER_ANNOUNCED,

    /** It announced itself, and the hub has not asked it anything yet. */
    NOT_POLLED,

    ANSWERING,

    /** It announced itself, the hub asked, and no answer came. */
    SILENT,
}

/** What the hub says of a device, read as one of the four. The hub knows; nothing is guessed from times. */
fun standingOf(device: Device): Standing =
    when {
        device.address == null -> Standing.NEVER_ANNOUNCED
        device.reachable == null -> Standing.NOT_POLLED
        device.reachable == true -> Standing.ANSWERING
        else -> Standing.SILENT
    }

/** A length of time, in the largest unit it is a whole number of. */
sealed interface Span {
    data class Seconds(
        val count: Int,
    ) : Span

    data class Minutes(
        val count: Int,
    ) : Span

    data class Hours(
        val count: Int,
    ) : Span
}

/**
 * How long a rule holds a relay, as a person would say it: 120 seconds is two
 * minutes, 90 is ninety seconds, because "one and a half minutes" is not how
 * a timer is set. Rounded to the second, which is as fine as the hub keeps time.
 */
fun spanOf(seconds: Double): Span {
    val whole = Math.round(seconds).coerceAtLeast(0)
    return when {
        whole >= 3_600 && whole % 3_600 == 0L -> Span.Hours((whole / 3_600).toInt())
        whole >= 60 && whole % 60 == 0L -> Span.Minutes((whole / 60).toInt())
        else -> Span.Seconds(whole.toInt())
    }
}
