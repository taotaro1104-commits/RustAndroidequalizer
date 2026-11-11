package dev.rusteq

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.os.Build
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DesignNavigationTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) find(node.getChild(i), label)?.let { return it }
        return null
    }

    private fun awaitNode(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + if (Build.VERSION.SDK_INT == 33) 60000 else 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(instrument.uiAutomation.rootInActiveWindow, label)?.let { return it }
            Thread.sleep(50)
        }
        val visible = mutableListOf<String>()
        fun describe(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (!node.text.isNullOrBlank()) visible += node.text.toString() + " " + Rect().also(node::getBoundsInScreen)
            for (index in 0 until node.childCount) describe(node.getChild(index))
        }
        describe(instrument.uiAutomation.rootInActiveWindow)
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrument.targetContext.getExternalFilesDir(null), "external-picker-failure.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        error("Visible accessible control missing: $label. Visible: $visible")
    }

    private fun tap(label: String) {
        val deadline = SystemClock.elapsedRealtime() + if (Build.VERSION.SDK_INT == 33) 60000 else 5000
        var previous = Rect()
        var stableSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = find(instrument.uiAutomation.rootInActiveWindow, label)
            val bounds = Rect()
            node?.getBoundsInScreen(bounds)
            if (bounds != previous) {
                previous = Rect(bounds)
                stableSince = SystemClock.elapsedRealtime()
            }
            // A fling can leave an accessible node visible while its touch
            // coordinates are still moving. Wait for the actual target to settle.
            if (SystemClock.elapsedRealtime() - stableSince < 250) { Thread.sleep(50); continue }
            if (node?.isVisibleToUser == true && !bounds.isEmpty) {
                // Exercise the actual touch target, including Compose hit testing.
                val time = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try {
                    assertTrue(instrument.uiAutomation.injectInputEvent(down, true))
                    assertTrue(instrument.uiAutomation.injectInputEvent(up, true))
                } finally { down.recycle(); up.recycle() }
                instrument.waitForIdleSync()
                return
            }
            Thread.sleep(50)
        }
        fail("Control must be clickable: $label")
    }

    private fun reveal(label: String) {
        val metrics = instrument.targetContext.resources.displayMetrics
        val viewport = Rect(0, (metrics.heightPixels * .15f).toInt(), metrics.widthPixels, (metrics.heightPixels * .85f).toInt())
        repeat(8) {
            val root = instrument.uiAutomation.rootInActiveWindow
            val target = find(root, label)
            val bounds = Rect().also { target?.getBoundsInScreen(it) }
            if (target?.isVisibleToUser == true && !bounds.isEmpty && viewport.contains(bounds)) return
            // Swipe the page margin so the editable graph does not capture the drag.
            val x = 10f * metrics.density
            val start = metrics.heightPixels * .75f
            val end = metrics.heightPixels * .4f
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int, y: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(instrument.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN, start)
            for (step in 1..12) { Thread.sleep(20); inject(MotionEvent.ACTION_MOVE, start + (end - start) * step / 12f) }
            inject(MotionEvent.ACTION_UP, end)
            instrument.waitForIdleSync()
        }
        awaitNode(label)
    }

    private fun awaitState(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }

    @Test fun navigationAndVisualPresetSelectionPreserveSoundSettings() {
        val context = instrument.targetContext
        val app = context.applicationContext as EqApplication
        val original = app.player.state.value.config
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            tap(context.getString(R.string.ui_tab_space))
            awaitNode(context.getString(R.string.ui_space_heading))
            tap(context.getString(R.string.ui_tab_tools))
            awaitNode(context.getString(R.string.ui_tools_heading))
            tap(context.getString(R.string.ui_tab_sound))
            tap(context.getString(R.string.ui_preset_vocal))
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (app.player.state.value.presetTitle != "ボーカル" && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertEquals("ボーカル", app.player.state.value.presetTitle)
            assertEquals(10, app.player.state.value.config.bands.size)
            val selected = app.player.state.value.config
            reveal(context.getString(R.string.ui_open_details))
            tap(context.getString(R.string.ui_open_details))
            awaitNode(context.getString(R.string.ui_close_details))
            tap(context.getString(R.string.ui_tab_space))
            tap(context.getString(R.string.ui_tab_sound))
            assertEquals(selected, app.player.state.value.config)
            awaitNode(context.getString(R.string.ui_close_details))
            awaitNode(context.getString(R.string.ui_enable_eq))
        } finally {
            instrument.runOnMainSync { app.player.configure { original }; activity.finish() }
        }
    }

    @Test fun unloadedPlayerCanResumeItsSavedQueue() {
        val context = instrument.targetContext
        val app = context.applicationContext as EqApplication
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val file = File(context.cacheDir, "design-queue.wav")
        instrument.context.assets.open("seek-ramp.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            instrument.runOnMainSync { app.player.loadQueue(listOf(Uri.fromFile(file))) }
            awaitState("Queue source must load") { app.player.state.value.loaded && !app.player.state.value.loading }
            instrument.runOnMainSync { app.player.stopPlayback() }
            awaitState("Source must stop while retaining the queue") { !app.player.state.value.loaded && !app.playbackServiceRunning.value }
            tap(context.getString(R.string.ui_resume_queue))
            awaitState("The retained queue must load again from the empty player") { app.player.state.value.loaded && !app.player.state.value.loading }
            assertEquals(1, app.player.state.value.queueSize)
        } finally {
            instrument.runOnMainSync { app.player.clearQueue(); app.player.stopPlayback(); activity.finish() }
            file.delete()
        }
    }

    @android.annotation.SuppressLint("DiscouragedApi") // This regression also runs against the previous APK's resource table.
    @Test fun connectDoesNotOpenPlayerIfListenerCannotStart() {
        val context = instrument.targetContext
        val app = context.applicationContext as EqApplication
        fun label(name: String) = context.getString(context.resources.getIdentifier(name, "string", context.packageName))
        val saved = app.externalEqSettings.state.value
        val component = android.content.ComponentName(context, SystemEqService::class.java)
        val manager = context.packageManager
        val originalComponentState = manager.getComponentEnabledSetting(component)
        val player = Intent().setComponent(android.content.ComponentName("dev.rusteq.fixture", "dev.rusteq.fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val announced = java.util.concurrent.atomic.AtomicBoolean(false)
        val probe = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) {
                if (intent?.getStringExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME) == "dev.rusteq.fixture") announced.set(true)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, probe,
            android.content.IntentFilter(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        instrument.runOnMainSync { ExternalEqController.stop(context) }
        awaitState("Previous listener must stop") { !app.systemEqState.value.running }
        app.externalEqSettings.update { it.copy(selectedPackages = setOf("dev.rusteq.fixture"), enabled = false) }
        if (Build.VERSION.SDK_INT >= 33) instrument.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            tap(label("ui_tab_tools"))
            reveal(label("external_ui_connect"))
            manager.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            tap(label("external_ui_connect"))
            assertNotNull("A failed listener start must be reported", app.systemEqState.value.message)
            val until = SystemClock.elapsedRealtime() + 2500
            while (SystemClock.elapsedRealtime() < until) {
                assertFalse("A player must not open when the EQ listener cannot start", announced.get())
                Thread.sleep(30)
            }
            assertFalse(app.systemEqState.value.running)
            assertEquals(setOf("dev.rusteq.fixture"), app.externalEqSettings.state.value.selectedPackages)
        } finally {
            runCatching { context.startActivity(Intent(player).setAction("STOP")) }
            context.unregisterReceiver(probe)
            manager.setComponentEnabledSetting(component, originalComponentState, android.content.pm.PackageManager.DONT_KILL_APP)
            instrument.runOnMainSync { ExternalEqController.stop(context); activity.finish() }
            app.externalEqSettings.update { saved }
        }
    }

    @Test fun targetPickerSavesOnlyOnSaveAndConnectLaunchesSelectedPlayer() {
        val context = instrument.targetContext
        val app = context.applicationContext as EqApplication
        val original = app.externalEqSettings.state.value
        app.externalEqSettings.update { it.copy(selectedPackages = emptySet(), enabled = false) }
        if (Build.VERSION.SDK_INT >= 33) instrument.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val player = Intent().setComponent(android.content.ComponentName("dev.rusteq.fixture", "dev.rusteq.fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun editNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (index in 0 until node.childCount) editNode(node.getChild(index))?.let { return it }
            return null
        }
        fun selectFixture() {
            tap(context.getString(R.string.external_ui_choose_apps))
            awaitNode(context.getString(R.string.external_ui_search_apps))
            val editor = editNode(instrument.uiAutomation.rootInActiveWindow) ?: error("App search must be editable")
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "dev.rusteq.fixture")
            }))
            tap("Rust EQ test player")
        }
        try {
            tap(context.getString(R.string.ui_tab_tools))
            reveal(context.getString(R.string.external_ui_choose_apps))
            selectFixture()
            tap(context.getString(R.string.external_ui_cancel))
            assertTrue("Cancel discards draft selection", app.externalEqSettings.state.value.selectedPackages.isEmpty())
            selectFixture()
            tap(context.getString(R.string.external_ui_save))
            awaitState("Picker saves the selected package") { app.externalEqSettings.state.value.selectedPackages == setOf("dev.rusteq.fixture") }
            assertFalse("Choosing apps alone does not silently enable EQ", app.externalEqSettings.state.value.enabled)
            reveal(context.getString(R.string.external_ui_connect))
            tap(context.getString(R.string.external_ui_connect))
            awaitState("Connect starts the listener and attaches the selected app") {
                app.systemEqState.value.running && app.systemEqState.value.sessions.any { it.player == "dev.rusteq.fixture" && it.connected }
            }
            awaitNode("Integration test player\nSession " + app.systemEqState.value.sessions.first { it.player == "dev.rusteq.fixture" }.id)
        } finally {
            runCatching { context.startActivity(Intent(player).setAction("STOP")) }
            instrument.runOnMainSync { ExternalEqController.stop(context); activity.finish() }
            awaitState("Cleanup stops target listener") { !app.systemEqState.value.running }
            app.externalEqSettings.update { original }
        }
    }
}
