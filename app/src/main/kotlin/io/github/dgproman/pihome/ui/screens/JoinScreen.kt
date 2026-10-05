package io.github.dgproman.pihome.ui.screens

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.connect.JoinState
import io.github.dgproman.pihome.connect.JoinViewModel
import io.github.dgproman.pihome.connect.Reach
import io.github.dgproman.pihome.connect.Spent
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.connect.Busy
import io.github.dgproman.pihome.ui.connect.Problem
import io.github.dgproman.pihome.ui.connect.ReachPanel
import io.github.dgproman.pihome.ui.connect.rememberLocalNetworkRequest

/**
 * The hub an invitation leads to, and the one button that redeems it.
 *
 * Says which hub before anything is sent, since the invitation is spent on the
 * first try. Once it is spent or refused, the only way on is a new one.
 */
@Composable
fun JoinScreen(
    model: JoinViewModel,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val askForLocalNetwork = rememberLocalNetworkRequest(model::permissionAnswered)
    LifecycleResumeEffect(model) {
        model.resumed()
        onPauseOrDispose {}
    }

    Screen(title = stringResource(R.string.join_title), onBack = onBack) {
        Text(stringResource(R.string.join_body, model.link.hub.origin), style = MaterialTheme.typography.bodyLarge)

        when (val current = state) {
            is JoinState.Preparing -> {
                ReachPanel(current.reach, onAllow = askForLocalNetwork, onRetry = model::reach)
                if (current.reach == Reach.Reached) {
                    Button(onClick = model::join) { Text(stringResource(R.string.join_button)) }
                }
            }

            JoinState.Joining -> {
                Busy(stringResource(R.string.joining))
            }

            is JoinState.Over -> {
                Problem(
                    stringResource(
                        when (current.why) {
                            Spent.EXPIRED -> R.string.join_expired
                            Spent.REFUSED -> R.string.join_refused
                            Spent.LOST -> R.string.join_lost
                        },
                    ),
                )
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back_to_start)) }
            }
        }
    }
}
