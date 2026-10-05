package io.github.dgproman.pihome.ui.house

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.house.Age
import io.github.dgproman.pihome.house.Section
import io.github.dgproman.pihome.house.Span
import io.github.dgproman.pihome.house.ageOf
import io.github.dgproman.pihome.hub.HubErrorKind
import io.github.dgproman.pihome.ui.connect.messageFor
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One part of the house under its heading, and whatever became of reading it.
 *
 * The four states every part shares, said the same way for each: reading,
 * failed with nothing to show, empty, and the last answer kept under a note
 * when a later read failed.
 */
@Composable
fun <T> HouseSection(
    title: String,
    section: Section<T>,
    @StringRes loading: Int,
    @StringRes empty: Int,
    isEmpty: (T) -> Boolean,
    onRetry: () -> Unit,
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.(T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            action()
        }
        val data = section.data
        val failure = section.failure
        when {
            data != null -> {
                if (failure != null) Failed(failure, onRetry, lastKnown = true)
                if (isEmpty(data)) Note(stringResource(empty)) else content(data)
            }

            failure != null -> {
                Failed(failure, onRetry, lastKnown = false)
            }

            else -> {
                Text(
                    stringResource(loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** A read that failed, and the way to ask again. */
@Composable
private fun Failed(
    kind: HubErrorKind,
    onRetry: () -> Unit,
    lastKnown: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                if (lastKnown) {
                    stringResource(messageFor(kind)) + " " + stringResource(R.string.showing_last)
                } else {
                    stringResource(messageFor(kind))
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.try_again))
            }
        }
    }
}

/** One item in a part of the house: a relay, a sensor, a device, a rule. */
@Composable
fun Item(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/** A short mark beside a name: stale, not answering, disabled. */
@Composable
fun Badge(
    text: String,
    fault: Boolean = false,
) {
    Surface(
        color = if (fault) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (fault) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** A line of lesser text. */
@Composable
fun Note(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A line saying something went wrong, read out when it appears. */
@Composable
fun Problem(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** How long before [asOf] the moment [at] was, in words. */
@Composable
fun ago(
    at: Instant,
    asOf: Instant,
): String =
    when (val age = ageOf(at, asOf)) {
        Age.JustNow -> stringResource(R.string.just_now)
        is Age.Seconds -> pluralStringResource(R.plurals.seconds_ago, age.count, age.count)
        is Age.Minutes -> pluralStringResource(R.plurals.minutes_ago, age.count, age.count)
        is Age.Hours -> pluralStringResource(R.plurals.hours_ago, age.count, age.count)
        is Age.Days -> pluralStringResource(R.plurals.days_ago, age.count, age.count)
    }

/** A length of time, in words. */
@Composable
fun spanText(span: Span): String =
    when (span) {
        is Span.Seconds -> pluralStringResource(R.plurals.span_seconds, span.count, span.count)
        is Span.Minutes -> pluralStringResource(R.plurals.span_minutes, span.count, span.count)
        is Span.Hours -> pluralStringResource(R.plurals.span_hours, span.count, span.count)
    }

/** A measurement, to one decimal at most, written the way the reader's language writes numbers. */
@Composable
fun decimal(value: Double): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    return format.format(value)
}

/** A time of day, as the reader's language writes one. */
@Composable
fun timeOfDay(
    at: Instant,
    zone: ZoneId,
): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale, zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone) }
    return format.format(at)
}
