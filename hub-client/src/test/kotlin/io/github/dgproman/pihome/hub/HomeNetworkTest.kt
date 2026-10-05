package io.github.dgproman.pihome.hub

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeNetworkTest {
    @Test
    fun `the home network ranges are home, up to each edge`() {
        val home =
            listOf(
                "127.0.0.1",
                "127.255.255.254",
                "10.0.0.0",
                "10.255.255.255",
                "172.16.0.0",
                "172.31.255.255",
                "192.168.0.0",
                "192.168.255.255",
                "100.64.0.0",
                "100.127.255.255",
                "169.254.0.1",
                "169.254.255.254",
                "::1",
                "fe80::1",
                "febf::1",
                "fc00::1",
                "fdff::1",
            )
        for (text in home) {
            assertTrue(isHomeNetwork(InetAddress.getByName(text)), text)
        }
    }

    @Test
    fun `the addresses just outside each range are not`() {
        val outside =
            listOf(
                "0.0.0.0",
                "9.255.255.255",
                "11.0.0.0",
                "172.15.255.255",
                "172.32.0.0",
                "192.167.255.255",
                "192.169.0.0",
                "100.63.255.255",
                "100.128.0.0",
                "169.253.255.255",
                "169.255.0.0",
                "203.0.113.9",
                "::",
                "::2",
                "fec0::1",
                "fe7f::1",
                "fbff::1",
                "fe00::1",
                "2001:db8::1",
            )
        for (text in outside) {
            assertFalse(isHomeNetwork(InetAddress.getByName(text)), text)
        }
    }

    @Test
    fun `an IPv4 address written as IPv6 is judged as the IPv4 it is`() {
        fun mapped(vararg v4: Int): InetAddress {
            val bytes = ByteArray(16)
            bytes[10] = 0xff.toByte()
            bytes[11] = 0xff.toByte()
            v4.forEachIndexed { index, octet -> bytes[12 + index] = octet.toByte() }
            // Inet6Address directly, because InetAddress turns these into IPv4 itself.
            return Inet6Address.getByAddress(null, bytes, null)
        }

        assertTrue(isHomeNetwork(mapped(192, 168, 1, 20)))
        assertFalse(isHomeNetwork(mapped(203, 0, 113, 9)))
    }

    @Test
    fun `a name that resolves only to the home network is let through`() {
        val addresses = listOf(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("fd00::20"))
        val dns = HomeNetworkDns { addresses }

        assertEquals(addresses, dns.lookup("hub.local"))
    }

    @Test
    fun `a name with any address outside the home network is refused whole`() {
        val dns =
            HomeNetworkDns {
                listOf(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("203.0.113.9"))
            }

        assertFailsWith<PlainHttpRefusedException> { dns.lookup("hub.example.org") }
    }

    @Test
    fun `a name that does not resolve fails as it would have anyway`() {
        val dns = HomeNetworkDns { throw UnknownHostException(it) }

        val failure = assertFailsWith<UnknownHostException> { dns.lookup("no-such-host.invalid") }
        assertFalse(failure is PlainHttpRefusedException)
    }
}
