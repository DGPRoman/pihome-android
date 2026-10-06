package io.github.dgproman.pihome.quick

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dgproman.pihome.MainActivity
import io.github.dgproman.pihome.PihomeApplication
import io.github.dgproman.pihome.ui.screens.ShortcutDialog
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a launcher shortcut opens: it does what the shortcut says, shows what
 * came of it in a small window over whatever was on the screen, and closes.
 *
 * Not exported: only this app, and the launcher on its behalf, can start it.
 * In a task of its own, because the launcher clears the task a shortcut opens
 * into, and the app's own screens should not be cleared by a shortcut.
 */
class ShortcutActivity : ComponentActivity() {
    private val graph get() = (application as PihomeApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = requestOf(intent)
        if (request == null) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            // So the launcher can offer the shortcuts used most.
            getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(idOf(request))
        }
        setContent {
            PihomeTheme {
                val model = viewModel { ShortcutViewModel(graph.shortcutModel, request, graph.scope) }
                val result by model.state.collectAsStateWithLifecycle()
                ShortcutDialog(
                    request = request,
                    result = result,
                    onClose = ::finish,
                    onOpenApp = ::openApp,
                )
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        /** In shortcuts.xml as well, which cannot refer to these. */
        const val ACTION_ALL_OFF = "io.github.dgproman.pihome.action.ALL_OFF"
        const val ACTION_LIGHTS = "io.github.dgproman.pihome.action.LIGHTS"
        const val ACTION_SWITCH = "io.github.dgproman.pihome.action.SWITCH_RELAY"

        /** All off's id in shortcuts.xml. */
        const val ALL_OFF_ID = "all_off"

        /** All lights' id in shortcuts.xml. */
        const val LIGHTS_ID = "lights"

        private const val EXTRA_HUB = "hub"
        private const val EXTRA_RELAY = "relay"
        private const val EXTRA_NAME = "name"

        /** What a relay's shortcut starts. Its extras outlive the app's updates, so only strings. */
        fun switching(
            context: Context,
            shortcut: RelayShortcut,
        ): Intent =
            Intent(ACTION_SWITCH)
                .setClass(context, ShortcutActivity::class.java)
                .putExtra(EXTRA_HUB, shortcut.hub)
                .putExtra(EXTRA_RELAY, shortcut.relayId)
                .putExtra(EXTRA_NAME, shortcut.name)

        /** What [intent] asks for, or null when it is not a shortcut's. */
        fun requestOf(intent: Intent?): ShortcutRequest? =
            when (intent?.action) {
                ACTION_ALL_OFF -> {
                    ShortcutRequest.AllOff
                }

                ACTION_LIGHTS -> {
                    ShortcutRequest.Lights
                }

                ACTION_SWITCH -> {
                    val hub = intent.getStringExtra(EXTRA_HUB)
                    val relay = intent.getStringExtra(EXTRA_RELAY)
                    if (hub == null ||
                        relay == null
                    ) {
                        null
                    } else {
                        ShortcutRequest.Switch(RelayShortcut(hub, relay, intent.getStringExtra(EXTRA_NAME) ?: relay))
                    }
                }

                else -> {
                    null
                }
            }

        private fun idOf(request: ShortcutRequest): String =
            when (request) {
                ShortcutRequest.AllOff -> ALL_OFF_ID
                ShortcutRequest.Lights -> LIGHTS_ID
                is ShortcutRequest.Switch -> request.relay.id
            }
    }
}

/**
 * Runs one shortcut's request, once, however often the window is redrawn.
 *
 * In [appScope], so that a switch already sent still finishes when the window
 * is closed before the hub answers.
 */
class ShortcutViewModel(
    model: ShortcutModel,
    request: ShortcutRequest,
    appScope: CoroutineScope,
) : ViewModel() {
    private val result = MutableStateFlow<ShortcutResult?>(null)

    /** Null while the hub has not answered. */
    val state: StateFlow<ShortcutResult?> = result.asStateFlow()

    init {
        appScope.launch { result.value = model.run(request) }
    }
}
