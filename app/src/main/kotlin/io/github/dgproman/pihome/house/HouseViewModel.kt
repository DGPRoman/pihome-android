package io.github.dgproman.pihome.house

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dgproman.pihome.hub.Hub
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.HubException
import io.github.dgproman.pihome.hub.Relay
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration

/**
 * The house as the hub reports it, read every [POLL_EVERY] while the screen is
 * in view, and the relays switched from it, and their automation turned off
 * and on.
 *
 * Everything runs on the main thread, so the bookkeeping below needs no locks:
 * only one thing changes it at a time.
 *
 * **A press is shown at once.** The switch moves before the hub answers, and
 * moves back if the hub refuses: but only if it still shows what this press put
 * there, since anything else is newer than this press's memory of the past.
 * When the answer is lost rather than a refusal, it stays where it was pressed
 * and says it is not sure (see [mayHaveHappened]).
 *
 * **A poll never undoes a press.** A read of the relays that overlaps a press
 * may carry the state from before it, whichever of the two the hub served
 * first. So no read starts while a press is under way, a press cancels any
 * read in flight, and a read's answer is dropped when a press began after it
 * or has not finished. Every press ends with a read of its own, which is what
 * settles it.
 *
 * **The first refusal of the session stops everything.** The hub counts each
 * one against this phone's address, and locks the address out after a few. So
 * the relays are read first, alone, and the rest only once they answered: a
 * session that has ended costs one refusal, not four.
 */
