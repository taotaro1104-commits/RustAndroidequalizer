package dev.rusteq.audio

import kotlin.math.ln
import kotlin.math.max

/**
 * Volume-linked loudness compensation. The ear loses bass (and a little treble) sensitivity as the
 * level drops (ISO 226 equal-loudness contours). Below [REFERENCE_VOLUME] the EQ gains are raised
 * smoothly toward low and high frequencies, scaled by the user's strength, and the preamp is lowered
 * by the largest boost so the result cannot clip.
 *
 * This runs on the control side only: it returns a derived config for the engine and the user's saved
 * curve is never modified. Compensation is applied at each band's centre frequency, so it is accurate for
 * the 31-band layout and approximate for coarse layouts.
 */
object LoudnessCompensation {
    /** Normalised system volume at and above which no compensation is applied. */
    const val REFERENCE_VOLUME = 0.8f
    private const val MAX_LOW_DB = 12f
    private const val MAX_HIGH_DB = 5f

    private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /** Extra gain in dB at [hz] for a normalised [depth] 0..1 (0 = reference level). */
    internal fun boostDb(hz: Float, depth: Float): Float {
        val low = smooth(ln(250f / hz.coerceAtLeast(1f)) / ln(250f / 40f)) * MAX_LOW_DB   // full below 40 Hz, none above 250 Hz
        val high = smooth(ln(hz / 3000f) / ln(12000f / 3000f)) * MAX_HIGH_DB              // none below 3 kHz, full above 12 kHz
        return (low + high) * depth
    }

    fun apply(config: EqConfig, volume: Float): EqConfig {
        val strength = config.loudnessStrength
        if (strength <= 0f || !volume.isFinite()) return config
        val v = volume.coerceIn(0f, 1f)
        val depth = ((REFERENCE_VOLUME - v) / REFERENCE_VOLUME).coerceIn(0f, 1f) * strength.coerceIn(0f, 1f)
        if (depth <= 0f) return config
        var peak = 0f
        val bands = config.bands.map { b ->
            val target = (b.db + boostDb(b.hz, depth)).coerceIn(-24f, 24f)
            peak = max(peak, target - b.db)
            b.copy(db = target)
        }
        return config.copy(bands = bands, preamp = (config.preamp - peak).coerceIn(-60f, 12f))
    }
}
