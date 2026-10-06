package io.github.dgproman.pihome.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.Parsed
import io.github.dgproman.pihome.hub.Role
import io.github.dgproman.pihome.hub.Session
import io.github.dgproman.pihome.hub.SessionToken
import io.github.dgproman.pihome.session.SavedSession
import io.github.dgproman.pihome.ui.Screen
import io.github.dgproman.pihome.ui.theme.PihomeTheme
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** How close to its end a session is before the app says so. */
private val WARN_BEFORE: Duration = Duration.ofDays(5)

/** Which hub this phone is signed in to and as whom, until when, which build it runs, and the way out. */
@Composable
fun AccountScreen(
    saved: SavedSession,
    version: String,
    clock: Clock,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onTile: () -> Unit = {},
    onLights: () -> Unit = {},
) {
    val locale = LocalConfiguration.current.locales[0]
    val ends =
        remember(saved.session.expiresAt, locale) {
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(clock.zone)
                .format(saved.session.expiresAt)
        }
    val left = Duration.between(clock.instant(), saved.session.expiresAt)

    Screen(title = stringResource(R.string.account_title), onBack = onBack) {
        Fact(stringResource(R.string.account_hub), saved.address.origin)
        Fact(stringResource(R.string.account_name), saved.session.username)
        Fact(stringResource(R.string.account_role), stringResource(nameOf(saved.session.role)))
        Fact(stringResource(R.string.account_session_ends), ends)
        if (left < WARN_BEFORE) {
            val days = left.toDays().toInt()
            Text(
                if (days < 1) {
                    stringResource(R.string.session_ends_today)
                } else {
                    pluralStringResource(R.plurals.session_ends_in_days, days, days)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Fact(stringResource(R.string.account_version), version)
        OutlinedButton(onClick = onTile) {
            Text(stringResource(R.string.tile_title))
        }
        OutlinedButton(onClick = onLights) {
            Text(stringResource(R.string.lights_title))
        }
        OutlinedButton(onClick = onSignOut) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

private fun nameOf(role: Role): Int =
    when (role) {
        Role.ADMIN -> R.string.role_admin
        Role.OPERATOR -> R.string.role_operator
        Role.VIEWER -> R.string.role_viewer
    }

/** A label over its value, read out by a screen reader as one item. */
@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountPreview() {
    val now = Instant.parse("2026-10-05T12:00:00Z")
    val saved =
        SavedSession(
            address = (HubAddress.parse("http://hub.local:5002") as Parsed.Valid).value,
            token = SessionToken("preview"),
            session = Session("olya", Role.OPERATOR, now.plus(Duration.ofDays(3))),
        )
    PihomeTheme {
        AccountScreen(saved, version = "0.1.0", clock = Clock.fixed(now, ZoneId.of("UTC")), onBack = {}, onSignOut = {})
    }
}
