package dev.rusteq.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Plays short sine pulses for the hearing test. Independent of the EQ engine (the test hears the raw device). */
object ToneOutput {
    private const val RATE = 48000

    /** 16-bit mono sine with 40 ms raised-cosine fades so there are no clicks. */
    internal fun samples(hz: Float, dbfs: Float, ms: Int): ShortArray {
        val n = RATE * ms / 1000
        val amp = HearingProfile.amplitude(dbfs.coerceAtMost(HearingProfile.MAX_DB + HearingProfile.STEP_DB)) * 32767f
        val fade = RATE * 40 / 1000
        return ShortArray(n) { i ->
            val env = when {
                i < fade -> .5f - .5f * cos(PI.toFloat() * i / fade)
                i >= n - fade -> .5f - .5f * cos(PI.toFloat() * (n - 1 - i) / fade)
                else -> 1f
            }
            (sin(2.0 * PI * hz * i / RATE) * amp * env).toInt().toShort()
        }
    }

    suspend fun pulse(hz: Float, dbfs: Float, ms: Int = 1000) {
        val data = samples(hz, dbfs, ms)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(data.size * 2).build()
        try {
            track.write(data, 0, data.size)
            track.play()
            delay(ms + 60L)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
