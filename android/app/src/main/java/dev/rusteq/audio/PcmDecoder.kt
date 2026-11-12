package dev.rusteq.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes an audio file with the platform codecs into mono float chunks for analysis (spectrum, tempo).
 * Control plane only. Long tracks are read from the middle for at most [MAX_SECONDS] seconds.
 */
object PcmDecoder {
    const val MAX_SECONDS = 90
    private const val TIMEOUT_US = 10_000L

    /** Receives mono samples and their sample rate; the rate can change after an output-format change. */
    fun interface Sink { fun accept(mono: FloatArray, count: Int, sampleRate: Int) }

    fun decode(context: Context, uri: Uri, sink: Sink): Boolean = decode({ it.setDataSource(context, uri, null) }, sink)
    fun decode(path: String, sink: Sink): Boolean = decode({ it.setDataSource(path) }, sink)

    /** Returns false when no audio track could be decoded. */
    private fun decode(source: (MediaExtractor) -> Unit, sink: Sink): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            source(extractor)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val durationUs = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > (MAX_SECONDS * 1.6 * 1_000_000L).toLong()) extractor.seekTo(durationUs / 2 - MAX_SECONDS * 500_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!).also { it.configure(input, null, null, 0); it.start() }

            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var sampleRate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var frames = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        sampleRate = rate
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    out >= 0 -> {
                        idle = 0
                        val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset).limit(info.offset + info.size)
                        frames += feed(buffer, channels, encoding, sampleRate, sink)
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || frames >= MAX_SECONDS.toLong() * sampleRate) outputDone = true
                    }
                    else -> if (inputDone && ++idle > 500) outputDone = true // decoder never produced EOS; give up with what we have
                }
            }
            return frames > 0
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Downmixes interleaved PCM to mono and hands it to the sink; returns the number of frames consumed. */
    private fun feed(buffer: java.nio.ByteBuffer, channels: Int, encoding: Int, sampleRate: Int, sink: Sink): Int {
        val bytesPer = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frames = buffer.remaining() / (bytesPer * channels)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += if (bytesPer == 4) buffer.getFloat() else buffer.getShort() / 32768f
            mono[i] = sum / channels
        }
        sink.accept(mono, frames, sampleRate)
        return frames
    }
}
