package dev.rusteq

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rusteq.audio.DiscoveryStatus
import dev.rusteq.audio.ExternalEqSettingsStore
import dev.rusteq.audio.AndroidSessionDiscovery
import dev.rusteq.audio.SelectedSessionSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class EnhancedSessionDetectionTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as EqApplication
    private val fixture = "dev.rusteq.fixture"
    private fun shell(command: String): String = instrument.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("$label: ${app.systemEqState.value}", condition())
    }
    private fun player() = Intent().setComponent(ComponentName(fixture, "$fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun startPlayer(silent: Boolean = false, multiple: Boolean = false) {
        context.startActivity(player().putExtra("silent", silent).putExtra("multiple", multiple))
        fun ready(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString()?.startsWith("Integration test player\nSession ") == true) return true
            for (index in 0 until node.childCount) if (ready(node.getChild(index))) return true
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (!ready(instrument.uiAutomation.rootInActiveWindow) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Wait for the fixture to create and play its AudioTrack before testing discovery", ready(instrument.uiAutomation.rootInActiveWindow))
    }

    private fun isolated(test: () -> Unit) {
        val saved = app.externalEqSettings.state.value
        val granted = context.checkSelfPermission(android.Manifest.permission.DUMP) == android.content.pm.PackageManager.PERMISSION_GRANTED
        instrument.runOnMainSync { ExternalEqController.stop(context) }
        await("Previous listener stops") { !app.systemEqState.value.running }
        app.externalEqSettings.update { it.copy(selectedPackages=setOf(fixture), enabled=false, enhancedDetection=true) }
        shell("pm revoke dev.rusteq android.permission.DUMP")
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { test() } finally {
            context.startActivity(player().setAction("STOP"))
            instrument.runOnMainSync { ExternalEqController.stop(context); activity.finish() }
            await("Cleanup stops listener") { !app.systemEqState.value.running }
            app.externalEqSettings.update { saved }
            shell("pm ${if(granted) "grant" else "revoke"} dev.rusteq android.permission.DUMP")
        }
    }

    @Test fun audioDumpIsReadableFromTheAppUidForASilentPlayer() = isolated {
        shell("pm grant dev.rusteq android.permission.DUMP")
        startPlayer(silent=true)
        val result = runBlocking { AndroidSessionDiscovery(context).scan(setOf(fixture)) }
        assertTrue("The normal app UID must read actual session IDs: $result", result is SelectedSessionSnapshot.Success && result.sessions.values.contains(fixture))
    }

    @Test fun unselectedSilentPlayerAndOptOutCannotKeepEffects() = isolated {
        shell("pm grant dev.rusteq android.permission.DUMP")
        startPlayer(silent=true)
        app.externalEqSettings.update { it.copy(selectedPackages=setOf("not.selected.player"), enabled=true) }
        instrument.runOnMainSync { ExternalEqController.start(context) }
        await("Unselected player is scanned without attachment") { app.systemEqState.value.discovery==DiscoveryStatus.READY }
        assertTrue(app.systemEqState.value.sessions.isEmpty())
        app.externalEqSettings.update { it.copy(selectedPackages=setOf(fixture)) }
        await("Selecting the already-playing silent app attaches it") { app.systemEqState.value.sessions.any { it.player==fixture && it.connected } }
        app.externalEqSettings.update { it.copy(selectedPackages=setOf("not.selected.player")) }
        await("Deselection releases discovery handles") { app.systemEqState.value.sessions.isEmpty() }
        app.externalEqSettings.update { it.copy(selectedPackages=setOf(fixture)) }
        await("Re-selection discovers it again") { app.systemEqState.value.sessions.any { it.connected } }
        app.externalEqSettings.update { it.copy(enhancedDetection=false) }
        await("Opt-out releases discovery-only sessions") { app.systemEqState.value.sessions.isEmpty() && app.systemEqState.value.discovery==DiscoveryStatus.OFF }
        instrument.runOnMainSync { ExternalEqController.stop(context) }
        Thread.sleep(3500)
        assertFalse("An old scan cannot restart the stopped listener", app.systemEqState.value.running)
        assertTrue(app.systemEqState.value.sessions.isEmpty())
    }

    @Test fun standardAnnouncedSessionSurvivesEnhancedDetectionOptOut() = isolated {
        shell("pm grant dev.rusteq android.permission.DUMP")
        instrument.runOnMainSync { ExternalEqController.start(context) }
        await("Listener starts") { app.systemEqState.value.running }
        startPlayer()
        await("Both discovery and announcement have observed the player") { app.systemEqState.value.discovery==DiscoveryStatus.READY && app.systemEqState.value.sessions.any { it.connected } }
        // Wait for the next scan too, ensuring discovery ownership overlaps.
        Thread.sleep(3500)
        app.externalEqSettings.update { it.copy(enhancedDetection=false) }
        await("Discovery is off") { app.systemEqState.value.discovery==DiscoveryStatus.OFF }
        assertTrue("The standard announcement still owns the effect", app.systemEqState.value.sessions.any { it.player==fixture && it.connected })
        app.externalEqSettings.update { it.copy(enhancedDetection=true) }
        await("Discovery resumes") { app.systemEqState.value.discovery==DiscoveryStatus.READY }
        val id = app.systemEqState.value.sessions.first { it.player==fixture }.id
        context.sendBroadcast(Intent(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, id)
            .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, fixture))
        Thread.sleep(300)
        assertTrue("CLOSE must preserve a still-playing discovered session", app.systemEqState.value.sessions.any { it.player==fixture && it.connected })
        context.startActivity(player().setAction("STOP"))
        await("CLOSE releases the remaining announced ownership") { app.systemEqState.value.sessions.isEmpty() }
    }

    @Test fun permissionCanBeGrantedAfterPlaybackStartsWithoutLosingSettings() = isolated {
        startPlayer(silent=true)
        instrument.runOnMainSync { ExternalEqController.start(context) }
        await("Missing permission is reported separately") { app.systemEqState.value.discovery==DiscoveryStatus.PERMISSION_REQUIRED }
        assertTrue(app.systemEqState.value.sessions.isEmpty())
        assertTrue(app.externalEqSettings.state.value.enabled)
        assertTrue("Opt-in persists across store recreation", ExternalEqSettingsStore(context).state.value.enhancedDetection)
        shell("pm grant dev.rusteq android.permission.DUMP")
        await("Granting permission discovers existing playback") { app.systemEqState.value.sessions.any { it.player==fixture && it.connected } }
    }

    @Test fun discoversAlreadyPlayingSilentSessionsAndReleasesOnRevocation() {
        val saved = app.externalEqSettings.state.value
        val preferences = context.getSharedPreferences("external_eq", Context.MODE_PRIVATE)
        val enhanced = preferences.getBoolean("enhanced_detection", false)
        var activity: MainActivity? = null
        try {
            instrument.runOnMainSync { ExternalEqController.stop(context) }
            await("Listener stops") { !app.systemEqState.value.running }
            shell("pm grant dev.rusteq android.permission.DUMP")
            startPlayer(silent=true, multiple=true)
            preferences.edit().putBoolean("enhanced_detection", true).commit()
            app.externalEqSettings.update { it.copy(selectedPackages=setOf(fixture), enabled=true) }
            activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            await("Both non-announcing sessions must gain real EQ control") { app.systemEqState.value.sessions.count { it.player==fixture && it.connected } == 2 }
            shell("pm revoke dev.rusteq android.permission.DUMP")
            await("Revoking access releases discovery effects") { app.systemEqState.value.sessions.none { it.player==fixture } }
            assertTrue("Permission loss preserves selected app intent", app.externalEqSettings.state.value.enabled)
            assertEquals(setOf(fixture), app.externalEqSettings.state.value.selectedPackages)
        } finally {
            context.startActivity(player().setAction("STOP"))
            instrument.runOnMainSync { ExternalEqController.stop(context); activity?.finish() }
            await("Cleanup stops listener") { !app.systemEqState.value.running }
            preferences.edit().putBoolean("enhanced_detection", enhanced).commit()
            app.externalEqSettings.update { saved }
            shell("pm revoke dev.rusteq android.permission.DUMP")
        }
    }
}
