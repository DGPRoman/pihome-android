package io.github.dgproman.pihome

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `opens on the way to connect, with nothing saved`() {
        compose.waitUntilAtLeastOneExists(hasText("Not connected to a hub yet"), timeoutMillis = 5_000)
        compose.onNodeWithText("pihome").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "uk")
    fun `speaks Ukrainian on a phone set to it`() {
        compose.waitUntilAtLeastOneExists(hasText("Ще не підключено до хаба"), timeoutMillis = 5_000)
    }
}
