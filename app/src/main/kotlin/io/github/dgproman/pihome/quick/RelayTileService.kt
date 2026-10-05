package io.github.dgproman.pihome.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.dgproman.pihome.MainActivity
import io.github.dgproman.pihome.PihomeApplication
import io.github.dgproman.pihome.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile: one relay, switched without opening the app.
 *
 * Draws what [TileModel] says and hands it the taps; everything it decides is
 * there. On a locked phone a tap asks for the phone to be unlocked first, so
 * whoever picks up somebody else's phone cannot switch the house with it.
 */
class RelayTileService : TileService() {
    private val graph get() = (application as PihomeApplication).graph
    private var drawing: Job? = null

    override fun onStartListening() {
        drawing?.cancel()
        drawing = graph.scope.launch { graph.tile.state.collect(::draw) }
        graph.tile.refresh()
    }

    override fun onStopListening() {
        drawing?.cancel()
        drawing = null
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::act) else act()
    }

    private fun act() {
        if (graph.tile.tap() == Tap.OPEN_APP) openApp()
    }

    private fun draw(look: TileLook) {
        val tile = qsTile ?: return
        val face = faceOf(look)
        val name = look.name ?: getString(R.string.tile_label)
        val subtitle = getString(face.subtitle)
        tile.state = if (face.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = name
            tile.subtitle = subtitle
        } else {
            tile.label = getString(R.string.tile_label_with_state, name, subtitle)
        }
        // Read out in place of a bare "on" or "off", which says nothing while it is switching.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = subtitle
        tile.updateTile()
    }

    // The Intent form only below Android 14, where the PendingIntent one does not exist.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Ask Android to show the tile again, if it has been added. */
        fun redraw(context: Context) {
            requestListeningState(context, ComponentName(context, RelayTileService::class.java))
        }
    }
}
