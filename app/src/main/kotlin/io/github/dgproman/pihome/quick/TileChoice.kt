package io.github.dgproman.pihome.quick

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.dgproman.pihome.hub.HubAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The relay the Quick Settings tile switches, on the hub it was chosen on.
 *
 * Its name is kept with it, so the tile can say which relay it is before the
 * hub has answered, or when it cannot.
 */
data class TileChoice(
    /** The hub's origin, so a choice made on one hub is not applied to another. */
    val hub: String,
    val relayId: String,
    val relayName: String,
)

/** Where the tile's relay is kept. Tests keep it in memory. */
interface TileChoices {
    val choice: Flow<TileChoice?>

    suspend fun choose(choice: TileChoice?)
}

/** Where the choice lives. Not a secret, and like everything here excluded from backups. */
val Context.tileData: DataStore<Preferences> by preferencesDataStore(name = "tile")

class StoredTileChoices(
    private val data: DataStore<Preferences>,
) : TileChoices {
    override val choice: Flow<TileChoice?> =
        data.data.map { stored ->
            val hub = stored[HUB] ?: return@map null
            val id = stored[RELAY_ID] ?: return@map null
            TileChoice(hub, id, stored[RELAY_NAME] ?: id)
        }

    override suspend fun choose(choice: TileChoice?) {
        data.edit { stored ->
            if (choice == null) {
                stored.clear()
            } else {
                stored[HUB] = choice.hub
                stored[RELAY_ID] = choice.relayId
                stored[RELAY_NAME] = choice.relayName
            }
        }
    }

    private companion object {
        val HUB = stringPreferencesKey("hub")
        val RELAY_ID = stringPreferencesKey("relay_id")
        val RELAY_NAME = stringPreferencesKey("relay_name")
    }
}

/** The choice, if it was made on the hub at [address]. */
fun TileChoice?.on(address: HubAddress): TileChoice? = this?.takeIf { it.hub == address.origin }
