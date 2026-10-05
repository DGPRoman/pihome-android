package io.github.dgproman.pihome.hub

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/** What came of reading something a person typed, pasted or scanned. */
sealed interface Parsed<out T> {
    data class Valid<out T>(
        val value: T,
    ) : Parsed<T>

    data class Invalid(
        val problem: InputProblem,
    ) : Parsed<Nothing>
}

/** Why an address or an invitation link was not accepted, for the app to put in words. */
enum class InputProblem {
    BLANK,
    NOT_AN_ADDRESS,

    /** A scheme other than `http` or `https`. */
    NOT_HTTP,

    /** A path, a query, a fragment or a user name: the hub is addressed by its origin alone. */
    MORE_THAN_AN_ADDRESS,

    /** Plain `http://` to an address written as numbers that is not on the home network. */
    PUBLIC_OVER_PLAIN_HTTP,

    /** An address, but not one of the hub's `/join#…` links. */
    NOT_AN_INVITATION,
}

/**
 * Where a hub is: a scheme, a host and a port, and nothing else.
 *
 * Only made by [parse], so every one in the app has passed its rules. Equal when
 * the origins are, whatever spelling they were typed in. Serialized as its
 * origin, and read back through [parse], so a stored one is held to the rules
 * of the build that reads it.
 */
@Serializable(with = HubAddressSerializer::class)
class HubAddress private constructor(
    private val root: HttpUrl,
) {
    /** `http://host:port`, without a trailing slash, as a person would write it. */
    val origin: String = root.toString().removeSuffix("/")

    /** The name or the address the hub is reached at, IPv6 without its brackets. */
    val host: String get() = root.host

    /**
     * Whether the address can lead only to this device: `localhost`, or a
     * loopback address. A hub reached this way is one this device forwards to,
     * and a link naming it opens nothing anywhere else.
     */
    val onlyThisDevice: Boolean
        get() =
            host.equals("localhost", ignoreCase = true) ||
                host.endsWith(".localhost", ignoreCase = true) ||
                (literalHost(host) as? Literal.Address)?.address?.isLoopbackAddress == true

    /** Plain `http://`, which [HomeNetworkDns] holds to the home network. */
    internal val isPlainHttp: Boolean get() = !root.isHttps

    internal fun url(segments: List<String>): HttpUrl =
        root
            .newBuilder()
            .apply { segments.forEach(::addPathSegment) }
            .build()

    override fun equals(other: Any?): Boolean = other is HubAddress && other.root == root

    override fun hashCode(): Int = root.hashCode()

    override fun toString(): String = origin

    companion object {
        /**
         * Read a hub address the way a person would write one.
         *
         * Without a scheme it is `http://`, since that is how a hub on the home
         * network is reached and what somebody typing `raspberrypi:5002` means.
         * An address written as numbers is checked here, because none reaches the
         * name lookup that checks every other one.
         */
        fun parse(text: String): Parsed<HubAddress> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return Parsed.Invalid(InputProblem.BLANK)
            }

            val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
            val scheme = withScheme.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") {
                return Parsed.Invalid(InputProblem.NOT_HTTP)
            }

            val url = withScheme.toHttpUrlOrNull() ?: return Parsed.Invalid(InputProblem.NOT_AN_ADDRESS)
            if (url.username.isNotEmpty() ||
                url.password.isNotEmpty() ||
                url.encodedPath != "/" ||
                url.query != null ||
                url.fragment != null
            ) {
                return Parsed.Invalid(InputProblem.MORE_THAN_AN_ADDRESS)
            }

            val literal =
                when (val host = literalHost(url.host)) {
                    is Literal.Address -> host.address
                    Literal.Malformed -> return Parsed.Invalid(InputProblem.NOT_AN_ADDRESS)
                    Literal.Name -> null
                }
            if (!url.isHttps && literal != null && !isHomeNetwork(literal)) {
                return Parsed.Invalid(InputProblem.PUBLIC_OVER_PLAIN_HTTP)
            }

            return Parsed.Valid(HubAddress(url))
        }

        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    }
}

internal object HubAddressSerializer : KSerializer<HubAddress> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.dgproman.pihome.hub.HubAddress", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): HubAddress =
        when (val parsed = HubAddress.parse(decoder.decodeString())) {
            is Parsed.Valid -> parsed.value
            is Parsed.Invalid -> throw SerializationException("not a hub address: ${parsed.problem}")
        }

    override fun serialize(
        encoder: Encoder,
        value: HubAddress,
    ) {
        encoder.encodeString(value.origin)
    }
}

private sealed interface Literal {
    data class Address(
        val address: InetAddress,
    ) : Literal

    /** Digits and dots that are not four numbers up to 255. */
    data object Malformed : Literal

    data object Name : Literal
}

/**
 * The address a host is written as, if it is written as one.
 *
 * The same test OkHttp makes before deciding to skip the name lookup: anything
 * with a colon, or only digits and dots. IPv4 is read here rather than handed to
 * [InetAddress.getByName], which takes `1.2.3` as `1.2.0.3` and looks `999.1.1.1`
 * up as a name, outside every rule this client sets for one.
 */
private fun literalHost(host: String): Literal =
    when {
        ':' in host -> {
            Literal.Address(InetAddress.getByName(host))
        }

        host.all { it.isDigit() || it == '.' } -> {
            val parts = host.split('.')
            val octets = parts.mapNotNull { part -> part.takeIf { it.length in 1..3 }?.toIntOrNull()?.takeIf { it <= 255 } }
            if (parts.size == 4 && octets.size == 4) {
                Literal.Address(InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() }))
            } else {
                Literal.Malformed
            }
        }

        else -> {
            Literal.Name
        }
    }
