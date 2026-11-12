package dev.rusteq.audio

import kotlin.math.ln
import kotlin.math.pow

/**
 * Personal hearing compensation from a simple self-administered threshold test.
 * Thresholds are in dBFS (uncalibrated: they depend on the headphones and the system volume), so only the
 * shape relative to 1 kHz is used, after removing the typical threshold-in-quiet shape. This is not a medical test.
 */
object HearingProfile {
    val testFrequencies = listOf(250f, 500f, 1000f, 2000f, 4000f, 8000f)
    const val START_DB = -75f
    const val MAX_DB = -25f
    const val STEP_DB = 5f
    const val MAX_BOOST_DB = 12f
    private const val HALF_GAIN = 0.5f

    /** Typical normal-hearing threshold in quiet relative to 1 kHz (ISO 226 shape, dB) at [testFrequencies]. */
    private val normalRelative = mapOf(250f to 9f, 500f to 1.5f, 1000f to 0f, 2000f to -3.5f, 4000f to -7.5f, 8000f to 10f)

    /** Level reached without a response counts as "not heard" one step above the maximum. */
    const val NOT_HEARD_DB = MAX_DB + STEP_DB

    /** Boost per test frequency (half-gain rule, boost only, capped). */
    fun boosts(thresholds: Map<Float, Float>): Map<Float, Float> {
        val ref = thresholds[1000f] ?: return testFrequencies.associateWith { 0f }
        return testFrequencies.associateWith { f ->
            val t = thresholds[f] ?: return@associateWith 0f
            val loss = (t - ref) - normalRelative.getValue(f)
            (loss * HALF_GAIN).coerceIn(0f, MAX_BOOST_DB)
        }
    }

    /** 31-band gains by log-frequency interpolation of [boosts]; flat beyond the tested range. */
    fun gains31(thresholds: Map<Float, Float>): FloatArray {
        val b = boosts(thresholds)
        val xs = testFrequencies
        return FloatArray(31) { i ->
            val hz = BuiltinPresets31.frequencies[i]
            val v = when {
                hz <= xs.first() -> b.getValue(xs.first())
                hz >= xs.last() -> b.getValue(xs.last())
                else -> {
                    val hi = xs.indexOfFirst { it >= hz }
                    val (f0, f1) = xs[hi - 1] to xs[hi]
                    val t = ln(hz / f0) / ln(f1 / f0)
                    b.getValue(f0) + (b.getValue(f1) - b.getValue(f0)) * t
                }
            }
            (v * 2f).let { kotlin.math.round(it) / 2f }
        }
    }

    fun amplitude(dbfs: Float): Float = 10f.pow(dbfs / 20f)
}

/** Ascending-level threshold search, one test frequency at a time. */
class HearingTestSession {
    var index = 0; private set
    var levelDb = HearingProfile.START_DB; private set
    val thresholds = LinkedHashMap<Float, Float>()
    val finished get() = index >= HearingProfile.testFrequencies.size
    val frequency get() = HearingProfile.testFrequencies[index.coerceAtMost(HearingProfile.testFrequencies.lastIndex)]

    /** The listener heard the current tone. */
    fun heard() { check(!finished); record(levelDb) }

    /** The current tone was not answered; goes one step up, or gives up on this frequency at the maximum. */
    fun notHeard() {
        check(!finished)
        if (levelDb + HearingProfile.STEP_DB > HearingProfile.MAX_DB) record(HearingProfile.NOT_HEARD_DB) else levelDb += HearingProfile.STEP_DB
    }

    private fun record(db: Float) { thresholds[frequency] = db; index++; levelDb = HearingProfile.START_DB }
}
