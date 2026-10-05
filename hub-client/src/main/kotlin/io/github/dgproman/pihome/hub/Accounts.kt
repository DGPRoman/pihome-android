@file:UseSerializers(InstantSerializer::class)

package io.github.dgproman.pihome.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * An account, as an admin sees it. Mirrors `UserResponse` in the hub's v1 schema.
 *
 * No password and no hash: the hub never puts one in a reply.
 */
@Serializable
data class Account(
    val username: String,
    val role: Role,
    val disabled: Boolean,
    @SerialName("created_at") val createdAt: Instant,
    /** When its outstanding invitation stops working, or null if it has none. */
    @SerialName("invitation_expires_at") val invitationExpiresAt: Instant?,
)

/** What may change about an account from the app. Null means unchanged. */
data class AccountChange(
    val role: ManagedRole? = null,
    val disabled: Boolean? = null,
) {
    init {
        // The hub refuses a change that names nothing; so does this, before sending it.
        require(role != null || disabled != null) { "an account change names at least one field" }
    }
}

/**
 * A one-time way into an account, as the hub issued it.
 *
 * The one credential an admin is meant to see, so that it can be passed on. The
 * hub keeps only its hash, and returns it this once.
 */
@Serializable
data class Invitation(
    val token: InvitationToken,
    @SerialName("expires_at") val expiresAt: Instant,
)
