@file:UseSerializers(InstantSerializer::class)

package io.github.dgproman.pihome.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A relay and whether its circuit is on. Mirrors `RelayState` in the hub's v1 schema. */
@Serializable
data class Relay(
    val id: String,
    val label: String,
    val on: Boolean,
    /**
     * When an automation rule is due to put this relay back, or null when nothing
     * is. Lets the app show that a state has a timer against it rather than
     * presenting it as settled. A write to the relay cancels the timer, so the
     * reply to one always has null here.
     */
    @SerialName("hold_expires_at") val holdExpiresAt: Instant? = null,
    /**
     * Whether the hub's automation may switch this relay. False once a person has
     * turned it off, until somebody turns it back on: no rule touches the relay in
     * between, though a person still can. Null from a hub too old to send it,
     * which has no such switch to offer.
     */
    val automatic: Boolean? = null,
)

/**
 * One quantity a sensor may report, in every state it can be in.
 *
 * Three states rather than a nullable value, because missing, never reported
 * and a reading are different things to show: a hub that does not report
 * humidity, a sensor that has not sent any yet, and a sensor that sent 0 %.
 */
sealed interface Reading<out T> {
    /** The hub did not send the field at all: it does not report this quantity. */
    data object Unsupported : Reading<Nothing>

    /** The hub sent null: the sensor is set up for this and has never reported it. */
    data object Never : Reading<Nothing>

    /** A value, and when it was taken, if the hub said. */
    data class Value<out T>(
        val value: T,
        val at: Instant?,
    ) : Reading<T>
}

/**
 * A sensor and its latest readings. Mirrors `DeviceSnapshot` in the hub's v1 schema.
 *
 * Built by hand from the JSON object rather than generated, because whether a
 * field is there at all is part of what it says: see [Reading].
 */
data class Sensor(
    val id: String,
    val label: String,
    /**
     * Nothing at all has arrived within the sensor's window. One flag for the
     * whole sensor, so a reading can still be old while this is false: judge each
     * one by its own time against [staleAfterSeconds].
     */
    val stale: Boolean,
    /** The window [stale] is decided against, or null from a hub that does not send it. */
    val staleAfterSeconds: Double?,
    /** Null when the sensor has never reported at all, which is not the same as stale. */
    val lastSeen: Instant?,
    val motion: Reading<Boolean>,
    val temperature: Reading<Double>,
    val humidity: Reading<Double>,
)

/** Read one sensor from the hub's JSON, keeping apart the ways a reading can be missing. */
internal fun sensorFrom(body: JsonObject): Sensor {
    val wire = HubJson.decodeFromJsonElement(SensorWire.serializer(), body)
    return Sensor(
        id = wire.id,
        label = wire.label,
        stale = wire.stale,
        staleAfterSeconds = wire.staleAfterSeconds,
        lastSeen = wire.lastSeen,
        motion = reading(body, "motion", wire.motion, wire.motionUpdatedAt),
        // One time for both: the sensor that measures one sends the other with it.
        temperature = reading(body, "temperature", wire.temperature, wire.climateUpdatedAt),
        humidity = reading(body, "humidity", wire.humidity, wire.climateUpdatedAt),
    )
}

private fun <T : Any> reading(
    body: JsonObject,
    field: String,
    value: T?,
    at: Instant?,
): Reading<T> =
    when {
        field !in body -> Reading.Unsupported
        value == null -> Reading.Never
        else -> Reading.Value(value, at)
    }

/** `DeviceSnapshot` as it is on the wire, before presence is taken into account. */
@Serializable
private class SensorWire(
    val id: String,
    val label: String,
    val stale: Boolean,
    @SerialName("stale_after_seconds") val staleAfterSeconds: Double? = null,
    @SerialName("last_seen") val lastSeen: Instant? = null,
    val motion: Boolean? = null,
    @SerialName("motion_updated_at") val motionUpdatedAt: Instant? = null,
    val temperature: Double? = null,
    val humidity: Double? = null,
    @SerialName("climate_updated_at") val climateUpdatedAt: Instant? = null,
)

/**
 * A device the hub polls, and what it last found. Mirrors `DeviceStatus` in the
 * hub's v1 schema.
 *
 * A sensor pushes and a device is asked, so here the hub states whether the
 * last answer came rather than leaving it to be worked out from timestamps.
 */
@Serializable
data class Device(
    val id: String,
    val label: String,
    /** What sort of device it is. Kept as text, so a kind added on the hub still shows. */
    val kind: String,
    /** Where it last said it was, or null if it never has. */
    val address: String? = null,
    /** Whatever the device calls its build. Shown, never interpreted. */
    val firmware: String? = null,
    @SerialName("announced_at") val announcedAt: Instant? = null,
    /**
     * Whether the last poll succeeded. Null means never polled, which is not the
     * same as unreachable: one needs switching on, the other needs looking at.
     */
    val reachable: Boolean? = null,
    @SerialName("last_polled_at") val lastPolledAt: Instant? = null,
    /** When it last answered, which is the date on [state]. */
    @SerialName("last_seen_at") val lastSeenAt: Instant? = null,
    /** When the current run of failures began, or null while it answers. */
    @SerialName("unreachable_since") val unreachableSince: Instant? = null,
    /** Why the last poll failed, in the hub's words. */
    @SerialName("last_error") val lastError: String? = null,
    /**
     * The device's own status document, exactly as it served it. Not modelled:
     * what its fields mean is the device's to say, and modelling them here would
     * make every field a device adds a change to this app as well.
     */
    val state: JsonObject? = null,
)

/** An automation rule. Mirrors `AutomationRule` in the hub's v1 schema. */
@Serializable
data class AutomationRule(
    val id: String,
    val enabled: Boolean,
    @SerialName("only_after_dark") val onlyAfterDark: Boolean,
    @SerialName("when") val trigger: Trigger,
    @SerialName("then") val action: Action,
) {
    /** A sensor's motion changing to [motion]. */
    @Serializable
    data class Trigger(
        val device: String,
        val motion: Boolean,
    )

    /** What the rule does to a relay, and for how long. */
    @Serializable
    data class Action(
        val relay: String,
        val state: SwitchState,
        /** Put the relay back after this many seconds; null leaves it as set. */
        @SerialName("hold_seconds") val holdSeconds: Double? = null,
    )
}

@Serializable
enum class SwitchState {
    @SerialName("on")
    ON,

    @SerialName("off")
    OFF,
}
