package dev.rusteq

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    private var diagnostic: () -> String = { "" }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime()+15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$message: ${diagnostic()}", condition())
    }

    @Test fun backgroundPlaybackNotificationTransportAndQueueWork() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val runtime = context.applicationContext as EqApplication
        diagnostic = { val s = runtime.player.state.value; "service=${runtime.playbackServiceRunning.value}, playing=${s.playing}, loaded=${s.loaded}, loading=${s.loading}, error=${s.message}, metrics=${s.metrics}" }
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val files = listOf("queue-one.wav", "queue-two.wav").map { name ->
            File(context.cacheDir, name).also { file -> instrument.context.assets.open("seek-ramp.wav").use { input -> file.outputStream().use { input.copyTo(it) } } }
        }
        try {
            instrument.runOnMainSync { runtime.player.demo() }
            await("Foreground playback must start") { runtime.playbackServiceRunning.value && runtime.player.state.value.playing && runtime.player.state.value.metrics[6] > .05f }
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == PlaybackService.NOTIFICATION_ID }
            assertNotNull("Media notification must be posted", notification)
            assertTrue(notification!!.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            val before = runtime.player.state.value.metrics[6]
            instrument.runOnMainSync { activity.moveTaskToBack(true) }
            await("Audio must continue when the Activity is in the background") { runtime.player.state.value.metrics[6] > before+.15f }
            context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.PAUSE))
            await("Notification pause must stop transport") { !runtime.player.state.value.playing }
            context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.PLAY))
            await("Notification play must resume transport") { runtime.player.state.value.playing }
            instrument.runOnMainSync { runtime.player.loadQueue(files.map(Uri::fromFile)) }
            await("Queue must advance automatically to the second file") { runtime.player.state.value.queueIndex == 1 && !runtime.player.state.value.loading }
            instrument.runOnMainSync { runtime.player.seek(.1f) }
            context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.STOP))
            await("Stop must release the foreground service and native source") { !runtime.playbackServiceRunning.value && !runtime.player.state.value.loaded }
            Thread.sleep(300)
            assertFalse("A pending seek must not restart playback after stop", runtime.player.state.value.loaded || runtime.player.state.value.playing || runtime.playbackServiceRunning.value)
        } finally {
            instrument.runOnMainSync { runtime.player.clearQueue(); runtime.player.stopPlayback(); activity.finish() }
            files.forEach(File::delete)
        }
    }
}
