package io.github.dgproman.pihome.session

import android.util.Log
import io.github.dgproman.pihome.Hubs
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.HubException
import io.github.dgproman.pihome.hub.SessionToken
import io.github.dgproman.pihome.hub.SignedIn
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
        val saved: SavedSession,
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
 * a few. Whatever is refused first, anywhere, calls [refused].
 *
 * Changes run in [scope], which outlives any screen, so a sign-out is finished
 * even when the screen that asked for it is already gone. One at a time, in
 * the order asked.
 */
class SessionGate(
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val hubs: Hubs,
) {
    private val gate = MutableStateFlow<Gate>(Gate.Loading)
    private val changes = Mutex()
    private var checking: Job? = null

    val state: StateFlow<Gate> = gate.asStateFlow()

    init {
        change { gate.value = store.read()?.let(Gate::SignedIn) ?: Gate.SignedOut }
    }

    /** Keep a session just opened on the hub at [address]. */
    fun signIn(
        address: HubAddress,
        signedIn: SignedIn,
    ): Job =
        change {
            val saved = SavedSession(address, signedIn.token, signedIn.session)
            store.save(saved)
            gate.value = Gate.SignedIn(saved)
        }

    /**
     * Forget the session here, then end it on the hub.
     *
     * In that order, so the phone is signed out at once whether or not the hub
     * can be reached. Ending it there is a courtesy that may not arrive; a
     * session the hub still holds runs out on its own.
     */
    fun signOut(): Job =
        change {
            val held = (gate.value as? Gate.SignedIn)?.saved
            store.clear()
            gate.value = Gate.SignedOut
            if (held != null) {
                scope.launch {
                    try {
                        hubs.at(held.address, held.token).logOut()
                    } catch (e: HubException) {
                        Log.i(TAG, "the hub was not told of the sign-out: ${e.kind}", e)
                    }
                }
            }
        }

    /**
     * The hub refused [token]. Ends the session, if it is still the one held.
     *
     * Only then: a refusal can arrive late, from a request sent before the person
     * signed in again, and it says nothing about the session that replaced it.
     */
    fun refused(token: SessionToken): Job =
        change {
            val current = gate.value
            if (current is Gate.SignedIn && current.saved.token == token) {
                store.clear()
                gate.value = Gate.Ended
            }
        }

    /**
     * Ask the hub whether it still takes the session, as the app starts or comes back.
     *
     * The hub's answer is kept: the role an admin has changed since, and the
     * expiry, which the hub moves on when asked from the home network. A refusal
     * ends the session. Not being able to ask changes nothing; the phone may be
     * away from home, and the screens say so themselves when they cannot reach it.
     *
     * One check at a time: asked again while one is under way, it is that one.
     * The question goes out without holding up anything else the gate is asked,
     * so a sign-out does not wait for a hub that is slow to answer.
     */
    fun check(): Job {
        checking?.takeIf { it.isActive }?.let { return it }
        return scope
            .launch {
                val held = changes.withLock { (gate.value as? Gate.SignedIn)?.saved } ?: return@launch
                val session =
                    try {
                        hubs.at(held.address, held.token).readSession()
                    } catch (e: HubException) {
                        Log.i(TAG, "the session could not be checked: ${e.kind}", e)
                        return@launch
                    }
                if (session == null) {
                    refused(held.token).join()
                    return@launch
                }
                change {
                    val current = gate.value
                    if (current is Gate.SignedIn && current.saved.token == held.token && current.saved.session != session) {
                        val updated = current.saved.copy(session = session)
                        store.save(updated)
                        gate.value = Gate.SignedIn(updated)
                    }
                }.join()
            }.also { checking = it }
    }

    /** The person has read that the session ended. */
    fun acknowledgeEnded(): Job =
        change {
            if (gate.value == Gate.Ended) gate.value = Gate.SignedOut
        }

    private fun change(block: suspend () -> Unit): Job = scope.launch { changes.withLock { block() } }

    private companion object {
        const val TAG = "SessionGate"
    }
}
