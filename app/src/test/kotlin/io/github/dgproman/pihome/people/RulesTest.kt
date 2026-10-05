package io.github.dgproman.pihome.people

import io.github.dgproman.pihome.hub.Account
import io.github.dgproman.pihome.hub.Invitation
import io.github.dgproman.pihome.hub.InvitationToken
import io.github.dgproman.pihome.hub.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RulesTest {
    private val issued = Instant.parse("2026-10-05T12:00:00Z")
    private val expires = issued.plus(Duration.ofMinutes(15))
    private val shown = Shown("olya", Invitation(InvitationToken("made-up"), expires), issuedAt = issued)

    private fun olya(invitationExpiresAt: Instant?) =
        Account("olya", Role.VIEWER, disabled = false, createdAt = issued.minusSeconds(3_600), invitationExpiresAt = invitationExpiresAt)

    private val later = issued.plusSeconds(10)

    // The web client's cases, asked of the same question.

    @Test
    fun `an invitation the hub still lists is not gone`() {
        assertFalse(wentAway(shown, listOf(olya(expires)), sentAt = later, arrivedAt = later))
    }

    @Test
    fun `listed with the fraction written another way, it is still the same one`() {
        assertFalse(wentAway(shown, listOf(olya(expires.plusMillis(400))), later, later))
        assertFalse(wentAway(shown, listOf(olya(expires.minusMillis(999))), later, later))
    }

    @Test
    fun `no longer listed before it ran out, it was used or withdrawn`() {
        assertTrue(wentAway(shown, listOf(olya(null)), later, later))
    }

    @Test
    fun `listed with another expiry, it was replaced somewhere else`() {
        assertTrue(wentAway(shown, listOf(olya(expires.plusSeconds(60))), later, later))
    }

    @Test
    fun `an account that is gone took its invitation with it`() {
        assertTrue(wentAway(shown, emptyList(), later, later))
    }

    @Test
    fun `a list sent before it was issued says nothing about it`() {
        assertFalse(wentAway(shown, listOf(olya(null)), sentAt = issued.minusMillis(1), arrivedAt = later))
    }

    @Test
    fun `a list that came back once it ran out says nothing about it either`() {
        assertFalse(wentAway(shown, listOf(olya(null)), sentAt = expires.minusSeconds(1), arrivedAt = expires))
    }

    @Test
    fun `the time left is rounded up to the minute, and is less than a minute at the end`() {
        assertEquals(TimeLeft.Minutes(15), timeLeft(expires, issued))
        assertEquals(TimeLeft.Minutes(15), timeLeft(expires, issued.plusMillis(1)))
        assertEquals(TimeLeft.Minutes(14), timeLeft(expires, issued.plusSeconds(60)))
        assertEquals(TimeLeft.Minutes(1), timeLeft(expires, expires.minusSeconds(60)))
        assertEquals(TimeLeft.LessThanAMinute, timeLeft(expires, expires.minusSeconds(59)))
        assertEquals(TimeLeft.LessThanAMinute, timeLeft(expires, expires.minusMillis(1)))
        assertNull(timeLeft(expires, expires))
        assertNull(timeLeft(expires, expires.plusSeconds(1)))
    }

    /** Each verdict is the hub's own, read from its `check_username`, as the web client's tests hold its copy to them. */
    @Test
    fun `names the hub takes`() {
        val names = listOf("olya", "ol", "Olya", "olya.k", "olya_k", "olya-k", "o.l-y_a", "olya..k", "a1", "007", "x".repeat(32))

        assertEquals(emptyList<String>(), names.filterNot(::isUsername))
    }

    @Test
    fun `names the hub refuses`() {
        val names =
            listOf(
                "",
                "o",
                "x".repeat(33),
                ".olya",
                "olya.",
                "-olya",
                "_olya",
                "olya_",
                "olya kovalenko",
                "olya@home",
                "olya/k",
                // Another alphabet, and a Latin name with one Cyrillic letter in it,
                // which looks like "ola" and is not: the reason the rule is Latin at all.
                "Оля",
                "olа",
                "olya\n",
            )

        assertEquals(emptyList<String>(), names.filter(::isUsername))
    }

    @Test
    fun `only an admin manages people`() {
        assertEquals(listOf(Role.ADMIN), Role.entries.filter { it.mayManagePeople })
    }
}
