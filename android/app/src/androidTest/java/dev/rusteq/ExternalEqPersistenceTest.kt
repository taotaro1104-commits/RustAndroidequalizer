package dev.rusteq

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rusteq.audio.ExternalEqSettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalEqPersistenceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as EqApplication
    private val fixture = "dev.rusteq.fixture"
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$message: ${app.systemEqState.value}", predicate())
    }

    @Test fun settingsSurviveStoreRecreationAndEmptySelectionDisables() {
        val saved = app.externalEqSettings.state.value
        try {
            app.externalEqSettings.update { it.copy(selectedPackages = setOf(fixture, "dev.missing.saved"), enabled = true, resumeAfterBoot = false) }
            val restored = ExternalEqSettingsStore(context).state.value
            assertEquals(setOf(fixture, "dev.missing.saved"), restored.selectedPackages)
            assertTrue(restored.shouldRun)
            assertFalse(restored.resumeAfterBoot)
            app.externalEqSettings.update { it.copy(selectedPackages = emptySet()) }
            assertFalse(app.externalEqSettings.state.value.enabled)
            assertFalse(ExternalEqSettingsStore(context).state.value.shouldRun)
        } finally { app.externalEqSettings.update { saved } }
    }

    @Test fun aLatePlayerSessionClearsTheWaitingMessage() {
        val saved = app.externalEqSettings.state.value
        app.externalEqSettings.update { it.copy(selectedPackages = setOf(fixture), enabled = false) }
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val player = Intent().setComponent(ComponentName(fixture, "$fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            instrument.runOnMainSync { ExternalEqController.connect(context, fixture) }
            await("Listener is waiting for the selected player") { app.systemEqState.value.running && app.systemEqState.value.message != null }
            context.startActivity(player)
            await("Player announces a compatible session") { app.systemEqState.value.sessions.any { it.player == fixture && it.connected } }
            assertNull("A connected player must not keep showing the old waiting error", app.systemEqState.value.message)
        } finally {
            runCatching { context.startActivity(Intent(player).setAction("STOP")) }
            instrument.runOnMainSync { ExternalEqController.stop(context); activity.finish() }
            await("Cleanup stops listener") { !app.systemEqState.value.running }
            app.externalEqSettings.update { saved }
        }
    }

    @Test fun liveSelectionReleasesEffectsAndTapConnectReattachesOnlySelectedApp() {
        val saved = app.externalEqSettings.state.value
        app.externalEqSettings.update { it.copy(selectedPackages = setOf("dev.other.saved"), enabled = false) }
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val player = Intent().setComponent(ComponentName(fixture, "$fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            instrument.runOnMainSync { ExternalEqController.start(context) }
            await("Listener starts") { app.systemEqState.value.running }
            context.startActivity(player)
            Thread.sleep(1000)
            assertFalse(app.systemEqState.value.sessions.any { it.player == fixture })
            instrument.runOnMainSync { app.externalEqSettings.update { it.copy(selectedPackages = setOf(fixture)) } }
            await("Selecting an already observed session attaches it") { app.systemEqState.value.sessions.any { it.player == fixture && it.connected } }
            val sessionId = app.systemEqState.value.sessions.first { it.player == fixture }.id
            instrument.runOnMainSync { app.externalEqSettings.update { it.copy(selectedPackages = setOf("dev.other.saved")) } }
            await("Deselect immediately releases its effect") { app.systemEqState.value.sessions.none { it.player == fixture } }
            instrument.runOnMainSync { ExternalEqController.connect(context, fixture, sessionId to fixture) }
            Thread.sleep(250)
            assertFalse("Connect cannot opt in an unselected app", app.systemEqState.value.sessions.any { it.player == fixture })
            instrument.runOnMainSync {
                app.externalEqSettings.update { it.copy(selectedPackages = setOf(fixture)) }
                ExternalEqController.stop(context)
            }
            await("Explicit stop stops the service") { !app.systemEqState.value.running }
            assertFalse(app.externalEqSettings.state.value.enabled)
            instrument.runOnMainSync { ExternalEqController.connect(context, fixture, sessionId to fixture) }
            await("Tap connect restarts and reattaches a known live session") { app.systemEqState.value.sessions.any { it.player == fixture && it.connected } }
            assertTrue(app.externalEqSettings.state.value.enabled)
            context.startActivity(Intent(player).setAction("STOP"))
            await("Close releases the selected session") { app.systemEqState.value.sessions.isEmpty() }
        } finally {
            runCatching { context.startActivity(Intent(player).setAction("STOP")) }
            instrument.runOnMainSync { ExternalEqController.stop(context); activity.finish() }
            await("Cleanup stops listener") { !app.systemEqState.value.running }
            app.externalEqSettings.update { saved }
        }
    }

    @Test fun restorationHonorsBootPreferenceAndExplicitStop() {
        val saved = app.externalEqSettings.state.value
        app.externalEqSettings.update { it.copy(enabled = false) }
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrument.runOnMainSync {
                app.externalEqSettings.update { it.copy(selectedPackages = setOf(fixture), enabled = true, resumeAfterBoot = false) }
                ExternalEqRestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            }
            Thread.sleep(300)
            assertFalse("Boot opt-out leaves listener stopped", app.systemEqState.value.running)
            instrument.runOnMainSync { ExternalEqRestoreReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)) }
            await("App update restores enabled listener even with boot opt-out") { app.systemEqState.value.running }
            context.stopService(Intent(context, SystemEqService::class.java))
            await("OS-style service destruction") { !app.systemEqState.value.running }
            assertTrue("Destruction preserves saved user intent", app.externalEqSettings.state.value.enabled)
            instrument.runOnMainSync { ExternalEqController.restore(context) }
            await("Visible app restores saved intent") { app.systemEqState.value.running }
            instrument.runOnMainSync { ExternalEqController.stop(context) }
            await("Explicit stop") { !app.systemEqState.value.running }
            assertFalse(app.externalEqSettings.state.value.enabled)
            instrument.runOnMainSync { ExternalEqRestoreReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)) }
            Thread.sleep(300)
            assertFalse("Update does not reenable an explicit stop", app.systemEqState.value.running)
            assertEquals(setOf(fixture), app.externalEqSettings.state.value.selectedPackages)
        } finally {
            instrument.runOnMainSync { ExternalEqController.stop(context); activity.finish() }
            await("Cleanup stops listener") { !app.systemEqState.value.running }
            app.externalEqSettings.update { saved }
        }
    }
}
