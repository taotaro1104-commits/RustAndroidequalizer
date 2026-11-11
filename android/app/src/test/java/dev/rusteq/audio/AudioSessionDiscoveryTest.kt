package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioSessionDiscoveryTest {
    private fun dump(vararg rows: String) = """
        PlaybackActivityMonitor dump time: 12:00:00
          playback listeners:
          players:
        ${rows.joinToString("\n") { "  $it" }}
          ducked players piids:
          playback activity as reported through PlayerBase:
            AudioPlaybackConfiguration piid:900 u/pid:10999/800 state:started attr:usage=USAGE_MEDIA sessionId:999
    """.trimIndent()

    private fun row(sid: String = "117", uid: String = "10123", state: String = "started", usage: String = "USAGE_MEDIA") =
        "AudioPlaybackConfiguration piid:8 deviceId:2 type:android.media.AudioTrack u/pid:$uid/314 state:$state attr:AudioAttributes: usage=$usage content=CONTENT_TYPE_MUSIC flags=0x800 sessionId:$sid"

    @Test fun findsMultipleLiveSessionsWithoutReadingHistoricalEvents() {
        val result = AudioSessionDumpParser.parse(dump(row(), row("121"), row()))
        assertEquals(SessionDumpResult.Success(setOf(DetectedAudioSession(117, 10123), DetectedAudioSession(121, 10123))), result)
    }

    @Test fun excludesStoppedInvalidAndNonMediaPlayers() {
        val text = dump(row(state="paused"), row(state="stopped"), row("0"), row("-1"), row(uid="-1"), row(usage="USAGE_ALARM"), row(usage="USAGE_VOICE_COMMUNICATION"), row("201", usage="USAGE_GAME"))
        assertEquals(SessionDumpResult.Success(setOf(DetectedAudioSession(201, 10123))), AudioSessionDumpParser.parse(text))
    }

    @Test fun distinguishesEmptyPlaybackFromPermissionAndUnknownFormats() {
        assertEquals(SessionDumpResult.Success(emptySet()), AudioSessionDumpParser.parse(dump()))
        assertEquals(SessionDumpResult.Failure(DiscoveryFailure.PERMISSION), AudioSessionDumpParser.parse("Permission Denial: can't dump AudioService without android.permission.DUMP"))
        assertEquals(SessionDumpResult.Failure(DiscoveryFailure.FORMAT), AudioSessionDumpParser.parse("unrelated service\n" + row()))
    }

    @Test fun doesNotTreatMalformedCurrentRowsAsAnEmptySuccessfulScan() {
        assertEquals(SessionDumpResult.Failure(DiscoveryFailure.FORMAT), AudioSessionDumpParser.parse(dump("AudioPlaybackConfiguration broken vendor row")))
        assertEquals(SessionDumpResult.Failure(DiscoveryFailure.FORMAT), AudioSessionDumpParser.parse(dump(row()).substringBefore("ducked players")))
    }

    @Test fun handlesTheInactivePlayerMarkerInAndroid13Dumps() {
        val text = dump("(not logged)  " + row("0", state="idle", usage="USAGE_ASSISTANCE_SONIFICATION"), row())
        assertEquals(SessionDumpResult.Success(setOf(DetectedAudioSession(117, 10123))), AudioSessionDumpParser.parse(text))
    }

    @Test fun filtersBySelectedUidRejectingSharedUidAndOwnProcess() {
        val sessions = setOf(DetectedAudioSession(117, 10123), DetectedAudioSession(118, 10444), DetectedAudioSession(119, 10555))
        val selected = mapOf("youtube" to 10123, "shared" to 10444, "self" to 10555)
        val owners = mapOf(10123 to setOf("youtube"), 10444 to setOf("shared", "other"), 10555 to setOf("self"))
        assertEquals(mapOf(117 to "youtube"), SelectedAudioSessions.resolve(sessions, selected, owners, 10555))
    }

    private fun command(mode: String) = listOf(File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "").path, "-cp", File(ProcessProbe::class.java.protectionDomain!!.codeSource!!.location.toURI()).path, ProcessProbe::class.java.name, mode)
    private fun processAlive(pid: Long): Boolean {
        // Android's compile stubs omit ProcessHandle; these tests run on JDK 17+.
        val type = Class.forName("java.lang.ProcessHandle")
        val handle = type.getMethod("of", Long::class.javaPrimitiveType).invoke(null, pid) as java.util.Optional<*>
        return handle.isPresent && type.getMethod("isAlive").invoke(handle.get()) == true
    }

    @Test fun processReaderCollectsOutputAndRejectsNonzeroExit() {
        assertEquals(ProcessDumpResult.Success("ready\n"), BoundedDumpProcess.read(command("success"), 3000, 1024))
        assertEquals(ProcessDumpResult.Failure(DiscoveryFailure.PROCESS), BoundedDumpProcess.read(command("failure"), 3000, 1024))
    }

    @Test fun processReaderBoundsSilentAndOversizedProcesses() {
        val started = System.nanoTime()
        assertEquals(ProcessDumpResult.Failure(DiscoveryFailure.TIMEOUT), BoundedDumpProcess.read(command("sleep"), 250, 1024))
        assertTrue("A blocked read must respect the deadline", (System.nanoTime()-started)/1_000_000 < 2500)
        assertEquals(ProcessDumpResult.Failure(DiscoveryFailure.OUTPUT_LIMIT), BoundedDumpProcess.read(command("large"), 3000, 1024))
    }

    @Test fun cancellationInterruptsAReadWithoutWaitingForTheChild() {
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val pidFile = File.createTempFile("eq-process-", ".pid")
        val worker = Thread {
            try { BoundedDumpProcess.read(command("sleep") + pidFile.path, 30000, 1024) }
            catch (_: InterruptedException) { interrupted.set(true) }
        }
        try {
            worker.start()
            val deadline = System.nanoTime() + 3_000_000_000
            while (pidFile.length()==0L && System.nanoTime()<deadline) Thread.sleep(20)
            val pid = pidFile.readText().trim().toLong()
            worker.interrupt()
            worker.join(2500)
            assertFalse("Cancellation must unblock the worker", worker.isAlive)
            assertTrue(interrupted.get())
            val exitDeadline = System.nanoTime() + 2_000_000_000
            while (processAlive(pid) && System.nanoTime()<exitDeadline) Thread.sleep(20)
            assertFalse("Cancellation must terminate the child process", processAlive(pid))
        } finally { worker.interrupt(); pidFile.delete() }
    }
}
