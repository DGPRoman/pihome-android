package io.github.dgproman.pihome.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.dgproman.pihome.AppGraph
import io.github.dgproman.pihome.session.Gate
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.ui.screens.AccountScreen
import io.github.dgproman.pihome.ui.screens.HouseScreen
import io.github.dgproman.pihome.ui.screens.SessionEndedScreen
import io.github.dgproman.pihome.ui.screens.WelcomeScreen

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
            SignedOut()
        }

        is Gate.SignedIn -> {
            key(current.session) {
                SignedIn(current.session, version, onSignOut = { graph.gate.signOut() })
            }
        }

        Gate.Ended -> {
            SessionEndedScreen(onContinue = { graph.gate.acknowledgeEnded() })
        }
    }
}

@Composable
private fun SignedOut() {
    val backStack = rememberNavBackStack(Welcome)
    Navigation(backStack) {
        entry<Welcome> { WelcomeScreen() }
    }
}

@Composable
private fun SignedIn(
    session: SavedSession,
    version: String,
    onSignOut: () -> Unit,
) {
    val backStack = rememberNavBackStack(House)
    Navigation(backStack) {
        entry<House> { HouseScreen(hub = session.hub, onAccount = { backStack.add(Account) }) }
        entry<Account> {
            AccountScreen(
                hub = session.hub,
                version = version,
                onBack = { backStack.removeLastOrNull() },
                onSignOut = onSignOut,
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
