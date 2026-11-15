package app.dcrux.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dcrux.MainActivity
import app.dcrux.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts AI tasks in flight. While there is at least one, a small foreground service keeps
 * the app running, so switching to another app does not freeze it and break the request.
 */
object AiWork {
    internal val active = AtomicInteger(0)

    fun begin(context: Context) {
        if (active.incrementAndGet() == 1) {
            // Not allowed when the app itself is in the background (the daily sync): that work
            // is already kept alive by the system's job scheduler, so failing here is fine.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AiWorkService::class.java)) }
        }
    }

    fun end(context: Context) {
        if (active.decrementAndGet() <= 0) {
            active.set(0)
            runCatching { context.startService(Intent(context, AiWorkService::class.java).setAction(AiWorkService.ACTION_DONE)) }
        }
    }
}

class AiWorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private val handler = Handler(Looper.getMainLooper())
    private var latestStart = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start is answered with startForeground straight away; Android requires it.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        latestStart = startId
        handler.removeCallbacksAndMessages(null)
        if (AiWork.active.get() <= 0) {
            // Tasks often follow one another (a page of headlines, then the next). Wait a moment,
            // and stop only if nothing new has begun; stopSelf(id) does nothing when a newer
            // start is already on its way, so a fresh task can never find the service half-stopped.
            handler.postDelayed({
                if (AiWork.active.get() <= 0) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(latestStart)
                }
            }, 2500)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Crux AI at work", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dcrux)
            .setContentTitle("DCrux is writing")
            .setContentText("Your summary or test will be ready when you come back")
            .setContentIntent(open)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        const val CHANNEL = "ai_work"
        const val NOTIFICATION_ID = 42
        const val ACTION_DONE = "app.dcrux.action.AI_DONE"
    }
}
