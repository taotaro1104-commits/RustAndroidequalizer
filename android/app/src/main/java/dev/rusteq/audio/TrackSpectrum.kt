package dev.rusteq.audio

import android.content.Context
import android.net.Uri

/** Long-term 31-band spectrum of a file, from at most [PcmDecoder.MAX_SECONDS] seconds. */
object TrackSpectrum {
    fun analyze(context: Context, uri: Uri): DoubleArray? = run { var a: SpectrumAnalyzer? = null; var rate = 0
        PcmDecoder.decode(context, uri) { m, n, sr -> if (a == null || sr != rate) { a = SpectrumAnalyzer(sr); rate = sr }; a!!.add(m, n) }
        a?.takeIf { it.hasData() }?.bandLevelsDb() }
    fun analyze(path: String): DoubleArray? = run { var a: SpectrumAnalyzer? = null; var rate = 0
        PcmDecoder.decode(path) { m, n, sr -> if (a == null || sr != rate) { a = SpectrumAnalyzer(sr); rate = sr }; a!!.add(m, n) }
        a?.takeIf { it.hasData() }?.bandLevelsDb() }
}
