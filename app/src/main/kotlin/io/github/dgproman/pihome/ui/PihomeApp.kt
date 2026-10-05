package io.github.dgproman.pihome.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.dgproman.pihome.AppGraph
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.connect.Incoming
import io.github.dgproman.pihome.connect.JoinViewModel
import io.github.dgproman.pihome.connect.LogInViewModel
import io.github.dgproman.pihome.house.HouseViewModel
import io.github.dgproman.pihome.house.mayChangeTheHouse
import io.github.dgproman.pihome.hub.InputProblem
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.people.PeopleViewModel
import io.github.dgproman.pihome.people.mayManagePeople
import io.github.dgproman.pihome.quick.TileSettingsViewModel
import io.github.dgproman.pihome.session.Gate
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.ui.connect.linkMessageFor
import io.github.dgproman.pihome.ui.screens.AccountScreen
import io.github.dgproman.pihome.ui.screens.HouseScreen
import io.github.dgproman.pihome.ui.screens.JoinScreen
import io.github.dgproman.pihome.ui.screens.LogInScreen
import io.github.dgproman.pihome.ui.screens.PasteInvitationScreen
import io.github.dgproman.pihome.ui.screens.PeopleScreen
import io.github.dgproman.pihome.ui.screens.SessionEndedScreen
import io.github.dgproman.pihome.ui.screens.TileScreen
import io.github.dgproman.pihome.ui.screens.WelcomeScreen
import java.time.Instant

/**
 * The whole app: which of its two halves to show, decided by the session gate.
 *
 * Signed out and signed in each have their own back stack, made fresh whenever
 * the gate changes, so neither can be reached from the other by going back and
 * a new session never starts on a screen left over from the last one.
 */
@Composable
fun PihomeApp(
    graph: AppGraph,
    version: String,
) {
    val gate by graph.gate.state.collectAsStateWithLifecycle()

    when (val current = gate) {
        // A few milliseconds at start, while the saved session is read. The
        // background alone, rather than a flash of the signed-out screen.
        Gate.Loading -> {
            Box(Modifier.fillMaxSize())
        }

        Gate.SignedOut -> {
            SignedOut(graph)
        }

        // Keyed on the token alone: the hub's account of who this is can change
        // under an open screen without starting the signed-in half over.
        is Gate.SignedIn -> {
            key(current.saved.token) {
                SignedIn(graph, current.saved, version)
            }
        }

        Gate.Ended -> {
            SessionEndedScreen(onContinue = { graph.gate.acknowledgeEnded() })
        }
    }
}

/**
 * The ways in. Joining or logging in signs the gate in, which replaces all of
 * this with the signed-in half; nothing here navigates there itself.
 */
@Composable
private fun SignedOut(graph: AppGraph) {
    val backStack = rememberNavBackStack(Welcome)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    val join: (InvitationLink) -> Unit = { link -> backStack.add(Join(link, graph.clock.millis())) }
    var problem by rememberSaveable { mutableStateOf<InputProblem?>(null) }

    // An invitation handed to the app starts over from the first screen, on its
    // own join screen, whatever was open: it is what the person just asked for.
    val incoming by graph.incoming.next.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        val arrived = incoming ?: return@LaunchedEffect
        while (backStack.size > 1) backStack.removeLastOrNull()
        when (arrived) {
            is Incoming.Invitation -> {
                problem = null
                backStack.add(Join(arrived.link, arrived.receivedAt.toEpochMilli()))
            }

            is Incoming.Broken -> {
                problem = arrived.problem
            }
        }
        graph.incoming.take(arrived)
    }

    Navigation(backStack) {
        entry<Welcome> {
            WelcomeScreen(
                problem = problem,
                onPaste = {
                    problem = null
                    backStack.add(PasteInvitation)
                },
                onLogIn = {
                    problem = null
                    backStack.add(LogIn)
                },
            )
        }
        entry<PasteInvitation> {
            PasteInvitationScreen(
                onBack = back,
                // In place of this screen, so going back from the invitation is the start.
                onInvitation = { link ->
                    backStack.removeLastOrNull()
                    join(link)
                },
            )
        }
        entry<Join> { key ->
            val model =
                viewModel {
                    JoinViewModel(
                        link = key.link,
                        received = Instant.ofEpochMilli(key.receivedAt),
                        hubs = graph.hubs,
                        localNetwork = graph.localNetwork,
                        gate = graph.gate,
                        appScope = graph.scope,
                        clock = graph.clock,
                    )
                }
            JoinScreen(model, onBack = back)
        }
        entry<LogIn> {
            val model = viewModel { LogInViewModel(graph.hubs, graph.localNetwork, graph.gate, graph.scope) }
            LogInScreen(model, onBack = back)
        }
    }
}

