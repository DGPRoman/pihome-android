package io.github.dgproman.pihome.quick

import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.session.SavedSession

/**
 * Told each time the hub says what its relays are, wherever the app asked it,
 * so that every control outside the app keeps up: a relay switched from the
 * tile shows as switched on the widget, and a relay added on the hub gets a
 * shortcut once the app has seen it.
 *
 * [saved] is the session the answer came to, so it is never taken for another
 * hub's. Called on the main thread, and must not block it.
 */
interface RelayNews {
    /** Every relay, as the hub listed them. */
    fun all(
        saved: SavedSession,
        relays: List<Relay>,
    )

    /** One relay, as the hub answered a switch of it. */
    fun one(
        saved: SavedSession,
        relay: Relay,
    ) {}

    companion object {
        /** Tells each of [listeners], in order. With none, tells nobody. */
        fun of(vararg listeners: RelayNews): RelayNews =
            object : RelayNews {
                override fun all(
                    saved: SavedSession,
                    relays: List<Relay>,
                ) = listeners.forEach { it.all(saved, relays) }

                override fun one(
                    saved: SavedSession,
                    relay: Relay,
                ) = listeners.forEach { it.one(saved, relay) }
            }
    }
}
