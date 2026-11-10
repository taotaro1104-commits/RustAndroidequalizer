package dev.rusteq

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ExternalAppSelectionTest {
    @Test fun anUnselectedPlayerIsNotAttached() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val app = context.applicationContext as EqApplication
        val preferences = context.getSharedPreferences("external_eq", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val observed = AtomicInteger(0)
        val probe = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) == "dev.rusteq.fixture") {
                    observed.set(intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0))
                }
            }
        }
        ContextCompat.registerReceiver(context, probe, IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION), ContextCompat.RECEIVER_EXPORTED)
        preferences.edit().putStringSet("selected_packages", setOf("dev.rusteq.not_selected"))
            .putBoolean("enabled", true).putBoolean("resume_after_boot", true).commit()
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val fixture = Intent().setComponent(ComponentName("dev.rusteq.fixture", "dev.rusteq.fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue(message, predicate())
        }
        try {
            instrument.runOnMainSync { ContextCompat.startForegroundService(context, Intent(context, SystemEqService::class.java).setAction(SystemEqService.START)) }
            await("Listener starts") { app.systemEqState.value.running }
            context.startActivity(fixture)
            await("Fixture must really announce its audio session") { observed.get() > 0 }
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (SystemClock.elapsedRealtime() < deadline) {
                assertFalse("An unselected package must not be attached", app.systemEqState.value.sessions.any { it.player == "dev.rusteq.fixture" })
                Thread.sleep(50)
            }
        } finally {
            runCatching { context.startActivity(Intent(fixture).setAction("STOP")) }
            context.stopService(Intent(context, SystemEqService::class.java))
            context.unregisterReceiver(probe)
            instrument.runOnMainSync { activity.finish() }
            val editor = preferences.edit().clear()
            original.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
    }
}