@Composable
private fun SignedIn(
    graph: AppGraph,
    saved: SavedSession,
    version: String,
) {
    val backStack = rememberNavBackStack(House)
    // An account that is no longer an admin has no business on the people screen.
    LaunchedEffect(saved.session.role) {
        if (!saved.session.role.mayManagePeople) backStack.removeAll { it == People }
    }
    IncomingWhileSignedIn(graph, saved)
    Navigation(backStack) {
        entry<House> {
            val model =
                viewModel {
                    HouseViewModel(
                        hub = graph.hubs.at(saved.address, saved.token),
                        clock = graph.clock,
                        onRefused = { graph.gate.refused(saved.token) },
                        // The role may have changed since this phone last asked.
                        onForbidden = { graph.gate.check() },
                    )
                }
            HouseScreen(
                model = model,
                mayChange = saved.session.role.mayChangeTheHouse,
                zone = graph.clock.zone,
                onAccount = { backStack.add(Account) },
                onPeople = if (saved.session.role.mayManagePeople) ({ backStack.add(People) }) else null,
            )
        }
        entry<People> {
            val model =
                viewModel {
                    PeopleViewModel(
                        hub = graph.hubs.at(saved.address, saved.token),
                        clock = graph.clock,
                        onRefused = { graph.gate.refused(saved.token) },
                        onForbidden = { graph.gate.check() },
                    )
                }
            PeopleScreen(model, saved.address, graph.clock, onBack = { backStack.removeLastOrNull() })
        }
        entry<Account> {
            AccountScreen(
                saved = saved,
                version = version,
                clock = graph.clock,
                onBack = { backStack.removeLastOrNull() },
                onSignOut = { graph.gate.signOut() },
                onTile = { backStack.add(TileSettings) },
            )
        }
        entry<TileSettings> {
            val model =
                viewModel {
                    TileSettingsViewModel(
                        hub = graph.hubs.at(saved.address, saved.token),
                        choices = graph.tileChoices,
                        clock = graph.clock,
                        onRefused = { graph.gate.refused(saved.token) },
                        onChosen = graph.redrawTile,
                    )
                }
            TileScreen(model, mayChange = saved.session.role.mayChangeTheHouse, onBack = { backStack.removeLastOrNull() })
        }
    }
}

/**
 * An invitation handed to the app while it is signed in, asked about first:
 * using it signs this phone out of the account it has. Signing out leaves the
 * invitation waiting, for the signed-out half to open.
 */
@Composable
private fun IncomingWhileSignedIn(
    graph: AppGraph,
    saved: SavedSession,
) {
    val incoming by graph.incoming.next.collectAsStateWithLifecycle()
    when (val arrived = incoming) {
        null -> {}

        is Incoming.Invitation -> {
            AlertDialog(
                onDismissRequest = { graph.incoming.take(arrived) },
                title = { Text(stringResource(R.string.incoming_title)) },
                text = { Text(stringResource(R.string.incoming_body, saved.address.origin, saved.session.username)) },
                confirmButton = {
                    TextButton(onClick = { graph.gate.signOut() }) { Text(stringResource(R.string.incoming_use)) }
                },
                dismissButton = {
                    TextButton(onClick = { graph.incoming.take(arrived) }) { Text(stringResource(R.string.incoming_keep)) }
                },
            )
        }

        is Incoming.Broken -> {
            AlertDialog(
                onDismissRequest = { graph.incoming.take(arrived) },
                text = { Text(stringResource(linkMessageFor(arrived.problem))) },
                confirmButton = {
                    TextButton(onClick = { graph.incoming.take(arrived) }) { Text(stringResource(R.string.dismiss)) }
                },
            )
        }
    }
}

/** A back stack shown one screen at a time, each screen keeping its own state and ViewModels. */
@Composable
private fun Navigation(
    backStack: NavBackStack<NavKey>,
    entries: EntryProviderScope<NavKey>.() -> Unit,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider = entryProvider(builder = entries),
    )
}
