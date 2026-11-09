package dev.rusteq.audio

/** All calls are control operations. PCM never crosses the JNI boundary. */
class NativeEngine {
    companion object {
        private val nextOwner = java.util.concurrent.atomic.AtomicLong(1)
        init { System.loadLibrary("rusteq") }
    }
    private val ownerId = nextOwner.getAndIncrement()
    external fun load(path: String?): String
    external fun configure(values: FloatArray): Boolean
    external fun setPlaying(playing: Boolean)
    external fun metrics(): FloatArray
    external fun error(): String
    external fun close()
    external fun seekTo(seconds: Double): String
    external fun prepareEffect(sampleRate: Int, ir: String?, sofa: String?, azimuth: Float, wet: Float, quality: Int): Long
    /** Consumes the prepared token only when true is returned. */
    external fun installEffect(prepared: Long): Boolean
    external fun releaseEffect(prepared: Long)
    external fun effectLatency(prepared: Long): Int
    external fun optimize(csv: String, bands: Int, sampleRate: Int): String
}