class HouseViewModel(
    private val hub: Hub,
    private val clock: Clock,
    /** The hub refused the session. The gate ends it, and with it this screen. */
    private val onRefused: () -> Unit,
    /** The hub said this account may not do something. Its role may have changed. */
    private val onForbidden: () -> Unit,
    /** The hub reported every relay, as it is now. */
    private val onRelays: (List<Relay>) -> Unit = {},
) : ViewModel() {
    private val house = MutableStateFlow(House())

    val state: StateFlow<House> = house.asStateFlow()

    /** Set by the first refusal. Nothing more is sent. */
    private var stopped = false

    private var round: Job? = null
    private var relayRead: Job? = null

    /** Presses begun so far, so that a read can tell whether one began after it. */
    private var pressesBegun = 0L

    /** Presses under way: a switch, an automation, or all off. */
    private var pressesUnderWay = 0

    /** Read everything, then again every [POLL_EVERY], until cancelled: for as long as the screen is in view. */
    suspend fun poll() {
        while (!stopped) {
            refresh().join()
            delay(POLL_EVERY.toMillis())
        }
    }

    /**
     * Read everything once. Asked again while a read is under way, it is that one,
     * so a pull, a retry and a poll arriving together cost one round of requests.
     */
    fun refresh(): Job {
        round?.takeIf { it.isActive }?.let { return it }
        return viewModelScope
            .launch {
                if (stopped) return@launch
                readRelays().join()
                if (stopped) return@launch
                coroutineScope {
                    launch { read({ hub.sensors() }) { section -> copy(sensors = section(sensors)) } }
                    launch { read({ hub.devices() }) { section -> copy(devices = section(devices)) } }
                    launch { read({ hub.rules() }) { section -> copy(rules = section(rules)) } }
                }
            }.also { round = it }
    }

    /** The person pulled the screen down: read now, and show that it is happening. */
    fun pull() {
        viewModelScope.launch {
            house.update { it.copy(refreshing = true) }
            try {
                refresh().join()
            } finally {
                house.update { it.copy(refreshing = false) }
            }
        }
    }

    /**
     * Drive one relay to [on]. Named, not toggled, so a press that reaches the hub
     * twice leaves the circuit where it was asked to be.
     */
    fun setRelay(
        id: String,
        on: Boolean,
    ) {
        val row =
            house.value.relays.data
                ?.find { it.relay.id == id } ?: return
        if (stopped || row.pending) return

        begin()
        updateRelays { rows -> rows.map { if (it.relay.id == id) RelayRow(it.relay.copy(on = on), pending = true) else it } }
        val before = row.relay.on

        viewModelScope.launch {
            try {
                val reply = hub.setRelay(id, on)
                updateRelays { rows -> rows.map { if (it.relay.id == id) RelayRow(reply) else it } }
            } catch (e: HubException) {
                Log.i(TAG, "a relay was not switched: ${e.kind}", e)
                updateRelays { rows ->
                    rows.map {
                        when {
                            it.relay.id != id -> it

                            e.kind.mayHaveHappened -> it.copy(pending = false, failure = e.kind, unconfirmed = true)

                            // Back only if the switch still shows this press.
                            it.relay.on == on -> it.copy(relay = it.relay.copy(on = before), pending = false, failure = e.kind)

                            else -> it.copy(pending = false, failure = e.kind)
                        }
                    }
                }
                failed(e.kind)
            } finally {
                pressesUnderWay--
            }
            readRelays()
        }
    }

    /**
     * Let the hub's automation switch one relay, or stop it from doing so.
     *
     * Turning it off switches the relay off on the hub as well, and cancels any
     * rule's timer on it, so the row shows all of that at once; turning it on
     * switches nothing. Otherwise it is a press like any other: shown at once,
     * put back on a refusal, left in doubt when the answer is lost, and read back.
     * A relay whose hub does not say whether it is automatic has nothing to press.
     */
    fun setAutomatic(
        id: String,
        automatic: Boolean,
    ) {
        val row =
            house.value.relays.data
                ?.find { it.relay.id == id } ?: return
        if (stopped || row.pending || row.relay.automatic == null) return

        begin()
        val before = row.relay
        val expected =
            if (automatic) {
                before.copy(automatic = true)
            } else {
                before.copy(automatic = false, on = false, holdExpiresAt = null)
            }
        updateRelays { rows ->
            rows.map { if (it.relay.id == id) RelayRow(expected, pending = true, pressed = Control.AUTOMATION) else it }
        }

        viewModelScope.launch {
            try {
                val reply = hub.setAutomatic(id, automatic)
                updateRelays { rows -> rows.map { if (it.relay.id == id) RelayRow(reply) else it } }
            } catch (e: HubException) {
                Log.i(TAG, "a relay's automation was not changed: ${e.kind}", e)
                updateRelays { rows ->
                    rows.map {
                        when {
                            it.relay.id != id -> it

                            e.kind.mayHaveHappened -> it.copy(pending = false, failure = e.kind, unconfirmed = true)

                            // Back only if the row still shows this press.
                            it.relay.automatic == automatic -> it.copy(relay = before, pending = false, failure = e.kind)

                            else -> it.copy(pending = false, failure = e.kind)
                        }
                    }
                }
                failed(e.kind)
            } finally {
                pressesUnderWay--
            }
            readRelays()
        }
    }

    /**
     * Switch every relay off: the control for leaving the house. There is no
     * "all on", because no moment calls for closing every circuit at once.
     */
    fun allOff() {
        val rows = house.value.relays.data ?: return
        if (stopped || house.value.allOff.pending || rows.none { it.relay.on }) return

        begin()
        val before = rows.associate { it.relay.id to it.relay.on }
        house.update { it.copy(allOff = AllOff(pending = true)) }
        updateRelays { list -> list.map { it.copy(relay = it.relay.copy(on = false)) } }

        viewModelScope.launch {
            try {
                val reply = hub.setAllRelays(on = false)
                onRelays(reply)
                house.update { it.copy(allOff = AllOff()) }
                updateRelays { list -> fromHub(reply, list) }
            } catch (e: HubException) {
                Log.i(TAG, "the relays were not all switched off: ${e.kind}", e)
                val unconfirmed = e.kind.mayHaveHappened
                house.update { it.copy(allOff = AllOff(failure = e.kind, unconfirmed = unconfirmed)) }
                if (!unconfirmed) {
                    // Each back, and only where it still shows what this press put there.
                    // Which of them the hub switched before failing is for the hub to say.
                    updateRelays { list ->
                        list.map { row ->
                            val was = before[row.relay.id]
                            if (was != null && !row.relay.on && !row.pending) row.copy(relay = row.relay.copy(on = was)) else row
                        }
                    }
                }
                failed(e.kind)
            } finally {
                pressesUnderWay--
            }
            readRelays()
        }
    }

    private fun begin() {
        pressesBegun++
        pressesUnderWay++
        relayRead?.cancel()
    }

    private fun readRelays(): Job {
        val begunBefore = pressesBegun
        return viewModelScope
            .launch {
                // The press under way ends with a read of its own.
                if (stopped || pressesUnderWay > 0) return@launch
                val answer =
                    try {
                        Result.success(hub.relays())
                    } catch (e: HubException) {
                        Result.failure(e)
                    }
                // Started before a press, or ended during one: it may predate the press.
                if (pressesBegun != begunBefore || pressesUnderWay > 0) return@launch
                answer.fold(
                    onSuccess = { list ->
                        onRelays(list)
                        house.update { current ->
                            current.copy(
                                relays = Section(fromHub(list, current.relays.data.orEmpty()), clock.instant()),
                                // Whatever all off left in doubt, the hub has now answered.
                                allOff = if (current.allOff.unconfirmed) AllOff() else current.allOff,
                            )
                        }
                    },
                    onFailure = { e ->
                        val kind = (e as HubException).kind
                        house.update { it.copy(relays = it.relays.copy(failure = kind)) }
                        failed(kind)
                    },
                )
            }.also { relayRead = it }
    }

    /**
     * The relays as the hub reported them, keeping what each row has to say about
     * its last press. Doubt is settled by the hub's answer; a refusal is kept, so
     * the reason stays beside what was pressed until the relay is pressed again.
     * A row with a press of its own under way is left to that press.
     */
    private fun fromHub(
        relays: List<Relay>,
        rows: List<RelayRow>,
    ): List<RelayRow> {
        val previous = rows.associateBy { it.relay.id }
        return relays.map { relay ->
            val row = previous[relay.id]
            when {
                row?.pending == true -> row
                row?.unconfirmed == true -> RelayRow(relay)
                else -> row?.copy(relay = relay) ?: RelayRow(relay)
            }
        }
    }

    private suspend fun <T> read(
        fetch: suspend () -> T,
        put: House.((Section<T>) -> Section<T>) -> House,
    ) {
        if (stopped) return
        try {
            val data = fetch()
            house.update { it.put { Section(data, clock.instant()) } }
        } catch (e: HubException) {
            house.update { it.put { section -> section.copy(failure = e.kind) } }
            failed(e.kind)
        }
    }

    private fun updateRelays(change: (List<RelayRow>) -> List<RelayRow>) {
        house.update { current ->
            val rows = current.relays.data ?: return@update current
            current.copy(relays = current.relays.copy(data = change(rows)))
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
        /** As often as the web client asks. The hub pushes nothing, so this is how news arrives. */
        val POLL_EVERY: Duration = Duration.ofSeconds(10)

        private const val TAG = "House"
    }
}
