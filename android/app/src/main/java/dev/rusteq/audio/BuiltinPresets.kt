package dev.rusteq.audio

import kotlin.math.max
import kotlin.math.pow

data class BuiltinPreset(val id: String, val title: String, val description: String, private val base: EqConfig) {
    /** Lightweight visualization; does not calculate the prepared preset on the UI thread. */
    val previewBands: List<EqBand> get() = base.bands
    /** Calculated on first selection, never on the audio thread. */
    val config: EqConfig by lazy {
        val curve = base.copy(preamp = 0f)
        var peak = 0f
        for (rate in listOf(44100f, 48000f, 96000f)) {
            for (i in 0..384) peak = max(peak, curve.responseDb(20f * 1000f.pow(i/384f), rate))
        }
        base.copy(preamp = -max(6f, peak+2f+base.virtualBassAmount*4f))
    }
}

object BuiltinPresets {
    private val frequencies = listOf(31.5f,63f,125f,250f,500f,1000f,2000f,4000f,8000f,16000f)
    private fun preset(id: String, title: String, description: String, gains: List<Float>, bass: Float = 0f, hz: Float = 110f,
                       compressor: Boolean = false, threshold: Float = -18f, ratio: Float = 3f, spatial: Int = 0, width: Float = 1.3f): BuiltinPreset {
        require(gains.size == frequencies.size)
        return BuiltinPreset(id,title,description,EqConfig(bands = frequencies.zip(gains).map { (f,g) -> EqBand(f,g,1.1f) },
            virtualBassAmount = bass, virtualBassHz = hz, compressor = compressor, threshold = threshold, ratio = ratio, spatial = spatial, width = width))
    }
    val all = listOf(
        preset("flat", "フラット / Flat", "周波数バランスを変えずに比較", List(10) { 0f }),
        preset("warm", "ウォーム / Warm", "低音に厚みを添える", listOf(2f,3f,2.5f,1f,0f,0f,0f,-.5f,-1f,-1f)),
        preset("bass", "ベースブースト", "低音を EQ で持ち上げる", listOf(5f,5f,4f,2f,0f,-1f,0f,0f,0f,0f)),
        preset("deep", "ディープベース", "低い帯域を中心に強調", listOf(6f,5f,2f,0f,-1f,0f,0f,0f,0f,0f)),
        preset("woofer", "疑似ウーファー", "倍音を加える疑似 BASS", listOf(1f,2f,1f,0f,-1f,0f,0f,0f,0f,0f), bass=.55f, hz=110f),
        preset("vocal", "ボーカル", "声の帯域を聴き取りやすく調整", listOf(-2f,-1f,-1f,0f,1f,2f,3f,1.5f,0f,-1f)),
        preset("treble", "クリア / Treble", "高音側の輪郭を調整", listOf(0f,0f,-.5f,-1f,0f,.5f,1f,2f,3f,2f)),
        preset("rock", "ロック", "低音と高音にメリハリ", listOf(3f,4f,2f,0f,-1.5f,-1f,1f,2.5f,3f,1f)),
        preset("pop", "ポップ", "低音と声をバランスよく調整", listOf(1f,2f,1f,-1f,0f,1f,2f,1.5f,1f,0f)),
        preset("jazz", "ジャズ", "低域の厚みと自然な輪郭", listOf(1f,1.5f,1f,.5f,0f,.5f,1f,1.5f,1f,0f)),
        preset("classical", "クラシック", "穏やかな低域と高域の広がり", listOf(1f,1f,.5f,0f,0f,0f,.5f,1f,1.5f,1f)),
        preset("acoustic", "アコースティック", "弦や声の存在感を調整", listOf(-1f,0f,1f,1f,0f,1f,2f,2f,1f,0f)),
        preset("electronic", "エレクトロニック", "低音のリズムと高域を強調", listOf(4f,5f,3f,0f,-2f,-1f,0f,2f,3f,1f)),
        preset("podcast", "ポッドキャスト", "低域を控えめにして声を中心に", listOf(-5f,-4f,-2f,-1f,0f,2f,3f,1f,-1f,-3f), compressor=true, threshold=-22f, ratio=2.5f),
        preset("night", "ナイト", "音量差と低音を控えめに", listOf(-4f,-3f,-2f,-1f,0f,1f,1f,0f,-1f,-2f), compressor=true, threshold=-28f, ratio=5f),
        preset("gaming", "ゲーム", "中高域とステレオの広がりを調整", listOf(0f,1f,0f,-1f,0f,1f,2f,2f,1f,0f), spatial=1, width=1.4f),
    )
    fun find(id: String): BuiltinPreset? = all.firstOrNull { it.id == id }
}
