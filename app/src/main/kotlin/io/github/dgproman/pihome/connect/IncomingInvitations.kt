package io.github.dgproman.pihome.connect

import io.github.dgproman.pihome.hub.InputProblem
import io.github.dgproman.pihome.hub.InvitationLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** A link handed to the app from outside, read as an invitation or not. */
sealed interface Incoming {
    data class Invitation(
        val link: InvitationLink,
        val receivedAt: Instant,
    ) : Incoming

    /** A link the app was opened with that is not an invitation it can use. */
    data class Broken(
        val problem: InputProblem,
    ) : Incoming
}

/**
 * The invitation most recently handed to the app, until a screen takes it.
 *
 * Held here rather than by a screen because it can arrive before there is one
 * to take it, or while the app is signed in and has to ask first. Only the
 * latest is kept: an older one was replaced by the person opening another.
 * In memory only, so an invitation is never written to disk; one lost with the
 * process is opened again from the page that offered it.
 */
class IncomingInvitations {
    private val waiting = MutableStateFlow<Incoming?>(null)

    val next: StateFlow<Incoming?> = waiting.asStateFlow()

    fun offer(incoming: Incoming) {
        waiting.value = incoming
    }

    /** Taken, or turned down: either way it is no longer waiting. Only [incoming] itself, if still there. */
    fun take(incoming: Incoming) {
        waiting.compareAndSet(incoming, null)
    }
}
