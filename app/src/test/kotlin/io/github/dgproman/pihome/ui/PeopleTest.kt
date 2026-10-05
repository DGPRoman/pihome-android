package io.github.dgproman.pihome.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.house.House
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.Account
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Invitation
import io.github.dgproman.pihome.hub.InvitationToken
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.people.People
import io.github.dgproman.pihome.people.PeopleViewModel
import io.github.dgproman.pihome.people.Shown
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.session.address
import io.github.dgproman.pihome.ui.screens.HouseContent
import io.github.dgproman.pihome.ui.screens.PeopleContent
import io.github.dgproman.pihome.ui.screens.PeopleScreen
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The people screen and the invitation, in both languages. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class PeopleTest(
    qualifiers: String,
) {
    @get:Rule(order = 0)
    val config = Qualifiers(qualifiers)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val admin = Account("admin", Role.ADMIN, disabled = false, createdAt = now, invitationExpiresAt = null)
    private val olya = Account("olya", Role.OPERATOR, disabled = false, createdAt = now, invitationExpiresAt = null)
    private val taras = Account("taras", Role.VIEWER, disabled = true, createdAt = now, invitationExpiresAt = null)
    private val everybody = People(accounts = Section(listOf(admin, olya, taras), now))

    private val invitation = Invitation(InvitationToken("made-up-token"), now.plus(Duration.ofMinutes(15)))
    private val link = "http://192.168.1.20:5002/join#made-up-token"
    private val shown = Shown("olya", invitation, issuedAt = now)

    /** What the screen asked for, in order. */
    private val asked = mutableListOf<String>()

    private fun text(
        @StringRes id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    private fun plural(
        @PluralsRes id: Int,
        count: Int,
    ): String = compose.activity.resources.getQuantityString(id, count, count)

    private fun show(
        people: People,
        at: Instant = now,
        address: HubAddress = HOME.address,
    ) = show(at, address) { people }

    private fun show(
        at: Instant = now,
        address: HubAddress = HOME.address,
        people: () -> People,
    ) {
        compose.setContent {
            PihomeTheme {
                PeopleContent(
                    people = people(),
                    address = address,
                    now = at,
                    zone = ZoneOffset.UTC,
                    onBack = { asked += "back" },
                    onRetry = { asked += "retry" },
                    onPull = { asked += "pull" },
                    onRole = { name, role -> asked += "role $name $role" },
                    onDisabled = { name, disabled -> asked += "disabled $name $disabled" },
                    onInvite = { asked += "invite $it" },
                    onWithdraw = { asked += "withdraw $it" },
                    onDelete = { asked += "delete $it" },
                    onAdd = { name, role -> asked += "add $name $role" },
                    onWithdrawShown = { asked += "withdraw shown" },
                    onClose = { asked += "close" },
                )
            }
        }
    }

    private fun seen(
        text: String,
        substring: Boolean = false,
    ) {
        compose.onNode(hasText(text, substring = substring)).performScrollTo().assertIsDisplayed()
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private val code get() = compose.onNode(hasContentDescription(text(R.string.invitation_qr, "olya")))

    @Test
    fun `an admin is offered the people from the house, and nobody else is`() {
        var opened = false
        var admin by mutableStateOf(true)
        compose.setContent {
            PihomeTheme {
                HouseContent(
                    House(),
                    mayChange = true,
                    zone = ZoneOffset.UTC,
                    onSet = { _, _ -> },
                    onAllOff = {},
                    onRetry = {},
                    onPull = {},
                    onAccount = {},
                    onPeople = if (admin) ({ opened = true }) else null,
                )
            }
        }
        compose.onNode(hasContentDescription(text(R.string.people_title))).performClick()
        assertTrue(opened)

        admin = false
        compose.onNode(hasContentDescription(text(R.string.people_title))).assertDoesNotExist()
    }

    @Test
    fun `every account with its role, and nothing to change on an admin's`() {
        show(everybody)

        seen("admin")
        seen(text(R.string.managed_on_console))
        seen("olya")
        seen(text(R.string.role_operator))
        button(text(R.string.make_viewer)).performScrollTo().performClick()
        seen(text(R.string.person_disabled))
        button(text(R.string.make_operator)).performScrollTo().performClick()
        button(text(R.string.enable)).performScrollTo().performClick()

        assertEquals(listOf("role olya VIEWER", "role taras OPERATOR", "disabled taras false"), asked)
    }

    @Test
    fun `a disabled account is not offered an invitation, and an open one says until when`() {
        show(People(accounts = Section(listOf(olya.copy(invitationExpiresAt = invitation.expiresAt), taras), now)))

        seen(text(R.string.person_invited, "§").substringBefore("§"), substring = true)
        button(text(R.string.invite_again)).performScrollTo().performClick()
        button(text(R.string.withdraw_invitation)).performScrollTo().performClick()
        // olya's, and none for taras.
        compose.onAllNodes(hasText(text(R.string.invite)) and hasClickAction()).assertCountEquals(0)

        assertEquals(listOf("invite olya", "withdraw olya"), asked)
    }

    @Test
    fun `deleting asks first, and keeping sends nothing`() {
        show(everybody)

        compose.onAllNodes(hasText(text(R.string.delete)) and hasClickAction())[0].performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.delete_body, "olya")).assertIsDisplayed()
        button(text(R.string.keep)).performClick()
        assertEquals(emptyList<String>(), asked)

        compose.onAllNodes(hasText(text(R.string.delete)) and hasClickAction())[0].performScrollTo().performClick()
        button(text(R.string.delete_confirm, "olya")).performClick()
        assertEquals(listOf("delete olya"), asked)
    }

    @Test
    fun `adding says what a name may be, sends nothing until it is one, and makes a viewer unless told`() {
        show(everybody)

        val field = compose.onNode(hasSetTextAction())
        field.performScrollTo()
        seen(text(R.string.username_rule))
        field.performTextInput("Оля")
        button(text(R.string.add_button)).performScrollTo().performClick()
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        assertEquals(emptyList<String>(), asked)

        compose.onNode(hasText(text(R.string.role_choice_viewer))).assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("bohdana")
        button(text(R.string.add_button)).performScrollTo().performClick()
        assertEquals(listOf("add bohdana VIEWER"), asked)
    }

    @Test
    fun `a name that is taken says so`() {
        show(everybody.copy(adding = everybody.adding.copy(failure = HubErrorKind.CONFLICT)))

        seen(text(R.string.name_taken))
    }

    @Test
    fun `an invitation shows a code, its link, who to send it to, and how long it works`() {
        show(everybody.copy(shown = shown), at = now.plusSeconds(60))

        compose.onNodeWithText(text(R.string.invitation_title, "olya")).assertIsDisplayed()
        seen(text(R.string.invitation_body, "olya"))
        code.performScrollTo().assertIsDisplayed()
        seen(link)
        seen(plural(R.plurals.minutes_left, 14), substring = true)
        seen(text(R.string.invitation_stays))
        compose.onNodeWithText(text(R.string.invitation_only_this_device)).assertDoesNotExist()

        button(text(R.string.withdraw)).performScrollTo().performClick()
        button(text(R.string.done)).performScrollTo().performClick()
        assertEquals(listOf("withdraw shown", "close"), asked)
    }

    @Test
    fun `a link naming an address only this phone can reach says so before the code`() {
        show(everybody.copy(shown = shown), address = address("http://127.0.0.1:5002"))

        seen(text(R.string.invitation_only_this_device))
    }

    @Test
    fun `copying marks the link sensitive, and sharing hands it to the share sheet`() {
        show(everybody.copy(shown = shown))

        button(text(R.string.copy)).performScrollTo().performClick()
        val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(link, clip.getItemAt(0).text)
        assertEquals(true, clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))

        button(text(R.string.share)).performScrollTo().performClick()
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(link, send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a used invitation says so, and shows the code no more`() {
        show(everybody.copy(shown = shown.copy(gone = true)))

        seen(text(R.string.invitation_gone))
        code.assertDoesNotExist()
        compose.onNodeWithText(link).assertDoesNotExist()
        button(text(R.string.invite_again)).performScrollTo().performClick()
        assertEquals(listOf("invite olya"), asked)
    }

    @Test
    fun `one that ran out says so, and is not called used`() {
        show(everybody.copy(shown = shown), at = invitation.expiresAt)

        seen(text(R.string.invitation_ran_out, "§").substringBefore("§"), substring = true)
        compose.onNodeWithText(text(R.string.invitation_gone)).assertDoesNotExist()
        code.assertDoesNotExist()
    }

    @Test
    fun `at twice the text size, on a tablet held sideways, the list and the invitation can still be reached`() {
        RuntimeEnvironment.setQualifiers("+w1280dp-h800dp-land")
        RuntimeEnvironment.setFontScale(2f)
        var people by mutableStateOf(everybody)
        show { people }
        button(text(R.string.add_button)).performScrollTo().assertIsDisplayed()

        people = everybody.copy(shown = shown)
        button(text(R.string.done)).performScrollTo().assertIsDisplayed()
        code.performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `inviting from the list shows the invitation the hub issued, and done goes back to the list`() {
        val hubs = FakeHubs()
        var onHub = listOf(admin, olya)
        hubs.accounts = { onHub }
        hubs.issueInvitation = {
            onHub = listOf(admin, olya.copy(invitationExpiresAt = invitation.expiresAt))
            invitation
        }
        val clock = TestClock(now)
        val model = PeopleViewModel(hubs.at(HOME.address, HOME.token), clock, onRefused = {}, onForbidden = {})
        compose.setContent { PihomeTheme { PeopleScreen(model, HOME.address, clock, onBack = {}) } }
        compose.waitUntilAtLeastOneExists(hasText("olya"), timeoutMillis = 5_000)

        button(text(R.string.invite)).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText(link), timeoutMillis = 5_000)
        code.assertExists()

        button(text(R.string.done)).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.add_title)), timeoutMillis = 5_000)
        compose.onNodeWithText(link).assertDoesNotExist()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> = listOf(arrayOf("en"), arrayOf("uk"))
    }
}
