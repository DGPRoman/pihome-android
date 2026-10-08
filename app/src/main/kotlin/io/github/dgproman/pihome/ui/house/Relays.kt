package io.github.dgproman.pihome.ui.house

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.AllOff
import io.github.dgproman.pihome.house.Control
import io.github.dgproman.pihome.house.RelayRow
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.ui.connect.messageFor
import java.time.Instant
import java.time.ZoneId

/** "All off", beside the relays' heading. Only live while something is on. */
@Composable
fun AllOffButton(
    rows: List<RelayRow>?,
    allOff: AllOff,
    mayChange: Boolean,
    onAllOff: () -> Unit,
) {
    if (rows == null) return
    OutlinedButton(
        onClick = onAllOff,
        enabled = mayChange && !allOff.pending && rows.any { it.relay.on },
    ) {
        Text(stringResource(R.string.all_off))
    }
}

/**
 * The relays, each a switch, with whether automation may switch it and whatever
 * the last press on it left to say.
 */
@Composable
fun RelayList(
    rows: List<RelayRow>,
    allOff: AllOff,
    mayChange: Boolean,
    zone: ZoneId,
    onSet: (String, Boolean) -> Unit,
    onAutomatic: (String, Boolean) -> Unit,
) {
    // The relay whose automation is about to be turned off, while that is being asked.
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    // Shown rather than hidden: a switch that is missing says the house cannot be
    // switched from here at all, one that is there and says why says whom to ask.
    if (!mayChange) Note(stringResource(R.string.read_only))
    val failure = allOff.failure
    if (failure != null) {
        Problem(stringResource(if (allOff.unconfirmed) R.string.all_off_unconfirmed else writeMessageFor(failure)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            RelaySwitch(
                row = row,
                mayChange = mayChange,
                zone = zone,
                onSet = { on -> onSet(row.relay.id, on) },
                // Back on at once: it switches nothing. Off is asked first, because the light goes off with it.
                onAutomatic = { automatic -> if (automatic) onAutomatic(row.relay.id, true) else confirming = row.relay.id },
            )
        }
    }

    confirming?.let { id ->
        val label = rows.find { it.relay.id == id }?.relay?.label ?: id
        // A dialog, so that the light going off as well is said before anything is sent.
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.automation_off_title, label)) },
            text = { Text(stringResource(R.string.automation_off_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onAutomatic(id, false)
                }) { Text(stringResource(R.string.automation_turn_off)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.automation_keep)) } },
        )
    }
}

@Composable
private fun RelaySwitch(
    row: RelayRow,
    mayChange: Boolean,
    zone: ZoneId,
    onSet: (Boolean) -> Unit,
    onAutomatic: (Boolean) -> Unit,
) {
    val relay = row.relay
    val shown =
        when {
            row.unconfirmed && relay.on -> R.string.relay_on_unsure
            row.unconfirmed -> R.string.relay_off_unsure
            relay.on -> R.string.relay_on
            else -> R.string.relay_off
        }
    val spoken = stringResource(if (relay.on) R.string.relay_probably_on else R.string.relay_probably_off)
    Item {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                // The whole row is the switch, so it is a big target and one thing to a
                // screen reader: its name, its state, and that it is a switch.
                .toggleable(
                    value = relay.on,
                    enabled = mayChange && !row.pending,
                    role = Role.Switch,
                    onValueChange = onSet,
                ).semantics {
                    // Only while in doubt. Otherwise the system says on or off in its own words.
                    if (row.unconfirmed) stateDescription = spoken
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                // Marked while automation may not switch it, so that nobody forgets it is so.
                NameLine(relay.label) {
                    if (relay.automatic == false) Badge(stringResource(R.string.automation_off))
                }
                // The state in words as well as by the switch's position and colour.
                Text(
                    stringResource(shown),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (relay.on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = relay.on, onCheckedChange = null, enabled = mayChange && !row.pending)
        }
        relay.holdExpiresAt?.let { until ->
            Note(stringResource(if (relay.on) R.string.relay_hold_off else R.string.relay_hold_on, timeOfDay(until, zone)))
        }
        AutomationLine(row, mayChange, onAutomatic)
        val failure = row.failure
        when {
            row.unconfirmed && row.pressed == Control.AUTOMATION -> Problem(stringResource(R.string.automation_unconfirmed))
            row.unconfirmed -> Problem(stringResource(R.string.relay_unconfirmed))
            failure != null -> Problem(stringResource(writeMessageFor(failure)))
        }
    }
}

/**
 * Whether automation may switch the relay, and the way to change that. Nothing
 * at all from a hub that does not say, which has no such switch to offer.
 */
@Composable
private fun AutomationLine(
    row: RelayRow,
    mayChange: Boolean,
    onAutomatic: (Boolean) -> Unit,
) {
    val automatic = row.relay.automatic ?: return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Note(stringResource(if (automatic) R.string.automation_on else R.string.automation_off_note), Modifier.weight(1f))
        // There for a viewer too, unavailable, as the switch is.
        TextButton(onClick = { onAutomatic(!automatic) }, enabled = mayChange && !row.pending) {
            Text(stringResource(if (automatic) R.string.automation_turn_off else R.string.automation_turn_on))
        }
    }
}

/** What to say when a press was refused this way. */
@StringRes
private fun writeMessageFor(kind: HubErrorKind): Int = if (kind == HubErrorKind.FORBIDDEN) R.string.relay_forbidden else messageFor(kind)
