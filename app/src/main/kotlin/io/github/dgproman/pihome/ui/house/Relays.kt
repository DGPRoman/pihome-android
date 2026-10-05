package io.github.dgproman.pihome.ui.house

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.AllOff
import io.github.dgproman.pihome.house.RelayRow
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.ui.connect.messageFor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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

/** The relays, each a switch, with whatever the last press on it left to say. */
@Composable
fun RelayList(
    rows: List<RelayRow>,
    allOff: AllOff,
    mayChange: Boolean,
    zone: ZoneId,
    onSet: (String, Boolean) -> Unit,
) {
    // Shown rather than hidden: a switch that is missing says the house cannot be
    // switched from here at all, one that is there and says why says whom to ask.
    if (!mayChange) Note(stringResource(R.string.read_only))
    val failure = allOff.failure
    if (failure != null) {
        Problem(stringResource(if (allOff.unconfirmed) R.string.all_off_unconfirmed else writeMessageFor(failure)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row -> RelaySwitch(row, mayChange, zone) { on -> onSet(row.relay.id, on) } }
    }
}

@Composable
private fun RelaySwitch(
    row: RelayRow,
    mayChange: Boolean,
    zone: ZoneId,
    onSet: (Boolean) -> Unit,
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
                Text(relay.label, style = MaterialTheme.typography.titleMedium)
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
        val failure = row.failure
        when {
            row.unconfirmed -> Problem(stringResource(R.string.relay_unconfirmed))
            failure != null -> Problem(stringResource(writeMessageFor(failure)))
        }
    }
}

/** What to say when a press was refused this way. */
@StringRes
private fun writeMessageFor(kind: HubErrorKind): Int = if (kind == HubErrorKind.FORBIDDEN) R.string.relay_forbidden else messageFor(kind)

@Composable
private fun timeOfDay(
    at: Instant,
    zone: ZoneId,
): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale, zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone) }
    return format.format(at)
}
