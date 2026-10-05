package io.github.dgproman.pihome.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Account
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.hub.ManagedRole
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.people.Action
import io.github.dgproman.pihome.people.People
import io.github.dgproman.pihome.people.PeopleViewModel
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.house.HouseSection
import io.github.dgproman.pihome.ui.people.AddPerson
import io.github.dgproman.pihome.ui.people.InvitationView
import io.github.dgproman.pihome.ui.people.PersonItem
import io.github.dgproman.pihome.ui.people.messageFor
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Who can get into the house, for an admin: every account, a way to add one,
 * and the invitation that lets somebody in.
 *
 * Read from the hub while in view, which is how a used invitation is noticed.
 */
@Composable
fun PeopleScreen(
    model: PeopleViewModel,
    address: HubAddress,
    clock: Clock,
    onBack: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.poll() }
    }
    val people by model.state.collectAsStateWithLifecycle()
    // Each second while an invitation is up, for the time it has left.
    var now by remember { mutableStateOf(clock.instant()) }
    if (people.shown != null) {
        LaunchedEffect(clock) {
            while (true) {
                now = clock.instant()
                delay(1_000)
            }
        }
    }
    PeopleContent(
        people = people,
        address = address,
        now = now,
        zone = clock.zone,
        onBack = onBack,
        onRetry = { model.refresh() },
        onPull = model::pull,
        onRole = model::setRole,
        onDisabled = model::setDisabled,
        onInvite = model::invite,
        onWithdraw = model::withdraw,
        onDelete = model::delete,
        onAdd = model::add,
        onWithdrawShown = model::withdrawShown,
        onClose = model::close,
    )
}

/** The accounts, or the invitation on screen in their place. */
@Composable
fun PeopleContent(
    people: People,
    address: HubAddress,
    now: Instant,
    zone: ZoneId,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPull: () -> Unit,
    onRole: (String, ManagedRole) -> Unit,
    onDisabled: (String, Boolean) -> Unit,
    onInvite: (String) -> Unit,
    onWithdraw: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: (String, ManagedRole) -> Unit,
    onWithdrawShown: () -> Unit,
    onClose: () -> Unit,
) {
    val shown = people.shown
    if (shown != null) {
        // Back closes the invitation, as Done does, and leaves the list.
        BackHandler(onBack = onClose)
        val failure = people.failures[shown.username]?.takeIf { it.action == Action.INVITE }
        Screen(title = stringResource(R.string.invitation_title, shown.username), onBack = onClose) {
            InvitationView(
                shown = shown,
                link = InvitationLink(address, shown.invitation.token).webLink(),
                onlyThisDevice = address.onlyThisDevice,
                now = now,
                zone = zone,
                inviting = people.inviting == shown.username,
                inviteFailure = failure?.let { messageFor(it) },
                onWithdraw = onWithdrawShown,
                onInviteAgain = { onInvite(shown.username) },
                onDone = onClose,
            )
        }
        return
    }

    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Screen(
        title = stringResource(R.string.people_title),
        onBack = onBack,
        refreshing = people.refreshing,
        onRefresh = onPull,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
            HouseSection(
                title = stringResource(R.string.accounts_title),
                section = people.accounts,
                loading = R.string.people_loading,
                // Never seen in practice: the admin reading this has an account.
                empty = R.string.people_empty,
                isEmpty = { it.isEmpty() },
                onRetry = onRetry,
            ) { accounts ->
                accounts.forEach { account ->
                    val name = account.username
                    PersonItem(
                        account = account,
                        busy = name in people.busy,
                        inviting = people.inviting == name,
                        anyInviting = people.inviting != null,
                        failure = people.failures[name],
                        zone = zone,
                        onRole = { onRole(name, it) },
                        onDisabled = { onDisabled(name, it) },
                        onInvite = { onInvite(name) },
                        onWithdraw = { onWithdraw(name) },
                        onDelete = { deleting = name },
                    )
                }
            }
            AddPerson(people.adding, onAdd)
        }
    }

    deleting?.let { name ->
        // A dialog, so the question is asked before anything is sent and cannot be missed.
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_title, name)) },
            text = { Text(stringResource(R.string.delete_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(name)
                }) { Text(stringResource(R.string.delete_confirm, name), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.keep)) } },
        )
    }
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun PeoplePreview() {
    val now = Instant.parse("2026-10-05T12:00:00Z")
    val accounts =
        listOf(
            Account("admin", Role.ADMIN, disabled = false, createdAt = now, invitationExpiresAt = null),
            Account("olya", Role.OPERATOR, disabled = false, createdAt = now, invitationExpiresAt = now.plusSeconds(600)),
            Account("taras", Role.VIEWER, disabled = true, createdAt = now, invitationExpiresAt = null),
        )
    PihomeTheme {
        PeopleContent(
            people = People(accounts = Section(accounts, now)),
            address = (HubAddress.parse("http://hub.local:5002") as Parsed.Valid).value,
            now = now,
            zone = ZoneId.of("UTC"),
            onBack = {},
            onRetry = {},
            onPull = {},
            onRole = { _, _ -> },
            onDisabled = { _, _ -> },
            onInvite = {},
            onWithdraw = {},
            onDelete = {},
            onAdd = { _, _ -> },
            onWithdrawShown = {},
            onClose = {},
        )
    }
}
