package io.github.dgproman.pihome.hub

import kotlinx.serialization.Serializable

/**
 * The secret behind a session: the value of the hub's `pihome_session` cookie.
 *
 * Its own type so it cannot be passed where a name or an address was meant, and
 * so it prints as nothing. A token in a log, a crash report or a test failure is
 * a token anybody reading it can use for a month.
 */
@JvmInline
value class SessionToken(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a session token is never blank" }
    }

    override fun toString(): String = "SessionToken(redacted)"
}

/**
 * The secret in an invitation: opens one account once, within fifteen minutes.
 *
 * Redacted when printed, for the same reason as [SessionToken]. An admin's
 * screen shows it on purpose; nothing else should by accident.
 */
@Serializable
@JvmInline
value class InvitationToken(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "an invitation token is never blank" }
    }

    override fun toString(): String = "InvitationToken(redacted)"
}
