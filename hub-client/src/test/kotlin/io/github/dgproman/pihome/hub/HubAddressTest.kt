package io.github.dgproman.pihome.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HubAddressTest {
    @Test
    fun `an address without a scheme is plain http`() {
        val address = HubAddress.parse("raspberrypi:5002").valid()

        assertEquals("http://raspberrypi:5002", address.origin)
        assertTrue(address.isPlainHttp)
    }

    @Test
    fun `https is kept`() {
        val address = HubAddress.parse("https://hub.example.org").valid()

        assertEquals("https://hub.example.org", address.origin)
        assertFalse(address.isPlainHttp)
    }

    @Test
    fun `spaces, a trailing slash and capitals are tidied`() {
        assertEquals("http://hub.local:5002", HubAddress.parse("  HTTP://Hub.Local:5002/ ").valid().origin)
    }

    @Test
    fun `two spellings of one origin are equal`() {
        assertEquals(HubAddress.parse("hub.local:80").valid(), HubAddress.parse("http://hub.local/").valid())
    }

    @Test
    fun `nothing at all is blank`() {
        for (text in listOf("", "   ", "\n")) {
            assertEquals(Parsed.Invalid(InputProblem.BLANK), HubAddress.parse(text), "'$text'")
        }
    }

    @Test
    fun `a scheme other than http is refused`() {
        for (text in listOf("ftp://hub.local", "file:///etc/hosts", "ws://hub.local")) {
            assertEquals(Parsed.Invalid(InputProblem.NOT_HTTP), HubAddress.parse(text), text)
        }
    }

    @Test
    fun `text that is not an address is refused`() {
        for (text in listOf("http://", "hub local", "http://[zz::1]", "999.1.1.1", "1.2.3", "10.0.0.5.6")) {
            assertEquals(Parsed.Invalid(InputProblem.NOT_AN_ADDRESS), HubAddress.parse(text), text)
        }
    }

    @Test
    fun `anything past the origin is refused`() {
        val texts =
            listOf(
                "http://hub.local/v1",
                "http://hub.local/?next=1",
                "http://hub.local/#top",
                "http://me:secret@hub.local",
                "http://hub.local/join#token",
            )
        for (text in texts) {
            assertEquals(Parsed.Invalid(InputProblem.MORE_THAN_AN_ADDRESS), HubAddress.parse(text), text)
        }
    }

    @Test
    fun `a public address written as numbers is refused over plain http`() {
        val texts =
            listOf(
                "203.0.113.9",
                "http://198.51.100.7:5002",
                "http://[2001:db8::1]:5002",
                "http://[::ffff:203.0.113.9]",
            )
        for (text in texts) {
            assertEquals(Parsed.Invalid(InputProblem.PUBLIC_OVER_PLAIN_HTTP), HubAddress.parse(text), text)
        }
    }

    @Test
    fun `a public address written as numbers is fine over https`() {
        assertEquals("https://203.0.113.9", HubAddress.parse("https://203.0.113.9").valid().origin)
    }

    @Test
    fun `home network addresses written as numbers are fine over plain http`() {
        val texts =
            listOf(
                "192.168.1.20:5002",
                "10.0.0.5",
                "172.16.0.1",
                "100.100.1.1",
                "127.0.0.1:5002",
                "169.254.10.1",
                "[fd00::1]",
                "[fe80::1]",
                "[::1]:5002",
            )
        for (text in texts) {
            assertTrue(HubAddress.parse(text) is Parsed.Valid, text)
        }
    }

    @Test
    fun `path segments are encoded`() {
        val address = HubAddress.parse("hub.local:5002").valid()

        assertEquals("/v1/users/o%2Fl%20ya", address.url(listOf("v1", "users", "o/l ya")).encodedPath)
    }

    @Test
    fun `localhost and loopback addresses lead only to this device`() {
        val only =
            listOf(
                "http://localhost:5002",
                "http://hub.localhost",
                "http://LOCALHOST",
                "http://127.0.0.1:5002",
                "http://127.8.9.1",
                "http://[::1]:5002",
            )
        val reachable =
            listOf(
                "http://192.168.1.20:5002",
                "http://[fd00::20]",
                "http://hub.local",
                "https://hub.example.org",
                "http://localhost.example",
            )

        assertEquals(emptyList<String>(), only.filterNot { HubAddress.parse(it).valid().onlyThisDevice })
        assertEquals(emptyList<String>(), reachable.filter { HubAddress.parse(it).valid().onlyThisDevice })
    }
}
