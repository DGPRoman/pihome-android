package io.github.dgproman.pihome.ui.people

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.mayHaveHappened
import io.github.dgproman.pihome.hub.Account
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.ManagedRole
import io.github.dgproman.pihome.people.Action
import io.github.dgproman.pihome.people.Adding
import io.github.dgproman.pihome.people.Failure
import io.github.dgproman.pihome.people.isUsername
import io.github.dgproman.pihome.ui.connect.messageFor
import io.github.dgproman.pihome.ui.house.Badge
import io.github.dgproman.pihome.ui.house.Item
import io.github.dgproman.pihome.ui.house.NameLine
import io.github.dgproman.pihome.ui.house.Note
import io.github.dgproman.pihome.ui.house.Problem
import io.github.dgproman.pihome.ui.house.timeOfDay
import java.time.ZoneId
import io.github.dgproman.pihome.hub.Role as AccountRole

/**
 * One account, and what an admin may do to it from here.
 *
 * Actions named for what they do rather than a role picker: with two roles to
 * choose from, a picker is a control that can only ever be set to the other one.
 */
@Composable
fun PersonItem(
    account: Account,
    busy: Boolean,
    /** An invitation is being issued to this account. */
    inviting: Boolean,
    /** An invitation is being issued to anybody: one at a time. */
    anyInviting: Boolean,
    failure: Failure?,
    zone: ZoneId,
    onRole: (ManagedRole) -> Unit,
    onDisabled: (Boolean) -> Unit,
    onInvite: () -> Unit,
    onWithdraw: () -> Unit,
    onDelete: () -> Unit,
) {
    Item {
        NameLine(account.username) {
            Badge(stringResource(nameOf(account.role)))
            if (account.disabled) Badge(stringResource(R.string.person_disabled), fault = true)
        }
        account.invitationExpiresAt?.let { Note(stringResource(R.string.person_invited, timeOfDay(it, zone))) }

        if (account.role == AccountRole.ADMIN) {
            Note(stringResource(R.string.managed_on_console))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onRole(if (account.role == AccountRole.VIEWER) ManagedRole.OPERATOR else ManagedRole.VIEWER) },
                    enabled = !busy,
                ) {
                    Text(stringResource(if (account.role == AccountRole.VIEWER) R.string.make_operator else R.string.make_viewer))
                }
                OutlinedButton(onClick = { onDisabled(!account.disabled) }, enabled = !busy) {
                    Text(stringResource(if (account.disabled) R.string.enable else R.string.disable))
                }
                // Not offered for a disabled account: the hub refuses it, and the badge says why.
                if (!account.disabled) {
                    OutlinedButton(onClick = onInvite, enabled = !anyInviting) {
                        Text(
                            stringResource(
                                when {
                                    inviting -> R.string.inviting
                                    account.invitationExpiresAt == null -> R.string.invite
                                    else -> R.string.invite_again
                                },
                            ),
                        )
                    }
                }
                if (account.invitationExpiresAt != null) {
                    OutlinedButton(onClick = onWithdraw, enabled = !busy) { Text(stringResource(R.string.withdraw_invitation)) }
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
            if (busy) {
                Note(stringResource(R.string.saving), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        failure?.let { Problem(stringResource(messageFor(it))) }
    }
}

/** What to say when a change to an account did not go through. */
@StringRes
fun messageFor(failure: Failure): Int =
    when {
        failure.kind.mayHaveHappened -> R.string.change_unconfirmed
        failure.kind == HubErrorKind.NOT_FOUND -> R.string.person_gone
        failure.kind == HubErrorKind.FORBIDDEN -> R.string.admins_only
        failure.action == Action.INVITE && failure.kind == HubErrorKind.CONFLICT -> R.string.invite_disabled
        else -> messageFor(failure.kind)
    }

@StringRes
fun nameOf(role: AccountRole): Int =
    when (role) {
        AccountRole.ADMIN -> R.string.role_admin
        AccountRole.OPERATOR -> R.string.role_operator
        AccountRole.VIEWER -> R.string.role_viewer
    }

/**
 * Add somebody: a name and a role, then straight to their invitation.
 *
 * No password field. The account is made with no password anybody holds, and
 * the invitation is the way in, which is why adding a person no longer needs
 * somebody at the hub's console.
 */
@Composable
fun AddPerson(
    adding: Adding,
    onAdd: (String, ManagedRole) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    // The least an account can be. Making somebody an operator is a choice made on purpose.
    var role by rememberSaveable { mutableStateOf(ManagedRole.VIEWER) }
    // Not flagged while it is typed: every name is wrong one letter in.
    var submitted by rememberSaveable { mutableStateOf(false) }
    // Emptied once for each person added, and not again when the screen is made anew.
    var seen by rememberSaveable { mutableIntStateOf(adding.added) }
    if (adding.added != seen) {
        seen = adding.added
        name = ""
        role = ManagedRole.VIEWER
        submitted = false
    }
    val flagged = submitted && !isUsername(name)
    val submit = {
        submitted = true
        if (isUsername(name)) onAdd(name, role)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.add_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.person_name)) },
            // Read before it is broken, and in the error colour once it is.
            supportingText = { Text(stringResource(R.string.username_rule)) },
            isError = flagged,
            enabled = !adding.pending,
            singleLine = true,
            // A phone that capitalises the first letter, or corrects a name, has changed it.
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.person_role), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            // Never admin: that is granted on the hub's console.
            listOf(
                ManagedRole.VIEWER to R.string.role_choice_viewer,
                ManagedRole.OPERATOR to R.string.role_choice_operator,
            ).forEach { (option, words) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = role == option, enabled = !adding.pending, role = Role.RadioButton) { role = option },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = role == option, onClick = null, enabled = !adding.pending)
                    Text(stringResource(words), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }

        Button(onClick = submit, enabled = !adding.pending) {
            Text(stringResource(if (adding.pending) R.string.adding else R.string.add_button))
        }
        adding.failure?.let { Problem(stringResource(addMessageFor(it))) }
    }
}

/** What to say when the hub would not make the account. */
@StringRes
private fun addMessageFor(kind: HubErrorKind): Int =
    when (kind) {
        HubErrorKind.CONFLICT -> R.string.name_taken
        HubErrorKind.MALFORMED -> R.string.name_refused
        HubErrorKind.FORBIDDEN -> R.string.admins_only
        else -> if (kind.mayHaveHappened) R.string.change_unconfirmed else messageFor(kind)
    }
