package io.github.dgproman.pihome.quick

import io.github.dgproman.pihome.house.mayHaveHappened
import io.github.dgproman.pihome.hub.HubErrorKind
import kotlinx.coroutines.flow.first

/** What a launcher shortcut asks for. */
sealed interface ShortcutRequest {
    data object AllOff : ShortcutRequest

    /** Switch the chosen relays off if any is on, and on if none is. */
    data object Lights : ShortcutRequest

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

    /** The All lights shortcut's relays are now [on]. */
    data class Lights(
        val on: Boolean,
    ) : ShortcutResult

    /** None of the All lights shortcut's relays is on the hub. */
    data object NoLights : ShortcutResult

    /** No usable answer came back to a write, so it may have happened. [name] is null for all off and all lights. */
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
 * A relay's shortcut switches it to the state it is not in, and All lights
 * decides from the state of several, so both read the relays first: the places
 * outside the app that ask the hub more than once for one tap, and the ones
 * that know least about what they last showed. What they read and what the
 * hub answers go to [news] on the way, which is how a
 * shortcut to a relay that has gone stops being offered, and how the widget
 * learns of the switch.
 */
class ShortcutModel(
    private val quick: QuickActions,
    private val news: RelayNews,
    private val lights: LightsChoices,
) {
    suspend fun run(request: ShortcutRequest): ShortcutResult {
        val ready = quick.readiness() as? Readiness.Ready ?: return ShortcutResult.OpenApp
        return when (request) {
            ShortcutRequest.AllOff -> allOff(ready)
            ShortcutRequest.Lights -> lights(ready)
            is ShortcutRequest.Switch -> switch(ready, request.relay)
        }
    }

    private suspend fun allOff(ready: Readiness.Ready): ShortcutResult =
        when (val outcome = quick.ask(ready) { setAllRelays(on = false) }) {
            is Outcome.Done -> {
                news.all(ready.saved, outcome.value)
                ShortcutResult.AllOff
            }

            is Outcome.Failed -> {
                failed(outcome.kind, name = null, write = true)
            }
        }

    /**
     * The chosen relays off if any of them is on, and on if all are off.
     *
     * Only the relays not already that way are written, one at a time. One that
     * fails after another was switched leaves the lights half done, which is
     * said as a switch that may have happened: the app shows which.
     */
    private suspend fun lights(ready: Readiness.Ready): ShortcutResult {
        val relays =
            when (val outcome = quick.ask(ready) { relays() }) {
                is Outcome.Done -> outcome.value
                is Outcome.Failed -> return failed(outcome.kind, name = null, write = false)
            }
        news.all(ready.saved, relays)
        val chosen = lightsAmong(relays, lights.choice.first().on(ready.saved.address))
        if (chosen.isEmpty()) return ShortcutResult.NoLights
        val on = chosen.none { it.on }
        var switched = false
        for (relay in chosen.filter { it.on != on }) {
            when (val outcome = quick.ask(ready) { setRelay(relay.id, on) }) {
                is Outcome.Done -> {
                    news.one(ready.saved, outcome.value)
                    switched = true
                }

                is Outcome.Failed -> {
                    val result = failed(outcome.kind, name = null, write = true)
                    return if (switched && result is ShortcutResult.Failed) ShortcutResult.Unsure(null) else result
                }
            }
        }
        return ShortcutResult.Lights(on)
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
        news.all(ready.saved, relays)
        val relay = relays.find { it.id == shortcut.relayId } ?: return ShortcutResult.Gone(shortcut.name)
        return when (val outcome = quick.ask(ready) { setRelay(relay.id, !relay.on) }) {
            is Outcome.Done -> {
                news.one(ready.saved, outcome.value)
                ShortcutResult.Switched(outcome.value.label, outcome.value.on)
            }

            is Outcome.Failed -> {
                failed(outcome.kind, relay.label, write = true)
            }
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
