package io.github.dgproman.pihome

import io.github.dgproman.pihome.hub.Hub
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.HubClient
import io.github.dgproman.pihome.hub.SessionToken
import okhttp3.OkHttpClient

/** Where the app gets a [Hub] to talk to. Tests hand it one that answers as they need. */
fun interface Hubs {
    /** The hub at [address], as whoever holds [token], or as nobody yet. */
    fun at(
        address: HubAddress,
        token: SessionToken?,
    ): Hub
}

/** Every hub over one HTTP client, so connections and threads are shared. */
class HttpHubs : Hubs {
    private val http = OkHttpClient()

    override fun at(
        address: HubAddress,
        token: SessionToken?,
    ): Hub = HubClient(address, token, http)
}
