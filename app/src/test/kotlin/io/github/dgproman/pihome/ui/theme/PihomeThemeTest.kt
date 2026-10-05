package io.github.dgproman.pihome.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PihomeThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun surface(): Color {
        var surface = Color.Unspecified
        compose.setContent { PihomeTheme { surface = MaterialTheme.colorScheme.surface } }
        compose.waitForIdle()
        return surface
    }

    @Test
    fun `is light when the system is`() {
        assertEquals(Color(0xFFFFFFFF), surface())
    }

    @Test
    @Config(qualifiers = "night")
    fun `is dark when the system is`() {
        assertEquals(Color(0xFF14161A), surface())
    }
}
