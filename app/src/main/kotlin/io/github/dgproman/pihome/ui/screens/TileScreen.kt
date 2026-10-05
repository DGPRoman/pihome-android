package io.github.dgproman.pihome.ui.screens

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.quick.RelayTileService
import io.github.dgproman.pihome.quick.TileSettingsViewModel
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.house.HouseSection
import io.github.dgproman.pihome.ui.house.Note

/** Which relay the Quick Settings tile switches, and a way to put the tile there. */
@Composable
fun TileScreen(
    model: TileSettingsViewModel,
    mayChange: Boolean,
    onBack: () -> Unit,
) {
    LaunchedEffect(model) { model.read() }
    val relays by model.relays.collectAsStateWithLifecycle()
    val chosen by model.chosen.collectAsStateWithLifecycle()
    TileContent(
        relays = relays,
        chosen = chosen,
        mayChange = mayChange,
        canAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        onChoose = model::choose,
        onRetry = { model.read() },
        onBack = onBack,
    )
}

@Composable
fun TileContent(
    relays: Section<List<Relay>>,
    chosen: String?,
    mayChange: Boolean,
    /** Android 13 and later can be asked to add the tile; earlier, the person adds it. */
    canAddTile: Boolean,
    onChoose: (Relay) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Screen(title = stringResource(R.string.tile_title), onBack = onBack) {
        Text(stringResource(R.string.tile_body), style = MaterialTheme.typography.bodyLarge)
        if (!mayChange) Note(stringResource(R.string.tile_viewer))

        HouseSection(
            title = stringResource(R.string.tile_relay),
            section = relays,
            loading = R.string.relays_loading,
            empty = R.string.relays_empty,
            isEmpty = { it.isEmpty() },
            onRetry = onRetry,
        ) { list ->
            Column(Modifier.selectableGroup()) {
                list.forEach { relay ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = relay.id == chosen, role = Role.RadioButton) { onChoose(relay) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = relay.id == chosen, onClick = null)
                        Text(relay.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }

        if (canAddTile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AddTile()
        } else {
            Note(stringResource(R.string.tile_add_by_hand))
        }
    }
}

/** Ask Android to put the tile in Quick Settings, and say what it answered. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AddTile() {
    val context = LocalContext.current
    var answer by rememberSaveable { mutableStateOf<Int?>(null) }
    val label = stringResource(R.string.tile_label)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, RelayTileService::class.java),
                label,
                Icon.createWithResource(context, R.drawable.ic_tile),
                context.mainExecutor,
            ) { answer = it }
        }) { Text(stringResource(R.string.tile_add)) }
        val said =
            when (answer) {
                null, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.tile_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.tile_already_added
                else -> R.string.tile_add_failed
            }
        said?.let { Note(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
