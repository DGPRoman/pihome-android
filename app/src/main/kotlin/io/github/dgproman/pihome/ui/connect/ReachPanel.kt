package io.github.dgproman.pihome.ui.connect

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.connect.LOCAL_NETWORK_PERMISSION
import io.github.dgproman.pihome.connect.Reach

/** Asks Android for the local network permission, and hands [onAnswer] whether it was given. */
@Composable
fun rememberLocalNetworkRequest(onAnswer: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onAnswer)
    return { launcher.launch(LOCAL_NETWORK_PERMISSION) }
}

/**
 * How far reaching the hub has got, and what the person can do about it.
 *
 * Nothing once it is reached: the screen carries on with its own step.
 * [onAllow] asks Android for the local network permission, [onRetry] checks the
 * hub again, and [extra] is anything more a screen offers beside them.
 */
@Composable
fun ReachPanel(
    reach: Reach,
    onAllow: () -> Unit,
    onRetry: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    when (reach) {
        Reach.Checking -> {
            Busy(stringResource(R.string.checking_hub))
        }

        Reach.NeedsPermission -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.permission_body), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onAllow) { Text(stringResource(R.string.continue_button)) }
                extra()
            }
        }

        Reach.PermissionRefused -> {
            val context = LocalContext.current
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Problem(stringResource(R.string.permission_refused))
                Button(onClick = onAllow) { Text(stringResource(R.string.permission_ask_again)) }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                ) { Text(stringResource(R.string.open_settings)) }
                extra()
            }
        }

        is Reach.Failed -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Problem(stringResource(messageFor(reach.kind)))
                Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
                extra()
            }
        }

        Reach.Reached -> {}
    }
}

/** Something under way, said out loud when it starts. */
@Composable
fun Busy(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** What went wrong, in the error colour and said out loud when it appears. */
@Composable
fun Problem(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
