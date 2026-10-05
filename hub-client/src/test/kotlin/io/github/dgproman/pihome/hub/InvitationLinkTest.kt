package io.github.dgproman.pihome.hub

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `a link the join page hands the app gives the hub and the token`() {
        val link = InvitationLink.parse("pihome://join?hub=http%3A%2F%2F192.168.1.20%3A5002&token=Ab3_-xYz").valid()

        assertEquals("http://192.168.1.20:5002", link.hub.origin)
        assertEquals("Ab3_-xYz", link.token.value)
    }

    @Test
    fun `the app link may come unencoded, in either order, with any case of scheme`() {
        val link = InvitationLink.parse("PIHOME://join?token=Ab3_-xYz&hub=https://hub.example.org").valid()

        assertEquals("https://hub.example.org", link.hub.origin)
        assertEquals("Ab3_-xYz", link.token.value)
    }

    @Test
    fun `an app link without both values, once each, is not an invitation`() {
        val texts =
            listOf(
                "pihome://join",
                "pihome://join?hub=http%3A%2F%2Fhub.local",
                "pihome://join?token=abc",
                "pihome://join?hub=http%3A%2F%2Fhub.local&token=",
                "pihome://join?hub=http%3A%2F%2Fhub.local&token=abc&token=def",
                "pihome://join?hub=http%3A%2F%2Fhub.local&token=abc&extra=1",
                "pihome://join?hub=http%3A%2F%2Fhub.local&token=%ZZ",
                "pihome://join/more?hub=http%3A%2F%2Fhub.local&token=abc",
                "pihome://joined?hub=http%3A%2F%2Fhub.local&token=abc",
                "pihome://join?hub=http%3A%2F%2Fhub.local&token=abc#more",
                "pihome:join",
                "pihome://join?hub=http://hub.local&token=a b",
            )
        for (text in texts) {
            assertEquals(Parsed.Invalid(InputProblem.NOT_AN_INVITATION), InvitationLink.parse(text), text)
        }
    }

    @Test
    fun `the hub in an app link is held to the address rules too`() {
        assertEquals(
            Parsed.Invalid(InputProblem.PUBLIC_OVER_PLAIN_HTTP),
            InvitationLink.parse("pihome://join?hub=http%3A%2F%2F203.0.113.9%3A5002&token=abc"),
        )
        assertEquals(
            Parsed.Invalid(InputProblem.MORE_THAN_AN_ADDRESS),
            InvitationLink.parse("pihome://join?hub=http%3A%2F%2Fhub.local%2Fjoin&token=abc"),
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

    @Test
    fun `a link survives being saved and read back, and is held to the rules again on the way in`() {
        val link = InvitationLink.parse("http://hub.local:5002/join#Ab3_-xYz").valid()

        val saved = Json.encodeToString(InvitationLink.serializer(), link)

        assertEquals("""{"hub":"http://hub.local:5002","token":"Ab3_-xYz"}""", saved)
        assertEquals(link, Json.decodeFromString(InvitationLink.serializer(), saved))
        assertFailsWith<SerializationException> {
            Json.decodeFromString(InvitationLink.serializer(), """{"hub":"http://203.0.113.9","token":"Ab3_-xYz"}""")
        }
    }

    @Test
    fun `an invitation is passed on as the web client's link, which reads back as itself`() {
        val link = InvitationLink(HubAddress.parse("http://[fd00::20]:5002").valid(), InvitationToken("Ab3_-xYz"))

        assertEquals("http://[fd00::20]:5002/join#Ab3_-xYz", link.webLink())
        assertEquals(link, InvitationLink.parse(link.webLink()).valid())
    }
}
