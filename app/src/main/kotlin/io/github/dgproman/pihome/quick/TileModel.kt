package io.github.dgproman.pihome.quick

import io.github.dgproman.pihome.house.mayHaveHappened
import io.github.dgproman.pihome.hub.HubErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the Quick Settings tile says. [name] is the relay's, when one is chosen and known. */
sealed interface TileLook {
    val name: String?

    /** Asking the hub, as the shade opens. */
    data class Reading(
        override val name: String?,
    ) : TileLook

    data class Showing(
        override val name: String,
        val on: Boolean,
    ) : TileLook

    data class Switching(
        override val name: String,
        val to: Boolean,
    ) : TileLook

    /** No usable answer came back to a switch: the relay may well be [to]. */
    data class Unsure(
        override val name: String,
        val to: Boolean,
    ) : TileLook

    /** The hub could not be read, or refused a switch. [on] is what it last said, if anything. */
    data class Failed(
        override val name: String,
        val on: Boolean?,
        val kind: HubErrorKind,
    ) : TileLook

    /** The tile cannot act, and a tap opens the app, where the reason can be dealt with. */
    data class CannotAct(
        override val name: String?,
        val reason: Reason,
    ) : TileLook

    enum class Reason { NOT_SIGNED_IN, READ_ONLY, NEEDS_PERMISSION, NOT_CHOSEN, NOT_FOUND }
}

/** What a tap on the tile came to. */
enum class Tap {
    /** The tile is dealing with it. */
    HANDLED,

    /** The tile cannot act: the app should open. */
    OPEN_APP,
}

/**
 * The Quick Settings tile, apart from Android's service, so it can be tested.
 *
 * Lives as long as the process, not as long as the service. Android lets go of
 * the service when the shade closes, and a switch already sent should still
 * finish and be shown, so its work runs in [scope], and [onChange] asks
 * Android, once the switch is over, to show the tile again.
 *
 * One thing at a time: a tap while the tile is reading or switching is
 * ignored, rather than queued up for a hub that is already slow to answer.
 */
class TileModel(
    private val quick: QuickActions,
    private val choices: TileChoices,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit = {},
) {
    private val look = MutableStateFlow<TileLook>(TileLook.Reading(null))

    val state: StateFlow<TileLook> = look.asStateFlow()

    private var work: Job? = null

    /** Read the chosen relay from the hub, as the shade opens or after a choice. */
    fun refresh(): Job = start { read() }

    /** The tile was tapped, on an unlocked phone. */
    fun tap(): Tap =
        when (val current = look.value) {
            is TileLook.CannotAct -> {
                Tap.OPEN_APP
            }

            is TileLook.Showing -> {
                start { switch(!current.on) }
                Tap.HANDLED
            }

            // Pressed again after a refusal: try again. After a failed read or a
            // lost answer the state is not known, so it is read rather than guessed.
            is TileLook.Failed -> {
                val on = current.on
                start { if (on != null) switch(!on) else read() }
                Tap.HANDLED
            }

            is TileLook.Unsure -> {
                start { read() }
                Tap.HANDLED
            }

            is TileLook.Reading, is TileLook.Switching -> {
                Tap.HANDLED
            }
        }

    private fun start(block: suspend () -> Unit): Job {
        work?.takeIf { it.isActive }?.let { return it }
        return scope.launch { block() }.also { work = it }
    }

    private suspend fun read() {
        val (ready, choice) = prepare() ?: return
        show(TileLook.Reading(choice.relayName))
        show(
            when (val outcome = quick.ask(ready) { relays() }) {
                is Outcome.Done -> {
                    val relay = outcome.value.find { it.id == choice.relayId }
                    if (relay == null) {
                        TileLook.CannotAct(choice.relayName, TileLook.Reason.NOT_FOUND)
                    } else {
                        // Renamed on the hub since it was chosen: the tile says what the hub says.
                        if (relay.label != choice.relayName) choices.choose(choice.copy(relayName = relay.label))
                        TileLook.Showing(relay.label, relay.on)
                    }
                }

                is Outcome.Failed -> {
                    failed(choice.relayName, on = null, kind = outcome.kind)
                }
            },
        )
    }

    private suspend fun switch(to: Boolean) {
        val (ready, choice) = prepare() ?: return
        show(TileLook.Switching(choice.relayName, to))
        show(
            when (val outcome = quick.ask(ready) { setRelay(choice.relayId, to) }) {
                is Outcome.Done -> TileLook.Showing(outcome.value.label, outcome.value.on)
                is Outcome.Failed -> failed(choice.relayName, on = !to, kind = outcome.kind, to = to)
            },
        )
        // The shade may have closed while the hub answered. Only here, and not after a
        // read: Android shows the tile again by reading it, which would read again.
        onChange()
    }

    private fun failed(
        name: String,
        on: Boolean?,
        kind: HubErrorKind,
        to: Boolean? = null,
    ): TileLook =
        when {
            kind == HubErrorKind.UNAUTHORIZED -> TileLook.CannotAct(name, TileLook.Reason.NOT_SIGNED_IN)
            kind == HubErrorKind.FORBIDDEN -> TileLook.CannotAct(name, TileLook.Reason.READ_ONLY)
            kind == HubErrorKind.NOT_FOUND -> TileLook.CannotAct(name, TileLook.Reason.NOT_FOUND)
            to != null && kind.mayHaveHappened -> TileLook.Unsure(name, to)
            else -> TileLook.Failed(name, on, kind)
        }

    /** Whether the tile may act, and on which relay; when it may not, the tile says why. */
    private suspend fun prepare(): Pair<Readiness.Ready, TileChoice>? {
        val readiness = quick.readiness()
        val chosen = choices.choice.first()
        val ready =
            when (readiness) {
                Readiness.NotSignedIn -> return cannot(chosen?.relayName, TileLook.Reason.NOT_SIGNED_IN)
                Readiness.ReadOnly -> return cannot(chosen?.relayName, TileLook.Reason.READ_ONLY)
                Readiness.NeedsPermission -> return cannot(chosen?.relayName, TileLook.Reason.NEEDS_PERMISSION)
                is Readiness.Ready -> readiness
            }
        val choice = chosen.on(ready.saved.address) ?: return cannot(null, TileLook.Reason.NOT_CHOSEN)
        return ready to choice
    }

    private fun cannot(
        name: String?,
        reason: TileLook.Reason,
    ): Nothing? {
        show(TileLook.CannotAct(name, reason))
        return null
    }

    private fun show(next: TileLook) {
        look.value = next
    }
}
