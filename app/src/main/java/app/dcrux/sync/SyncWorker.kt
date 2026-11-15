package app.dcrux.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dcrux.data.Dates
import app.dcrux.graph
import java.util.concurrent.TimeUnit

private const val WORK = "dcrux-daily-sync"

/** Daily background sync, early morning, whenever the phone has a connection. */
fun scheduleDailySync(context: Context, enabled: Boolean, wifiOnly: Boolean) {
    val wm = WorkManager.getInstance(context)
    if (!enabled) {
        wm.cancelUniqueWork(WORK)
        return
    }
    val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS, 3, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
        .setInitialDelay(Dates.untilNext(6, 30), TimeUnit.MILLISECONDS)
        .build()
    wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.graph.sync.run()) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
}
