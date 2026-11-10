package dev.rusteq

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.rusteq.ui.AudioApp
import dev.rusteq.ui.AudioTheme

class MainActivity : ComponentActivity() {
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

    private fun acceptSessionRequest(intent: Intent) {
        if (intent.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        if (id > 0) (application as EqApplication).requestedEffectSession.value =
            id to (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: callingPackage ?: "外部プレーヤー")
    }
}
