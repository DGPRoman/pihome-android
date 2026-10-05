package io.github.dgproman.pihome.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dgproman.pihome.Hubs
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.HubException
import io.github.dgproman.pihome.hub.InvitationLink
import io.github.dgproman.pihome.session.SessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Why an invitation can no longer be used from this screen. */
enum class Spent {
    /** Older than any invitation lasts, by the time it was received here. Never sent. */
    EXPIRED,

    /** The hub said no: used, withdrawn, replaced, out of time or never real. */
    REFUSED,

    /** Sent, and no usable answer came back. The hub may have taken it. */
    LOST,
}

sealed interface JoinState {
    /** Getting through to the hub. Join is offered once it is [Reach.Reached]. */
    data class Preparing(
        val reach: Reach,
    ) : JoinState

    data object Joining : JoinState

    /** Nothing more to do here but ask for a new invitation. */
    data class Over(
        val why: Spent,
    ) : JoinState
}

/**
 * Redeeming one invitation, on the person's word and only once.
 *
 * The hub spends an invitation on its first use, refused or not, so nothing
 * here sends it on its own or sends it again. Before Join is offered the hub is
 * reached and checked, so the one attempt goes to a hub that is answering.
 *
 * The join itself runs in [appScope]: once sent, its answer matters even if
 * the person has left this screen, because a session the hub opened and the
 * phone never kept would leave the invitation spent for nothing.
 */
class JoinViewModel(
    val link: InvitationLink,
    /** When the link reached this phone. No invitation lasts longer than [LIFETIME] from then. */
    private val received: Instant,
    private val hubs: Hubs,
    private val localNetwork: LocalNetwork,
    private val gate: SessionGate,
    private val appScope: CoroutineScope,
    private val clock: Clock,
) : ViewModel() {
    private val reacher = Reacher(hubs, localNetwork)
    private val current = MutableStateFlow<JoinState>(JoinState.Preparing(Reach.Checking))
    private var reaching: Job? = null

    val state: StateFlow<JoinState> = current.asStateFlow()

    init {
        if (expired()) current.value = JoinState.Over(Spent.EXPIRED) else reach()
    }

    /** Check the hub again: after a failure, or once the permission has been given. */
    fun reach() {
        if (current.value is JoinState.Joining || current.value is JoinState.Over) return
        reaching?.cancel()
        current.value = JoinState.Preparing(Reach.Checking)
        reaching = viewModelScope.launch { current.value = JoinState.Preparing(reacher.reach(link.hub)) }
    }

    /** What the person answered when Android asked about the local network. */
    fun permissionAnswered(granted: Boolean) {
        if (granted) reach() else current.value = JoinState.Preparing(Reach.PermissionRefused)
    }

    /**
     * Back on screen, perhaps from the settings, where the permission may have
     * been given. Only then is the hub checked again: a refusal stays on screen
     * until something has changed.
     */
    fun resumed() {
        if (current.value != JoinState.Preparing(Reach.PermissionRefused)) return
        viewModelScope.launch { if (!localNetwork.mustAsk(link.hub)) reach() }
    }

    fun join() {
        if (current.value != JoinState.Preparing(Reach.Reached)) return
        if (expired()) {
            current.value = JoinState.Over(Spent.EXPIRED)
            return
        }
        current.value = JoinState.Joining
        appScope.launch {
            try {
                gate.signIn(link.hub, hubs.at(link.hub, token = null).join(link.token))
            } catch (e: HubException) {
                current.value =
                    when (e.kind) {
                        // Refused before the invitation was looked at, or never sent at
                        // all. It is as it was, so the person may try again.
                        HubErrorKind.OFFLINE, HubErrorKind.INSECURE, HubErrorKind.NOT_THE_HUB,
                        HubErrorKind.RATE_LIMITED, HubErrorKind.FORBIDDEN,
                        -> JoinState.Preparing(Reach.Failed(e.kind))

                        HubErrorKind.UNAUTHORIZED -> JoinState.Over(Spent.REFUSED)

                        HubErrorKind.TIMEOUT, HubErrorKind.UNREADABLE, HubErrorKind.SERVER,
                        HubErrorKind.NOT_FOUND, HubErrorKind.CONFLICT, HubErrorKind.MALFORMED,
                        HubErrorKind.UNEXPECTED,
                        -> JoinState.Over(Spent.LOST)
                    }
            }
        }
    }

    private fun expired(): Boolean = Duration.between(received, clock.instant()) >= LIFETIME

    companion object {
        /** How long the hub keeps an invitation open after issuing it. */
        val LIFETIME: Duration = Duration.ofMinutes(15)
    }
}
