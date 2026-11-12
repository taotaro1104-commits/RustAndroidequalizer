package dev.rusteq.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Note values for tempo-synced echo, as a fraction of one quarter-note beat. */
enum class EchoNote(val beats: Float, val label: String) {
    SIXTEENTH(.25f, "1/16"), TRIPLET_EIGHTH(1f / 3f, "1/8T"), EIGHTH(.5f, "1/8"), DOTTED_EIGHTH(.75f, "1/8."), QUARTER(1f, "1/4"), DOTTED_QUARTER(1.5f, "1/4.")
}

object TempoSync {
    /** Echo delay in ms for [bpm] and [note], folded by octaves into the engine's 20–1000 ms range. */
    fun echoMs(bpm: Float, note: EchoNote): Float {
        require(bpm.isFinite() && bpm > 0f)
        var ms = 60000f / bpm * note.beats
        while (ms > EqConfig.MAX_ECHO_MS) ms /= 2f
        while (ms < EqConfig.MIN_ECHO_MS) ms *= 2f
        return ms
    }
}

/**
 * Estimates tempo (60–180 BPM) from mono audio: 200 Hz onset envelope (positive log-energy flux),
 * then autocorrelation with a mild preference for 80–160 BPM to avoid half/double-time picks.
 */
class BpmDetector {
    private val envRate = 200
    private val flux = ArrayList<Float>()
    private var acc = 0.0
    private var accN = 0
    private var hop = 0
    private var rate = 0
    private var prev = 0f

    fun add(mono: FloatArray, count: Int, sampleRate: Int) {
        if (rate != sampleRate) { rate = sampleRate; hop = sampleRate / envRate; acc = 0.0; accN = 0 }
        for (i in 0 until count) {
            val v = mono[i]
            acc += (if (v.isFinite()) v else 0f).toDouble() * v
            if (++accN >= hop) {
                val e = ln(sqrt(acc / accN) + 1e-4).toFloat()
                flux.add(max(0f, e - prev)); prev = e; acc = 0.0; accN = 0
            }
        }
    }

    /** BPM or null when there is too little rhythmic evidence. */
    fun bpm(): Float? {
        val n = flux.size
        if (n < envRate * 6) return null
        val mean = flux.average().toFloat()
        val x = FloatArray(n) { flux[it] - mean }
        val minLag = envRate * 60 / 180; val maxLag = envRate * 60 / 60
        val r = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) { var s = 0.0; for (i in 0 until n - lag) s += x[i] * x[i + lag]; r[lag] = s / (n - lag) }
        var best = -1; var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * envRate / lag
            val pref = if (bpm in 80.0..160.0) 1.0 else .85
            val score = r[lag] * pref
            if (r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1] && score > bestScore) { bestScore = score; best = lag }
        }
        if (best < 0 || r[0].let { r0 -> val v = (0 until n).sumOf { (x[it] * x[it]).toDouble() } / n; v <= 1e-9 }) return null
        val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
        val denom = a - 2 * b + c
        val shift = if (abs(denom) > 1e-12) (0.5 * (a - c) / denom).coerceIn(-.5, .5) else 0.0
        return (60.0 * envRate / (best + shift)).toFloat()
    }
}
