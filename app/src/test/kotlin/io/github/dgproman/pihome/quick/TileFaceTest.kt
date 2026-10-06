package io.github.dgproman.pihome.quick

import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.hub.HubErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TileFaceTest {
    @Test
    fun `lit while the relay is on, is being switched on, or is probably on`() {
        val lit =
            listOf(
                TileLook.Showing("Porch", on = true),
                TileLook.Switching("Porch", to = true),
                TileLook.Unsure("Porch", to = true),
                TileLook.Failed("Porch", on = true, HubErrorKind.SERVER),
            )
        val dark =
            listOf(
                TileLook.Reading("Porch"),
                TileLook.Showing("Porch", on = false),
                TileLook.Switching("Porch", to = false),
                TileLook.Unsure("Porch", to = false),
                TileLook.Failed("Porch", on = null, HubErrorKind.OFFLINE),
                TileLook.CannotAct("Porch", TileLook.Reason.READ_ONLY),
            )

        assertEquals(lit.map { true }, lit.map { faceOf(it).active })
        assertEquals(dark.map { false }, dark.map { faceOf(it).active })
    }

    @Test
    fun `every state has its own words, and doubt is never said as certainty`() {
        assertEquals(R.string.relay_on_unsure, faceOf(TileLook.Unsure("Porch", to = true)).subtitle)
        assertEquals(R.string.tile_unreachable, faceOf(TileLook.Failed("Porch", on = null, HubErrorKind.TIMEOUT)).subtitle)
        assertEquals(R.string.tile_refused, faceOf(TileLook.Failed("Porch", on = false, HubErrorKind.SERVER)).subtitle)
        val reasons = TileLook.Reason.entries.map { faceOf(TileLook.CannotAct(null, it)).subtitle }
        assertEquals(reasons.size, reasons.toSet().size)
    }
}
