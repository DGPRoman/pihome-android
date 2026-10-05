package io.github.dgproman.pihome.quick

import androidx.annotation.StringRes
import io.github.dgproman.pihome.R

/** How the tile draws a [TileLook]: lit or not, and the line under the relay's name. */
data class TileFace(
    /** Lit when the relay is on, or being switched on, or probably on. */
    val active: Boolean,
    @param:StringRes val subtitle: Int,
)

fun faceOf(look: TileLook): TileFace =
    when (look) {
        is TileLook.Reading -> {
            TileFace(false, R.string.tile_reading)
        }

        is TileLook.Showing -> {
            TileFace(look.on, if (look.on) R.string.relay_on else R.string.relay_off)
        }

        is TileLook.Switching -> {
            TileFace(look.to, if (look.to) R.string.tile_switching_on else R.string.tile_switching_off)
        }

        is TileLook.Unsure -> {
            TileFace(look.to, if (look.to) R.string.relay_on_unsure else R.string.relay_off_unsure)
        }

        is TileLook.Failed -> {
            TileFace(look.on == true, if (look.on == null) R.string.tile_unreachable else R.string.tile_refused)
        }

        is TileLook.CannotAct -> {
            TileFace(
                false,
                when (look.reason) {
                    TileLook.Reason.NOT_SIGNED_IN -> R.string.tile_not_connected
                    TileLook.Reason.READ_ONLY -> R.string.tile_read_only
                    TileLook.Reason.NEEDS_PERMISSION -> R.string.tile_needs_permission
                    TileLook.Reason.NOT_CHOSEN -> R.string.tile_not_chosen
                    TileLook.Reason.NOT_FOUND -> R.string.tile_not_found
                },
            )
        }
    }
