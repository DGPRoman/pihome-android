package io.github.dgproman.pihome.ui.connect

import androidx.annotation.StringRes
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.InputProblem

/** What to tell a person whose request to the hub failed this way. */
@StringRes
fun messageFor(kind: HubErrorKind): Int =
    when (kind) {
        HubErrorKind.OFFLINE -> R.string.error_offline

        HubErrorKind.TIMEOUT -> R.string.error_timeout

        HubErrorKind.INSECURE -> R.string.error_insecure

        HubErrorKind.NOT_THE_HUB -> R.string.error_not_the_hub

        HubErrorKind.RATE_LIMITED -> R.string.error_rate_limited

        HubErrorKind.SERVER -> R.string.error_server

        HubErrorKind.UNAUTHORIZED, HubErrorKind.FORBIDDEN, HubErrorKind.NOT_FOUND, HubErrorKind.CONFLICT,
        HubErrorKind.MALFORMED, HubErrorKind.UNREADABLE, HubErrorKind.UNEXPECTED,
        -> R.string.error_other
    }

/** What is wrong with an address a person typed, or one inside a link they brought. */
@StringRes
fun messageFor(problem: InputProblem): Int =
    when (problem) {
        InputProblem.BLANK -> R.string.address_blank
        InputProblem.NOT_AN_ADDRESS -> R.string.address_invalid
        InputProblem.NOT_HTTP -> R.string.address_not_http
        InputProblem.MORE_THAN_AN_ADDRESS -> R.string.address_more
        InputProblem.PUBLIC_OVER_PLAIN_HTTP -> R.string.address_public_http
        InputProblem.NOT_AN_INVITATION -> R.string.not_an_invitation
    }

/** The same, for an invitation link: anything that does not read as one is simply not one. */
@StringRes
fun linkMessageFor(problem: InputProblem): Int =
    when (problem) {
        InputProblem.BLANK -> R.string.paste_blank
        InputProblem.NOT_AN_ADDRESS, InputProblem.NOT_AN_INVITATION -> R.string.not_an_invitation
        else -> messageFor(problem)
    }
