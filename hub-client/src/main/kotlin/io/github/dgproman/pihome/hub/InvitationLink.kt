package io.github.dgproman.pihome.hub

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * An invitation as it travels, in either of two forms.
 *
 * - `<hub>/join#<token>`, as the web client builds it and its QR code holds.
 *   The token is after `#` because a browser never sends that part to a server.
 * - `pihome://join?hub=<hub origin>&token=<token>`, which the hub's join page
 *   hands this app through Android when the code was scanned with the camera.
 *   It never goes over the network, so the query is as private as the fragment.
 *
 * Carries both halves a phone needs, so one code is enough to find the hub and
 * to be let in. This app presents the token only in the body of the request
 * that redeems it.
 *
 * Serializable so the screen that offers to redeem it can be restored, and
 * printed with its token redacted.
 */
@Serializable
data class InvitationLink(
    val hub: HubAddress,
    val token: InvitationToken,
) {
    /**
     * The link as the web client builds it, `<hub>/join#<token>`: the form an
     * invitation is passed on in, which opens the hub's join page anywhere and
     * this app on a phone that has it. Carries the token, so it is for the
     * person it is meant for and for nothing that keeps a record.
     */
    fun webLink(): String = "${hub.origin}$JOIN_PATH#${token.value}"

    companion object {
        fun parse(text: String): Parsed<InvitationLink> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return Parsed.Invalid(InputProblem.BLANK)
            }
            if (trimmed.startsWith("$APP_SCHEME:", ignoreCase = true)) {
                return parseAppLink(trimmed)
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
            return of(origin.toString(), token)
        }

        /** `pihome://join?hub=…&token=…`: both values once each, and nothing else. */
        private fun parseAppLink(text: String): Parsed<InvitationLink> {
            val uri =
                try {
                    URI(text)
                } catch (_: URISyntaxException) {
                    return Parsed.Invalid(InputProblem.NOT_AN_INVITATION)
                }
            if (uri.rawAuthority != "join" || !uri.rawPath.isNullOrEmpty() || uri.rawFragment != null) {
                return Parsed.Invalid(InputProblem.NOT_AN_INVITATION)
            }
            val values =
                uri.rawQuery
                    .orEmpty()
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
            val names = values.map { it.first }
            if (names.sorted() != listOf("hub", "token")) {
                return Parsed.Invalid(InputProblem.NOT_AN_INVITATION)
            }
            val decoded =
                try {
                    // The two-argument form taking a name: the one older Android has.
                    values.associate { (name, value) -> name to URLDecoder.decode(value, "UTF-8").trim() }
                } catch (_: IllegalArgumentException) {
                    return Parsed.Invalid(InputProblem.NOT_AN_INVITATION)
                } catch (_: UnsupportedEncodingException) {
                    return Parsed.Invalid(InputProblem.NOT_AN_INVITATION)
                }
            return of(decoded.getValue("hub"), decoded.getValue("token"))
        }

        /** The hub held to the same rules as one typed in, and a token that is there. */
        private fun of(
            origin: String,
            token: String,
        ): Parsed<InvitationLink> {
            if (token.isEmpty()) return Parsed.Invalid(InputProblem.NOT_AN_INVITATION)
            return when (val hub = HubAddress.parse(origin)) {
                is Parsed.Invalid -> hub
                is Parsed.Valid -> Parsed.Valid(InvitationLink(hub.value, InvitationToken(token)))
            }
        }

        /** The scheme of the links the hub's join page hands this app. */
        const val APP_SCHEME = "pihome"

        private const val JOIN_PATH = "/join"

        private val JOIN_PATHS = setOf(JOIN_PATH, "/join/")
    }
}
