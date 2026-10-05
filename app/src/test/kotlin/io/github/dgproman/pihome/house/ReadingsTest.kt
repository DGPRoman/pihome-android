package io.github.dgproman.pihome.house

import io.github.dgproman.pihome.hub.Device
import io.github.dgproman.pihome.hub.Reading
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ReadingsTest {
    private val asOf = Instant.parse("2026-10-05T12:00:00Z")

    private fun before(seconds: Long): Instant = asOf.minusSeconds(seconds)

    @Test
    fun `an age is in the largest unit that fits, rounded down`() {
        assertEquals(Age.JustNow, ageOf(asOf, asOf))
        assertEquals(Age.JustNow, ageOf(asOf.minusMillis(999), asOf))
        assertEquals(Age.Seconds(1), ageOf(before(1), asOf))
        assertEquals(Age.Seconds(59), ageOf(before(59), asOf))
        assertEquals(Age.Minutes(1), ageOf(before(60), asOf))
        assertEquals(Age.Minutes(1), ageOf(before(119), asOf))
        assertEquals(Age.Minutes(59), ageOf(before(3_599), asOf))
        assertEquals(Age.Hours(1), ageOf(before(3_600), asOf))
        assertEquals(Age.Hours(23), ageOf(before(86_399), asOf))
        assertEquals(Age.Days(1), ageOf(before(86_400), asOf))
        assertEquals(Age.Days(40), ageOf(before(40 * 86_400L), asOf))
    }

    @Test
    fun `a time slightly ahead is the hub's clock running fast, and just now`() {
        assertEquals(Age.JustNow, ageOf(asOf.plusSeconds(4), asOf))
    }

    @Test
    fun `a reading is current up to its sensor's window, and stale past it`() {
        fun at(seconds: Long) = freshnessOf(Reading.Value(21.5, before(seconds)), 300.0, asOf)

        assertEquals(Freshness.CURRENT, at(0))
        assertEquals(Freshness.CURRENT, at(300))
        assertEquals(Freshness.STALE, freshnessOf(Reading.Value(21.5, before(300).minusMillis(1)), 300.0, asOf))
        assertEquals(Freshness.STALE, at(3_600))
        // From the future is the hub's clock, not a fault.
        assertEquals(Freshness.CURRENT, freshnessOf(Reading.Value(21.5, asOf.plusSeconds(30)), 300.0, asOf))
    }

    @Test
    fun `a reading with no time, or no window to judge it by, is neither current nor stale`() {
        assertEquals(Freshness.UNKNOWN, freshnessOf(Reading.Value(true, null), 300.0, asOf))
        assertEquals(Freshness.UNKNOWN, freshnessOf(Reading.Value(true, before(10)), null, asOf))
        assertEquals(Freshness.UNKNOWN, freshnessOf(Reading.Never, 300.0, asOf))
        assertEquals(Freshness.UNKNOWN, freshnessOf(Reading.Unsupported, 300.0, asOf))
    }

    @Test
    fun `a device stands where the hub says, and only a silent one is a fault`() {
        val device = Device(id = "pc", label = "PC", kind = "pc-power")

        assertEquals(Standing.NEVER_ANNOUNCED, standingOf(device))
        // Never announced wins: with no address there is nothing to ask.
        assertEquals(Standing.NEVER_ANNOUNCED, standingOf(device.copy(reachable = false)))
        assertEquals(Standing.NOT_POLLED, standingOf(device.copy(address = "192.168.1.50")))
        assertEquals(Standing.ANSWERING, standingOf(device.copy(address = "192.168.1.50", reachable = true)))
        assertEquals(Standing.SILENT, standingOf(device.copy(address = "192.168.1.50", reachable = false)))
    }

    @Test
    fun `a hold is said in the largest unit it is a whole number of`() {
        assertEquals(Span.Seconds(0), spanOf(0.0))
        assertEquals(Span.Seconds(45), spanOf(45.0))
        assertEquals(Span.Seconds(59), spanOf(59.4))
        assertEquals(Span.Minutes(1), spanOf(59.6))
        assertEquals(Span.Seconds(90), spanOf(90.0))
        assertEquals(Span.Minutes(2), spanOf(120.0))
        assertEquals(Span.Minutes(90), spanOf(5_400.0))
        assertEquals(Span.Hours(1), spanOf(3_600.0))
        assertEquals(Span.Hours(2), spanOf(7_200.0))
    }
}
