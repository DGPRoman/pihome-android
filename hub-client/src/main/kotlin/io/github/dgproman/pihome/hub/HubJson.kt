package io.github.dgproman.pihome.hub

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * How the hub's JSON is read.
 *
 * Fields this app does not know are skipped, so a hub newer than the app keeps
 * working with it. Everything else is the library's strict default: a field the
 * app needs that is missing, null where null is not allowed, or a value of the
 * wrong type fails the whole reply rather than being guessed at.
 */
internal val HubJson =
    Json {
        ignoreUnknownKeys = true
    }

/**
 * A moment as the hub writes one: ISO 8601 with an offset, `Z` in practice.
 *
 * Read through [OffsetDateTime] rather than [Instant.parse], which on the older
 * Android releases this app runs on accepts nothing but `Z`. A time with no
 * offset at all is refused: it would be a guess at which zone it meant.
 */
internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (cause: DateTimeParseException) {
            throw SerializationException("'$text' is not a time with an offset", cause)
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }
}
