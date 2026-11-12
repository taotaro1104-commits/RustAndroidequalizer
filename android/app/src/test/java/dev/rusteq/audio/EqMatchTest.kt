package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class EqMatchTest {
    private val sr = 48000
    private fun tone(hz: Double, seconds: Double = 2.0, amp: Double = .5) =
        FloatArray((sr * seconds).toInt()) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }
    private fun analyze(x: FloatArray) = SpectrumAnalyzer(sr).also { it.add(x, x.size) }.bandLevelsDb()
    private val freqs = BuiltinPresets31.frequencies

    @Test fun toneEnergyLandsInItsOwnThirdOctaveBand() {
        for (hz in listOf(100.0, 1000.0, 4000.0)) {
            val l = analyze(tone(hz))
            val peak = l.indices.maxByOrNull { l[it] }!!
            assertEquals("tone $hz", hz, freqs[peak].toDouble(), hz * 0.13)
        }
    }

    @Test fun halvingAmplitudeLowersLevelsBySixDb() {
        val a = analyze(tone(1000.0, amp = .5))
        val b = analyze(tone(1000.0, amp = .25))
        val i = freqs.indexOf(1000f)
        assertEquals(6.02, a[i] - b[i], 0.1)
    }

    @Test fun identicalTracksNeedNoCorrection() {
        val x = tone(440.0)
        val y = tone(3000.0)
        val l = analyze(FloatArray(x.size) { x[it] + y[it] * .5f })
        assertTrue(EqMatch.gains(l, l, 1f).all { it == 0f })
    }

    @Test fun gainsFollowTheDifferenceAndDropTheOverallLevel() {
        val cur = DoubleArray(31) { -40.0 }
        val ref = DoubleArray(31) { -50.0 }.also { for (i in 0..8) it[i] += 16.0 } // quieter overall, bass-heavy
        val g = EqMatch.gains(ref, cur, 1f)
        assertTrue("bass boosted ${g[3]}", g[3] > 3f)
        assertTrue("highs cut ${g[25]}", g[25] < 0f)
        assertTrue("balanced around zero", abs(g.average()) < 3.0)
    }

    @Test fun strengthScalesAndIsClampedAndSilentBandsAreIgnored() {
        val cur = DoubleArray(31) { -40.0 }.also { it[5] = -200.0 }
        val ref = DoubleArray(31) { -40.0 }.also { it[10] = 60.0; it[5] = -20.0 }
        val full = EqMatch.gains(ref, cur, 1f)
        val half = EqMatch.gains(ref, cur, .5f)
        assertTrue(full.all { it in -EqMatch.MAX_DB..EqMatch.MAX_DB })
        assertEquals(EqMatch.MAX_DB, full[10], 0.01f)
        assertEquals(0f, full[5], 0f) // current band is silent: do not invent gain
        assertTrue(half[10] <= full[10])
    }

    @Test fun toConfigUses31BandsKeepsOtherSettingsAndLeavesHeadroom() {
        val base = EqConfig(echoMix = .4f, virtualBassAmount = .5f, loudnessStrength = .3f)
        val gains = FloatArray(31) { if (it == 4) 9f else 0f }
        val c = EqMatch.toConfig(base, gains)
        assertEquals(31, c.bands.size)
        assertEquals(9f, c.bands[4].db, 0f)
        assertEquals(.4f, c.echoMix, 0f)
        assertEquals(.5f, c.virtualBassAmount, 0f)
        assertEquals(.3f, c.loudnessStrength, 0f)
        assertEquals(-11f, c.preamp, 0.01f)
    }

    @Test fun shortOrSilentInputGivesNoUsableSpectrum() {
        val a = SpectrumAnalyzer(sr)
        a.add(FloatArray(100), 100)
        assertFalse(a.hasData())
        a.add(FloatArray(sr * 2), sr * 2)
        assertFalse(a.hasData()) // all zeros: still no usable energy
    }
}
