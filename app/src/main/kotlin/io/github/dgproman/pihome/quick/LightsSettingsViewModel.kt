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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * Choosing the relays the All lights shortcut switches.
 *
 * The relays are read once as the screen opens, and again on request: a list
 * to choose from, not a house to watch.
 */
class LightsSettingsViewModel(
    private val hub: Hub,
    private val choices: LightsChoices,
    private val clock: Clock,
    /** The hub refused the session. The gate ends it, and with it this screen. */
    private val onRefused: () -> Unit,
) : ViewModel() {
    private val list = MutableStateFlow(Section<List<Relay>>())

    val relays: StateFlow<Section<List<Relay>>> = list.asStateFlow()

    /** The chosen relays' ids, if they were chosen on this hub; null for every relay. */
    val chosen: StateFlow<Set<String>?> =
        choices.choice
            .map { it.on(hub.address) }
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

    /**
     * Put [relay] in the shortcut, or leave it out.
     *
     * The last relay in it stays: a shortcut that switches nothing is not one.
     * A choice that comes back to every relay is forgotten, so that a relay the
     * hub gains later is in it too.
     */
    fun include(
        relay: Relay,
        included: Boolean,
    ): Job =
        viewModelScope.launch {
            val relays = list.value.data ?: return@launch
            // Counted among the relays the hub has now, so one it has dropped is dropped here too.
            val before = lightsAmong(relays, choices.choice.first().on(hub.address)).map { it.id }.toSet()
            val after = if (included) before + relay.id else before - relay.id
            when {
                after.isEmpty() -> return@launch
                after.containsAll(relays.map { it.id }) -> choices.choose(null)
                else -> choices.choose(LightsChoice(hub.address.origin, after))
            }
        }

    private companion object {
        const val TAG = "LightsSettings"
    }
}
