package io.github.dgproman.pihome.ui.people

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.people.QrCode
import kotlin.math.floor

/**
 * [text] as a QR code: black on white whatever the theme, since a camera looks
 * for dark modules on a light ground, and as wide as the column allows up to a
 * size a phone across a table can read.
 *
 * Each module a whole number of pixels, so no seam of half-lit pixels runs
 * between two of them for a camera to stumble on.
 */
@Composable
fun QrImage(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val code = remember(text) { QrCode.encode(text) }
    Canvas(
        modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics {
                contentDescription = description
                role = Role.Image
            },
    ) {
        drawRect(Color.White)
        val module = floor(size.minDimension / code.size)
        val start = (size.minDimension - module * code.size) / 2
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code.isDark(x, y)) {
                    drawRect(Color.Black, Offset(start + x * module, start + y * module), Size(module, module))
                }
            }
        }
    }
}
