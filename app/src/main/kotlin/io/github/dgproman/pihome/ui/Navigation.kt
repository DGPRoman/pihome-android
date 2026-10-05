package io.github.dgproman.pihome.ui

import androidx.navigation3.runtime.NavKey
import io.github.dgproman.pihome.hub.InvitationLink
import kotlinx.serialization.Serializable

// Each key is a place in one of two back stacks: the one for being signed out
// and the one for being signed in. Which of the two is shown is not a place to
// navigate to; see PihomeApp.

/** Signed out: how to get connected. */
@Serializable
data object Welcome : NavKey

/** Signed out: an invitation link brought by hand. */
@Serializable
data object PasteInvitation : NavKey

/**
 * Signed out: the hub an invitation leads to, and the button that redeems it.
 *
 * Kept with the back stack, token and all, so that turning the phone does not
 * lose an invitation that works only once. Android keeps that state for this
 * app alone, and the key prints without the token.
 */
@Serializable
data class Join(
    val link: InvitationLink,
    /** When the link reached this phone, in milliseconds since the epoch. */
    val receivedAt: Long,
) : NavKey

/** Signed out: an address, a name and a password. */
@Serializable
data object LogIn : NavKey

/** Signed in: the relays, sensors and devices of the house. */
@Serializable
data object House : NavKey

/** Signed in: who this is, which hub, and the way out. */
@Serializable
data object Account : NavKey

/**
 * Signed in, as an admin: the accounts, and invitations for them.
 *
 * Holds nothing: an invitation issued there lives in that screen's model, in
 * memory, and never in a back stack that Android saves.
 */
@Serializable
data object People : NavKey
