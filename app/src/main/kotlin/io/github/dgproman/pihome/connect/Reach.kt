package io.github.dgproman.pihome.connect

import io.github.dgproman.pihome.Hubs
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.HubException

/** How far the app has got in reaching a hub, before it sends the hub anything secret. */
sealed interface Reach {
    data object Checking : Reach

    /**
     * The hub is on the local network, and Android needs the person's leave to
     * reach it. The screen says why, then asks.
     */
    data object NeedsPermission : Reach

    /** Asked, and not given. The screen offers to ask again, and the way to the settings. */
    data object PermissionRefused : Reach

    /** Nothing usable answered. Nothing secret was sent, so trying again is safe. */
    data class Failed(
        val kind: HubErrorKind,
    ) : Reach

    data object Reached : Reach
}

/**
 * The first steps towards a hub: leave to reach it, then its health check.
 *
 * Both before anything with a secret in it: an invitation is spent by its first
 * use, and a password should only go to something that has answered as a hub.
 */
class Reacher(
    private val hubs: Hubs,
    private val localNetwork: LocalNetwork,
) {
    suspend fun reach(address: HubAddress): Reach {
        if (localNetwork.mustAsk(address)) return Reach.NeedsPermission
        return try {
            hubs.at(address, token = null).checkHealth()
            Reach.Reached
        } catch (e: HubException) {
            Reach.Failed(e.kind)
        }
    }
}
