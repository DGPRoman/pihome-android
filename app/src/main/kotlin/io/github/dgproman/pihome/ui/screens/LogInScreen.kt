package io.github.dgproman.pihome.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.connect.LogInProblem
import io.github.dgproman.pihome.connect.LogInState
import io.github.dgproman.pihome.connect.LogInViewModel
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.connect.Busy
import io.github.dgproman.pihome.ui.connect.Problem
import io.github.dgproman.pihome.ui.connect.ReachPanel
import io.github.dgproman.pihome.ui.connect.messageFor
import io.github.dgproman.pihome.ui.connect.rememberLocalNetworkRequest

/** An address, a name and a password: the way in for an admin. */
@Composable
fun LogInScreen(
    model: LogInViewModel,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val askForLocalNetwork = rememberLocalNetworkRequest(model::permissionAnswered)
    LifecycleResumeEffect(model) {
        model.resumed()
        onPauseOrDispose {}
    }
    val editing = state is LogInState.Editing
    val problem = (state as? LogInState.Editing)?.problem

    Screen(title = stringResource(R.string.log_in_title), onBack = onBack) {
        OutlinedTextField(
            value = model.address,
            onValueChange = { model.address = it },
            label = { Text(stringResource(R.string.hub_address)) },
            enabled = editing,
            isError = problem is LogInProblem.Address,
            supportingText = (problem as? LogInProblem.Address)?.let { { Text(stringResource(messageFor(it.problem))) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.username,
            onValueChange = { model.username = it },
            label = { Text(stringResource(R.string.username)) },
            enabled = editing,
            isError = problem == LogInProblem.NoUsername,
            supportingText = if (problem == LogInProblem.NoUsername) ({ Text(stringResource(R.string.no_username)) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )
        OutlinedTextField(
            value = model.password,
            onValueChange = { model.password = it },
            label = { Text(stringResource(R.string.password)) },
            enabled = editing,
            isError = problem == LogInProblem.NoPassword,
            supportingText = if (problem == LogInProblem.NoPassword) ({ Text(stringResource(R.string.no_password)) }) else null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { model.logIn() }),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )

        when (val current = state) {
            is LogInState.Editing -> {
                (current.problem as? LogInProblem.Hub)?.let {
                    Problem(
                        stringResource(if (it.kind == HubErrorKind.UNAUTHORIZED) R.string.log_in_refused else messageFor(it.kind)),
                    )
                }
                Button(onClick = model::logIn) { Text(stringResource(R.string.log_in_button)) }
            }

            is LogInState.Reaching -> {
                ReachPanel(current.reach, onAllow = askForLocalNetwork, onRetry = model::logIn) {
                    OutlinedButton(onClick = model::dismiss) { Text(stringResource(R.string.navigate_up)) }
                }
            }

            LogInState.Checking -> {
                Busy(stringResource(R.string.logging_in))
            }
        }
    }
}
