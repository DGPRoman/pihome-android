package io.github.dgproman.pihome.ui.people

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.mayHaveHappened
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.people.Shown
import io.github.dgproman.pihome.people.TimeLeft
import io.github.dgproman.pihome.people.timeLeft
import io.github.dgproman.pihome.ui.connect.messageFor
import io.github.dgproman.pihome.ui.house.Note
import io.github.dgproman.pihome.ui.house.Problem
import io.github.dgproman.pihome.ui.house.timeOfDay
import java.time.Instant
import java.time.ZoneId

/**
 * One invitation, shown so it can be passed on: a code to scan, and a link to
 * send or copy.
 *
 * Both, because either alone leaves somebody out: a phone standing beside this
 * one, a relative in another town. And the time left, because the fifteen
 * minutes are the hub's and do not stop for anybody.
 */
@Composable
fun InvitationView(
    shown: Shown,
    /** The link the token travels in. */
    link: String,
    /** The link names an address that leads only to this phone. */
    onlyThisDevice: Boolean,
    now: Instant,
    zone: ZoneId,
    /** A new invitation is being issued to this account. */
    inviting: Boolean,
    /** Why the last attempt at a new one failed, if it did. */
    inviteFailure: Int?,
    onWithdraw: () -> Unit,
    onInviteAgain: () -> Unit,
    onDone: () -> Unit,
) {
    val name = shown.username
    val until = timeOfDay(shown.invitation.expiresAt, zone)
    val left = timeLeft(shown.invitation.expiresAt, now)
    val over = shown.gone || left == null

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            // Not "used": the hub stops listing an invitation when it is redeemed, and
            // also when it is withdrawn or replaced elsewhere, and does not say which.
            shown.gone -> {
                Text(stringResource(R.string.invitation_gone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.announced())
            }

            left == null -> {
                Text(
                    stringResource(R.string.invitation_ran_out, until),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.announced(),
                )
            }

            else -> {
                Text(stringResource(R.string.invitation_body, name), style = MaterialTheme.typography.bodyLarge)
                if (onlyThisDevice) {
                    // Said before the code rather than after it fails: a phone that opens
                    // localhost opens itself, and its error would blame the invitation.
                    Text(
                        stringResource(R.string.invitation_only_this_device),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                QrImage(link, stringResource(R.string.invitation_qr, name))
                Column {
                    Text(
                        stringResource(R.string.invitation_link),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer { Text(link, style = MaterialTheme.typography.bodyLarge) }
                }
                PassOn(link)
                // Not a live region: it changes every second, and reading each one out
                // would drown everything else.
                Text(
                    stringResource(R.string.invitation_countdown, until, timeLeftText(left)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Note(stringResource(R.string.invitation_stays))
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (over) {
                Button(onClick = onInviteAgain, enabled = !inviting) {
                    Text(stringResource(if (inviting) R.string.inviting else R.string.invite_again))
                }
            } else {
                OutlinedButton(
                    onClick = onWithdraw,
                    enabled = !shown.withdrawing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(if (shown.withdrawing) R.string.withdrawing else R.string.withdraw))
                }
            }
            OutlinedButton(onClick = onDone) { Text(stringResource(R.string.done)) }
        }
        shown.withdrawFailure?.let { Problem(stringResource(withdrawMessageFor(it))) }
        inviteFailure?.let { Problem(stringResource(it)) }
    }
}

/** Share and Copy, and what came of copying. */
@Composable
private fun PassOn(link: String) {
    val context = LocalContext.current
    var copied by rememberSaveable(link) { mutableStateOf(false) }
    val share = stringResource(R.string.share)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { context.share(link, share) }) { Text(share) }
        OutlinedButton(onClick = {
            context.copySensitive(link)
            copied = true
        }) { Text(stringResource(R.string.copy)) }
    }
    // Android 13 and later say so themselves, and saying it twice is noise.
    if (copied && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Note(stringResource(R.string.copied), Modifier.announced())
    }
}

/** Hand the link to whichever app the person picks: a message, a mail. */
private fun Context.share(
    link: String,
    title: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link)
    startActivity(Intent.createChooser(send, title))
}

/**
 * Put the link on the clipboard, marked sensitive, so the preview Android shows
 * of what was copied hides it, and keyboards that suggest from the clipboard
 * leave it alone.
 */
private fun Context.copySensitive(link: String) {
    val clip = ClipData.newPlainText(getString(R.string.invitation_link), link)
    clip.description.extras =
        PersistableBundle().apply {
            // The same key before Android 13, where the constant was added; apps that look for it there find it.
            putBoolean(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else SENSITIVE,
                true,
            )
        }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private const val SENSITIVE = "android.content.extra.IS_SENSITIVE"

@Composable
private fun timeLeftText(left: TimeLeft): String =
    when (left) {
        TimeLeft.LessThanAMinute -> stringResource(R.string.less_than_a_minute_left)
        is TimeLeft.Minutes -> pluralStringResource(R.plurals.minutes_left, left.count, left.count)
    }

private fun withdrawMessageFor(kind: HubErrorKind): Int =
    when {
        kind.mayHaveHappened -> R.string.change_unconfirmed
        kind == HubErrorKind.NOT_FOUND -> R.string.person_gone
        kind == HubErrorKind.FORBIDDEN -> R.string.admins_only
        else -> messageFor(kind)
    }

private fun Modifier.announced(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
