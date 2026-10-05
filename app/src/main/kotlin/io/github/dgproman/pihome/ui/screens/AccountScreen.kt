package io.github.dgproman.pihome.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.theme.PihomeTheme

/** Which hub this phone is signed in to, which build it runs, and the way out. */
@Composable
fun AccountScreen(
    hub: String,
    version: String,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    Screen(title = stringResource(R.string.account_title), onBack = onBack) {
        Fact(stringResource(R.string.account_hub), hub)
        Fact(stringResource(R.string.account_version), version)
        OutlinedButton(onClick = onSignOut) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

/** A label over its value, read out by a screen reader as one item. */
@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountPreview() {
    PihomeTheme { AccountScreen(hub = "http://hub.local:5002", version = "0.1.0", onBack = {}, onSignOut = {}) }
}
