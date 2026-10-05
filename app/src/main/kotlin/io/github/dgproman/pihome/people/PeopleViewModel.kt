package io.github.dgproman.pihome.people

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Account
import io.github.dgproman.pihome.hub.AccountChange
import io.github.dgproman.pihome.hub.Hub
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.HubException
import io.github.dgproman.pihome.hub.ManagedRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration

/**
 * The hub's accounts, read every [POLL_EVERY] while the screen is in view, and
 * an admin's changes to them.
 *
 * Everything runs on the main thread, so the bookkeeping below needs no locks.
 *
 * **Reading is how news arrives.** The hub says nothing when somebody uses an
 * invitation; the account just stops listing it. So the list is read while the
 * screen is open, and an invitation on screen is marked used when a read sent
 * after it was issued no longer has it (see [wentAway]).
 *
 * **A write is never undone by a read.** A read that overlaps a write may carry
 * the list from before it. So no read starts while a write is under way, a
 * write cancels any read in flight, and a read's answer is dropped when a write
 * began after it. Every write ends with a read, which is what settles it.
 *
 * **The first refusal of the session stops everything**, as on the house
 * screen: the hub counts each one against this phone's address.
 */
class PeopleViewModel(
    private val hub: Hub,
    private val clock: Clock,
    /** The hub refused the session. The gate ends it, and with it this screen. */
    private val onRefused: () -> Unit,
    /** The hub said this account may not do something. Its role may have changed. */
    private val onForbidden: () -> Unit,
) : ViewModel() {
    private val people = MutableStateFlow(People())

    val state: StateFlow<People> = people.asStateFlow()

    private var stopped = false
    private var round: Job? = null
    private var writesBegun = 0L
    private var writesUnderWay = 0

    /** Read the accounts, then again every [POLL_EVERY], until cancelled. */
    suspend fun poll() {
        while (!stopped) {
            refresh().join()
            delay(POLL_EVERY.toMillis())
        }
    }

    /** Read the accounts once. Asked again while a read is under way, it is that one. */
    fun refresh(): Job {
        round?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch { read() }.also { round = it }
    }

    /** The person pulled the screen down: read now, and show that it is happening. */
    fun pull() {
        viewModelScope.launch {
            people.update { it.copy(refreshing = true) }
            try {
                refresh().join()
            } finally {
                people.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun read() {
        // The write under way ends with a read of its own.
        if (stopped || writesUnderWay > 0) return
        val begunBefore = writesBegun
        val sentAt = clock.instant()
        val answer =
            try {
                Result.success(hub.accounts())
            } catch (e: HubException) {
                Result.failure(e)
            }
        if (writesBegun != begunBefore || writesUnderWay > 0) return
        answer.fold(
            onSuccess = { list ->
                val arrivedAt = clock.instant()
                people.update { current ->
                    val shown = current.shown
                    current.copy(
                        accounts = Section(list, arrivedAt),
                        shown =
                            if (shown != null && !shown.gone && wentAway(shown, list, sentAt, arrivedAt)) {
                                shown.copy(gone = true)
                            } else {
                                shown
                            },
                    )
                }
            },
            onFailure = { e ->
                val kind = (e as HubException).kind
                people.update { it.copy(accounts = it.accounts.copy(failure = kind)) }
                failed(kind)
            },
        )
    }

    /** Make an operator a viewer, or a viewer an operator. */
    fun setRole(
        username: String,
        role: ManagedRole,
    ) = change(username, Action.ROLE, AccountChange(role = role))

    /** Block an account, or let it back in. Blocking keeps its sessions from working until it is enabled again. */
    fun setDisabled(
        username: String,
        disabled: Boolean,
    ) = change(username, if (disabled) Action.DISABLE else Action.ENABLE, AccountChange(disabled = disabled))

    private fun change(
        username: String,
        action: Action,
        change: AccountChange,
    ) = write(username, action) {
        val updated = hub.changeAccount(username, change)
        // The hub's own answer, shown at once rather than after the next read.
        updateAccounts { list -> list.map { if (it.username == updated.username) updated else it } }
    }

    /** Delete an account, which logs out every device signed in as it. */
    fun delete(username: String) =
        write(username, Action.DELETE) {
            hub.deleteAccount(username)
            updateAccounts { list -> list.filterNot { it.username == username } }
        }

    /** Withdraw an account's outstanding invitation. */
    fun withdraw(username: String) =
        write(username, Action.WITHDRAW) {
            hub.revokeInvitation(username)
            updateAccounts { list -> list.map { if (it.username == username) it.copy(invitationExpiresAt = null) else it } }
        }

    private fun write(
        username: String,
        action: Action,
        call: suspend () -> Unit,
    ) {
        if (stopped || username in people.value.busy) return
        begin()
        people.update { it.copy(busy = it.busy + username, failures = it.failures - username) }
        viewModelScope.launch {
            try {
                call()
            } catch (e: HubException) {
                Log.i(TAG, "an account was not changed: $action ${e.kind}", e)
                people.update { it.copy(failures = it.failures + (username to Failure(action, e.kind))) }
                failed(e.kind)
            } finally {
                writesUnderWay--
                people.update { it.copy(busy = it.busy - username) }
            }
            refresh()
        }
    }

    /**
     * Issue an invitation to [username] and show it, replacing whatever invitation
     * was on screen, as the hub replaces the account's token.
     */
    fun invite(username: String) {
        if (stopped || people.value.inviting != null) return
        begin()
        people.update { it.copy(inviting = username, failures = it.failures - username) }
        viewModelScope.launch {
            try {
                val invitation = hub.issueInvitation(username)
                people.update { current ->
                    current.copy(
                        shown = Shown(username, invitation, issuedAt = clock.instant()),
                        failures = current.failures - username,
                    )
                }
                updateAccounts { list ->
                    list.map { if (it.username == username) it.copy(invitationExpiresAt = invitation.expiresAt) else it }
                }
            } catch (e: HubException) {
                Log.i(TAG, "no invitation was issued: ${e.kind}", e)
                people.update { it.copy(failures = it.failures + (username to Failure(Action.INVITE, e.kind))) }
                failed(e.kind)
            } finally {
                writesUnderWay--
                people.update { it.copy(inviting = null) }
            }
            refresh()
        }
    }

    /** Withdraw the invitation on screen, and close it once the hub has. */
    fun withdrawShown() {
        val shown = people.value.shown ?: return
        if (stopped || shown.withdrawing) return
        begin()
        updateShown(shown) { it.copy(withdrawing = true, withdrawFailure = null) }
        viewModelScope.launch {
            try {
                hub.revokeInvitation(shown.username)
                people.update { if (it.shown?.invitation == shown.invitation) it.copy(shown = null) else it }
                updateAccounts { list ->
                    list.map { if (it.username == shown.username) it.copy(invitationExpiresAt = null) else it }
                }
            } catch (e: HubException) {
                Log.i(TAG, "the invitation was not withdrawn: ${e.kind}", e)
                updateShown(shown) { it.copy(withdrawFailure = e.kind) }
                failed(e.kind)
            } finally {
                writesUnderWay--
                updateShown(shown) { it.copy(withdrawing = false) }
            }
            refresh()
        }
    }

    /** Let go of the invitation on screen. It still works, until it is used or runs out. */
    fun close() {
        people.update { it.copy(shown = null) }
    }

    /**
     * Make an account for [username] with [role] and no password, and go
     * straight on to its invitation, which is the only way into it.
     *
     * A name the hub's rule refuses is not sent; the form says why before this
     * is called.
     */
    fun add(
        username: String,
        role: ManagedRole,
    ) {
        if (stopped || people.value.adding.pending || !isUsername(username)) return
        begin()
        people.update { it.copy(adding = it.adding.copy(pending = true, failure = null)) }
        viewModelScope.launch {
            val created =
                try {
                    hub.createAccount(username, role).also { account ->
                        updateAccounts { list -> list + account }
                        people.update { it.copy(adding = Adding(added = it.adding.added + 1)) }
                    }
                } catch (e: HubException) {
                    Log.i(TAG, "no account was made: ${e.kind}", e)
                    people.update { it.copy(adding = it.adding.copy(pending = false, failure = e.kind)) }
                    failed(e.kind)
                    null
                } finally {
                    writesUnderWay--
                }
            if (created != null) invite(created.username) else refresh()
        }
    }

    private fun begin() {
        writesBegun++
        writesUnderWay++
        round?.cancel()
    }

    private fun updateAccounts(change: (List<Account>) -> List<Account>) {
        people.update { current ->
            val list = current.accounts.data ?: return@update current
            current.copy(accounts = current.accounts.copy(data = change(list)))
        }
    }

    /** Change the invitation on screen, if it is still [shown] and not one issued since. */
    private fun updateShown(
        shown: Shown,
        change: (Shown) -> Shown,
    ) {
        people.update { current ->
            val now = current.shown
            if (now?.invitation == shown.invitation) current.copy(shown = change(now)) else current
        }
    }

    private fun failed(kind: HubErrorKind) {
        when (kind) {
            HubErrorKind.UNAUTHORIZED -> {
                if (!stopped) {
                    stopped = true
                    onRefused()
                }
            }

            HubErrorKind.FORBIDDEN -> {
                onForbidden()
            }

            else -> {}
        }
    }

    companion object {
        /** As often as the web client asks, and as often as the house screen. */
        val POLL_EVERY: Duration = Duration.ofSeconds(10)

        private const val TAG = "People"
    }
}
