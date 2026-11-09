package dev.rusteq

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.annotation.SuppressLint
import android.graphics.drawable.Icon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlaybackService : Service() {
    companion object {
        const val PREPARE = "dev.rusteq.PREPARE"
        const val PLAY = "dev.rusteq.PLAY"
        const val PAUSE = "dev.rusteq.PAUSE"
        const val STOP = "dev.rusteq.STOP"
        const val NEXT = "dev.rusteq.NEXT"
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL = "rusteq-playback"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = application as EqApplication
    private val player get() = runtime.player
    private lateinit var session: MediaSession
    private lateinit var wakeLock: PowerManager.WakeLock
    private var wakeAcquiredAt = 0L

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RustEQ:playback").apply { setReferenceCounted(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "音楽の再生", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "RustEQ").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { player.resumeFromNotification() }
                override fun onPause() { player.pause() }
                override fun onStop() { player.stopPlayback() }
                override fun onSeekTo(pos: Long) { player.seek(pos / 1000f) }
                override fun onSkipToNext() { player.nextTrack() }
                override fun onSkipToPrevious() { player.previousTrack() }
            })
            isActive = true
        }
        scope.launch {
            player.state.map { listOf(it.title, it.playing, it.loading, it.metrics[6].toInt(), it.metrics[13].toInt(), it.queueIndex, it.queueSize) }
                .distinctUntilChanged().collect {
                    if (runtime.playbackServiceRunning.value) {
                        val state = player.state.value
                        updateSession(state)
                        updateWakeLock(state.playing)
                        publishNotification(state)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            player.stopPlayback()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(player.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        runtime.playbackServiceRunning.value = true
        when (intent?.action) { PLAY -> player.resumeFromNotification(); PAUSE -> player.pause(); NEXT -> player.nextTrack() }
        return START_NOT_STICKY
    }

    private fun action(action: String): PendingIntent = PendingIntent.getForegroundService(this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(state: PlayerState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(dev.rusteq.R.drawable.ic_notification)
            .setContentTitle(state.title)
            .setContentText(if (state.loading) "読み込み中" else if (state.playing) "再生中" else "一時停止")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource("android", if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), if (state.playing) "一時停止" else "再生", action(if (state.playing) PAUSE else PLAY)).build())
        val hasNext = state.queueIndex < state.queueSize-1
        if (hasNext) builder.addAction(Notification.Action.Builder(Icon.createWithResource("android", android.R.drawable.ic_media_next), "次の曲", action(NEXT)).build())
        builder.addAction(Notification.Action.Builder(Icon.createWithResource("android", android.R.drawable.ic_menu_close_clear_cancel), "停止", action(STOP)).build())
        return builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*if (hasNext) intArrayOf(0, 1, 2) else intArrayOf(0, 1))).build()
    }

    private fun updateSession(state: PlayerState) {
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, state.title).putLong(MediaMetadata.METADATA_KEY_DURATION, (state.metrics[13]*1000).toLong()).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, (state.metrics[6]*1000).toLong(), if (state.playing) 1f else 0f).build())
    }

    override fun onBind(intent: Intent?): IBinder? = null
    // MediaSession notifications are exempt from POST_NOTIFICATIONS. No ordinary alerts are posted.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun publishNotification(state: PlayerState) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state)) }
    private fun updateWakeLock(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing && (!wakeLock.isHeld || now-wakeAcquiredAt > 5*60*1000L)) {
            wakeLock.acquire(10*60*1000L); wakeAcquiredAt = now
        } else if (!playing && wakeLock.isHeld) wakeLock.release()
    }
    override fun onDestroy() {
        runtime.playbackServiceRunning.value = false
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        session.release()
        player.pause()
        super.onDestroy()
    }
}
