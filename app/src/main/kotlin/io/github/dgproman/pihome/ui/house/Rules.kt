package io.github.dgproman.pihome.ui.house

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.spanOf
import io.github.dgproman.pihome.hub.AutomationRule
import io.github.dgproman.pihome.hub.SwitchState

/**
 * One rule, as a sentence. Relays and sensors by the names the hub gives them,
 * where the screen has them, and by their ids where it does not yet.
 */
@Composable
fun RuleItem(
    rule: AutomationRule,
    relayNames: Map<String, String>,
    sensorNames: Map<String, String>,
) {
    val sensor = sensorNames[rule.trigger.device] ?: rule.trigger.device
    val relay = relayNames[rule.action.relay] ?: rule.action.relay
    val whenPart = stringResource(if (rule.trigger.motion) R.string.rule_when_motion else R.string.rule_when_still, sensor)
    val thenPart =
        stringResource(if (rule.action.state == SwitchState.ON) R.string.rule_turn_on else R.string.rule_turn_off, relay)
    val hold = rule.action.holdSeconds
    Item {
        Text(
            if (hold == null) {
                stringResource(R.string.rule_sentence, whenPart, thenPart)
            } else {
                stringResource(R.string.rule_sentence_hold, whenPart, thenPart, spanText(spanOf(hold)))
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!rule.enabled || rule.onlyAfterDark) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!rule.enabled) Badge(stringResource(R.string.rule_disabled))
                if (rule.onlyAfterDark) Badge(stringResource(R.string.rule_after_dark))
            }
        }
    }
}
