package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test

class HearingProfileTest {
    private val normal = mapOf(250f to -66f, 500f to -73.5f, 1000f to -75f, 2000f to -78.5f, 4000f to -82.5f, 8000f to -65f)

    @Test fun typicalNormalHearingNeedsNoCompensation() {
        assertTrue(HearingProfile.boosts(normal).values.all { it == 0f })
        assertTrue(HearingProfile.gains31(normal).all { it == 0f })
    }

    @Test fun uniformlyLouderThresholdsAreNotALossBecauseOnlyTheShapeCounts() {
        val quietPhones = normal.mapValues { it.value + 20f }
        assertTrue(HearingProfile.gains31(quietPhones).all { it == 0f })
    }

    @Test fun highFrequencyLossIsHalfCompensatedAndCapped() {
        val loss = normal + mapOf(4000f to -82.5f + 10f, 8000f to -65f + 40f)
        val b = HearingProfile.boosts(loss)
        assertEquals(5f, b.getValue(4000f), .01f)
        assertEquals(HearingProfile.MAX_BOOST_DB, b.getValue(8000f), 0f)
        assertEquals(0f, b.getValue(1000f), 0f)
    }

    @Test fun betterThanTypicalHearingNeverCuts() {
        val sharp = normal.mapValues { it.value - 5f } + (1000f to -75f)
        assertTrue(HearingProfile.boosts(sharp).values.all { it >= 0f })
    }

    @Test fun interpolationIsSmoothFlatBeyondTheEndsAndMissingDataIsZero() {
        val g = HearingProfile.gains31(normal + (8000f to -65f + 40f))
        val f = BuiltinPresets31.frequencies
        assertEquals(12f, g[f.indexOf(8000f)], 0f); assertEquals(12f, g[f.indexOf(20000f)], 0f)
        assertEquals(0f, g[f.indexOf(2000f)], 0f)
        assertTrue(g[f.indexOf(5000f)] in 0f..12f && g[f.indexOf(5000f)] > 0f)
        assertTrue(HearingProfile.boosts(mapOf(500f to -70f)).values.all { it == 0f }) // no 1 kHz reference
    }

    @Test fun sessionRecordsFirstHeardLevelAndGivesUpAtTheMaximum() {
        val s = HearingTestSession()
        assertEquals(250f, s.frequency, 0f)
        s.notHeard(); s.notHeard(); assertEquals(HearingProfile.START_DB + 10f, s.levelDb, 0f)
        s.heard()
        assertEquals(HearingProfile.START_DB + 10f, s.thresholds[250f]!!, 0f)
        assertEquals(500f, s.frequency, 0f); assertEquals(HearingProfile.START_DB, s.levelDb, 0f)
        repeat(100) { if (s.index == 1) s.notHeard() }
        assertEquals(HearingProfile.NOT_HEARD_DB, s.thresholds[500f]!!, 0f)
        while (!s.finished) s.heard()
        assertEquals(6, s.thresholds.size)
        try { s.heard(); fail() } catch (_: IllegalStateException) {}
    }
}
