package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test

class RoomCalibrationTest {
    private val f = BuiltinPresets31.frequencies
    private fun flat(level: Double = -40.0) = DoubleArray(31) { level }

    @Test fun pinkNoiseIsRoughlyFlatPerThirdOctaveBand() {
        val noise = PinkNoise(7)
        val x = FloatArray(48000 * 10) { noise.next() }
        val a = SpectrumAnalyzer(48000); a.add(x, x.size)
        val l = a.bandLevelsDb()
        val mid = (f.indexOf(125f)..f.indexOf(8000f)).map { l[it] }
        assertTrue("spread ${mid.max() - mid.min()}", mid.max() - mid.min() < 4.0)
        assertTrue(x.all { it.isFinite() && kotlin.math.abs(it) < 1.5f })
    }

    @Test fun pinkNoiseIsDeterministic() {
        val a = PinkNoise(3); val b = PinkNoise(3)
        repeat(1000) { assertEquals(a.next(), b.next(), 0f) }
    }

    @Test fun flatResponseNeedsNoCorrectionAndLevelDoesNotMatter() {
        assertTrue(RoomCalibration.gains(flat()).all { it == 0f })
        assertTrue(RoomCalibration.gains(flat(-10.0)).all { it == 0f })
    }

    @Test fun peaksAreCutAndDipsOnlyFilledSlightly() {
        val m = flat().also { it[f.indexOf(100f)] += 9.0; it[f.indexOf(250f)] -= 15.0 }
        val g = RoomCalibration.gains(m)
        assertTrue("peak cut ${g[f.indexOf(100f)]}", g[f.indexOf(100f)] <= -3.5f)
        assertTrue(g[f.indexOf(250f)] in 0.5f..RoomCalibration.MAX_BOOST_DB)
        assertTrue(g.all { it in -RoomCalibration.MAX_CUT_DB..RoomCalibration.MAX_BOOST_DB })
    }

    @Test fun hugePeakIsCappedAndOutOfRangeBandsAreLeftAlone() {
        val m = flat().also { it[f.indexOf(80f)] += 40.0; it[0] += 30.0; it[30] += 30.0 }
        val g = RoomCalibration.gains(m)
        assertEquals(-RoomCalibration.MAX_CUT_DB, g[f.indexOf(80f)], .01f)
        assertEquals(0f, g[0], 0f); assertEquals(0f, g[30], 0f)
    }

    @Test fun strengthScalesAndSilentBandsAreIgnored() {
        val m = flat().also { it[f.indexOf(500f)] += 8.0; it[f.indexOf(1000f)] = -200.0 }
        val full = RoomCalibration.gains(m, 1f); val half = RoomCalibration.gains(m, .5f)
        assertTrue(half[f.indexOf(500f)] > full[f.indexOf(500f)])
        assertEquals(0f, RoomCalibration.gains(m, 0f)[f.indexOf(500f)], 0f)
        assertTrue(RoomCalibration.gains(DoubleArray(31) { -200.0 }).all { it == 0f })
    }

    @Test fun measurementMustStandAboveBackgroundNoise() {
        assertTrue(RoomCalibration.isUsable(flat(-30.0), flat(-60.0)))
        assertFalse(RoomCalibration.isUsable(flat(-30.0), flat(-35.0)))
    }

    @Test fun correctionIsAddedToAnIsoConfigAndKeepsHeadroom() {
        val base = EqConfig(bands = BuiltinPresets31.frequencies.map { EqBand(it, 2f, 4.3f) }, preamp = -6f)
        val g = FloatArray(31).also { it[10] = 3f; it[11] = -4f }
        val c = EqMatch.addTo(base, g)
        assertEquals(5f, c.bands[10].db, 0f); assertEquals(-2f, c.bands[11].db, 0f)
        assertEquals(-9f, c.preamp, .01f)
        val coarse = EqMatch.addTo(EqConfig(bands = EqConfig.defaultBands(10)), g)
        assertEquals(31, coarse.bands.size)
    }
}
