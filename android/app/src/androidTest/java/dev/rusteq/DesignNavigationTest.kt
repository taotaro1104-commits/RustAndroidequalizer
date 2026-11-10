package dev.rusteq

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
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
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(instrument.uiAutomation.rootInActiveWindow, label)?.let { return it }
            Thread.sleep(50)
        }
        error("Visible accessible control missing: $label")
    }

    private fun tap(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = find(instrument.uiAutomation.rootInActiveWindow, label)
            val bounds = Rect()
            node?.getBoundsInScreen(bounds)
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
            tap("空間")
            awaitNode("音の広がり")
            tap("ツール")
            awaitNode("設定を持ち歩く")
            tap("音づくり")
            tap("ボーカル")
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (app.player.state.value.presetTitle != "ボーカル" && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertEquals("ボーカル", app.player.state.value.presetTitle)
            assertEquals(10, app.player.state.value.config.bands.size)
            val selected = app.player.state.value.config
            reveal("バンドを詳しく調整")
            tap("バンドを詳しく調整")
            awaitNode("詳細調整を閉じる")
            tap("空間")
            tap("音づくり")
            assertEquals(selected, app.player.state.value.config)
            awaitNode("詳細調整を閉じる")
            awaitNode("イコライザーを有効にする")
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
            tap("再生リストを再生")
            awaitState("The retained queue must load again from the empty player") { app.player.state.value.loaded && !app.player.state.value.loading }
            assertEquals(1, app.player.state.value.queueSize)
        } finally {
            instrument.runOnMainSync { app.player.clearQueue(); app.player.stopPlayback(); activity.finish() }
            file.delete()
        }
    }
}
