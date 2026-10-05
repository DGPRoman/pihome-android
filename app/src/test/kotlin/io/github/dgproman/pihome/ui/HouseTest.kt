package io.github.dgproman.pihome.ui

import androidx.activity.ComponentActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.dgproman.pihome.FakeHubs
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.TestClock
import io.github.dgproman.pihome.failure
import io.github.dgproman.pihome.house.AllOff
import io.github.dgproman.pihome.house.House
import io.github.dgproman.pihome.house.HouseViewModel
import io.github.dgproman.pihome.house.RelayRow
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.hub.AutomationRule
import io.github.dgproman.pihome.hub.Device
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.hub.Reading
import io.github.dgproman.pihome.hub.Relay
import io.github.dgproman.pihome.hub.Sensor
import io.github.dgproman.pihome.hub.SwitchState
import io.github.dgproman.pihome.session.HOME
import io.github.dgproman.pihome.ui.screens.HouseContent
import io.github.dgproman.pihome.ui.screens.HouseScreen
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.ZoneOffset

/** The house in each of its states, in both languages. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class HouseTest(
    qualifiers: String,
    /** 21.5, as this language writes it. */
    private val twentyOneAndAHalf: String,
) {
    @get:Rule(order = 0)
    val config = Qualifiers(qualifiers)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val porch = Relay("porch", "Porch light", on = true)
    private val gate = Relay("gate", "Gate light", on = false)
    private val yard =
        Sensor(
            id = "yard",
            label = "Yard",
            stale = false,
            staleAfterSeconds = 300.0,
            lastSeen = now.minusSeconds(40),
            motion = Reading.Value(false, now.minusSeconds(3_600)),
            temperature = Reading.Value(21.5, now.minusSeconds(300)),
            humidity = Reading.Never,
        )
    private val rule =
        AutomationRule(
            id = "porch-on-motion",
            enabled = true,
            onlyAfterDark = true,
            trigger = AutomationRule.Trigger("yard", motion = true),
            action = AutomationRule.Action("porch", SwitchState.ON, holdSeconds = 120.0),
        )

    private val everything =
        House(
            relays = Section(listOf(RelayRow(porch), RelayRow(gate)), now),
            sensors = Section(listOf(yard), now),
            devices = Section(emptyList(), now),
            rules = Section(listOf(rule), now),
        )

    private fun text(
        @StringRes id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    private fun plural(
        @PluralsRes id: Int,
        count: Int,
    ): String = compose.activity.resources.getQuantityString(id, count, count)

    private fun show(
        house: House,
        mayChange: Boolean = true,
    ) {
        compose.setContent {
            PihomeTheme {
                HouseContent(
                    house = house,
                    mayChange = mayChange,
                    zone = ZoneOffset.UTC,
                    onSet = { _, _ -> },
                    onAllOff = {},
                    onRetry = {},
                    onPull = {},
                    onAccount = {},
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

    private fun switchFor(relay: Relay) =
        compose.onNode(hasText(relay.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    @Test
    fun `each part says it is reading until the hub answers`() {
        show(House())

        seen(text(R.string.relays_loading))
        seen(text(R.string.sensors_loading))
        seen(text(R.string.devices_loading))
        seen(text(R.string.rules_loading))
    }

    @Test
    fun `a hub with nothing set up says so for each part`() {
        show(House(Section(emptyList(), now), AllOff(), Section(emptyList(), now), Section(emptyList(), now), Section(emptyList(), now)))

        seen(text(R.string.relays_empty))
        seen(text(R.string.sensors_empty))
        seen(text(R.string.devices_empty))
        seen(text(R.string.rules_empty))
        compose.onNodeWithText(text(R.string.all_off)).assertIsNotEnabled()
    }

    @Test
    fun `a read that failed with nothing to show says why, and offers to try again`() {
        show(everything.copy(sensors = Section(failure = HubErrorKind.OFFLINE)))

        seen(text(R.string.error_offline))
        compose.onNode(hasText(text(R.string.try_again)) and hasClickAction()).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a read that failed after an answer keeps the answer, and says it is the last one`() {
        show(everything.copy(relays = everything.relays.copy(failure = HubErrorKind.TIMEOUT)))

        seen(text(R.string.error_timeout) + " " + text(R.string.showing_last))
        switchFor(porch).assertIsDisplayed()
    }

    @Test
    fun `a relay is a switch with its state in words, and all off is there while one is on`() {
        show(everything)

        switchFor(porch)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assertIsEnabled()
        switchFor(gate).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        seen(text(R.string.relay_on))
        seen(text(R.string.relay_off))
        compose.onNodeWithText(text(R.string.all_off)).assertIsEnabled()
    }

    @Test
    fun `a viewer sees the switches, unavailable, and why`() {
        show(everything, mayChange = false)

        seen(text(R.string.read_only))
        switchFor(porch).assertIsNotEnabled()
        switchFor(gate).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.all_off)).assertIsNotEnabled()
    }

    @Test
    fun `a press in doubt says so on the switch, to the eye and to a screen reader, and aloud`() {
        show(everything.copy(relays = Section(listOf(RelayRow(porch, failure = HubErrorKind.UNREADABLE, unconfirmed = true)), now)))

        seen(text(R.string.relay_on_unsure))
        switchFor(porch).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.relay_probably_on)))
        compose
            .onNode(hasText(text(R.string.relay_unconfirmed)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `a refused press says why beside the switch, aloud`() {
        show(everything.copy(relays = Section(listOf(RelayRow(porch, failure = HubErrorKind.FORBIDDEN)), now)))

        compose
            .onNode(hasText(text(R.string.relay_forbidden)))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `a relay an automation rule will put back says when`() {
        show(everything.copy(relays = Section(listOf(RelayRow(porch.copy(holdExpiresAt = Instant.parse("2026-10-05T12:15:00Z")))), now)))

        // As the language writes a time: 12:15 PM in English, 12:15 in Ukrainian.
        seen(text(R.string.relay_hold_off, "").removeSuffix("."), substring = true)
        seen("12:15", substring = true)
    }

    @Test
    fun `each reading has its own age and verdict, and one never reported says so`() {
        show(everything)

        seen(text(R.string.temperature_value, twentyOneAndAHalf))
        // Exactly at the window: still current. The motion, an hour old, is not.
        seen(plural(R.plurals.minutes_ago, 5))
        seen(plural(R.plurals.hours_ago, 1))
        compose.onAllNodes(hasText(text(R.string.stale))).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        seen(text(R.string.never_reported))
        seen(text(R.string.last_seen, plural(R.plurals.seconds_ago, 40)))
    }

    @Test
    fun `a sensor that has never reported anything says only that`() {
        val silent = yard.copy(lastSeen = null, motion = Reading.Never, temperature = Reading.Never)
        show(everything.copy(sensors = Section(listOf(silent), now)))

        seen(text(R.string.no_readings))
    }

    @Test
    fun `a silent device says since when, and what it last said with its age`() {
        val pc =
            Device(
                id = "pc",
                label = "Desk PC",
                kind = "pc-power",
                address = "192.168.1.50",
                firmware = "1.2.0",
                reachable = false,
                lastSeenAt = now.minusSeconds(600),
                unreachableSince = now.minusSeconds(120),
                lastError = "connection refused",
                state = buildJsonObject { put("power", JsonPrimitive("on")) },
            )
        show(everything.copy(devices = Section(listOf(pc), now)))

        seen(text(R.string.device_silent))
        seen(text(R.string.device_silent_since, plural(R.plurals.minutes_ago, 2)) + " connection refused")
        seen("power")
        seen(text(R.string.device_last_answered, plural(R.plurals.minutes_ago, 10)))
    }

    @Test
    fun `a rule reads as a sentence with the names of what it uses`() {
        show(everything)

        val sentence =
            text(
                R.string.rule_sentence_hold,
                text(R.string.rule_when_motion, "Yard"),
                text(R.string.rule_turn_on, "Porch light"),
                plural(R.plurals.span_minutes, 2),
            )
        seen(sentence)
        seen(text(R.string.rule_after_dark))
    }

    @Test
    fun `at twice the text size, on a tablet held sideways, everything can still be reached`() {
        RuntimeEnvironment.setQualifiers("+w1280dp-h800dp-land")
        RuntimeEnvironment.setFontScale(2f)
        show(everything)

        switchFor(gate).performScrollTo().assertIsDisplayed()
        seen(text(R.string.rule_after_dark))
        compose.onNodeWithText(text(R.string.all_off)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `pressing a switch asks the hub, and the house shows what it answered`() {
        val hubs = FakeHubs()
        var onHub = listOf(porch, gate)
        hubs.relays = { onHub }
        val answer = CompletableDeferred<Unit>()
        hubs.setRelay = { id, on ->
            answer.await()
            onHub = onHub.map { if (it.id == id) it.copy(on = on) else it }
            onHub.first { it.id == id }
        }
        val model = HouseViewModel(hubs.at(HOME.address, HOME.token), TestClock(now), onRefused = {}, onForbidden = {})
        compose.setContent { PihomeTheme { HouseScreen(model, mayChange = true, zone = ZoneOffset.UTC, onAccount = {}) } }
        compose.waitUntilAtLeastOneExists(hasText(gate.label), timeoutMillis = 5_000)

        switchFor(gate).performScrollTo().performClick()
        // Shown at once, and not pressable again until the hub answers.
        switchFor(gate)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assertIsNotEnabled()

        answer.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { "set gate true" in hubs.calls }
        compose.waitForIdle()
        switchFor(gate).assertIsEnabled()
        assertEquals(true, onHub.first { it.id == "gate" }.on)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<Any>> =
            listOf(
                arrayOf("en", "21.5"),
                arrayOf("uk", "21,5"),
            )
    }
}
