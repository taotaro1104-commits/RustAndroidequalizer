package dev.rusteq

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ExternalMusicInfoTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as EqApplication
    private val fixture = "dev.rusteq.fixture"
    private val listener = "dev.rusteq/dev.rusteq.ExternalPlaybackListener"
    private fun shell(command: String) = instrument.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }
    private fun player() = Intent().setComponent(ComponentName(fixture, "$fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node==null) return null
        if (node.isVisibleToUser && (node.text?.toString()==label || node.contentDescription?.toString()==label)) return node
        for (i in 0 until node.childCount) find(node.getChild(i),label)?.let { return it }
        return null
    }
    private fun scroll(node: AccessibilityNodeInfo?): Boolean {
        if (node==null) return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
        return false
    }
    private fun click(label: String) {
        val deadline=SystemClock.elapsedRealtime()+60000
        while (SystemClock.elapsedRealtime()<deadline) {
            var node=find(instrument.uiAutomation.rootInActiveWindow,label)
            if (node!=null) {
                while (node!=null && !node.isClickable) node=node.parent
                assertTrue("Clickable control: $label",node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
                instrument.waitForIdleSync(); return
            }
            scroll(instrument.uiAutomation.rootInActiveWindow); Thread.sleep(150)
        }
        fail("Visible control missing: $label")
    }
    @android.annotation.SuppressLint("DiscouragedApi")
    @Test fun selectedExternalTrackAppearsInPlaybackDetails() {
        val old=app.externalEqSettings.state.value
        val regression=InstrumentationRegistry.getArguments().getString("regression_old")=="true"
        var activity: android.app.Activity?=null
        try {
            shell("am force-stop $fixture")
            app.externalEqSettings.update { it.copy(selectedPackages=setOf(fixture),enabled=false) }
            if (!regression) shell("cmd notification allow_listener $listener")
            context.startActivity(player().putExtra("silent",true).putExtra("publish_metadata",true))
            instrument.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
            try {
                val manager=context.getSystemService(MediaSessionManager::class.java)
                val end=SystemClock.elapsedRealtime()+20000
                var available=false
                while (!available && SystemClock.elapsedRealtime()<end) {
                    available=manager.getActiveSessions(null).any { it.packageName==fixture && it.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)=="External music test title" }
                    Thread.sleep(100)
                }
                assertTrue("The fixture must publish actual MediaSession metadata",available)
            } finally { instrument.uiAutomation.dropShellPermissionIdentity() }
            activity=instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun label(name:String)=context.getString(context.resources.getIdentifier(name,"string",context.packageName))
            Thread.sleep(500)
            click(label("ui_tab_tools")); click(label("ui_open_diagnostics"))
            val end=SystemClock.elapsedRealtime()+10000
            while (find(instrument.uiAutomation.rootInActiveWindow,"External music test title")==null && SystemClock.elapsedRealtime()<end) {
                scroll(instrument.uiAutomation.rootInActiveWindow); Thread.sleep(150)
            }
            assertNotNull("Playback details must show the selected external track title",find(instrument.uiAutomation.rootInActiveWindow,"External music test title"))
            assertNotNull(find(instrument.uiAutomation.rootInActiveWindow,"External test artist"))
        } finally {
            context.startActivity(player().setAction("STOP"))
            instrument.runOnMainSync { activity?.finish(); ExternalEqController.stop(context) }
            app.externalEqSettings.update { old }
            if (!regression) shell("cmd notification disallow_listener $listener")
        }
    }

    @Test fun missingAccessExplainsSetupAndOpensAndroidSettingsWithoutGranting() {
        val old=app.externalEqSettings.state.value
        var activity:android.app.Activity?=null
        try {
            shell("cmd notification disallow_listener $listener")
            app.externalEqSettings.update { it.copy(selectedPackages=setOf(fixture),enabled=false) }
            activity=instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click(context.getString(R.string.ui_tab_tools)); click(context.getString(R.string.ui_open_diagnostics))
            click(context.getString(R.string.external_info_allow))
            val end=SystemClock.elapsedRealtime()+10000
            var settingsVisible=false
            while (!settingsVisible && SystemClock.elapsedRealtime()<end) {
                settingsVisible=instrument.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.settings"
                Thread.sleep(100)
            }
            assertTrue("Opens Android notification access settings",settingsVisible)
            assertFalse("The app cannot grant notification access itself",app.externalPlayback.hasAccess())
        } finally {
            instrument.runOnMainSync { activity?.finish() }
            app.externalEqSettings.update { old }
            shell("input keyevent KEYCODE_BACK")
        }
    }
}
