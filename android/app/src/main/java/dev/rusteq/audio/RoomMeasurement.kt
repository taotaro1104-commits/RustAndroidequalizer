package dev.rusteq.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import dev.rusteq.UiTextException
import dev.rusteq.R
import dev.rusteq.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Plays pink noise through the current output and records it with the microphone to measure the
 * room + speaker response. Audio is analysed in memory only: nothing is written to storage or sent anywhere.
 * The caller must hold RECORD_AUDIO and must have paused normal playback.
 */
object RoomMeasurement {
    private const val RATE = 48000
    private const val AMBIENT_SECONDS = 2
    private const val NOISE_SECONDS = 8
    private const val SKIP_SECONDS = 1.5
    private const val NOISE_DBFS = -22f

    class Result(val signalDb: DoubleArray, val ambientDb: DoubleArray)

    @SuppressLint("MissingPermission") // checked by the UI before calling
    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        val audio = context.getSystemService(AudioManager::class.java)
        val source = if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") MediaRecorder.AudioSource.UNPROCESSED
        else MediaRecorder.AudioSource.VOICE_RECOGNITION // least processing among the standard sources
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, RATE))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); throw UiTextException(uiText(R.string.player_room_mic_failed)) }
        val noise = noiseBuffer()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(noise.size * 2).build()
        try {
            record.startRecording()
            val ambient = SpectrumAnalyzer(RATE)
            capture(record, AMBIENT_SECONDS * RATE, 0, ambient)
            track.write(noise, 0, noise.size)
            track.play()
            val signal = SpectrumAnalyzer(RATE)
            val clipped = capture(record, NOISE_SECONDS * RATE, (SKIP_SECONDS * RATE).toInt(), signal)
            if (clipped) throw UiTextException(uiText(R.string.player_room_too_loud))
            if (!signal.hasData() || !ambient.hasData()) throw UiTextException(uiText(R.string.player_room_no_signal))
            Result(signal.bandLevelsDb(), ambient.bandLevelsDb())
        } finally {
            runCatching { record.stop() }; record.release()
            runCatching { track.stop() }; track.release()
        }
    }

    private fun noiseBuffer(): ShortArray {
        val n = RATE * NOISE_SECONDS
        val gen = PinkNoise()
        val amp = HearingProfile.amplitude(NOISE_DBFS) * 32767f
        val fade = RATE / 10
        return ShortArray(n) { i ->
            val env = when {
                i < fade -> .5f - .5f * cos(PI.toFloat() * i / fade)
                i >= n - fade -> .5f - .5f * cos(PI.toFloat() * (n - 1 - i) / fade)
                else -> 1f
            }
            (gen.next() * amp * env).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Reads [total] samples; the first [skip] go unanalysed. Returns true if the microphone clipped. */
    private fun capture(record: AudioRecord, total: Int, skip: Int, analyzer: SpectrumAnalyzer): Boolean {
        val buffer = ShortArray(4096)
        val floats = FloatArray(4096)
        var read = 0
        var clipped = false
        while (read < total) {
            val n = record.read(buffer, 0, minOf(buffer.size, total - read))
            if (n <= 0) throw UiTextException(uiText(R.string.player_room_mic_failed))
            for (i in 0 until n) { floats[i] = buffer[i] / 32768f; if (abs(buffer[i].toInt()) >= 32000) clipped = true }
            if (read >= skip) analyzer.add(floats, n)
            read += n
        }
        return clipped
    }
}
