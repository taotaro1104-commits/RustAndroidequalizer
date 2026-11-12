package dev.rusteq.audio

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Compact text form of a preset for sharing through any chat or note app:
 * `rusteq1:` + URL-safe Base64 of the deflated preset JSON. The decoder never trusts its input: it limits the
 * encoded and inflated sizes and the caller still runs the normal [EqConfig.fromJson] validation.
 */
object PresetShare {
    const val PREFIX = "rusteq1:"
    private const val MAX_ENCODED = 16 * 1024
    private const val MAX_JSON = 64 * 1024
    private val token = Regex("rusteq1:([A-Za-z0-9_-]+)")

    fun encodeJson(json: String): String {
        val input = json.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(input); deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** Finds the first share token anywhere in [text] (chat apps add words around links) and returns its JSON, or null. */
    fun decodeJson(text: String): String? {
        val encoded = token.find(text)?.groupValues?.get(1) ?: return null
        if (encoded.length > MAX_ENCODED) return null
        val compressed = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_JSON) return null // decompression bomb guard
            }
            if (!inflater.finished()) null else out.toString(Charsets.UTF_8.name())
        } catch (_: DataFormatException) { null } finally { inflater.end() }
    }

    fun encode(config: EqConfig): String = encodeJson(config.copy(bypass = false).toJson())
    fun decode(text: String): EqConfig? = decodeJson(text)?.let { runCatching { EqConfig.fromJson(it) }.getOrNull() }
}
