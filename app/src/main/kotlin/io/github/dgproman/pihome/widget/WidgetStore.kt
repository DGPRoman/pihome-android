package io.github.dgproman.pihome.widget

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.Json
import java.time.DateTimeException

/** Where what the widget shows is kept. Tests keep it in memory. */
interface WidgetStore {
    val house: Flow<WidgetHouse>

    /** Change it, and return what it now is. */
    suspend fun change(transform: (WidgetHouse) -> WidgetHouse): WidgetHouse
}

/** Where the widget's house lives. Like everything here, excluded from backups. */
val Context.widgetData: DataStore<Preferences> by preferencesDataStore(name = "widget")

class StoredWidgetHouse(
    private val data: DataStore<Preferences>,
) : WidgetStore {
    override val house: Flow<WidgetHouse> = data.data.map { decode(it[HOUSE]) }

    override suspend fun change(transform: (WidgetHouse) -> WidgetHouse): WidgetHouse {
        var changed = WidgetHouse()
        data.edit { stored ->
            changed = transform(decode(stored[HOUSE]))
            stored[HOUSE] = json.encodeToString(WidgetHouse.serializer(), changed)
        }
        return changed
    }

    /**
     * What was stored, or a widget that has read nothing yet when it cannot be read,
     * as after an update that changed its shape. The next read puts it right.
     */
    private fun decode(stored: String?): WidgetHouse {
        stored ?: return WidgetHouse()
        return try {
            json.decodeFromString(WidgetHouse.serializer(), stored)
        } catch (e: IllegalArgumentException) {
            // A SerializationException is one of these.
            Log.w(TAG, "what the widget kept could not be read", e)
            WidgetHouse()
        } catch (e: DateTimeException) {
            Log.w(TAG, "what the widget kept could not be read", e)
            WidgetHouse()
        }
    }

    private companion object {
        val HOUSE = stringPreferencesKey("house")
        val json = Json { ignoreUnknownKeys = true }
        const val TAG = "StoredWidgetHouse"
    }
}

/** Kept in memory: for tests, and for an app with no widget to keep it for. */
class MemoryWidgetStore(
    initial: WidgetHouse = WidgetHouse(),
) : WidgetStore {
    private val held = MutableStateFlow(initial)

    override val house: Flow<WidgetHouse> = held

    override suspend fun change(transform: (WidgetHouse) -> WidgetHouse): WidgetHouse = held.updateAndGet(transform)
}
