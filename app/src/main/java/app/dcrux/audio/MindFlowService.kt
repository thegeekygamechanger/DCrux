package app.dcrux.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dcrux.MainActivity
import app.dcrux.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What the Mind Flow screen shows and changes. One instance for the whole app. */
object MindFlow {
    val engine = SoundEngine()
    val levels = MutableStateFlow<Map<SoundId, Float>>(emptyMap())
    val paused = MutableStateFlow(false)

    /** elapsedRealtime when the sleep timer ends, or 0. */
    val timerEnd = MutableStateFlow(0L)

    fun set(context: Context, id: SoundId, level: Float) {
        levels.update { if (level <= 0f) it - id else it + (id to level.coerceIn(0f, 1f)) }
        if (level > 0f) paused.value = false
        push(context)
    }

    fun setMix(context: Context, mix: Map<SoundId, Float>) {
        levels.value = mix
        paused.value = false
        push(context)
    }

    fun setTimer(minutes: Int) {
        val end = if (minutes <= 0) 0L else SystemClock.elapsedRealtime() + minutes * 60_000L
        timerEnd.value = end
        engine.stopAt = end
    }

    fun pause(context: Context) {
        if (levels.value.isEmpty() || paused.value) return
        paused.value = true
        command(context, MindFlowService.ACTION_REFRESH)
        engine.stop()
    }

    fun resume(context: Context) {
        if (levels.value.isEmpty()) return
        paused.value = false
        push(context)
    }

    fun stopAll(context: Context) {
        levels.value = emptyMap()
        paused.value = false
        timerEnd.value = 0; engine.stopAt = 0
        SoundId.entries.forEach { engine.levels[it] = 0f }
        if (!engine.isRunning) command(context, MindFlowService.ACTION_STOP)
    }

    private fun push(context: Context) {
        val now = levels.value
        SoundId.entries.forEach { id -> engine.levels[id] = now[id] ?: 0f }
        if (now.isNotEmpty() && !paused.value) {
            ContextCompat.startForegroundService(context, Intent(context, MindFlowService::class.java))
        } else if (now.isEmpty() && !engine.isRunning) {
            command(context, MindFlowService.ACTION_STOP)
        }
    }

    private fun command(context: Context, action: String) {
        if (MindFlowService.alive) context.startService(Intent(context, MindFlowService::class.java).setAction(action))
    }

    internal fun finished() {
        levels.value = emptyMap()
        paused.value = false
        timerEnd.value = 0; engine.stopAt = 0
        SoundId.entries.forEach { engine.levels[it] = 0f }
    }
}

/**
 * Keeps the sounds playing with the screen off. The notification is a media player:
 * play, pause and stop work from the notification shade, the lock screen and headset buttons.
 */
class MindFlowService : Service() {
    private lateinit var session: MediaSessionCompat

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> MindFlow.pause(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        session = MediaSessionCompat(this, "MindFlow").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = MindFlow.resume(this@MindFlowService)
                override fun onPause() = MindFlow.pause(this@MindFlowService)
                override fun onStop() = MindFlow.stopAll(this@MindFlowService)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                MindFlow.finished()
                MindFlow.engine.stop()
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { MindFlow.pause(this); publish(); return START_NOT_STICKY }
            ACTION_PLAY -> { MindFlow.resume(this); return START_NOT_STICKY }
            ACTION_REFRESH -> { publish(); return START_NOT_STICKY }
        }
        publish()
        if (!MindFlow.paused.value) {
            @Suppress("DEPRECATION")
            (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            MindFlow.engine.start {
                // Ended by pause: stay alive with the notification. Ended any other way: done.
                if (MindFlow.paused.value && MindFlow.levels.value.isNotEmpty()) publish()
                else { MindFlow.finished(); stopEverything() }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Pushes the current state to the media session and the notification. */
    private fun publish() {
        val playing = !MindFlow.paused.value
        val names = MindFlow.levels.value.keys.joinToString(", ") { it.title }.ifBlank { "Soothing sounds" }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Mind Flow")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, names)
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
                .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
                .build()
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(playing, names),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    override fun onDestroy() {
        alive = false
        MindFlow.engine.stop()
        @Suppress("DEPRECATION")
        (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocus(focusListener)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun action(code: Int, name: String) =
        PendingIntent.getService(this, code, Intent(this, MindFlowService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(playing: Boolean, names: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mind Flow", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dcrux)
            .setContentTitle("Mind Flow")
            .setContentText(names)
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause" else "Play", action(2, if (playing) ACTION_PAUSE else ACTION_PLAY))
            .addAction(R.drawable.ic_stop, "Stop", action(1, ACTION_STOP))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    companion object {
        @Volatile var alive = false
        const val CHANNEL = "mindflow"
        const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "app.dcrux.action.STOP_SOUNDS"
        const val ACTION_PAUSE = "app.dcrux.action.PAUSE_SOUNDS"
        const val ACTION_PLAY = "app.dcrux.action.PLAY_SOUNDS"
        const val ACTION_REFRESH = "app.dcrux.action.REFRESH_SOUNDS"
    }
}
