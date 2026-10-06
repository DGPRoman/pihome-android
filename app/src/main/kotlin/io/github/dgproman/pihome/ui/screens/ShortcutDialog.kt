package io.github.dgproman.pihome.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.quick.ShortcutRequest
import io.github.dgproman.pihome.quick.ShortcutResult
import io.github.dgproman.pihome.ui.connect.messageFor
import kotlinx.coroutines.delay

/**
 * What a launcher shortcut is doing, and then what came of it.
 *
 * A shortcut that did what it said closes on its own after a moment, held
 * open longer for somebody who has asked Android for more time to read. One
 * that could not act opens the app; anything else stays until it is closed.
 */
@Composable
fun ShortcutDialog(
    request: ShortcutRequest,
    /** Null while the hub has not answered. */
    result: ShortcutResult?,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val close by rememberUpdatedState(onClose)
    val openApp by rememberUpdatedState(onOpenApp)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(result) {
        when (result) {
            ShortcutResult.OpenApp -> {
                openApp()
            }

            is ShortcutResult.Switched, ShortcutResult.AllOff -> {
                val shown = SHOWN_FOR_MILLIS
                delay(accessibility?.calculateRecommendedTimeoutMillis(shown, containsText = true, containsControls = true) ?: shown)
                close()
            }

            else -> {}
        }
    }

    val title =
        when (request) {
            ShortcutRequest.AllOff -> stringResource(R.string.all_off)
            is ShortcutRequest.Switch -> request.relay.name
        }
    val mayOpenApp = result is ShortcutResult.Unsure || result is ShortcutResult.Failed
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    sayWhat(result),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.shortcut_close)) } },
        dismissButton =
            if (mayOpenApp) {
                { TextButton(onClick = onOpenApp) { Text(stringResource(R.string.shortcut_open_app)) } }
            } else {
                null
            },
    )
}

@Composable
private fun sayWhat(result: ShortcutResult?): String =
    when (result) {
        null, ShortcutResult.OpenApp -> {
            stringResource(R.string.shortcut_asking)
        }

        is ShortcutResult.Switched -> {
            stringResource(if (result.on) R.string.shortcut_now_on else R.string.shortcut_now_off, result.name)
        }

        ShortcutResult.AllOff -> {
            stringResource(R.string.shortcut_all_off_done)
        }

        is ShortcutResult.Unsure -> {
            result.name?.let { stringResource(R.string.shortcut_unsure, it) }
                ?: stringResource(R.string.shortcut_all_off_unsure)
        }

        is ShortcutResult.Gone -> {
            stringResource(R.string.shortcut_gone, result.name)
        }

        is ShortcutResult.Failed -> {
            stringResource(messageFor(result.kind))
        }
    }

/** Long enough to read a short sentence, short enough not to be in the way. */
private const val SHOWN_FOR_MILLIS = 2500L
