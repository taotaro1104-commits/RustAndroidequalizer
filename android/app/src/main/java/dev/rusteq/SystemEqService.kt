package dev.rusteq

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.rusteq.audio.*
import kotlinx.coroutines.*

/** User-started, opt-in listener. Only cooperating players' announced sessions
 * are attached. A notification and explicit stop action remain available. */
class SystemEqService : Service() {
    companion object {
        const val START = "dev.rusteq.SESSION_EQ_START"
        const val STOP = "dev.rusteq.SESSION_EQ_STOP"
        const val ATTACH = "dev.rusteq.SESSION_EQ_ATTACH"
        const val NOTIFICATION_ID = 1002
        private const val CHANNEL = "rusteq-external-eq"
    }
    private val runtime get() = application as EqApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private lateinit var equalizers: SessionEqualizers
    private val preferences by lazy { getSharedPreferences("eq", MODE_PRIVATE) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last") {
            updateJob?.cancel()
            updateJob = scope.launch { delay(150); readConfig() }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            val player = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: return
            if (player == packageName || id <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> equalizers.open(id, player)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> equalizers.close(id, player)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "他アプリ EQ の接続", NotificationManager.IMPORTANCE_LOW))
        equalizers = SessionEqualizers { sessions ->
            runtime.systemEqState.value = runtime.systemEqState.value.copy(sessions = sessions)
            if (runtime.systemEqState.value.running && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        }
        readConfig()
        // Exported because cooperating players belong to other UIDs. Reject
        // invalid/zero IDs and cap resources; reported package is only a label.
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }, ContextCompat.RECEIVER_EXPORTED)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }
    private fun readConfig() {
        equalizers.update(runCatching { EqConfig.fromJson(preferences.getString("last", "")!!) }.getOrDefault(EqConfig()))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        startForeground(NOTIFICATION_ID, notification(), type)
        runtime.systemEqState.value = runtime.systemEqState.value.copy(running = true, message = null)
        if (intent?.action == ATTACH) {
            val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            val player = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
            if (!equalizers.open(id, player)) runtime.systemEqState.value = runtime.systemEqState.value.copy(message = "有効な session ID を受け取れませんでした")
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SystemEqService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("他アプリ EQ · 対応セッションのみ")
            .setContentText("${runtime.systemEqState.value.sessions.size} セッション · 全アプリへの適用は保証されません")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_menu_close_clear_cancel), "停止", stop).build()).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        runtime.systemEqState.value = SystemEqState()
        unregisterReceiver(receiver)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        scope.cancel(); equalizers.release()
        super.onDestroy()
    }
}
