package io.github.dgproman.pihome.hub

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Whether an address can only be on this device or a network it is part of.
 *
 * Loopback, the RFC 1918 ranges, `100.64.0.0/10` (where carrier NAT and mesh
 * VPNs number their peers), link-local, and IPv6 unique local. These are the
 * places a hub on plain `http://` is allowed to be: traffic to them does not
 * cross the internet, so a password and a session cookie in the clear stay on
 * a network the household runs.
 *
 * Decided on the address bytes, never on a name. `hub.lan` is only a promise,
 * and a resolver may break it.
 */
internal fun isHomeNetwork(address: InetAddress): Boolean {
    val bytes = address.address
    return when {
        address is Inet4Address -> isHomeNetworkV4(bytes)
        address is Inet6Address && isIpv4Mapped(bytes) -> isHomeNetworkV4(bytes.copyOfRange(12, 16))
        address is Inet6Address -> isHomeNetworkV6(bytes)
        else -> false
    }
}

private fun isHomeNetworkV4(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return first == 127 ||
        first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 100 && second in 64..127) ||
        (first == 169 && second == 254)
}

private fun isHomeNetworkV6(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    val loopback = bytes.copyOfRange(0, 15).all { it == 0.toByte() } && bytes[15] == 1.toByte()
    return loopback ||
        // fe80::/10, link-local.
        (first == 0xfe && (second and 0xc0) == 0x80) ||
        // fc00::/7, unique local.
        (first and 0xfe) == 0xfc
}

/** `::ffff:a.b.c.d`: an IPv4 address written as IPv6, judged as the IPv4 it is. */
private fun isIpv4Mapped(bytes: ByteArray): Boolean =
    bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
        bytes[10] == 0xff.toByte() &&
        bytes[11] == 0xff.toByte()

/**
 * Whether reaching [this] hub means reaching into the network the phone is on.
 *
 * Android 17 asks an app for permission before it connects to a device on the
 * local network, and without it a connection to one simply hangs. This is the
 * question of whether to ask: the hub's host, looked up with [dns], is on the
 * home network and is not this device itself. An address written as numbers
 * comes back from the lookup as it is.
 *
 * A name that does not resolve is taken as local over plain `http://`, where
 * only the home network is allowed anyway and a `.local` name may need the
 * permission to be found at all, and as not local over `https://`.
 *
 * Blocks while the name is looked up.
 */
fun HubAddress.reachesLocalNetwork(dns: Dns = Dns.SYSTEM): Boolean {
    val addresses =
        try {
            dns.lookup(host)
        } catch (_: UnknownHostException) {
            return isPlainHttp
        }
    return addresses.any { isHomeNetwork(it) && !it.isLoopbackAddress }
}

/** Raised in place of an answer from [HomeNetworkDns]. Becomes [HubErrorKind.INSECURE]. */
internal class PlainHttpRefusedException(
    host: String,
) : UnknownHostException("$host resolves outside the home network, so plain http is refused")

/**
 * Name lookup for a hub on plain `http://`: every address the name resolves to
 * must be on the home network, or none of them is used.
 *
 * Every one rather than any one, because the connection may go to whichever the
 * client tries first. Checked at the moment of connecting rather than when the
 * address was typed in, so a name that resolved to the house yesterday and to
 * somewhere else today is refused today.
 *
 * Covers names only. An address written as numbers never reaches a [Dns], so
 * [HubAddress.parse] refuses a public one before a client is built for it.
 */
internal class HomeNetworkDns(
    private val delegate: Dns,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.any { !isHomeNetwork(it) }) {
            throw PlainHttpRefusedException(hostname)
        }
        return addresses
    }
}
