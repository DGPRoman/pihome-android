package io.github.dgproman.pihome.hub

import kotlin.test.Test
import kotlin.test.assertEquals

class InvitationLinkTest {
    @Test
    fun `a link the web client builds gives the hub and the token`() {
        val link = InvitationLink.parse("http://192.168.1.20:5002/join#Ab3_-xYz").valid()

        assertEquals("http://192.168.1.20:5002", link.hub.origin)
        assertEquals("Ab3_-xYz", link.token.value)
    }

    @Test
    fun `a trailing slash on the path and spaces around the link are fine`() {
        val link = InvitationLink.parse("  https://hub.example.org/join/#token \n").valid()

        assertEquals("https://hub.example.org", link.hub.origin)
        assertEquals("token", link.token.value)
    }

    @Test
    fun `a link pasted without its scheme is plain http, as an address would be`() {
        assertEquals(
            "http://hub.local:5002",
            InvitationLink
                .parse("hub.local:5002/join#token")
                .valid()
                .hub.origin,
        )
    }

    @Test
    fun `nothing at all is blank`() {
        assertEquals(Parsed.Invalid(InputProblem.BLANK), InvitationLink.parse(" "))
    }

    @Test
    fun `anything that is not a join link with a token is not an invitation`() {
        val texts =
            listOf(
                "http://hub.local:5002",
                "http://hub.local:5002/join",
                "http://hub.local:5002/join#",
                "http://hub.local:5002/join#   ",
                "http://hub.local:5002/joined#token",
                "http://hub.local:5002/join?token=abc",
                "http://hub.local:5002/join?x=1#token",
                "ftp://hub.local/join#token",
                "WIFI:S:home;T:WPA;P:secret;;",
            )
        for (text in texts) {
            assertEquals(Parsed.Invalid(InputProblem.NOT_AN_INVITATION), InvitationLink.parse(text), text)
        }
    }

    @Test
    fun `the hub in a link is held to the address rules`() {
        assertEquals(
            Parsed.Invalid(InputProblem.PUBLIC_OVER_PLAIN_HTTP),
            InvitationLink.parse("http://203.0.113.9:5002/join#token"),
        )
        assertEquals(
            Parsed.Invalid(InputProblem.MORE_THAN_AN_ADDRESS),
            InvitationLink.parse("http://me@hub.local/join#token"),
        )
    }

    @Test
    fun `the token never prints`() {
        val link = InvitationLink.parse("http://hub.local/join#very-secret").valid()

        assertEquals(false, "very-secret" in link.toString())
    }
}
