package io.github.dgproman.pihome.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.hub.InputProblem
import io.github.dgproman.pihome.ui.connect.Problem
import io.github.dgproman.pihome.ui.connect.linkMessageFor
import io.github.dgproman.pihome.ui.theme.PihomeTheme

/**
 * Where a phone that is not signed in starts.
 *
 * Most people never press anything here: they scan an invitation with the
 * phone's camera, and the hub's join page hands it to the app, which opens on
 * the join screen. This is for everyone else: a link to paste, or a password.
 * [problem] is what was wrong with a link the app was opened with, if anything.
 */
@Composable
fun WelcomeScreen(
    problem: InputProblem?,
    onPaste: () -> Unit,
    onLogIn: () -> Unit,
) {
    Scaffold { insets ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.not_connected),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
            problem?.let { Problem(stringResource(linkMessageFor(it))) }
            Button(onClick = onPaste) { Text(stringResource(R.string.paste_invitation)) }
            TextButton(onClick = onLogIn) { Text(stringResource(R.string.log_in_with_password)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomePreview() {
    PihomeTheme { WelcomeScreen(problem = null, onPaste = {}, onLogIn = {}) }
}
