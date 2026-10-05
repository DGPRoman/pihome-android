package io.github.dgproman.pihome.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the app stands with its hub. */
sealed interface Gate {
    /** The saved session is still being read. A moment at start, and nothing to show. */
    data object Loading : Gate

    data object SignedOut : Gate

    data class SignedIn(
        val session: SavedSession,
    ) : Gate

    /**
     * The hub stopped accepting the session: it ran out, or the account was
     * disabled or deleted. Said once, so the person knows why they are being
     * asked to connect again, then [SignedOut].
     */
    data object Ended : Gate
}

/**
 * Whether anyone is signed in, for the whole app.
 *
 * Held above every screen rather than in the back stack. Each state draws its
 * own screens, so leaving [Gate.SignedIn] takes all of its screens, and
 * everything they were polling with, away at once: nothing is left behind to
 * keep asking the hub with a session it has refused. That matters, because the
 * hub counts each refusal against this phone's address and locks it out after
 * a few.
 *
 * Changes run in [scope], which outlives any screen, so a sign-out is finished
 * even when the screen that asked for it is already gone. One at a time, in
 * the order asked.
 */
class SessionGate(
    private val store: SessionStore,
    private val scope: CoroutineScope,
) {
    private val gate = MutableStateFlow<Gate>(Gate.Loading)
    private val changes = Mutex()

    val state: StateFlow<Gate> = gate.asStateFlow()

    init {
        change { gate.value = store.read()?.let(Gate::SignedIn) ?: Gate.SignedOut }
    }

    fun signIn(session: SavedSession): Job =
        change {
            store.save(session)
            gate.value = Gate.SignedIn(session)
        }

    fun signOut(): Job =
        change {
            store.clear()
            gate.value = Gate.SignedOut
        }

    /**
     * The hub refused [token]. Ends the session, if it is still the one held.
     *
     * Only then: a refusal can arrive late, from a request sent before the person
     * signed in again, and it says nothing about the session that replaced it.
     */
    fun refused(token: String): Job =
        change {
            val current = gate.value
            if (current is Gate.SignedIn && current.session.token == token) {
                store.clear()
                gate.value = Gate.Ended
            }
        }

    /** The person has read that the session ended. */
    fun acknowledgeEnded(): Job =
        change {
            if (gate.value == Gate.Ended) gate.value = Gate.SignedOut
        }

    private fun change(block: suspend () -> Unit): Job = scope.launch { changes.withLock { block() } }
}
