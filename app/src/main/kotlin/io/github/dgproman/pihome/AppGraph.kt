package io.github.dgproman.pihome

import android.content.Context
import io.github.dgproman.pihome.connect.AndroidLocalNetwork
import io.github.dgproman.pihome.connect.IncomingInvitations
import io.github.dgproman.pihome.connect.LocalNetwork
import io.github.dgproman.pihome.quick.AndroidShortcutShelf
import io.github.dgproman.pihome.quick.LightsChoices
import io.github.dgproman.pihome.quick.QuickActions
import io.github.dgproman.pihome.quick.RelayNews
import io.github.dgproman.pihome.quick.RelayShortcuts
import io.github.dgproman.pihome.quick.RelayTileService
import io.github.dgproman.pihome.quick.ShortcutModel
import io.github.dgproman.pihome.quick.ShortcutShelf
import io.github.dgproman.pihome.quick.StoredLightsChoices
import io.github.dgproman.pihome.quick.StoredTileChoices
import io.github.dgproman.pihome.quick.TileChoices
import io.github.dgproman.pihome.quick.TileModel
import io.github.dgproman.pihome.quick.lightsData
import io.github.dgproman.pihome.quick.tileData
import io.github.dgproman.pihome.session.Gate
import io.github.dgproman.pihome.session.KeystoreTokenCipher
import io.github.dgproman.pihome.session.SessionGate
import io.github.dgproman.pihome.session.SessionStore
import io.github.dgproman.pihome.session.sessionData
import io.github.dgproman.pihome.widget.GlanceWidgetHost
import io.github.dgproman.pihome.widget.MemoryWidgetStore
import io.github.dgproman.pihome.widget.StoredWidgetHouse
import io.github.dgproman.pihome.widget.WidgetHost
import io.github.dgproman.pihome.widget.WidgetModel
import io.github.dgproman.pihome.widget.WidgetStore
import io.github.dgproman.pihome.widget.widgetData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * Everything the app is made of, built once and handed down.
 *
 * By hand rather than with a framework: a handful of objects, each made in one
 * place that can be read top to bottom. Tests build one with their own parts.
 */
class AppGraph(
    val sessions: SessionStore,
    /** Lives as long as the process. Work started here survives the screen that asked for it. */
    val scope: CoroutineScope,
    val hubs: Hubs,
    val localNetwork: LocalNetwork,
    val clock: Clock,
    /** The relay the Quick Settings tile switches. */
    val tileChoices: TileChoices,
    /** The relays the All lights shortcut switches. */
    val lightsChoices: LightsChoices,
    /** Ask Android to show the tile again. Nothing, in tests. */
    val redrawTile: () -> Unit = {},
    /** The launcher's list of shortcuts. Nowhere, in tests. */
    shortcutShelf: ShortcutShelf = ShortcutShelf { true },
    /** What the home-screen widget shows. In memory, in tests. */
    widgetStore: WidgetStore = MemoryWidgetStore(),
    /** The home-screen widgets. None, in tests. */
    widgetHost: WidgetHost = NoWidgets,
) {
    val gate = SessionGate(sessions, scope, hubs)

    /** What the tile, the widget and the shortcuts share. */
    val quick = QuickActions(gate, localNetwork, hubs)

    /** A launcher shortcut for each relay, while somebody who may switch them is signed in. */
    val shortcuts = RelayShortcuts(shortcutShelf)

    val widget = WidgetModel(quick, widgetStore, widgetHost, clock, scope)

    /** Whatever the hub says of its relays, wherever the app asked, for the controls outside it to keep up. */
    val news = RelayNews.of(shortcuts, widget)

    val tile = TileModel(quick, tileChoices, scope, news, redrawTile)

    val shortcutModel = ShortcutModel(quick, news, lightsChoices)

    /** Invitations handed to the app from outside, waiting for the screens to take them. */
    val incoming = IncomingInvitations()

    init {
        scope.launch {
            gate.state.collect {
                if (it == Gate.SignedOut || it == Gate.Ended) {
                    shortcuts.clear()
                    widget.signedOut()
                }
            }
        }
    }

    companion object {
        fun create(context: Context): AppGraph =
            AppGraph(
                sessions = SessionStore(context.sessionData, KeystoreTokenCipher()),
                // Main, so that changes to what the app shows happen in the order they
                // were asked for. Anything slow inside moves itself off it.
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                hubs = HttpHubs(),
                localNetwork = AndroidLocalNetwork(context.applicationContext),
                clock = Clock.systemDefaultZone(),
                tileChoices = StoredTileChoices(context.tileData),
                lightsChoices = StoredLightsChoices(context.lightsData),
                redrawTile = { RelayTileService.redraw(context.applicationContext) },
                shortcutShelf = AndroidShortcutShelf(context.applicationContext),
                widgetStore = StoredWidgetHouse(context.widgetData),
                widgetHost = GlanceWidgetHost(context.applicationContext),
            )

        private object NoWidgets : WidgetHost {
            override suspend fun placed() = false

            override suspend fun redraw() {}
        }
    }
}
