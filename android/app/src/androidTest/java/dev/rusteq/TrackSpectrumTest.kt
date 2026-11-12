package dev.rusteq

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rusteq.audio.BuiltinPresets31
import dev.rusteq.audio.EqMatch
import dev.rusteq.audio.TrackSpectrum
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class TrackSpectrumTest {
    private fun wav(hzs: List<Double>, seconds: Int = 4, rate: Int = 44100, channels: Int = 2): File {
        val frames = rate * seconds
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = hzs.sumOf { sin(2 * PI * it * i / rate) } / hzs.size * 0.5
            repeat(channels) { data.putShort((v * 32767).toInt().toShort()) }
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(data.capacity())
        val f = File.createTempFile("spec-", ".wav", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        f.writeBytes(header.array() + data.array())
        return f
    }

    @Test fun decodesWavAndFindsToneBand() {
        val f = wav(listOf(1000.0))
        try {
            val levels = TrackSpectrum.analyze(f.path)
            assertNotNull("decoder returned no spectrum", levels)
            val peak = levels!!.indices.maxByOrNull { levels[it] }!!
            assertEquals(1000.0, BuiltinPresets31.frequencies[peak].toDouble(), 130.0)
        } finally { f.delete() }
    }

    @Test fun bassHeavyReferenceBoostsLowBandsOfABrightTrack() {
        val bright = wav(listOf(200.0, 3000.0, 8000.0)); val bassy = wav(listOf(50.0, 100.0, 200.0, 3000.0))
        try {
            val cur = TrackSpectrum.analyze(bright.path)!!; val ref = TrackSpectrum.analyze(bassy.path)!!
            val g = EqMatch.gains(ref, cur, 1f)
            val i63 = BuiltinPresets31.frequencies.indexOf(63f); val i8k = BuiltinPresets31.frequencies.indexOf(8000f)
            assertTrue("gains ${g.toList()}", g[BuiltinPresets31.frequencies.indexOf(50f)] > 0f && g[i8k] < 0f && i63 >= 0)
        } finally { bright.delete(); bassy.delete() }
    }

    @Test fun silentOrGarbageFilesReturnNullInsteadOfCrashing() {
        val silent = File.createTempFile("silent-", ".wav", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val garbage = File.createTempFile("junk-", ".mp3", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            garbage.writeBytes(ByteArray(2048) { (it * 31).toByte() })
            assertNull(runCatching { TrackSpectrum.analyze(garbage.path) }.getOrNull())
            val s = wav(listOf(1000.0)).also { it.writeBytes(it.readBytes().also { b -> for (i in 44 until b.size) b[i] = 0 }) }
            assertNull(TrackSpectrum.analyze(s.path)); s.delete()
        } finally { silent.delete(); garbage.delete() }
    }
}
