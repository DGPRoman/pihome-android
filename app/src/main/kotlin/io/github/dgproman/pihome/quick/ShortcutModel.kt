package io.github.dgproman.pihome.quick

import io.github.dgproman.pihome.house.mayHaveHappened
import io.github.dgproman.pihome.hub.HubErrorKind

/** What a launcher shortcut asks for. */
sealed interface ShortcutRequest {
    data object AllOff : ShortcutRequest

    /** Switch [relay] to whichever state it is not in. */
    data class Switch(
        val relay: RelayShortcut,
    ) : ShortcutRequest
}

/** What came of a shortcut, to be shown before it closes. */
sealed interface ShortcutResult {
    data class Switched(
        val name: String,
        val on: Boolean,
    ) : ShortcutResult

    data object AllOff : ShortcutResult

    /** No usable answer came back to a write, so it may have happened. [name] is null for all off. */
    data class Unsure(
        val name: String?,
    ) : ShortcutResult

    /** The relay is not on the hub, or not on the hub this phone is now connected to. */
    data class Gone(
        val name: String,
    ) : ShortcutResult

    data class Failed(
        val kind: HubErrorKind,
    ) : ShortcutResult

    /** The shortcut cannot act: the app opens, where the reason can be dealt with. */
    data object OpenApp : ShortcutResult
}

/**
 * The launcher's shortcuts, apart from the activity that shows them, so they
 * can be tested.
 *
 * A relay's shortcut switches it to the state it is not in, so it reads the
 * relays first: the one place outside the app that asks the hub twice for one
 * tap, and the one that knows least about what it last showed. The list it
 * reads is handed to [shortcuts] on the way, which is how a shortcut to a
 * relay that has gone stops being offered.
 */
class ShortcutModel(
    private val quick: QuickActions,
    private val shortcuts: RelayShortcuts,
) {
    suspend fun run(request: ShortcutRequest): ShortcutResult {
        val ready = quick.readiness() as? Readiness.Ready ?: return ShortcutResult.OpenApp
        return when (request) {
            ShortcutRequest.AllOff -> allOff(ready)
            is ShortcutRequest.Switch -> switch(ready, request.relay)
        }
    }

    private suspend fun allOff(ready: Readiness.Ready): ShortcutResult =
        when (val outcome = quick.ask(ready) { setAllRelays(on = false) }) {
            is Outcome.Done -> {
                shortcuts.follow(ready.saved, outcome.value)
                ShortcutResult.AllOff
            }

            is Outcome.Failed -> {
                failed(outcome.kind, name = null, write = true)
            }
        }

    private suspend fun switch(
        ready: Readiness.Ready,
        shortcut: RelayShortcut,
    ): ShortcutResult {
        if (shortcut.hub != ready.saved.address.origin) return ShortcutResult.Gone(shortcut.name)
        val relays =
            when (val outcome = quick.ask(ready) { relays() }) {
                is Outcome.Done -> outcome.value
                is Outcome.Failed -> return failed(outcome.kind, shortcut.name, write = false)
            }
        shortcuts.follow(ready.saved, relays)
        val relay = relays.find { it.id == shortcut.relayId } ?: return ShortcutResult.Gone(shortcut.name)
        return when (val outcome = quick.ask(ready) { setRelay(relay.id, !relay.on) }) {
            is Outcome.Done -> ShortcutResult.Switched(outcome.value.label, outcome.value.on)
            is Outcome.Failed -> failed(outcome.kind, relay.label, write = true)
        }
    }

    private fun failed(
        kind: HubErrorKind,
        name: String?,
        write: Boolean,
    ): ShortcutResult =
        when {
            // The session ended, or this account may no longer switch anything: the
            // app says which, and asks the hub what this account is now.
            kind == HubErrorKind.UNAUTHORIZED || kind == HubErrorKind.FORBIDDEN -> ShortcutResult.OpenApp

            kind == HubErrorKind.NOT_FOUND && name != null -> ShortcutResult.Gone(name)

            write && kind.mayHaveHappened -> ShortcutResult.Unsure(name)

            else -> ShortcutResult.Failed(kind)
        }
}
