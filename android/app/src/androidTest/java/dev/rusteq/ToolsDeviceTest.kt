package dev.rusteq

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rusteq.audio.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ToolsDeviceTest {
    @Before fun grantMicrophone() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clickWav(bpm: Double, seconds: Int = 20, rate: Int = 44100): File {
        val frames = rate * seconds
        val pcm = ShortArray(frames)
        var t = 0.0
        while (t < frames) {
            val s = t.toInt()
            for (i in 0 until 2000) if (s + i < frames) pcm[s + i] = (exp(-i / 300.0) * sin(2 * PI * 1500 * i / rate) * 20000).toInt().toShort()
            t += rate * 60.0 / bpm
        }
        val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> pcm.forEach { b.putShort(it) } }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.capacity())
        return File.createTempFile("bpm-", ".wav", context.cacheDir).also { it.writeBytes(header.array() + data.array()) }
    }

    @Test fun tempoIsDetectedFromADecodedFile() {
        val f = clickWav(120.0)
        try {
            val d = BpmDetector()
            assertTrue(PcmDecoder.decode(f.path) { m, n, sr -> d.add(m, n, sr) })
            assertEquals(120.0, d.bpm()!!.toDouble(), 2.5)
        } finally { f.delete() }
    }

    @Test fun profilesRoundTripAndTheStoreIsEmptyByDefault() {
        val prefs = context.getSharedPreferences("tools-test-" + System.nanoTime(), android.content.Context.MODE_PRIVATE)
        val store = ProfileStore(prefs)
        assertFalse(store.enabled); assertTrue(store.keys().isEmpty())
        val c = EqConfig(preamp = -9f, echoMix = .4f, bypass = true)
        store.put(ProfileResolver.NIGHT_KEY, c)
        store.put(ProfileResolver.deviceKey(OutputKind.BLUETOOTH), c)
        assertEquals(setOf("night", "device:BLUETOOTH"), store.keys())
        assertEquals(c.copy(bypass = false), store.get("night")) // a profile never stores bypass
        store.remove("night"); assertNull(store.get("night"))
        store.enabled = true; assertTrue(store.enabled)
    }

    @Test fun outputKindOfTheRealDeviceIsClassified() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val kind = OutputClassifier.classify(audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
        assertNotNull(kind)
    }

    @Test fun toneSamplesAreFadedAndBoundedByTheTestLevel() {
        val s = ToneOutput.samples(1000f, -25f, 1000)
        assertEquals(48000, s.size)
        assertEquals(0, s.first().toInt())
        val peak = s.maxOf { kotlin.math.abs(it.toInt()) }
        assertEquals(HearingProfile.amplitude(-25f) * 32767f, peak.toFloat(), 400f)
    }

    @Test fun roomMeasurementEitherProducesAResponseOrFailsWithAShownMessage() {
        // The emulator's microphone may be silent; the contract is: a result, or a UiTextException (never a crash).
        val outcome = runCatching { runBlocking { RoomMeasurement.run(context) } }
        outcome.onSuccess { r -> assertEquals(31, r.signalDb.size); assertEquals(31, r.ambientDb.size) }
        outcome.onFailure { assertTrue("unexpected ${it::class}: ${it.message}", it is UiTextException) }
    }
}
