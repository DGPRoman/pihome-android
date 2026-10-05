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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.connect.InvitationScanner
import io.github.dgproman.pihome.connect.Scan
import io.github.dgproman.pihome.hub.InputProblem
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.ui.connect.Problem
import io.github.dgproman.pihome.ui.connect.linkMessageFor
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import kotlinx.coroutines.launch

/**
 * Where a phone that is not signed in starts, and the three ways in.
 *
 * Scanning comes first, because most people arrive holding an invitation.
 * A code that is read goes to [onInvitation]; nothing is sent to the hub yet.
 */
@Composable
fun WelcomeScreen(
    scanner: InvitationScanner,
    onInvitation: (InvitationLink) -> Unit,
    onPaste: () -> Unit,
    onLogIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unavailable by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<InputProblem?>(null) }

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
            Button(
                onClick = {
                    unavailable = false
                    problem = null
                    scope.launch {
                        when (val scan = scanner.scan(context)) {
                            Scan.Cancelled -> {}

                            Scan.Unavailable -> {
                                unavailable = true
                            }

                            is Scan.Read -> {
                                when (val link = InvitationLink.parse(scan.text)) {
                                    is Parsed.Valid -> onInvitation(link.value)
                                    is Parsed.Invalid -> problem = link.problem
                                }
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.scan_invitation)) }
            if (unavailable) Problem(stringResource(R.string.scan_unavailable))
            problem?.let { Problem(stringResource(linkMessageFor(it))) }
            OutlinedButton(onClick = onPaste) { Text(stringResource(R.string.paste_invitation)) }
            TextButton(onClick = onLogIn) { Text(stringResource(R.string.log_in_with_password)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomePreview() {
    PihomeTheme { WelcomeScreen(scanner = { Scan.Cancelled }, onInvitation = {}, onPaste = {}, onLogIn = {}) }
}
