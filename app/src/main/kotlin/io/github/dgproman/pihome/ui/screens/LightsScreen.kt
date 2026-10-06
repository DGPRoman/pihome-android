package io.github.dgproman.pihome.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.quick.LightsSettingsViewModel
import io.github.dgproman.pihome.quick.lightsAmong
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.house.HouseSection
import io.github.dgproman.pihome.ui.house.Note

/** Which relays the All lights shortcut switches. */
@Composable
fun LightsScreen(
    model: LightsSettingsViewModel,
    mayChange: Boolean,
    onBack: () -> Unit,
) {
    LaunchedEffect(model) { model.read() }
    val relays by model.relays.collectAsStateWithLifecycle()
    val chosen by model.chosen.collectAsStateWithLifecycle()
    LightsContent(
        relays = relays,
        chosen = chosen,
        mayChange = mayChange,
        onInclude = { relay, included -> model.include(relay, included) },
        onRetry = { model.read() },
        onBack = onBack,
    )
}

@Composable
fun LightsContent(
    relays: Section<List<Relay>>,
    /** Null for every relay. */
    chosen: Set<String>?,
    mayChange: Boolean,
    onInclude: (Relay, Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Screen(title = stringResource(R.string.lights_title), onBack = onBack) {
        Text(stringResource(R.string.lights_body), style = MaterialTheme.typography.bodyLarge)
        if (!mayChange) Note(stringResource(R.string.lights_viewer))

        HouseSection(
            title = stringResource(R.string.lights_relays),
            section = relays,
            loading = R.string.relays_loading,
            empty = R.string.relays_empty,
            isEmpty = { it.isEmpty() },
            onRetry = onRetry,
        ) { list ->
            val included = lightsAmong(list, chosen).map { it.id }.toSet()
            Column {
                list.forEach { relay ->
                    val checked = relay.id in included
                    // The last relay in it stays, so its box cannot be cleared.
                    val enabled = !checked || included.size > 1
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onInclude(relay, it) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                        Text(relay.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            Note(stringResource(if (chosen == null) R.string.lights_every_relay else R.string.lights_some_relays))
        }
    }
}
