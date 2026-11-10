package dev.rusteq

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow

class EqApplication : Application() {
    val externalEqSettings by lazy { dev.rusteq.audio.ExternalEqSettingsStore(this) }
    val player by lazy { PlayerController(this) }
    val playbackServiceRunning = MutableStateFlow(false)
    val systemEqState = MutableStateFlow(dev.rusteq.audio.SystemEqState())
    val requestedEffectSession = MutableStateFlow<Pair<Int, String>?>(null)
}
