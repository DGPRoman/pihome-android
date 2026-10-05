package io.github.dgproman.pihome.hub

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * An invitation as it travels: `<hub>/join#<token>`, as the web client builds it.
 *
 * Carries both halves a phone needs, so scanning one code is enough to find the
 * hub and to be let in. The token is after `#` because a browser never sends that
 * part to a server; this app never does either, and presents it only in the body
 * of the request that redeems it.
 *
 * Serializable so the screen that offers to redeem it can be restored, and
 * printed with its token redacted.
 */
@Serializable
data class InvitationLink(
    val hub: HubAddress,
    val token: InvitationToken,
) {
    companion object {
        fun parse(text: String): Parsed<InvitationLink> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return Parsed.Invalid(InputProblem.BLANK)
            }

            val url =
                (if ("://" in trimmed) trimmed else "http://$trimmed").toHttpUrlOrNull()
                    ?: return Parsed.Invalid(InputProblem.NOT_AN_INVITATION)
            val token = url.fragment?.trim().orEmpty()
            if (url.encodedPath !in JOIN_PATHS || url.query != null || token.isEmpty()) {
                return Parsed.Invalid(InputProblem.NOT_AN_INVITATION)
            }

            // The rest is the hub's origin, held to the same rules as one typed in.
            // A user name in it is kept, so that the address rules refuse it.
            val origin =
                url
                    .newBuilder()
                    .encodedPath("/")
                    .fragment(null)
                    .build()
            return when (val hub = HubAddress.parse(origin.toString())) {
                is Parsed.Invalid -> hub
                is Parsed.Valid -> Parsed.Valid(InvitationLink(hub.value, InvitationToken(token)))
            }
        }

        private val JOIN_PATHS = setOf("/join", "/join/")
    }
}
