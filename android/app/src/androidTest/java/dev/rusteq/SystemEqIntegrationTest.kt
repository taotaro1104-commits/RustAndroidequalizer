package dev.rusteq

import android.content.ComponentName
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rusteq.audio.SessionEqualizers
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemEqIntegrationTest {
    @Test fun zeroSessionIsRejected() {
        val manager = SessionEqualizers { }
        try { assertFalse(manager.open(0, "fixture")); assertFalse(manager.open(-1, "fixture")) }
        finally { manager.release() }
    }
    @Test fun separatePlayerUidSessionIsAttachedBypassedAndReleased() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val app = context.applicationContext as EqApplication
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val original = app.player.state.value.config
        fun waitFor(message: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime()+15000
            while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
            assertTrue("$message: ${app.systemEqState.value}", predicate())
        }
        val player = Intent().setComponent(ComponentName("dev.rusteq.fixture", "dev.rusteq.fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            instrument.runOnMainSync { app.player.configure { it.copy(bypass = false) }; ContextCompat.startForegroundService(context, Intent(context, SystemEqService::class.java).setAction(SystemEqService.START)) }
            waitFor("Session EQ service starts") { app.systemEqState.value.running }
            try { context.startActivity(player) } catch (e: android.content.ActivityNotFoundException) { assumeNoException("Install :session-player:assembleDebug fixture first", e) }
            waitFor("External player must be processed") { app.systemEqState.value.sessions.any { it.player == "dev.rusteq.fixture" && it.bands > 0 && it.status.startsWith("適用中") } }
            instrument.runOnMainSync { app.player.configure { it.copy(bypass = true) } }
            waitFor("Master bypass reaches the external session") { app.systemEqState.value.sessions.any { it.status == "バイパス" } }
            context.startActivity(Intent(player).setAction("STOP"))
            waitFor("External close releases the effect") { app.systemEqState.value.sessions.isEmpty() }
            context.stopService(Intent(context, SystemEqService::class.java))
            waitFor("Stop releases the listener") { !app.systemEqState.value.running }
        } finally {
            runCatching { context.startActivity(Intent(player).setAction("STOP")) }
            context.stopService(Intent(context, SystemEqService::class.java))
            instrument.runOnMainSync { app.player.configure { original }; activity.finish() }
        }
    }
}
