package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test

class ExternalTrackTest {
    private fun track(pkg:String="com.google.android.apps.youtube.music", kind:ExternalPlaybackKind=ExternalPlaybackKind.PLAYING, updated:Long=1000)=
        ExternalTrack(pkg,"Song","Artist","Album",125000,15000,updated,1f,kind,false)
    @Test fun youtubeMusicIsDistinctFromYoutubeAndOnlySelectedAppsAppear() {
        val music=track()
        assertEquals(listOf(music),preferredExternalTracks(listOf(music,track("com.google.android.youtube"),track("dev.rusteq")),setOf(music.packageName,"dev.rusteq"),"dev.rusteq"))
    }
    @Test fun activePlayingSessionWinsOverANewerPausedSession() {
        val playing=track(updated=1000)
        assertEquals(listOf(playing),preferredExternalTracks(listOf(track(kind=ExternalPlaybackKind.PAUSED,updated=3000),playing),setOf(playing.packageName),"dev.rusteq"))
    }
    @Test fun stoppedSessionsDoNotLeaveStaleSongInformation() {
        assertTrue(preferredExternalTracks(listOf(track(kind=ExternalPlaybackKind.STOPPED)),setOf("com.google.android.apps.youtube.music"),"dev.rusteq").isEmpty())
    }
    @Test fun playingPositionAdvancesAndClampsToDuration() {
        assertEquals(17000L,track().positionAt(3000))
        assertEquals(125000L,track().positionAt(200000))
        assertEquals(15000L,track().positionAt(500))
    }
    @Test fun pauseAndBufferingDoNotAdvancePosition() {
        assertEquals(15000L,track(kind=ExternalPlaybackKind.PAUSED).positionAt(3000))
        assertEquals(15000L,track(kind=ExternalPlaybackKind.BUFFERING).positionAt(3000))
    }
    @Test fun unavailablePositionIsNotRenderedAsZeroAndInvalidSpeedCannotJump() {
        assertNull(track().copy(positionMs=null).positionAt(3000))
        assertNull(track().copy(positionMs=-1).positionAt(3000))
        assertEquals(15000L,track().copy(speed=Float.NaN).positionAt(3000))
    }
}
