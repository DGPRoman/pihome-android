package io.github.dgproman.pihome.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
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
import io.github.dgproman.pihome.connect.JoinViewModel
import io.github.dgproman.pihome.connect.LogInViewModel
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.session.Gate
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.ui.screens.AccountScreen
import io.github.dgproman.pihome.ui.screens.HouseScreen
import io.github.dgproman.pihome.ui.screens.JoinScreen
import io.github.dgproman.pihome.ui.screens.LogInScreen
import io.github.dgproman.pihome.ui.screens.PasteInvitationScreen
import io.github.dgproman.pihome.ui.screens.SessionEndedScreen
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
    Navigation(backStack) {
        entry<Welcome> {
            WelcomeScreen(
                scanner = graph.scanner,
                onInvitation = join,
                onPaste = { backStack.add(PasteInvitation) },
                onLogIn = { backStack.add(LogIn) },
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
    Navigation(backStack) {
        entry<House> { HouseScreen(hub = saved.address.origin, onAccount = { backStack.add(Account) }) }
        entry<Account> {
            AccountScreen(
                saved = saved,
                version = version,
                clock = graph.clock,
                onBack = { backStack.removeLastOrNull() },
                onSignOut = { graph.gate.signOut() },
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
