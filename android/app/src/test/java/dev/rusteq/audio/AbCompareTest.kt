package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AbCompareTest {
    private val sr = 48000f

    @Test fun flatConfigsHaveEqualLoudnessAndNeedNoTrim() {
        val a = EqConfig(preamp = -6f)
        assertEquals(0f, LoudnessMatch.trimDb(a, a.copy()), 1e-4f)
    }

    @Test fun preampDifferenceIsMatchedExactly() {
        val quiet = EqConfig(preamp = -12f); val loud = EqConfig(preamp = -6f)
        // loud is 6 dB louder, so it must be attenuated by 6 dB to match the quiet one.
        assertEquals(-6f, LoudnessMatch.trimDb(loud, quiet), 1e-3f)
        assertEquals(0f, LoudnessMatch.trimDb(quiet, loud), 1e-3f)
    }

    @Test fun bassBoostRaisesPerceivedLevelButLessThanItsPeak() {
        val boosted = EqConfig(preamp = 0f, bands = listOf(EqBand(60f, 10f, 1.1f)))
        val flat = EqConfig(preamp = 0f, bands = listOf(EqBand(60f, 0f, 1.1f)))
        val trim = LoudnessMatch.trimDb(boosted, flat)
        assertTrue("trim $trim", trim < -0.05f && trim > -10f)
    }

    @Test fun bypassIsTreatedAsUnityGain() {
        val eq = EqConfig(preamp = -6f)
        assertEquals(0f, LoudnessMatch.meanDb(eq.copy(bypass = true)), 1e-6f)
        assertEquals(-6f, LoudnessMatch.meanDb(eq), 0.01f)
    }

    @Test fun matchedPairHasEqualLoudnessAndNeverBoosts() {
        val a = EqConfig(preamp = -3f, bands = listOf(EqBand(80f, 8f, 1.1f), EqBand(3000f, -4f, 1.1f)))
        val b = a.copy(bands = a.bands.map { it.copy(db = 0f) }, preamp = 0f)
        val (ma, mb) = LoudnessMatch.matchedPair(a, b)
        assertEquals(LoudnessMatch.meanDb(ma, sr), LoudnessMatch.meanDb(mb, sr), 0.01f)
        assertTrue(ma.preamp <= a.preamp + 1e-4f)
        assertTrue(mb.preamp <= b.preamp + 1e-4f)
    }

    @Test fun binomialTailMatchesKnownValues() {
        assertEquals(1.0, AbxStats.pValue(0, 0), 1e-9)
        assertEquals(0.5, AbxStats.pValue(1, 1), 1e-9)
        assertEquals(1.0 / 1024, AbxStats.pValue(10, 10), 1e-9)
        assertEquals(9.0 / 256, AbxStats.pValue(7, 8), 1e-9) // (8+1)/256
        assertEquals(1.0, AbxStats.pValue(0, 10), 1e-9)
    }

    @Test fun abxScoresAnswersAndIsDeterministicWithSeed() {
        val s1 = AbxSession(Random(7), totalTrials = 8)
        val s2 = AbxSession(Random(7), totalTrials = 8)
        repeat(8) {
            assertEquals(s1.hiddenIsA, s2.hiddenIsA)
            val correct = s1.answer(guessA = s1.hiddenIsA)
            assertTrue(correct)
            s2.answer(guessA = !s2.hiddenIsA)
        }
        assertTrue(s1.finished); assertEquals(8, s1.correct); assertEquals(0, s2.correct)
        assertEquals(1.0 / 256, s1.pValue, 1e-9)
    }

    @Test fun abxCannotAnswerAfterFinishAndAssignmentsAreMixed() {
        val s = AbxSession(Random(1), totalTrials = 16)
        val seen = mutableSetOf<Boolean>()
        repeat(16) { seen += s.hiddenIsA; s.answer(true) }
        assertTrue(s.finished); assertEquals(setOf(true, false), seen)
        try { s.answer(true); fail() } catch (_: IllegalStateException) {}
    }
}
