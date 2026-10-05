package io.github.dgproman.pihome.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dgproman.pihome.Hubs
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.HubException
import io.github.dgproman.pihome.hub.InputProblem
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.session.SessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What stopped the last attempt, for the form to show where it belongs. */
sealed interface LogInProblem {
    data class Address(
        val problem: InputProblem,
    ) : LogInProblem

    data object NoUsername : LogInProblem

    data object NoPassword : LogInProblem

    /** The hub, or getting to it. [HubErrorKind.UNAUTHORIZED] is a wrong name or password. */
    data class Hub(
        val kind: HubErrorKind,
    ) : LogInProblem
}

sealed interface LogInState {
    /** The form, and what went wrong last, if anything did. */
    data class Editing(
        val problem: LogInProblem? = null,
    ) : LogInState

    /** Getting through to the hub before the password is sent. */
    data class Reaching(
        val reach: Reach,
    ) : LogInState

    /** The hub is checking the password, which on a small board takes a noticeable moment. */
    data object Checking : LogInState
}

/**
 * Logging in with a name and a password, as an admin does.
 *
 * The password goes only to an address that has answered as a hub, and only
 * once per press. What is typed lives here, for as long as the screen does: it
 * survives the phone being turned, and is gone when the screen is closed.
 */
class LogInViewModel(
    private val hubs: Hubs,
    private val localNetwork: LocalNetwork,
    private val gate: SessionGate,
    /** Where the login runs, so that a session the hub opens is kept even if the screen has gone. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val reacher = Reacher(hubs, localNetwork)
    private val current = MutableStateFlow<LogInState>(LogInState.Editing())

    val state: StateFlow<LogInState> = current.asStateFlow()

    var address by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    /** The address that was checked last, for the permission's answer to carry on with. */
    private var pending: HubAddress? = null

    fun logIn() {
        if (current.value !is LogInState.Editing) return
        val target =
            when (val parsed = HubAddress.parse(address)) {
                is Parsed.Invalid -> return stop(LogInProblem.Address(parsed.problem))
                is Parsed.Valid -> parsed.value
            }
        if (username.isBlank()) return stop(LogInProblem.NoUsername)
        if (password.isEmpty()) return stop(LogInProblem.NoPassword)
        pending = target
        reachThenLogIn(target)
    }

    /** What the person answered when Android asked about the local network. */
    fun permissionAnswered(granted: Boolean) {
        val target = pending ?: return
        if (granted) reachThenLogIn(target) else current.value = LogInState.Reaching(Reach.PermissionRefused)
    }

    /** Back on screen, perhaps from the settings: carries on only if the permission is now given. */
    fun resumed() {
        val target = pending ?: return
        if (current.value != LogInState.Reaching(Reach.PermissionRefused)) return
        viewModelScope.launch { if (!localNetwork.mustAsk(target)) reachThenLogIn(target) }
    }

    /** Leave the permission's question unanswered, and go back to the form. */
    fun dismiss() {
        if (current.value is LogInState.Reaching) current.value = LogInState.Editing()
    }

    private fun reachThenLogIn(target: HubAddress) {
        current.value = LogInState.Reaching(Reach.Checking)
        viewModelScope.launch {
            when (val reach = reacher.reach(target)) {
                Reach.Reached -> send(target)
                is Reach.Failed -> stop(LogInProblem.Hub(reach.kind))
                else -> current.value = LogInState.Reaching(reach)
            }
        }
    }

    private fun send(target: HubAddress) {
        current.value = LogInState.Checking
        val name = username.trim()
        val secret = password
        appScope.launch {
            try {
                gate.signIn(target, hubs.at(target, token = null).logIn(name, secret))
            } catch (e: HubException) {
                stop(LogInProblem.Hub(e.kind))
            }
        }
    }

    private fun stop(problem: LogInProblem) {
        current.value = LogInState.Editing(problem)
    }
}
