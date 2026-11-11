package dev.rusteq

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.rusteq.ui.AudioApp
import dev.rusteq.ui.AudioTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val UNKNOWN_EXTERNAL_PLAYER = "<external-player>"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptSessionRequest(intent)
        val player = (application as EqApplication).player
        setContent { AudioTheme { AudioApp(player) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSessionRequest(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as EqApplication).player.setForeground(true)
        ExternalEqController.restore(this)
        (application as EqApplication).externalPlayback.refresh()
    }

    override fun onStop() {
        val runtime = application as EqApplication
        runtime.player.setForeground(false)
        runtime.flushSettingsInBackground()
        super.onStop()
    }

    private fun acceptSessionRequest(intent: Intent) {
        if (intent.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        if (id > 0) (application as EqApplication).requestedEffectSession.value =
            id to (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: callingPackage ?: UNKNOWN_EXTERNAL_PLAYER)
    }
}
