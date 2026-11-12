package dev.rusteq.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

/**
 * Long-term average spectrum in the 31 ISO third-octave bands. Feed mono samples in any chunk size;
 * frames are Hann-windowed 16384-point FFTs (50% overlap). Control-plane only: never run on the audio thread.
 */
class SpectrumAnalyzer(private val sampleRate: Int) {
    private val n = 16384
    private val window = DoubleArray(n) { .5 - .5 * cos(2 * PI * it / n) }
    private val freqs = BuiltinPresets31.frequencies
    private val power = DoubleArray(freqs.size)
    private val buffer = DoubleArray(n)
    private var filled = 0
    private var frames = 0
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val bandBins: Array<IntArray> = Array(freqs.size) { i ->
        val lo = freqs[i] / 2.0.pow(1.0 / 6)
        val hi = freqs[i] * 2.0.pow(1.0 / 6)
        val binHz = sampleRate.toDouble() / n
        val bins = (1 until n / 2).filter { it * binHz >= lo && it * binHz < hi }
        // Narrow low bands can contain no bin; fall back to the nearest one.
        if (bins.isNotEmpty()) bins.toIntArray() else intArrayOf((freqs[i] / binHz).toInt().coerceIn(1, n / 2 - 1))
    }

    fun add(samples: FloatArray, count: Int) {
        for (k in 0 until count) {
            val v = samples[k].toDouble()
            buffer[filled++] = if (v.isFinite()) v else 0.0
            if (filled == n) {
                process()
                System.arraycopy(buffer, n / 2, buffer, 0, n / 2)
                filled = n / 2
            }
        }
    }

    private fun process() {
        for (i in 0 until n) { re[i] = buffer[i] * window[i]; im[i] = 0.0 }
        fft()
        for (b in freqs.indices) {
            var p = 0.0
            for (bin in bandBins[b]) p += re[bin] * re[bin] + im[bin] * im[bin]
            power[b] += p
        }
        frames++
    }

    /** Radix-2 in-place FFT on [re]/[im]. */
    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            for (start in 0 until n step len) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
    }

    fun hasData(): Boolean = frames > 0 && power.any { it > 1e-12 }

    /** Mean band level in dB (relative; only differences between tracks are meaningful). Silent bands are -200 dB. */
    fun bandLevelsDb(): DoubleArray =
        DoubleArray(freqs.size) { if (frames == 0 || power[it] <= 1e-20) -200.0 else 10 * log10(power[it] / frames) }
}

/** Turns two long-term spectra into 31-band corrective gains. */
object EqMatch {
    const val MAX_DB = 12f
    private const val SILENT_DB = -150.0

    /** Gains that move [current] toward [reference]; the overall level difference is removed. */
    fun gains(reference: DoubleArray, current: DoubleArray, strength: Float): FloatArray {
        require(reference.size == 31 && current.size == 31)
        val valid = BooleanArray(31) { reference[it] > SILENT_DB && current[it] > SILENT_DB }
        val diff = DoubleArray(31) { if (valid[it]) reference[it] - current[it] else 0.0 }
        val usable = diff.indices.filter { valid[it] }
        if (usable.isEmpty()) return FloatArray(31)
        val mean = usable.map { diff[it] }.average()
        val centered = DoubleArray(31) { if (valid[it]) diff[it] - mean else 0.0 }
        val smoothed = DoubleArray(31) { i ->
            if (!valid[i]) 0.0 else {
                val l = if (i > 0 && valid[i - 1]) centered[i - 1] else centered[i]
                val r = if (i < 30 && valid[i + 1]) centered[i + 1] else centered[i]
                .25 * l + .5 * centered[i] + .25 * r
            }
        }
        val s = strength.coerceIn(0f, 1f)
        return FloatArray(31) {
            val v = (smoothed[it] * s).toFloat().coerceIn(-MAX_DB, MAX_DB)
            if (abs(v) < .25f) 0f else round(v * 2f) / 2f // 0.5 dB steps; ignore sub-quarter-dB noise
        }
    }

    fun toConfig(base: EqConfig, gains: FloatArray): EqConfig {
        val bands = BuiltinPresets31.frequencies.mapIndexed { i, hz -> EqBand(hz, gains[i], 4.3f) }
        val peak = gains.maxOrNull() ?: 0f
        return base.copy(bands = bands, preamp = -max(6f, peak + 2f))
    }
}
