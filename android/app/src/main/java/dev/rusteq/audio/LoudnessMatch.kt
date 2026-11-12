package dev.rusteq.audio

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Level matching for fair A/B comparison. A louder sound is almost always judged "better",
 * so the louder side is attenuated until both sides have the same estimated loudness.
 *
 * Estimate: mean power gain of the EQ curve (including preamp) over 20 Hz–20 kHz, weighted by
 * pink noise (equal energy per octave, i.e. uniform on a log axis) times an A-weighting power
 * curve as a loudness proxy. It is a control-plane estimate that does not touch the audio thread.
 * Virtual bass, echo and dynamics are not modelled; the matched level is approximate (about ±1 dB).
 */
object LoudnessMatch {
    private const val POINTS = 256
    private const val SAMPLE_RATE = 48000f

    /** IEC 61672 A-weighting as a power ratio, normalised to 1.0 at 1 kHz. */
    internal fun aWeightPower(hz: Double): Double = rawA(hz) / rawA(1000.0)

    private fun rawA(hz: Double): Double {
        val f2 = hz * hz
        val num = 12194.0.pow(2) * f2 * f2
        val den = (f2 + 20.6.pow(2)) * sqrt((f2 + 107.7.pow(2)) * (f2 + 737.9.pow(2))) * (f2 + 12194.0.pow(2))
        val ra = num / den
        return ra * ra
    }

    /** Weighted mean level in dB of the full chain gain (preamp + bands); bypass is 0 dB. */
    fun meanDb(config: EqConfig, sampleRate: Float = SAMPLE_RATE): Float {
        if (config.bypass) return 0f
        var weighted = 0.0
        var total = 0.0
        for (i in 0 until POINTS) {
            val hz = 20.0 * 1000.0.pow(i / (POINTS - 1.0))
            val w = aWeightPower(hz)
            val gainPower = 10.0.pow(config.responseDb(hz.toFloat(), sampleRate) / 10.0)
            weighted += w * gainPower
            total += w
        }
        return (10.0 * log10(weighted / total)).toFloat()
    }

    /** dB to add to [config] so it sounds as loud as [reference]; never positive (no boosting). */
    fun trimDb(config: EqConfig, reference: EqConfig, sampleRate: Float = SAMPLE_RATE): Float =
        (meanDb(reference, sampleRate) - meanDb(config, sampleRate)).coerceAtMost(0f)

    /** Returns both configs with the louder one attenuated via its preamp; never boosts either. */
    fun matchedPair(a: EqConfig, b: EqConfig, sampleRate: Float = SAMPLE_RATE): Pair<EqConfig, EqConfig> {
        val da = meanDb(a, sampleRate); val db = meanDb(b, sampleRate)
        return when {
            da > db -> trimmed(a, db - da) to b
            db > da -> a to trimmed(b, da - db)
            else -> a to b
        }
    }

    private fun trimmed(c: EqConfig, trim: Float): EqConfig =
        // A bypassed config ignores preamp in the engine; compare against a flat, non-bypassed config instead.
        if (c.bypass) c else c.copy(preamp = (c.preamp + trim).coerceIn(-60f, 12f))
}
