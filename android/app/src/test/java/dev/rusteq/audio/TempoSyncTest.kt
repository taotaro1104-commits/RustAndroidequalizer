package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class TempoSyncTest {
    private fun clicks(bpm: Double, seconds: Int = 20, sr: Int = 44100, swing: Boolean = false): FloatArray {
        val x = FloatArray(sr * seconds)
        val period = sr * 60.0 / bpm
        var t = 0.0
        var k = 0
        while (t < x.size) {
            val start = t.toInt()
            for (i in 0 until 2000) if (start + i < x.size) x[start + i] += (exp(-i / 300.0) * sin(2 * PI * 1500 * i / sr) * (if (k % 4 == 0) 1.0 else .6)).toFloat()
            t += period; k++
        }
        return x
    }
    private fun detect(x: FloatArray, sr: Int = 44100) = BpmDetector().also { it.add(x, x.size, sr) }.bpm()

    @Test fun detectsCommonTempos() {
        for (bpm in listOf(90.0, 120.0, 128.0, 140.0)) assertEquals("bpm $bpm", bpm, detect(clicks(bpm))!!.toDouble(), bpm * .015)
    }

    @Test fun silenceAndShortInputGiveNull() {
        assertNull(detect(FloatArray(44100 * 20)))
        assertNull(detect(clicks(120.0, seconds = 3)))
    }

    @Test fun chunkedInputMatchesWholeInput() {
        val x = clicks(110.0)
        val d = BpmDetector()
        var i = 0
        while (i < x.size) { val n = minOf(4096, x.size - i); d.add(x.copyOfRange(i, i + n), n, 44100); i += n }
        assertEquals(detect(x)!!, d.bpm()!!, 1.5f)
    }

    @Test fun echoTimesMatchNoteValuesAndStayInEngineRange() {
        assertEquals(500f, TempoSync.echoMs(120f, EchoNote.QUARTER), .01f)
        assertEquals(250f, TempoSync.echoMs(120f, EchoNote.EIGHTH), .01f)
        assertEquals(375f, TempoSync.echoMs(120f, EchoNote.DOTTED_EIGHTH), .01f)
        assertEquals(125f, TempoSync.echoMs(120f, EchoNote.SIXTEENTH), .01f)
        assertEquals(750f, TempoSync.echoMs(60f, EchoNote.DOTTED_QUARTER), .01f) // 1500 ms folds to 750
        for (bpm in listOf(30f, 60f, 200f, 300f)) for (n in EchoNote.values()) assertTrue(TempoSync.echoMs(bpm, n) in EqConfig.MIN_ECHO_MS..EqConfig.MAX_ECHO_MS)
    }
}
