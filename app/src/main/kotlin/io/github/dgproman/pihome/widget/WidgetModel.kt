package io.github.dgproman.pihome.widget

import io.github.dgproman.pihome.house.mayChangeTheHouse
import io.github.dgproman.pihome.house.mayHaveHappened
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.quick.Outcome
import io.github.dgproman.pihome.quick.QuickActions
import io.github.dgproman.pihome.quick.Readiness
import io.github.dgproman.pihome.quick.RelayNews
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.widget.WidgetHouse.Blocked
import io.github.dgproman.pihome.widget.WidgetRelay.Press
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/** The home-screen widgets, as Android holds them. Tests stand in for Android. */
interface WidgetHost {
    /** Whether any is on a home screen. With none, there is nothing to keep up to date. */
    suspend fun placed(): Boolean

    /** Draw each of them again, from what is stored. */
    suspend fun redraw()
}

/**
 * The home-screen widget, apart from Glance, so it can be tested: what it
 * shows, and what a tap on it does.
 *
 * It reads the hub when it is placed, when its refresh is tapped, and every
 * fifteen minutes; nothing in the background asks more often, because the
 * battery would pay for it. Between those it keeps up with what the rest of
 * the app hears, as [RelayNews]: the house screen's reads, the tile's and the
 * shortcuts' switches.
 *
 * One thing at a time. A tap while the hub is being asked waits its turn,
 * because each switch on the widget says which way it is to go, and a read
 * that ended after a switch could show the relay as it was before it.
 */
class WidgetModel(
    private val quick: QuickActions,
    private val store: WidgetStore,
    private val host: WidgetHost,
    private val clock: Clock,
    /** Where news is taken in, since it comes from code that cannot wait. */
    private val scope: CoroutineScope,
) : RelayNews {
    private val lock = Mutex()

    val house: Flow<WidgetHouse> = store.house

    /** Read the whole house from the hub. */
    suspend fun refresh() = lock.withLock { read() }

    /** Switch the relay [relayId] [to] on or off, as the widget's switch for it was moved. */
    suspend fun switch(
        relayId: String,
        to: Boolean,
    ) = lock.withLock {
        val ready = prepare() ?: return@withLock
        val shown = house.first()
        val row = shown.relays.find { it.id == relayId }
        // Drawn from another hub's answer: the same id there may be a different circuit.
        if (shown.blocked != null || shown.hub != ready.saved.address.origin || row == null) return@withLock read(ready)

        change { it.withRelay(relayId) { relay -> relay.copy(on = to, press = Press.SWITCHING) } }
        when (val outcome = quick.ask(ready) { setRelay(relayId, to) }) {
            is Outcome.Done -> {
                change { it.withRelay(relayId) { widgetRelayOf(outcome.value) } }
            }

            is Outcome.Failed -> {
                val kind = outcome.kind
                val blocked = blockedBy(kind)
                when {
                    blocked != null -> change { WidgetHouse(blocked = blocked) }

                    // Gone from the hub: the list is read again, without it.
                    kind == HubErrorKind.NOT_FOUND -> read(ready)

                    kind.mayHaveHappened -> change { it.withRelay(relayId) { relay -> relay.copy(press = Press.UNSURE) } }

                    else -> change { it.withRelay(relayId) { relay -> relay.copy(on = row.on, press = Press.FAILED) } }
                }
            }
        }
    }

    /** Nobody is signed in any more: the widget shows nothing of the house. */
    fun signedOut() {
        scope.launch { lock.withLock { change { WidgetHouse(blocked = Blocked.NOT_SIGNED_IN) } } }
    }

    override fun all(
        saved: SavedSession,
        relays: List<Relay>,
    ) = heard(saved) { shown ->
        val rows = relays.map(::widgetRelayOf)
        if (rows == shown.relays && shown.failure == null) shown else shown.copy(relays = rows, asOf = clock.instant(), failure = null)
    }

    override fun one(
        saved: SavedSession,
        relay: Relay,
    ) = heard(saved) { shown ->
        if (shown.relays.none { it.id == relay.id }) shown else shown.withRelay(relay.id) { widgetRelayOf(relay) }
    }

    /**
     * Take in what the app heard from [saved]'s hub. A widget showing another
     * hub, or nothing yet, or a reason it cannot, reads the hub itself instead:
     * what was heard is the relays alone, and the session may not be one the
     * widget can act for.
     */
    private fun heard(
        saved: SavedSession,
        update: (WidgetHouse) -> WidgetHouse,
    ) {
        scope.launch {
            if (!host.placed()) return@launch
            lock.withLock {
                val shown = house.first()
                val current = shown.blocked == null && shown.hub == saved.address.origin
                if (current && saved.session.role.mayChangeTheHouse) change(update) else read()
            }
        }
    }

    private suspend fun read(known: Readiness.Ready? = null) {
        val ready = known ?: prepare() ?: return
        val origin = ready.saved.address.origin
        change { if (it.hub == origin && it.blocked == null) it.copy(reading = true) else WidgetHouse(hub = origin, reading = true) }
        val outcome =
            quick.ask(ready) {
                coroutineScope {
                    val relays = async { relays() }
                    val sensors = async { sensors() }
                    relays.await() to sensors.await()
                }
            }
        when (outcome) {
            is Outcome.Done -> {
                val now = clock.instant()
                val (relays, sensors) = outcome.value
                change {
                    WidgetHouse(
                        hub = origin,
                        relays = relays.map(::widgetRelayOf),
                        sensors = sensors.map { widgetSensorOf(it, now) },
                        asOf = now,
                    )
                }
            }

            is Outcome.Failed -> {
                val blocked = blockedBy(outcome.kind)
                change { if (blocked != null) WidgetHouse(blocked = blocked) else it.copy(reading = false, failure = outcome.kind) }
            }
        }
    }

    /** Whether the widget may ask the hub; when it may not, it says why. */
    private suspend fun prepare(): Readiness.Ready? {
        val blocked =
            when (val readiness = quick.readiness()) {
                is Readiness.Ready -> return readiness
                Readiness.NotSignedIn -> Blocked.NOT_SIGNED_IN
                Readiness.ReadOnly -> Blocked.READ_ONLY
                Readiness.NeedsPermission -> Blocked.NEEDS_PERMISSION
            }
        change { WidgetHouse(blocked = blocked) }
        return null
    }

    /**
     * What a refusal means for the widget. A refused session has already ended
     * for the whole app, and an account no longer allowed to switch is told so
     * in the app, which asks the hub what it now is.
     */
    private fun blockedBy(kind: HubErrorKind): Blocked? =
        when (kind) {
            HubErrorKind.UNAUTHORIZED -> Blocked.NOT_SIGNED_IN
            HubErrorKind.FORBIDDEN -> Blocked.READ_ONLY
            else -> null
        }

    private suspend fun change(transform: (WidgetHouse) -> WidgetHouse) {
        var before: WidgetHouse? = null
        val after =
            store.change {
                before = it
                transform(it)
            }
        if (after != before) host.redraw()
    }
}

private fun WidgetHouse.withRelay(
    id: String,
    transform: (WidgetRelay) -> WidgetRelay,
): WidgetHouse = copy(relays = relays.map { if (it.id == id) transform(it) else it })
