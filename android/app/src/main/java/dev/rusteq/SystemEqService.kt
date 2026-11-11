package dev.rusteq

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.rusteq.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** User-started, opt-in listener. Only cooperating players' announced sessions
 * are attached. A notification and explicit stop action remain available. */
class SystemEqService : Service() {
    companion object {
        const val START = "dev.rusteq.SESSION_EQ_START"
        const val STOP = "dev.rusteq.SESSION_EQ_STOP"
        const val ATTACH = "dev.rusteq.SESSION_EQ_ATTACH"
        const val CONNECT_PACKAGE = "dev.rusteq.SESSION_EQ_CONNECT_PACKAGE"
        const val EXTRA_TARGET_PACKAGE = "dev.rusteq.TARGET_PACKAGE"
        const val NOTIFICATION_ID = 1002
        private const val CHANNEL = "rusteq-external-eq"
        private const val MAX_OBSERVED_SESSIONS = 32
    }
    private val runtime get() = application as EqApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private lateinit var equalizers: SessionEqualizers
    private var started = false
    private var waitingPackage: String? = null
    private val observedSessions = linkedMapOf<Int, String>()
    private val settings get() = runtime.externalEqSettings.state.value
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
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (started && settings.shouldRun) observeSession(id, player)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    // CLOSE must also work after a package was deselected.
                    if (observedSessions[id] == player) observedSessions.remove(id)
                    equalizers.close(id, player)
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        updateNotificationChannel()
        equalizers = SessionEqualizers { sessions ->
            val connected = waitingPackage?.let { player -> sessions.any { it.player == player && it.connected } } == true
            runtime.systemEqState.value = runtime.systemEqState.value.copy(sessions = sessions,
                message = if (connected) null else runtime.systemEqState.value.message)
            if (connected) waitingPackage = null
            publishNotification()
        }
        readConfig()
        // Exported because cooperating players belong to other UIDs. Reject
        // invalid/zero IDs and cap resources; reported package is only a label.
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }, ContextCompat.RECEIVER_EXPORTED)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        scope.launch {
            runtime.externalEqSettings.state.collect { value ->
                if (waitingPackage?.let { it !in value.selectedPackages } == true) clearWaitingMessage()
                equalizers.retainPackages(if (value.shouldRun) value.selectedPackages else emptySet())
                if (started) {
                    if (!value.shouldRun) stopListener()
                    else observedSessions.toMap().forEach { (id, player) ->
                        if (player in value.selectedPackages) equalizers.open(id, player)
                    }
                }
            }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannel()
        publishNotification()
    }
    private fun updateNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.service_external_eq_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun publishNotification() {
        if (runtime.systemEqState.value.running && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }
    private fun readConfig() {
        equalizers.update(SoundSettingsStore(preferences).read().config)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            runtime.externalEqSettings.update { it.copy(enabled = false) }
            stopListener()
            return START_NOT_STICKY
        }
        if (!settings.shouldRun) {
            stopListener()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            startForeground(NOTIFICATION_ID, notification(), type)
        } catch (error: Exception) {
            runtime.systemEqState.value = runtime.systemEqState.value.copy(message = error.asUiText(R.string.external_service_start_failed))
            stopListener()
            return START_NOT_STICKY
        }
        started = true
        waitingPackage = null
        runtime.systemEqState.value = runtime.systemEqState.value.copy(running = true, message = null)
        equalizers.retainPackages(settings.selectedPackages)
        when (intent?.action) {
            ATTACH -> {
                val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                val player = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
                if (player !in settings.selectedPackages || !observeSession(id, player)) {
                    setMessage(uiText(R.string.external_service_invalid_session))
                }
            }
            CONNECT_PACKAGE -> connectPackage(intent)
        }
        return START_STICKY
    }

    private fun setMessage(message: UiText) {
        runtime.systemEqState.value = runtime.systemEqState.value.copy(message = message)
    }

    /** Broadcast package labels are compatibility hints, not authenticated sender identities. */
    private fun observeSession(id: Int, player: String, attach: Boolean = true): Boolean {
        if (id <= 0 || player.isBlank() || player.length > 200 || player == packageName) return false
        if (observedSessions[id] != player) {
            if (id !in observedSessions && observedSessions.size >= MAX_OBSERVED_SESSIONS) {
                // Prefer selected sessions when the bounded observation cache is full.
                val removable = observedSessions.entries.firstOrNull { it.value !in settings.selectedPackages }
                if (removable == null) return false
                observedSessions.remove(removable.key)
                equalizers.close(removable.key, removable.value)
            }
            observedSessions[id]?.let { equalizers.close(id, it) }
            observedSessions[id] = player
        }
        return !attach || player !in settings.selectedPackages || equalizers.open(id, player)
    }

    private fun connectPackage(intent: Intent) {
        val player = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        if (player !in settings.selectedPackages) {
            setMessage(uiText(R.string.external_service_not_selected))
            return
        }
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        if (id > 0 && intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) == player) {
            observeSession(id, player, attach = false)
        }
        val matching = observedSessions.filterValues { it == player }.keys.toList()
        if (matching.isEmpty()) {
            waitingPackage = player
            setMessage(uiText(R.string.external_service_waiting))
        } else if (matching.map { equalizers.reconnect(it, player) }.any { !it }) {
            setMessage(uiText(R.string.external_service_invalid_session))
        }
    }

    private fun stopListener() {
        clearWaitingMessage()
        started = false
        runtime.systemEqState.value = runtime.systemEqState.value.copy(running = false)
        equalizers.release()
        observedSessions.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    private fun clearWaitingMessage() {
        waitingPackage = null
        if (runtime.systemEqState.value.message == uiText(R.string.external_service_waiting)) {
            runtime.systemEqState.value = runtime.systemEqState.value.copy(message = null)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SystemEqService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_external_eq_title))
            .setContentText(getString(R.string.service_external_eq_sessions, runtime.systemEqState.value.sessions.count { it.connected }))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.service_stop), stop).build()).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        started = false
        runtime.systemEqState.value = runtime.systemEqState.value.copy(running = false, sessions = emptyList())
        unregisterReceiver(receiver)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        scope.cancel(); equalizers.release(); observedSessions.clear()
        super.onDestroy()
    }
}
