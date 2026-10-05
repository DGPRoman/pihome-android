package io.github.dgproman.pihome.ui.screens

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.theme.PihomeTheme

/** The house. For now, only which hub it is. */
@Composable
fun HouseScreen(
    hub: String,
    onAccount: () -> Unit,
) {
    Screen(
        title = stringResource(R.string.house_title),
        actions = {
            IconButton(onClick = onAccount) {
                Icon(painterResource(R.drawable.ic_account), contentDescription = stringResource(R.string.account_title))
            }
        },
    ) {
        Text(
            stringResource(R.string.house_connected, hub),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HousePreview() {
    PihomeTheme { HouseScreen(hub = "http://hub.local:5002", onAccount = {}) }
}
