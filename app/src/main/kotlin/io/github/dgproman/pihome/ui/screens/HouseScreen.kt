package io.github.dgproman.pihome.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.AllOff
import io.github.dgproman.pihome.house.House
import io.github.dgproman.pihome.house.HouseViewModel
import io.github.dgproman.pihome.house.RelayRow
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.AutomationRule
import io.github.dgproman.pihome.hub.Reading
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Sensor
import io.github.dgproman.pihome.hub.SwitchState
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.house.AllOffButton
import io.github.dgproman.pihome.ui.house.DeviceItem
import io.github.dgproman.pihome.ui.house.HouseSection
import io.github.dgproman.pihome.ui.house.RelayList
import io.github.dgproman.pihome.ui.house.RuleItem
import io.github.dgproman.pihome.ui.house.SensorItem
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import java.time.Instant
import java.time.ZoneId

/**
 * The house, read from the hub while the screen is in view and not at all
 * otherwise: in the background, the phone asks nothing.
 */
@Composable
fun HouseScreen(
    model: HouseViewModel,
    mayChange: Boolean,
    zone: ZoneId,
    onAccount: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.poll() }
    }
    val house by model.state.collectAsStateWithLifecycle()
    HouseContent(
        house = house,
        mayChange = mayChange,
        zone = zone,
        onSet = model::setRelay,
        onAllOff = model::allOff,
        onRetry = { model.refresh() },
        onPull = model::pull,
        onAccount = onAccount,
    )
}

/** The house as given, in the web client's order: relays, sensors, devices, automation. */
@Composable
fun HouseContent(
    house: House,
    mayChange: Boolean,
    zone: ZoneId,
    onSet: (String, Boolean) -> Unit,
    onAllOff: () -> Unit,
    onRetry: () -> Unit,
    onPull: () -> Unit,
    onAccount: () -> Unit,
) {
    Screen(
        title = stringResource(R.string.house_title),
        actions = {
            IconButton(onClick = onAccount) {
                Icon(painterResource(R.drawable.ic_account), contentDescription = stringResource(R.string.account_title))
            }
        },
        refreshing = house.refreshing,
        onRefresh = onPull,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
            HouseSection(
                title = stringResource(R.string.relays_title),
                section = house.relays,
                loading = R.string.relays_loading,
                empty = R.string.relays_empty,
                isEmpty = { it.isEmpty() },
                onRetry = onRetry,
                action = { AllOffButton(house.relays.data, house.allOff, mayChange, onAllOff) },
            ) { rows -> RelayList(rows, house.allOff, mayChange, zone, onSet) }

            HouseSection(
                title = stringResource(R.string.sensors_title),
                section = house.sensors,
                loading = R.string.sensors_loading,
                empty = R.string.sensors_empty,
                isEmpty = { it.isEmpty() },
                onRetry = onRetry,
            ) { sensors -> sensors.forEach { SensorItem(it, house.sensors.asOf ?: Instant.EPOCH) } }

            HouseSection(
                title = stringResource(R.string.devices_title),
                section = house.devices,
                loading = R.string.devices_loading,
                empty = R.string.devices_empty,
                isEmpty = { it.isEmpty() },
                onRetry = onRetry,
            ) { devices -> devices.forEach { DeviceItem(it, house.devices.asOf ?: Instant.EPOCH) } }

            val relayNames =
                house.relays.data
                    .orEmpty()
                    .associate { it.relay.id to it.relay.label }
            val sensorNames =
                house.sensors.data
                    .orEmpty()
                    .associate { it.id to it.label }
            HouseSection(
                title = stringResource(R.string.rules_title),
                section = house.rules,
                loading = R.string.rules_loading,
                empty = R.string.rules_empty,
                isEmpty = { it.isEmpty() },
                onRetry = onRetry,
            ) { rules -> rules.forEach { RuleItem(it, relayNames, sensorNames) } }
        }
    }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun HousePreview() {
    val now = Instant.parse("2026-10-05T12:00:00Z")
    val house =
        House(
            relays =
                Section(
                    listOf(
                        RelayRow(Relay("porch", "Porch light", on = true, holdExpiresAt = now.plusSeconds(90))),
                        RelayRow(Relay("gate", "Gate light", on = false)),
                    ),
                    now,
                ),
            allOff = AllOff(),
            sensors =
                Section(
                    listOf(
                        Sensor(
                            id = "yard",
                            label = "Yard",
                            stale = false,
                            staleAfterSeconds = 300.0,
                            lastSeen = now.minusSeconds(40),
                            motion = Reading.Value(true, now.minusSeconds(40)),
                            temperature = Reading.Value(21.5, now.minusSeconds(400)),
                            humidity = Reading.Never,
                        ),
                    ),
                    now,
                ),
            devices = Section(emptyList(), now),
            rules =
                Section(
                    listOf(
                        AutomationRule(
                            id = "porch-on-motion",
                            enabled = true,
                            onlyAfterDark = true,
                            trigger = AutomationRule.Trigger("yard", motion = true),
                            action = AutomationRule.Action("porch", SwitchState.ON, holdSeconds = 120.0),
                        ),
                    ),
                    now,
                ),
        )
    PihomeTheme {
        HouseContent(house, mayChange = true, zone = ZoneId.of("UTC"), onSet = {
            _,
            _,
            ->
        }, onAllOff = {}, onRetry = {}, onPull = {}, onAccount = {})
    }
}
