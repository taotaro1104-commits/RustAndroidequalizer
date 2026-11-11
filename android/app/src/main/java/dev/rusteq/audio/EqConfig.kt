package dev.rusteq.audio

import dev.rusteq.R
import dev.rusteq.UiText
import dev.rusteq.UiTextException
import dev.rusteq.uiText
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class EqBand(val hz: Float, val db: Float = 0f, val q: Float = 4.3f)

data class EqConfig(
    val bands: List<EqBand> = defaultBands(31),
    val preamp: Float = -6f,
    val bypass: Boolean = false,
    val compressor: Boolean = false,
    val threshold: Float = -18f,
    val ratio: Float = 3f,
    val spatial: Int = 0,
    val width: Float = 1.3f,
    val crossfeed: Float = .25f,
    val virtualBassAmount: Float = 0f,
    val virtualBassHz: Float = 110f,
) {
    fun packed(): FloatArray = FloatArray(104).also { v ->
        v[0] = preamp; v[1] = if (bypass) 1f else 0f; v[2] = bands.size.toFloat()
        v[3] = if (compressor) 1f else 0f; v[4] = threshold; v[5] = ratio
        v[6] = spatial.toFloat(); v[7] = width; v[8] = crossfeed
        v[102] = virtualBassAmount; v[103] = virtualBassHz
        bands.forEachIndexed { i, b -> v[9+i*3] = b.hz; v[10+i*3] = b.db; v[11+i*3] = b.q }
    }

    fun toJson(): String = JSONObject().apply {
        put("version", 2); put("preamp", preamp); put("bypass", bypass)
        put("compressor", compressor); put("threshold", threshold); put("ratio", ratio)
        put("spatial", spatial); put("width", width); put("crossfeed", crossfeed)
        put("virtualBassAmount", virtualBassAmount); put("virtualBassHz", virtualBassHz)
        put("bands", JSONArray().apply { bands.forEach { put(JSONArray(listOf(it.hz, it.db, it.q))) } })
    }.toString()

    companion object {
        fun defaultBands(count: Int): List<EqBand> {
            require(count in 1..31)
            if (count == 1) return listOf(EqBand(1000f, q = 1.4f))
            return List(count) { EqBand((20f * 1000f.pow(it.toFloat() / (count - 1))).coerceIn(20f, 20000f), q = if (count == 10) 1.4f else 4.3f) }
        }

        fun fromJson(text: String): EqConfig {
            val j = JSONObject(text)
            if (j.getInt("version") !in 1..2) throw UiTextException(uiText(R.string.player_preset_format_unsupported))
            val b = j.getJSONArray("bands")
            if (b.length() !in 1..31) throw UiTextException(uiText(R.string.player_band_count_invalid))
            fun finite(v: Double, min: Float, max: Float): Float {
                // JSON uses doubles; validate in the same float domain as the
                // sliders and DSP so 0.1 does not compare below 0.1f.
                val rounded = v.toFloat()
                if (!v.isFinite() || !rounded.isFinite() || rounded < min || rounded > max) throw UiTextException(uiText(R.string.player_setting_out_of_range))
                return rounded
            }
            return EqConfig(
                bands = List(b.length()) { i -> val a = b.getJSONArray(i); EqBand(finite(a.getDouble(0), 10f, 20000f), finite(a.getDouble(1), -24f, 24f), finite(a.getDouble(2), .1f, 18f)) },
                preamp = finite(j.getDouble("preamp"), -60f, 12f), bypass = j.optBoolean("bypass", false),
                compressor = j.optBoolean("compressor", false), threshold = finite(j.optDouble("threshold", -18.0), -60f, 0f),
                ratio = finite(j.optDouble("ratio", 3.0), 1f, 20f), spatial = j.optInt("spatial", 0).coerceIn(0, 2),
                width = finite(j.optDouble("width", 1.3), 0f, 2f), crossfeed = finite(j.optDouble("crossfeed", .25), 0f, .5f),
                virtualBassAmount = finite(j.optDouble("virtualBassAmount", 0.0), 0f, 1f),
                virtualBassHz = finite(j.optDouble("virtualBassHz", 110.0), 60f, 180f),
            )
        }

        /** Import generated AutoEq PK settings. Optimization belongs outside playback. */
        fun fromAutoEq(text: String): EqConfig {
            val number = "([-+]?\\d+(?:\\.\\d+)?)"
            val preampRegex = Regex("Preamp:\\s*$number\\s*dB", RegexOption.IGNORE_CASE)
            val filterRegex = Regex("Filter\\s+\\d+:\\s*ON\\s+PK\\s+Fc\\s+$number\\s*Hz\\s+Gain\\s+$number\\s*dB\\s+Q\\s+$number", RegexOption.IGNORE_CASE)
            val active = text.lineSequence().map { it.trim() }.filter { Regex("^Filter\\s+\\d+:\\s*ON\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.toList()
            if (active.isEmpty() || active.size > 31) throw UiTextException(uiText(R.string.player_pk_count_invalid))
            val bands = active.map { line ->
                val m = filterRegex.matchEntire(line) ?: throw UiTextException(uiText(R.string.player_pk_only, UiText.Literal(line)))
                EqBand(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(), m.groupValues[3].toFloat())
            }
            val preamp = preampRegex.find(text)?.groupValues?.get(1)?.toFloat() ?: -6f
            val cfg = EqConfig(bands = bands, preamp = preamp)
            return fromJson(cfg.toJson()) // Shared validation, no silent clamping of imported profiles.
        }
    }
}

/** Mirrors the Rust RBJ peaking response for the UI graph; runs only on UI/control. */
fun EqConfig.responseDb(hz: Float, sampleRate: Float): Float {
    if (bypass) return 0f
    val sr = sampleRate.coerceAtLeast(8000f).toDouble()
    val w = 2.0 * PI * hz / sr
    var db = preamp.toDouble()
    for (b in bands) {
        val omega = 2.0 * PI * b.hz.coerceIn(10f, (sr * .45).toFloat()) / sr
        val a = 10.0.pow(b.db / 40.0)
        val alpha = sin(omega) / (2.0 * b.q)
        val a0 = 1.0 + alpha / a
        val b0 = (1.0 + alpha * a) / a0; val b1 = -2.0 * cos(omega) / a0; val b2 = (1.0 - alpha * a) / a0
        val a1 = -2.0 * cos(omega) / a0; val a2 = (1.0 - alpha / a) / a0
        val br = b0 + b1 * cos(w) + b2 * cos(2*w); val bi = -b1 * sin(w) - b2 * sin(2*w)
        val ar = 1.0 + a1 * cos(w) + a2 * cos(2*w); val ai = -a1 * sin(w) - a2 * sin(2*w)
        db += 10.0 * log10((br*br + bi*bi) / (ar*ar + ai*ai))
    }
    return db.toFloat()
}
