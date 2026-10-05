@file:UseSerializers(InstantSerializer::class)

package io.github.dgproman.pihome.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * What an account may do.
 *
 * A closed set. A role the hub sends that is not one of these makes the reply
 * [HubErrorKind.UNREADABLE]: the app decides from the role what to offer, and a
 * guess would be a guess in the permissive direction.
 */
@Serializable
enum class Role {
    /** Everything, including accounts. Granted on the hub's console, never over HTTP. */
    @SerialName("admin")
    ADMIN,

    /** Switches relays and reads everything. The everyday account. */
    @SerialName("operator")
    OPERATOR,

    /** Reads the house and changes nothing. */
    @SerialName("viewer")
    VIEWER,
}

/** A role an admin can hand out from the app: everything but [Role.ADMIN]. */
enum class ManagedRole(
    val role: Role,
    internal val wire: String,
) {
    OPERATOR(Role.OPERATOR, "operator"),
    VIEWER(Role.VIEWER, "viewer"),
}

/** Who this phone is signed in as. Mirrors `SessionResponse` in the hub's v1 schema. */
@Serializable
data class Session(
    val username: String,
    val role: Role,
    /** When the hub stops accepting the session, unless it is renewed from home first. */
    @SerialName("expires_at") val expiresAt: Instant,
)

/** A session just opened, and the token that carries it from here on. */
data class SignedIn(
    val session: Session,
    val token: SessionToken,
)
