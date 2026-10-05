package io.github.dgproman.pihome.house

import io.github.dgproman.pihome.hub.AutomationRule
import io.github.dgproman.pihome.hub.Device
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.hub.Sensor
import java.time.Instant

/**
 * What the app knows of one part of the house.
 *
 * Loading, failed, empty and stale are all read from these three fields, and
 * the last answer is never thrown away for a failure: what the hub said ten
 * seconds ago, labelled as such, is worth more than a blank space.
 */
data class Section<out T>(
    /** The hub's last answer, or null before the first one arrived. */
    val data: T? = null,
    /** When [data] arrived. Every age on the screen is measured from here, not from now. */
    val asOf: Instant? = null,
    /** Why the latest read failed, or null when it did not. Shown over [data], which stays. */
    val failure: HubErrorKind? = null,
) {
    /** Nothing has arrived yet, and nothing has failed: the first read is under way. */
    val loading: Boolean get() = data == null && failure == null
}

/** One relay, and what became of the last press on it. */
data class RelayRow(
    /** As the hub last reported it, or as a press under way expects it to be. */
    val relay: Relay,
    /** A press is under way. The switch takes no other until it ends. */
    val pending: Boolean = false,
    /**
     * Why the last press failed, until the next one. [unconfirmed] says whether
     * that failure undid the press or left it standing.
     */
    val failure: HubErrorKind? = null,
    /**
     * The press may have worked: no usable answer came back. The switch stays
     * where it was pressed rather than going back, and says it is not sure,
     * until the hub is read again.
     */
    val unconfirmed: Boolean = false,
)

/** "All off", and what became of its last press. */
data class AllOff(
    val pending: Boolean = false,
    val failure: HubErrorKind? = null,
    /** As for a relay: the relays may well be off, and the hub is being asked. */
    val unconfirmed: Boolean = false,
)

/** The whole house screen, in the order the web client shows it. */
data class House(
    val relays: Section<List<RelayRow>> = Section(),
    val allOff: AllOff = AllOff(),
    val sensors: Section<List<Sensor>> = Section(),
    val devices: Section<List<Device>> = Section(),
    val rules: Section<List<AutomationRule>> = Section(),
    /** A refresh the person asked for by pulling the screen down, and only that. */
    val refreshing: Boolean = false,
)

/**
 * Whether a write that failed this way may have been carried out anyway.
 *
 * [HubErrorKind.UNREADABLE] says the hub took it. [HubErrorKind.TIMEOUT] says
 * the request went out and no answer came, which leaves both open. Either way,
 * putting the switch back would show a state nobody reported, and invite a
 * second press that the circuit does not need.
 */
internal val HubErrorKind.mayHaveHappened: Boolean
    get() = this == HubErrorKind.UNREADABLE || this == HubErrorKind.TIMEOUT

/**
 * Whether this role may switch a circuit. Only what to show: the hub decides,
 * and a wrong answer here offers a switch the hub then refuses.
 */
val Role.mayChangeTheHouse: Boolean
    get() =
        when (this) {
            Role.ADMIN, Role.OPERATOR -> true
            Role.VIEWER -> false
        }
