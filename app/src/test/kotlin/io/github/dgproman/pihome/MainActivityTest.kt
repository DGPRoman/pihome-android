package io.github.dgproman.pihome

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `says there is no hub yet`() {
        compose.onNodeWithText("pihome").assertIsDisplayed()
        compose.onNodeWithText("Not connected to a hub yet").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "uk")
    fun `speaks Ukrainian on a phone set to it`() {
        compose.onNodeWithText("Ще не підключено до хаба").assertIsDisplayed()
    }
}
