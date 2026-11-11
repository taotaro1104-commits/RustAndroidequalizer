package dev.rusteq

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EqApplication : Application() {
    private val settingsIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val externalEqSettings by lazy { dev.rusteq.audio.ExternalEqSettingsStore(this) }
    val externalPlayback by lazy { dev.rusteq.audio.ExternalPlaybackMonitor(this,externalEqSettings.state) }
    val player by lazy { PlayerController(this) }
    val playbackServiceRunning = MutableStateFlow(false)
    val systemEqState = MutableStateFlow(dev.rusteq.audio.SystemEqState())
    val requestedEffectSession = MutableStateFlow<Pair<Int, String>?>(null)

    fun flushSettingsInBackground() {
        settingsIo.launch {
            player.flushPendingSettings()
            if (!externalEqSettings.flush()) {
                systemEqState.update { it.copy(message = uiText(R.string.external_service_save_failed)) }
            }
        }
    }
}
