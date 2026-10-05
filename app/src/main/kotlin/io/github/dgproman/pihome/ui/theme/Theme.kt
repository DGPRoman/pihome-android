package io.github.dgproman.pihome.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The web client's design tokens (pihome-hub-web, src/styles/global.css), so the two
// clients read as one product. A fixed palette rather than the wallpaper's dynamic
// colours for the same reason.
//
// The border token becomes outlineVariant, which Material keeps for decoration. The
// outline proper marks the edges of controls and needs more contrast than a hairline
// between cards, so it takes the muted text colour.

private val Light =
    lightColorScheme(
        primary = Color(0xFF1B6EF3),
        onPrimary = Color(0xFFFFFFFF),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF16181D),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF16181D),
        onSurfaceVariant = Color(0xFF5C6270),
        surfaceContainer = Color(0xFFF4F5F7),
        outline = Color(0xFF5C6270),
        outlineVariant = Color(0xFFD8DBE0),
    )

// Dark text on the dark theme's accent and danger colours: white on either would
// fall short of 4.5:1, and the dark page text clears it comfortably.
private val Dark =
    darkColorScheme(
        primary = Color(0xFF4C8DFF),
        onPrimary = Color(0xFF16181D),
        error = Color(0xFFF2837C),
        onError = Color(0xFF16181D),
        background = Color(0xFF14161A),
        onBackground = Color(0xFFECEEF2),
        surface = Color(0xFF14161A),
        onSurface = Color(0xFFECEEF2),
        onSurfaceVariant = Color(0xFF9AA1AE),
        surfaceContainer = Color(0xFF1D2026),
        outline = Color(0xFF9AA1AE),
        outlineVariant = Color(0xFF2C3038),
    )

/** Light or dark as the system is set; there is no setting of the app's own. */
@Composable
fun PihomeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
