package io.github.dgproman.pihome.quick

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.mayChangeTheHouse
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.session.SavedSession

/** A launcher shortcut that switches one relay, on the hub it was made for. */
data class RelayShortcut(
    /** The hub's origin, so a shortcut made for one hub never switches another. */
    val hub: String,
    val relayId: String,
    val name: String,
) {
    /** The same relay keeps the same shortcut, so a copy pinned to the home screen follows a rename. */
    val id: String get() = "relay:$relayId"
}

/** Where the launcher's shortcuts are put. Tests keep them in a list. */
fun interface ShortcutShelf {
    /** Show these, in this order, in place of whatever was there. False when Android would not. */
    fun put(shortcuts: List<RelayShortcut>): Boolean
}

/**
 * The launcher's shortcuts kept in step with the hub's relays: one per relay,
 * for an account that may switch them, and none for anybody else.
 *
 * Told the relays whenever the app has read them, and told nothing changes
 * nothing, so the list follows what the app has seen. Android limits how
 * often an app may change its shortcuts, so they are put only when they
 * differ from what was put last.
 *
 * On the main thread, as everything that tells it is.
 */
class RelayShortcuts(
    private val shelf: ShortcutShelf,
) {
    /** What was put last, or null when that is not known: at start, and after Android refused. */
    private var shown: List<RelayShortcut>? = null

    /** The relays as [saved]'s hub has just reported them. */
    fun follow(
        saved: SavedSession,
        relays: List<Relay>,
    ) {
        show(
            if (saved.session.role.mayChangeTheHouse) {
                relays.map { RelayShortcut(saved.address.origin, it.id, it.label) }
            } else {
                emptyList()
            },
        )
    }

    /** Nobody is signed in: no relay can be switched from the launcher. */
    fun clear() = show(emptyList())

    private fun show(shortcuts: List<RelayShortcut>) {
        if (shortcuts == shown) return
        shown = shortcuts.takeIf { shelf.put(it) }
    }
}

/** The launcher's own list, behind a long press on the app's icon. */
class AndroidShortcutShelf(
    private val context: Context,
) : ShortcutShelf {
    override fun put(shortcuts: List<RelayShortcut>): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        // All off, from shortcuts.xml, takes one of the places.
        val room = (manager.maxShortcutCountPerActivity - STATIC_SHORTCUTS).coerceAtLeast(0)
        val infos =
            shortcuts.take(room).mapIndexed { rank, shortcut ->
                ShortcutInfo
                    .Builder(context, shortcut.id)
                    .setShortLabel(shortcut.name)
                    .setLongLabel(context.getString(R.string.shortcut_relay_long, shortcut.name))
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_relay))
                    .setIntent(ShortcutActivity.switching(context, shortcut))
                    .setRank(rank)
                    .build()
            }
        return try {
            // Copies pinned to the home screen are renamed with these. One whose relay
            // has gone stays, and says so when it is tapped.
            manager.setDynamicShortcuts(infos)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "the launcher's shortcuts were not changed", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "the launcher's shortcuts were not changed", e)
            false
        }
    }

    private companion object {
        const val STATIC_SHORTCUTS = 1
        const val TAG = "Shortcuts"
    }
}
