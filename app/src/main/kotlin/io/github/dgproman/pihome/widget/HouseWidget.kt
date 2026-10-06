package io.github.dgproman.pihome.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.dgproman.pihome.MainActivity
import io.github.dgproman.pihome.PihomeApplication
import io.github.dgproman.pihome.R
import io.github.dgproman.pihome.ui.theme.Dark
import io.github.dgproman.pihome.ui.theme.Light
import kotlinx.coroutines.flow.first

/**
 * The home-screen widget: the relays with their switches, and a line for each
 * sensor.
 *
 * Draws what [WidgetModel] keeps and hands it the taps; everything it decides
 * is there, and every word it says comes from [WidgetWords].
 */
class HouseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val graph = context.graph
        val stored = graph.widget.house.first()
        provideContent {
            val house by graph.widget.house.collectAsState(stored)
            GlanceTheme(colors = colors) {
                HouseWidgetContent(house, WidgetWords(LocalContext.current, graph.clock.instant(), graph.clock.zone))
            }
        }
    }

    private companion object {
        /** The app's own, light or dark as the phone is set. */
        val colors = ColorProviders(light = Light, dark = Dark)
    }
}

/** What the widget shows, apart from [HouseWidget] so tests can draw it. */
@Composable
@GlanceComposable
fun HouseWidgetContent(
    house: WidgetHouse,
    words: WidgetWords,
) {
    val context = LocalContext.current
    val blocked = house.blocked
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.background)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The title opens the app, for everything the widget does not show.
            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                Text(
                    context.getString(R.string.house_title),
                    style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                )
                if (blocked == null) Text(words.status(house), style = muted(alarm = house.failure != null))
            }
            if (blocked == null) {
                CircleIconButton(
                    imageProvider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = context.getString(R.string.widget_refresh),
                    onClick = actionRunCallback<RefreshHouse>(),
                    enabled = !house.reading,
                    backgroundColor = null,
                    contentColor = GlanceTheme.colors.primary,
                )
            }
        }
        if (blocked != null) {
            Text(
                words.blocked(blocked),
                style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp),
                modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp).clickable(actionStartActivity<MainActivity>()),
            )
            return@Column
        }
        LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (house.asOf != null && house.relays.isEmpty()) {
                item { Text(context.getString(R.string.relays_empty), style = muted()) }
            }
            items(house.relays, itemId = { it.id.hashCode().toLong() }) { relay -> RelayLine(relay, words) }
            if (house.sensors.isNotEmpty()) {
                item {
                    Text(
                        context.getString(R.string.sensors_title),
                        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier.padding(top = 8.dp),
                    )
                }
                items(house.sensors) { sensor -> SensorLine(sensor, words) }
            }
        }
    }
}

/** A relay: its switch, named, and its state in words under it. */
@Composable
@GlanceComposable
private fun RelayLine(
    relay: WidgetRelay,
    words: WidgetWords,
) {
    Column(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Switch(
            checked = relay.on,
            // Takes no other press until the hub has answered this one.
            onCheckedChange =
                if (relay.press == WidgetRelay.Press.SWITCHING) {
                    null
                } else {
                    actionRunCallback<SwitchRelay>(actionParametersOf(SwitchRelay.RELAY to relay.id))
                },
            text = relay.label,
            style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 16.sp),
            modifier = GlanceModifier.fillMaxWidth(),
        )
        Text(words.state(relay), style = muted(alarm = relay.press == WidgetRelay.Press.FAILED))
    }
}

@Composable
@GlanceComposable
private fun SensorLine(
    sensor: WidgetSensor,
    words: WidgetWords,
) {
    Column(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(sensor.label, style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp))
        Text(words.sensor(sensor), style = muted())
    }
}

@Composable
@GlanceComposable
private fun muted(alarm: Boolean = false): TextStyle =
    TextStyle(color = if (alarm) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp)

/** The refresh button. */
class RefreshHouse : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) = context.graph.widget.refresh()
}

/** A relay's switch, moved. Glance hands over which way it now points. */
class SwitchRelay : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val id = parameters[RELAY] ?: return
        val to = parameters[ToggleableStateKey] ?: return
        context.graph.widget.switch(id, to)
    }

    companion object {
        val RELAY = ActionParameters.Key<String>("relay")
    }
}

/** What Android talks to about the widget. */
class HouseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HouseWidget()

    // On the first widget placed, and after the phone or the app restarts: the
    // refresh is kept, rather than started again, when it is already there.
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.cancel(context)
    }
}

/** The widgets, as Glance holds them. */
class GlanceWidgetHost(
    private val context: Context,
) : WidgetHost {
    override suspend fun placed(): Boolean = GlanceAppWidgetManager(context).getGlanceIds(HouseWidget::class.java).isNotEmpty()

    override suspend fun redraw() = HouseWidget().updateAll(context)
}

private val Context.graph get() = (applicationContext as PihomeApplication).graph
