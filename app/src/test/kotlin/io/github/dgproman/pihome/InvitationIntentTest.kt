package io.github.dgproman.pihome

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app opened by the hub's join page, as Android opens it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class InvitationIntentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun open(link: String): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setClassName(ApplicationProvider.getApplicationContext(), MainActivity::class.java.name),
        )

    @Test
    fun `an invitation opens the app on its join screen`() {
        open("pihome://join?hub=http%3A%2F%2F192.168.1.20%3A5002&token=test-invitation").use {
            compose.waitUntilAtLeastOneExists(
                hasText("This invitation connects this phone to the hub at http://192.168.1.20:5002", substring = true),
                timeoutMillis = 5_000,
            )
        }
    }

    @Test
    fun `a link that is not a whole invitation opens the first screen, saying so`() {
        open("pihome://join?token=test-invitation").use {
            compose.waitUntilAtLeastOneExists(hasText("That is not an invitation to a hub", substring = true), timeoutMillis = 5_000)
        }
    }
}
