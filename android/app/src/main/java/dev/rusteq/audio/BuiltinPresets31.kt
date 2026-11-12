package dev.rusteq.audio

import kotlin.math.ln
import kotlin.math.roundToInt

/** 31-band (1/3 octave) factory presets. Curves are defined by a few control points. */
object BuiltinPresets31 {
    /** ISO 1/3 octave centers; identical to [EqConfig.defaultBands] (31). */
    val frequencies = listOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
        800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
    )
    private const val Q = 4.3f

    /** Log-frequency linear interpolation of control points, rounded to 0.5 dB. */
    internal fun curve(points: List<Pair<Float, Float>>): List<Float> = frequencies.map { f ->
        val hi = points.indexOfFirst { it.first >= f }
        val db = when {
            hi < 0 -> points.last().second
            hi == 0 -> points.first().second
            else -> {
                val (f0, d0) = points[hi - 1]; val (f1, d1) = points[hi]
                d0 + (d1 - d0) * (ln(f / f0) / ln(f1 / f0))
            }
        }
        (db * 2f).roundToInt() / 2f
    }

    private fun preset(id: String, title: String, description: String, points: List<Pair<Float, Float>>,
                       bass: Float = 0f, hz: Float = 110f): BuiltinPreset {
        val gains = if (points.isEmpty()) List(frequencies.size) { 0f } else curve(points)
        return BuiltinPreset(id, title, description,
            EqConfig(bands = frequencies.zip(gains).map { (f, g) -> EqBand(f, g, Q) }, virtualBassAmount = bass, virtualBassHz = hz))
    }

    val all = listOf(
        preset("flat31", "31バンド フラット", "31バンドの比較基準", emptyList()),
        preset("rock31", "31バンド ロック", "低音と高音にメリハリ",
            listOf(20f to 4f, 40f to 5f, 80f to 4f, 160f to 2f, 315f to 0f, 630f to -1.5f, 1000f to -1f, 2000f to 1f, 4000f to 3f, 8000f to 3.5f, 16000f to 2f, 20000f to 1f)),
        preset("classical31", "31バンド クラシック", "穏やかな低域と高域の広がり",
            listOf(20f to 1f, 63f to 1.5f, 125f to 1f, 250f to 0f, 1000f to 0f, 2000f to .5f, 4000f to 1.5f, 8000f to 2f, 16000f to 2f, 20000f to 1.5f)),
        preset("pop31", "31バンド ポップ", "低音と声をバランスよく調整",
            listOf(20f to 0f, 63f to 2f, 125f to 2.5f, 250f to 0f, 500f to -1f, 1000f to 0f, 2000f to 2f, 4000f to 2.5f, 8000f to 1.5f, 16000f to 1f, 20000f to .5f)),
        preset("jazz31", "31バンド ジャズ", "低域の厚みと自然な輪郭",
            listOf(20f to 1f, 63f to 2f, 125f to 1.5f, 250f to .5f, 500f to 0f, 1000f to .5f, 2000f to 1f, 4000f to 1.5f, 8000f to 1.5f, 16000f to 1f, 20000f to .5f)),
        preset("vocal31", "31バンド ボーカル", "声の帯域を聴き取りやすく調整",
            listOf(20f to -4f, 63f to -3f, 125f to -1.5f, 250f to 0f, 500f to 1.5f, 1000f to 2.5f, 2000f to 3.5f, 4000f to 2f, 8000f to 0f, 16000f to -1.5f, 20000f to -2f)),
        preset("acoustic31", "31バンド アコースティック", "弦や声の存在感を調整",
            listOf(20f to -1f, 63f to 0f, 125f to 1.5f, 250f to 1.5f, 500f to 0f, 1000f to 1f, 2000f to 2f, 4000f to 2.5f, 8000f to 1.5f, 16000f to .5f, 20000f to 0f)),
        preset("electronic31", "31バンド エレクトロニック", "低音のリズムと高域を強調",
            listOf(20f to 5f, 40f to 6f, 80f to 4f, 160f to 1f, 315f to -1.5f, 630f to -2f, 1000f to -1f, 2000f to .5f, 4000f to 2.5f, 8000f to 3.5f, 16000f to 2f, 20000f to 1f)),
        preset("treble31", "31バンド クリア", "高音側の輪郭を調整",
            listOf(20f to 0f, 250f to -.5f, 1000f to .5f, 2000f to 1f, 4000f to 2.5f, 8000f to 4f, 16000f to 3f, 20000f to 2f)),
        preset("bass31", "31バンド ベースブースト", "超低域から低域まで強力に持ち上げる",
            listOf(20f to 7f, 40f to 8f, 80f to 6f, 160f to 3f, 315f to 0f, 630f to -1f, 1000f to 0f, 20000f to 0f), bass = 1f, hz = 100f),
        preset("megabass31", "31バンド メガベース", "EQ と疑似 BASS 150% で最大級の低音",
            listOf(20f to 9f, 40f to 10f, 80f to 7f, 160f to 3f, 315f to 0f, 630f to -1.5f, 1000f to -.5f, 4000f to 1f, 20000f to 1f), bass = 1.5f, hz = 100f),
    )
}
