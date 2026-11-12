package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test

class LoudnessCompensationTest {
    private val base = EqConfig(bands = EqConfig.defaultBands(31), preamp = -6f, loudnessStrength = 1f)
    private fun db(c: EqConfig, hz: Float) = c.bands.minByOrNull { kotlin.math.abs(it.hz - hz) }!!.db

    @Test fun offWhenStrengthIsZeroOrVolumeIsHigh() {
        assertEquals(base.copy(loudnessStrength = 0f), LoudnessCompensation.apply(base.copy(loudnessStrength = 0f), 0.1f))
        assertEquals(base, LoudnessCompensation.apply(base, 0.9f))
        assertEquals(base, LoudnessCompensation.apply(base, 0.8f))
    }

    @Test fun lowVolumeBoostsBassAndTrebleButNotMidrange() {
        val c = LoudnessCompensation.apply(base, 0.2f)
        assertTrue(db(c, 40f) > 4f)
        assertTrue(db(c, 12500f) > 1.5f)
        assertEquals(0f, db(c, 1000f), 0.01f)
        assertTrue(db(c, 40f) > db(c, 12500f)) // ear loses more bass than treble
    }

    @Test fun quieterMeansMoreCompensationAndStrengthScalesIt() {
        val a = db(LoudnessCompensation.apply(base, 0.5f), 40f)
        val b = db(LoudnessCompensation.apply(base, 0.1f), 40f)
        assertTrue(b > a && a > 0f)
        val half = db(LoudnessCompensation.apply(base.copy(loudnessStrength = .5f), 0.1f), 40f)
        assertEquals(b / 2f, half, 0.05f)
    }

    @Test fun preampLowersByLargestBoostSoItCannotClip() {
        val c = LoudnessCompensation.apply(base, 0.1f)
        val peak = c.bands.zip(base.bands).maxOf { (x, y) -> x.db - y.db }
        assertEquals(base.preamp - peak, c.preamp, 0.01f)
    }

    @Test fun userGainsStayWithinEngineRangeAndOtherSettingsAreKept() {
        val loud = base.copy(bands = base.bands.map { it.copy(db = 23f) }, echoMix = .3f, virtualBassAmount = 1f)
        val c = LoudnessCompensation.apply(loud, 0f)
        assertTrue(c.bands.all { it.db <= 24f })
        assertEquals(.3f, c.echoMix, 0f); assertEquals(1f, c.virtualBassAmount, 0f)
        assertEquals(loud.bands.map { it.hz }, c.bands.map { it.hz })
    }

    @Test fun hostileVolumeValuesAreSafe() {
        for (v in listOf(Float.NaN, -3f, 9f, Float.POSITIVE_INFINITY)) {
            val c = LoudnessCompensation.apply(base, v)
            assertTrue(c.bands.all { it.db.isFinite() && it.db in -24f..24f } && c.preamp.isFinite())
        }
    }
}
