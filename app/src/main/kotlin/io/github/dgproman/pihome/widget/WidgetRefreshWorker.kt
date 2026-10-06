package io.github.dgproman.pihome.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.dgproman.pihome.PihomeApplication
import java.time.Duration

/**
 * Reads the house for the widget every fifteen minutes, the shortest period
 * Android allows, and only once there is a network.
 *
 * Succeeds whatever the hub said: the widget shows a failure itself, and a
 * read tried again sooner would cost the battery what the period saves.
 */
class WidgetRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as PihomeApplication).graph.widget.refresh()
        return Result.success()
    }

    companion object {
        private const val NAME = "widget-refresh"
        val PERIOD: Duration = Duration.ofMinutes(15)

        /** Start reading, at once and then every [PERIOD], unless that is already under way. */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(PERIOD)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** The last widget is gone. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
