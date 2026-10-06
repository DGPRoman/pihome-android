package io.github.dgproman.pihome.quick

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.Relay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The relays the All lights shortcut switches, on the hub they were chosen on.
 *
 * Kept only once something has been left out. Until then the shortcut means
 * every relay, those the hub gains later included.
 */
data class LightsChoice(
    /** The hub's origin, so a choice made on one hub is not applied to another. */
    val hub: String,
    val relayIds: Set<String>,
)

/** Where the shortcut's relays are kept. Tests keep them in memory. */
interface LightsChoices {
    val choice: Flow<LightsChoice?>

    /** Null goes back to every relay. */
    suspend fun choose(choice: LightsChoice?)
}

/** Where the choice lives. Not a secret, and like everything here excluded from backups. */
val Context.lightsData: DataStore<Preferences> by preferencesDataStore(name = "lights")

class StoredLightsChoices(
    private val data: DataStore<Preferences>,
) : LightsChoices {
    override val choice: Flow<LightsChoice?> =
        data.data.map { stored ->
            val hub = stored[HUB] ?: return@map null
            LightsChoice(hub, stored[RELAY_IDS] ?: return@map null)
        }

    override suspend fun choose(choice: LightsChoice?) {
        data.edit { stored ->
            if (choice == null) {
                stored.clear()
            } else {
                stored[HUB] = choice.hub
                stored[RELAY_IDS] = choice.relayIds
            }
        }
    }

    private companion object {
        val HUB = stringPreferencesKey("hub")
        val RELAY_IDS = stringSetPreferencesKey("relay_ids")
    }
}

/** The relays chosen on the hub at [address], or null for every relay. */
fun LightsChoice?.on(address: HubAddress): Set<String>? = this?.takeIf { it.hub == address.origin }?.relayIds

/** Which of [relays] the shortcut switches, given what was chosen on their hub. */
fun lightsAmong(
    relays: List<Relay>,
    chosen: Set<String>?,
): List<Relay> = if (chosen == null) relays else relays.filter { it.id in chosen }
