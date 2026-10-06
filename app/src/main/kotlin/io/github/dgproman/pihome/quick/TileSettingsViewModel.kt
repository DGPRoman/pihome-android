package io.github.dgproman.pihome.quick

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Hub
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.HubException
import io.github.dgproman.pihome.hub.Relay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * Choosing the relay the Quick Settings tile switches.
 *
 * The relays are read once as the screen opens, and again on request: a list
 * to choose from, not a house to watch.
 */
class TileSettingsViewModel(
    private val hub: Hub,
    private val choices: TileChoices,
    private val clock: Clock,
    /** The hub refused the session. The gate ends it, and with it this screen. */
    private val onRefused: () -> Unit,
    /** A relay was chosen: the tile should show it. */
    private val onChosen: () -> Unit,
) : ViewModel() {
    private val list = MutableStateFlow(Section<List<Relay>>())

    val relays: StateFlow<Section<List<Relay>>> = list.asStateFlow()

    /** The chosen relay's id, if it was chosen on this hub. */
    val chosen: StateFlow<String?> =
        choices.choice
            .map { it.on(hub.address)?.relayId }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var reading: Job? = null
    private var stopped = false

    fun read(): Job {
        reading?.takeIf { it.isActive }?.let { return it }
        return viewModelScope
            .launch {
                if (stopped) return@launch
                try {
                    val answer = hub.relays()
                    list.value = Section(answer, clock.instant())
                } catch (e: HubException) {
                    Log.i(TAG, "the relays were not read: ${e.kind}", e)
                    list.update { it.copy(failure = e.kind) }
                    if (e.kind == HubErrorKind.UNAUTHORIZED && !stopped) {
                        stopped = true
                        onRefused()
                    }
                }
            }.also { reading = it }
    }

    fun choose(relay: Relay) {
        viewModelScope.launch {
            choices.choose(TileChoice(hub.address.origin, relay.id, relay.label))
            onChosen()
        }
    }

    private companion object {
        const val TAG = "TileSettings"
    }
}
