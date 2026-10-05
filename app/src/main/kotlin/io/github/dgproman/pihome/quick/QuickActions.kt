package io.github.dgproman.pihome.quick

import android.util.Log
import io.github.dgproman.pihome.Hubs
import io.github.dgproman.pihome.connect.LocalNetwork
import io.github.dgproman.pihome.house.mayChangeTheHouse
import io.github.dgproman.pihome.hub.Hub
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.HubException
import io.github.dgproman.pihome.session.Gate
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.session.SessionGate
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.Duration

/** Whether a control outside the app (a tile, a widget, a shortcut) can act on the house now. */
sealed interface Readiness {
    /** No session on this phone: somebody has to connect first, in the app. */
    data object NotSignedIn : Readiness

    /** A viewer's session: it may read the house and change nothing. */
    data object ReadOnly : Readiness

    /** Android 17 has not been given leave to reach the network the hub is on. */
    data object NeedsPermission : Readiness

    data class Ready(
        val saved: SavedSession,
    ) : Readiness
}

/** What came of asking the hub something from outside the app. */
sealed interface Outcome<out T> {
    data class Done<T>(
        val value: T,
    ) : Outcome<T>

    data class Failed(
        val kind: HubErrorKind,
    ) : Outcome<Nothing>
}

/**
 * What every control outside the app shares: whether it may act, and asking
 * the hub with a deadline.
 *
 * Outside the app nobody is looking at a spinner, so each question gets
 * [DEADLINE] and no more, and no question is ever asked again on its own. Each
 * runs on the phone's one session, and a refusal of it ends that session for
 * the whole app, as a refusal does on any screen: the hub counts every one
 * against this phone's address.
 */
class QuickActions(
    private val gate: SessionGate,
    private val localNetwork: LocalNetwork,
    private val hubs: Hubs,
) {
    /** Where this phone stands, once the saved session has been read. */
    suspend fun readiness(): Readiness {
        val saved = (gate.state.first { it != Gate.Loading } as? Gate.SignedIn)?.saved ?: return Readiness.NotSignedIn
        return when {
            !saved.session.role.mayChangeTheHouse -> Readiness.ReadOnly
            localNetwork.mustAsk(saved.address) -> Readiness.NeedsPermission
            else -> Readiness.Ready(saved)
        }
    }

    /**
     * Ask the hub [call] as [ready]'s session, within [DEADLINE].
     *
     * A write that runs out of time may still have reached the hub, so it
     * comes back as [HubErrorKind.TIMEOUT], which says just that.
     */
    suspend fun <T> ask(
        ready: Readiness.Ready,
        call: suspend Hub.() -> T,
    ): Outcome<T> {
        val saved = ready.saved
        return try {
            Outcome.Done(withTimeout(DEADLINE.toMillis()) { hubs.at(saved.address, saved.token).call() })
        } catch (_: TimeoutCancellationException) {
            Outcome.Failed(HubErrorKind.TIMEOUT)
        } catch (e: HubException) {
            Log.i(TAG, "a quick action failed: ${e.kind}", e)
            if (e.kind == HubErrorKind.UNAUTHORIZED) gate.refused(saved.token).join()
            Outcome.Failed(e.kind)
        }
    }

    companion object {
        /** Long enough for a hub on the home network, short enough that a tap is answered while it is remembered. */
        val DEADLINE: Duration = Duration.ofSeconds(6)

        private const val TAG = "QuickActions"
    }
}
