package io.github.dgproman.pihome.ui.house

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.Freshness
import io.github.dgproman.pihome.house.Standing
import io.github.dgproman.pihome.house.freshnessOf
import io.github.dgproman.pihome.house.standingOf
import io.github.dgproman.pihome.hub.Device
import io.github.dgproman.pihome.hub.Reading
import io.github.dgproman.pihome.hub.Sensor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * One sensor and its readings, each with its own age and its own verdict.
 *
 * The sensor's own "stale" mark speaks only for a sensor gone quiet as a whole:
 * the hub moves its last-seen time on any reading, so it cannot say whether a
 * particular one is current. Each reading is judged on its own time.
 */
@Composable
fun SensorItem(
    sensor: Sensor,
    asOf: Instant,
) {
    Item {
        NameLine(sensor.label) {
            if (sensor.stale && sensor.lastSeen != null) Badge(stringResource(R.string.stale), fault = true)
        }
        val readings = listOf(sensor.motion, sensor.temperature, sensor.humidity)
        val lastSeen = sensor.lastSeen
        if (lastSeen == null && readings.none { it is Reading.Value }) {
            Note(stringResource(R.string.no_readings))
            return@Item
        }
        Quantity(stringResource(R.string.motion), sensor.motion, sensor.staleAfterSeconds, asOf) { detected ->
            stringResource(if (detected) R.string.motion_detected else R.string.motion_clear)
        }
        Quantity(stringResource(R.string.temperature), sensor.temperature, sensor.staleAfterSeconds, asOf) {
            stringResource(R.string.temperature_value, decimal(it))
        }
        Quantity(stringResource(R.string.humidity), sensor.humidity, sensor.staleAfterSeconds, asOf) {
            stringResource(R.string.humidity_value, decimal(it))
        }
        if (lastSeen != null) Note(stringResource(R.string.last_seen, ago(lastSeen, asOf)))
    }
}

/** One reading: what it is, its value, its age, and whether it is stale. Nothing for one the hub does not report. */
@Composable
private fun <T> Quantity(
    term: String,
    reading: Reading<T>,
    staleAfterSeconds: Double?,
    asOf: Instant,
    format: @Composable (T) -> String,
) {
    if (reading is Reading.Unsupported) return
    // Read out as one line: the name, the value and its age belong together.
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(term, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (reading) {
                Reading.Unsupported -> {}

                Reading.Never -> {
                    Note(stringResource(R.string.never_reported))
                }

                is Reading.Value -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(format(reading.value), style = MaterialTheme.typography.bodyLarge)
                        if (freshnessOf(reading, staleAfterSeconds, asOf) == Freshness.STALE) {
                            Badge(stringResource(R.string.stale), fault = true)
                        }
                    }
                    val at = reading.at
                    Note(if (at == null) stringResource(R.string.age_unknown) else ago(at, asOf))
                }
            }
        }
    }
}

/**
 * One device the hub polls: where it is, whether it answers, and what it last said.
 *
 * Its own fields under its own names: what they mean is the device's to say,
 * and renaming them here would be one more thing to keep in step with it.
 */
@Composable
fun DeviceItem(
    device: Device,
    asOf: Instant,
) {
    val standing = standingOf(device)
    Item {
        NameLine(device.label) {
            when (standing) {
                Standing.SILENT -> {
                    Badge(stringResource(R.string.device_silent), fault = true)
                }

                Standing.NEVER_ANNOUNCED -> {
                    Badge(stringResource(R.string.device_never_announced))
                }

                Standing.NOT_POLLED -> {
                    Badge(stringResource(R.string.device_not_polled))
                }

                Standing.ANSWERING -> {}
            }
        }
        if (standing == Standing.NEVER_ANNOUNCED) {
            Note(stringResource(R.string.device_never_announced_body))
        } else {
            Note(
                listOfNotNull(
                    device.address,
                    device.firmware,
                    device.announcedAt?.let { stringResource(R.string.device_announced, ago(it, asOf)) },
                ).joinToString(" · "),
            )
        }
        if (standing == Standing.SILENT) {
            val since = device.unreachableSince
            val said =
                if (since ==
                    null
                ) {
                    stringResource(R.string.device_unreachable)
                } else {
                    stringResource(R.string.device_silent_since, ago(since, asOf))
                }
            Problem(listOfNotNull(said, device.lastError).joinToString(" "))
        }
        // Kept across a failed poll on purpose, so it is shown with its age rather than hidden.
        val state = device.state ?: return@Item
        state.forEach { (field, value) ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(field, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(valueOf(value), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(2f))
            }
        }
        val seen = device.lastSeenAt
        Note(
            when {
                seen == null -> stringResource(R.string.device_reported_unknown)
                standing == Standing.SILENT -> stringResource(R.string.device_last_answered, ago(seen, asOf))
                else -> stringResource(R.string.device_reported, ago(seen, asOf))
            },
        )
    }
}

/** One value from a device's own document: text as it is, anything nested as JSON. */
internal fun valueOf(value: JsonElement): String = if (value is JsonPrimitive) value.content else value.toString()

/** A name, and the marks beside it. */
@Composable
fun NameLine(
    name: String,
    marks: @Composable () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        marks()
    }
}
