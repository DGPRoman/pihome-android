package io.github.dgproman.pihome.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.hub.InputProblem
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.connect.linkMessageFor
import io.github.dgproman.pihome.ui.theme.PihomeTheme

/**
 * An invitation link brought by hand, for a phone that cannot scan or a link
 * that came in a message. Read here, and sent nowhere until the person joins.
 */
@Composable
fun PasteInvitationScreen(
    onBack: () -> Unit,
    onInvitation: (InvitationLink) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<InputProblem?>(null) }

    fun submit() {
        when (val link = InvitationLink.parse(text)) {
            is Parsed.Valid -> onInvitation(link.value)
            is Parsed.Invalid -> problem = link.problem
        }
    }

    Screen(title = stringResource(R.string.paste_title), onBack = onBack) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                problem = null
            },
            label = { Text(stringResource(R.string.invitation_link)) },
            isError = problem != null,
            supportingText = problem?.let { { Text(stringResource(linkMessageFor(it))) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = ::submit) { Text(stringResource(R.string.continue_button)) }
    }
}

@Preview(showBackground = true)
@Composable
private fun PastePreview() {
    PihomeTheme { PasteInvitationScreen(onBack = {}, onInvitation = {}) }
}
