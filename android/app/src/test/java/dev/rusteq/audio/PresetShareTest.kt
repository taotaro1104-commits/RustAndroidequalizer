package dev.rusteq.audio

import org.junit.Assert.*
import org.junit.Test

class PresetShareTest {
    private val json = """{"version":2,"preamp":-6,"bands":[[20,3.5,4.3],[25,0,4.3]],"echoMix":0.3}"""

    @Test fun roundTripsAndIsMuchShorterThanTheJsonForRealPresets() {
        assertEquals(json, PresetShare.decodeJson(PresetShare.encodeJson(json)))
        val big = "{\"bands\":[" + (0 until 31).joinToString(",") { "[${20 + it * 7},${it % 5 - 2}.5,4.3]" } + "],\"preamp\":-6}"
        val link = PresetShare.encodeJson(big)
        assertTrue(link.length < big.length)
        assertEquals(big, PresetShare.decodeJson(link))
    }

    @Test fun findsTheTokenInsideSurroundingChatText() {
        val link = PresetShare.encodeJson(json)
        assertEquals(json, PresetShare.decodeJson("Try my EQ!\n$link\nenjoy"))
        assertEquals(json, PresetShare.decodeJson("(${link})"))
    }

    @Test fun linkUsesOnlyUrlSafeCharacters() {
        val link = PresetShare.encodeJson(json)
        assertTrue(link.startsWith(PresetShare.PREFIX))
        assertTrue(link.removePrefix(PresetShare.PREFIX).all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
    }

    @Test fun garbageTruncatedAndOversizedInputIsRejectedWithoutThrowing() {
        assertNull(PresetShare.decodeJson(""))
        assertNull(PresetShare.decodeJson("hello world"))
        assertNull(PresetShare.decodeJson("rusteq1:"))
        assertNull(PresetShare.decodeJson("rusteq1:!!!!"))
        assertNull(PresetShare.decodeJson("rusteq1:AAAA"))
        val link = PresetShare.encodeJson(json)
        assertNull(PresetShare.decodeJson(link.dropLast(6)))
        assertNull(PresetShare.decodeJson(PresetShare.PREFIX + "A".repeat(20000)))
    }

    @Test fun inflationBombIsStopped() {
        val bomb = PresetShare.encodeJson("0".repeat(5_000_000))
        assertTrue(bomb.length < 16 * 1024) // tiny on the wire...
        assertNull(PresetShare.decodeJson(bomb)) // ...but refused after the 64 KB inflate limit
    }
}
