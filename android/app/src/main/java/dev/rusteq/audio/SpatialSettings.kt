package dev.rusteq.audio

data class SpatialSettings(
    val hrtf: Boolean = false,
    val sofaPath: String? = null,
    val sofaName: String? = null,
    val reverb: Int = 0, // 0 off, 1 synthetic room, 2 imported IR
    val irPath: String? = null,
    val irName: String? = null,
    val wet: Float = .25f,
    val azimuth: Float = 30f,
    val quality: Int = 1,
)
