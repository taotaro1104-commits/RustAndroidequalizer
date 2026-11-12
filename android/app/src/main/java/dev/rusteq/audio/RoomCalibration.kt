package dev.rusteq.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** Deterministic pink noise (Paul Kellet's refined filter), scaled to roughly +-1. */
class PinkNoise(seed: Long = 1234L) {
    private var state = seed
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0

    private fun white(): Double {
        state = state * 6364136223846793005L + 1442695040888963407L // LCG; good enough for a test signal
        return ((state ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }

    fun next(): Float {
        val w = white()
        b0 = .99886 * b0 + w * .0555179; b1 = .99332 * b1 + w * .0750759; b2 = .96900 * b2 + w * .1538520
        b3 = .86650 * b3 + w * .3104856; b4 = .55000 * b4 + w * .5329522; b5 = -.7616 * b5 - w * .0168980
        val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * .5362
        b6 = w * .115926
        return (out * .11).toFloat()
    }
}

/**
 * Turns a measured third-octave response (pink noise played, microphone recorded) into corrective EQ gains.
 * Conservative by design: it mainly cuts room/speaker peaks (up to -9 dB) and only fills dips slightly
 * (up to +3 dB), because boosting a room null wastes headroom and rarely helps. Phone microphones are not
 * flat, so treat the result as a starting point to judge by ear.
 */
object RoomCalibration {
    const val MAX_CUT_DB = 9f
    const val MAX_BOOST_DB = 3f
    private const val FIRST_HZ = 63f
    private const val LAST_HZ = 10000f
    private const val REF_LOW_HZ = 200f
    private const val REF_HIGH_HZ = 4000f
    /** Required margin of the measured signal over the ambient noise, in the reference range. */
    const val MIN_SNR_DB = 12.0

    private val freqs = BuiltinPresets31.frequencies
    private fun indexRange(lo: Float, hi: Float) = freqs.indices.filter { freqs[it] in lo..hi }

    /** True when the measurement stands clearly above the room's background noise. */
    fun isUsable(signalDb: DoubleArray, ambientDb: DoubleArray): Boolean {
        val r = indexRange(REF_LOW_HZ, REF_HIGH_HZ)
        return r.isNotEmpty() && r.map { signalDb[it] - ambientDb[it] }.average() >= MIN_SNR_DB
    }

    fun gains(measuredDb: DoubleArray, strength: Float = 1f): FloatArray {
        require(measuredDb.size == 31)
        val ref = indexRange(REF_LOW_HZ, REF_HIGH_HZ).map { measuredDb[it] }.filter { it > -150.0 }.average()
        if (ref.isNaN()) return FloatArray(31)
        val active = indexRange(FIRST_HZ, LAST_HZ)
        val dev = DoubleArray(31) { if (it in active && measuredDb[it] > -150.0) measuredDb[it] - ref else 0.0 }
        val smooth = DoubleArray(31) { i ->
            if (i !in active) 0.0 else {
                val l = if (i - 1 in active) dev[i - 1] else dev[i]
                val r = if (i + 1 in active) dev[i + 1] else dev[i]
                .25 * l + .5 * dev[i] + .25 * r
            }
        }
        val s = strength.coerceIn(0f, 1f)
        return FloatArray(31) {
            val g = (-smooth[it] * s).toFloat().coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)
            if (abs(g) < .5f) 0f else round(g * 2f) / 2f
        }
    }
}

/** Adds corrective [gains] to a 31-band ISO config, or builds one when the layout differs; keeps headroom. */
fun EqMatch.addTo(base: EqConfig, gains: FloatArray): EqConfig {
    val iso = BuiltinPresets31.frequencies
    val compatible = base.bands.size == 31 && base.bands.indices.all { kotlin.math.abs(base.bands[it].hz - iso[it]) <= iso[it] * .02f }
    if (!compatible) return toConfig(base, gains)
    val bands = base.bands.mapIndexed { i, b -> b.copy(db = (b.db + gains[i]).coerceIn(-24f, 24f)) }
    val peak = max(0f, bands.indices.maxOf { bands[it].db - base.bands[it].db })
    return base.copy(bands = bands, preamp = (base.preamp - peak).coerceIn(-60f, 12f))
}
