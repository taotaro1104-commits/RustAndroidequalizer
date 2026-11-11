package dev.rusteq

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rusteq.audio.ExternalPlaybackKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** Real MediaSessions in a separate UID; the EQ process never borrows shell permissions. */
@RunWith(AndroidJUnit4::class)
class ExternalPlaybackMonitorTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val app get()=context.applicationContext as EqApplication
    private val fixture="dev.rusteq.fixture"
    private val listener="dev.rusteq/dev.rusteq.ExternalPlaybackListener"
    private fun shell(command:String)=instrument.uiAutomation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }
    private fun player(action:String?=null)=Intent(action).setComponent(ComponentName(fixture,"$fixture.SessionPlayer")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun await(message:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+20000
        while (!condition() && SystemClock.elapsedRealtime()<end) Thread.sleep(100)
        assertTrue(message,condition())
    }
    private fun withPlayer(block:()->Unit) {
        val old=app.externalEqSettings.state.value
        try {
            shell("am force-stop $fixture")
            app.externalEqSettings.update { it.copy(selectedPackages=setOf(fixture),enabled=false) }
            shell("cmd notification allow_listener $listener")
            context.startActivity(player().putExtra("silent",true).putExtra("publish_metadata",true))
            app.externalPlayback.refresh()
            await("Selected external MediaSession title") { app.externalPlayback.state.value.tracks.singleOrNull()?.title=="External music test title" }
            block()
        } finally {
            context.startActivity(player("STOP"))
            shell("am force-stop $fixture")
            shell("cmd notification disallow_listener $listener")
            app.externalEqSettings.update { old }
            app.externalPlayback.refresh()
        }
    }
    @Test fun callbacksUpdateTitlePauseResumeAndRemoveDestroyedSession()=withPlayer {
        context.startActivity(player("METADATA").putExtra("metadata_title","Changed external title"))
        await("Metadata callback updates title") { app.externalPlayback.state.value.tracks.singleOrNull()?.title=="Changed external title" }
        context.startActivity(player("PAUSE"))
        await("Pause callback") { app.externalPlayback.state.value.tracks.singleOrNull()?.kind==ExternalPlaybackKind.PAUSED }
        val paused=app.externalPlayback.state.value.tracks.single()
        assertEquals(20000L,paused.positionAt(SystemClock.elapsedRealtime()))
        assertEquals(paused.positionAt(100000),paused.positionAt(200000))
        context.startActivity(player("RESUME"))
        await("Resume callback") { app.externalPlayback.state.value.tracks.singleOrNull()?.kind==ExternalPlaybackKind.PLAYING }
        context.startActivity(player("STOP"))
        await("Destroyed session clears stale title") { app.externalPlayback.state.value.tracks.isEmpty() }
    }
    @Test fun selectionChangeClearsTrackAndReselectReadsLiveMetadata()=withPlayer {
        app.externalEqSettings.update { it.copy(selectedPackages=setOf("com.google.android.apps.youtube.music",context.packageName)) }
        await("Unselected fixture is excluded, own app never included") { app.externalPlayback.state.value.tracks.isEmpty() && !app.externalPlayback.state.value.refreshing }
        context.startActivity(player("METADATA").putExtra("metadata_title","New title while excluded"))
        Thread.sleep(300)
        assertTrue(app.externalPlayback.state.value.tracks.isEmpty())
        app.externalEqSettings.update { it.copy(selectedPackages=setOf(fixture)) }
        await("Reselection reads current metadata") { app.externalPlayback.state.value.tracks.singleOrNull()?.title=="New title while excluded" }
    }
    @Test fun revokeRegrantAndReconnectClearAndRecoverLiveTrack()=withPlayer {
        shell("cmd notification disallow_listener $listener")
        app.externalPlayback.refresh()
        await("Revoked access clears metadata") { !app.externalPlayback.state.value.accessGranted && app.externalPlayback.state.value.tracks.isEmpty() }
        shell("cmd notification allow_listener $listener")
        app.externalPlayback.refresh()
        await("Regrant recovers live session") { app.externalPlayback.state.value.tracks.size==1 }
        app.externalPlayback.disconnected()
        await("Disconnect clears callbacks and tracks") { app.externalPlayback.state.value.tracks.isEmpty() }
        app.externalPlayback.refresh()
        await("Reconnect recovers current metadata") { app.externalPlayback.state.value.tracks.singleOrNull()?.title=="External music test title" }
    }
}
