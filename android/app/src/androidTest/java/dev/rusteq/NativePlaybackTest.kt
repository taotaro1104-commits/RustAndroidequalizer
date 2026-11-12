package dev.rusteq

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rusteq.audio.EqConfig
import dev.rusteq.audio.NativeEngine
import dev.rusteq.audio.responseDb
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePlaybackTest {
    private val nativeEngine = NativeEngine()
    @After fun close() { nativeEngine.close() }

    @Test fun measurementOptimizerProducesImportableFiltersAndRejectsInvalidCsv() {
        val csv = "frequency,measured_db,target_db\n20,0,0\n40,0,0\n80,0,0\n160,2,0\n320,5,0\n640,2,0\n1280,0,0\n2560,0,0\n5120,0,0\n10240,0,0\n20000,0,0"
        val result = nativeEngine.optimize(csv, 5, 48000)
        val config = EqConfig.fromAutoEq(result)
        assertEquals(5, config.bands.size)
        assertTrue(config.preamp <= -1f)
        assertTrue(result.contains("shape RMSE"))
        assertTrue(runCatching { nativeEngine.optimize("20,NaN", 5, 48000) }.isFailure)
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as EqApplication
        val original = app.player.state.value.config
        val file = java.io.File(app.cacheDir, "measurement-test.csv").apply { writeText(csv) }
        try {
            instrument.runOnMainSync { app.player.optimizeMeasurement(android.net.Uri.fromFile(file), 5) }
            val deadline = android.os.SystemClock.elapsedRealtime()+15000
            while (app.player.state.value.optimizing && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertNotNull("CSV worker must publish a report: ${app.player.state.value.message}", app.player.state.value.optimizationReport)
            assertEquals(5, app.player.state.value.config.bands.size)
        } finally { instrument.runOnMainSync { app.player.configure { original } }; file.delete() }
    }

    @Test fun builtinPresetsAndBassSettingsAreValidAndBackwardCompatible() {
        val presets = dev.rusteq.audio.BuiltinPresets.all
        assertEquals(16, presets.size)
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        for (preset in presets) {
            val config = preset.config
            assertEquals(10, config.bands.size)
            assertEquals(config, EqConfig.fromJson(config.toJson()))
            assertTrue(config.preamp.isFinite() && config.preamp <= -6f)
            for (i in 0..128) assertTrue(config.responseDb(20f*kotlin.math.exp(kotlin.math.ln(1000f)*i/128f),48000f) <= -1.8f)
            assertTrue(nativeEngine.configure(config.packed()))
        }
        val woofer = dev.rusteq.audio.BuiltinPresets.find("woofer")!!.config
        assertTrue(woofer.virtualBassAmount > 0f)
        assertEquals(woofer, EqConfig.fromJson(woofer.toJson()))
        assertTrue(nativeEngine.configure(woofer.packed().copyOf(102)))
        assertFalse(nativeEngine.configure(FloatArray(103)))
        val legacy = org.json.JSONObject(woofer.toJson()).put("version",1).apply { remove("virtualBassAmount"); remove("virtualBassHz") }
        assertEquals(0f,EqConfig.fromJson(legacy.toString()).virtualBassAmount,0f)
        assertTrue(runCatching { EqConfig.fromJson(org.json.JSONObject(woofer.toJson()).put("virtualBassAmount",2.5).toString()) }.isFailure)
    }

    @Test fun builtin31BandPresetsAreValidAndBassCanReachTwoHundredPercent() {
        val presets = dev.rusteq.audio.BuiltinPresets31.all
        assertTrue(presets.size >= 10)
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        for (preset in presets) {
            val config = preset.config
            assertEquals(31, config.bands.size)
            config.bands.zip(EqConfig.defaultBands(31)).forEach { (band, nominal) -> assertEquals(nominal.hz, band.hz, nominal.hz * 0.02f) }
            assertEquals(config, EqConfig.fromJson(config.toJson()))
            assertTrue(config.preamp.isFinite() && config.preamp <= -6f)
            for (i in 0..128) assertTrue(config.responseDb(20f*kotlin.math.exp(kotlin.math.ln(1000f)*i/128f),48000f) <= -1.8f)
            assertTrue(nativeEngine.configure(config.packed()))
            assertSame(preset, dev.rusteq.audio.BuiltinPresets.find(preset.id))
        }
        val mega = dev.rusteq.audio.BuiltinPresets.find("megabass31")!!.config
        assertTrue(mega.virtualBassAmount > 1f)
        val strong = EqConfig(virtualBassAmount = 2f)
        assertEquals(strong, EqConfig.fromJson(strong.toJson()))
        assertTrue(nativeEngine.configure(strong.packed()))
    }

    @Test fun demoUsesNativeCallbackAndPausePreservesPosition() {
        assertTrue(nativeEngine.configure(EqConfig().packed()))
        assertEquals("", nativeEngine.load(null))
        nativeEngine.setPlaying(true)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var playing = nativeEngine.metrics()
        while (playing[6] <= 0f && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
            playing = nativeEngine.metrics()
        }
        assertTrue("Output stream must have a native sample rate", playing[0] >= 8000f)
        assertTrue("Callback must run", playing[12] > 0f)
        assertTrue("PCM must advance: ${playing.joinToString()} / ${nativeEngine.error()}", playing[6] > 0f)
        assertTrue(nativeEngine.configure(EqConfig(compressor = true, spatial = 1).packed()))
        nativeEngine.setPlaying(false)
        Thread.sleep(100)
        val paused = nativeEngine.metrics()[6]
        Thread.sleep(100)
        assertEquals(paused, nativeEngine.metrics()[6], 0.0001f)
        assertEquals("", nativeEngine.error())
    }

    @Test fun invalidSourceAndRepeatedCloseAreSafe() {
        assertTrue(nativeEngine.load("/file-does-not-exist.wav").isNotBlank())
        nativeEngine.close(); nativeEngine.close()
        assertEquals("", nativeEngine.load(null))
    }

    @Test fun autoEqImportAndJsonRoundTrip() {
        val source = "Preamp: -5.5 dB\nFilter 1: ON PK Fc 100 Hz Gain -3.5 dB Q 1.41\nFilter 2: ON PK Fc 2500 Hz Gain 2.0 dB Q 2.3"
        val config = EqConfig.fromAutoEq(source)
        assertEquals(2, config.bands.size)
        assertEquals(-5.5f, config.preamp, 0f)
        assertEquals(config, EqConfig.fromJson(config.toJson()))
        assertEquals(104, config.packed().size)
        val single = EqConfig.fromAutoEq("Filter 1: ON PK Fc 100 Hz Gain -3 dB Q 1")
        val reset = single.copy(bands = EqConfig.defaultBands(single.bands.size))
        assertTrue(reset.bands.single().hz.isFinite())
        assertEquals(reset, EqConfig.fromJson(reset.toJson()))
        assertTrue(runCatching { EqConfig.fromAutoEq("Filter 1: ON LS Fc 100 Hz Gain 5 dB Q 1") }.isFailure)
    }

    @Test fun roomAndSofaResourcesCanBePreparedAndSwappedWhilePlaying() {
        assertEquals("", nativeEngine.load(null))
        val rate = nativeEngine.metrics()[0].toInt()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val sofa = java.io.File(context.cacheDir, "test-synthetic.sofa")
        testContext.assets.open("synthetic.sofa").use { input -> sofa.outputStream().use { input.copyTo(it) } }
        try {
            assertTrue(runCatching { nativeEngine.prepareEffect(rate, null, "/missing.sofa", 30f, .3f, 1) }.isFailure)
            assertTrue(nativeEngine.metrics()[0] > 0f)
            for ((ir, hrtf) in listOf("" to null, "" to sofa.absolutePath, null to null)) {
                var token = nativeEngine.prepareEffect(rate, ir, hrtf, 30f, .3f, 2)
                try {
                    assertTrue(token != 0L)
                    assertTrue(nativeEngine.effectLatency(token) in listOf(0, 128))
                    nativeEngine.setPlaying(true)
                    val installDeadline = android.os.SystemClock.elapsedRealtime()+5000
                    var installed = nativeEngine.installEffect(token)
                    while (!installed && android.os.SystemClock.elapsedRealtime() < installDeadline) {
                        nativeEngine.metrics() // Collect retired resources on the control thread.
                        Thread.sleep(20)
                        installed = nativeEngine.installEffect(token)
                    }
                    assertTrue("Prepared resource queue must drain", installed)
                    token = 0
                    val startPosition = nativeEngine.metrics()[6]
                    val deadline = android.os.SystemClock.elapsedRealtime()+5000
                    var output = nativeEngine.metrics()
                    // Include warmup and the 50 ms transition, rather than reading a
                    // peak that may still belong to the previous renderer.
                    while ((output[6] < startPosition+.15f || output[5] <= .00001f) && android.os.SystemClock.elapsedRealtime() < deadline) { Thread.sleep(50); output = nativeEngine.metrics() }
                    assertTrue("Prepared spatial processing must advance and produce finite, non-silent output", output[6] >= startPosition+.15f && output[5].isFinite() && output[5] > .00001f)
                } finally { if (token != 0L) nativeEngine.releaseEffect(token) }
            }
        } finally { sofa.delete() }
    }

    @Test fun closingOldOwnerDoesNotCloseAnotherOwnersSession() {
        val other = NativeEngine()
        try {
            assertEquals("", nativeEngine.load(null))
            assertEquals("", other.load(null))
            nativeEngine.close()
            assertTrue(other.metrics()[0] > 0f)
            assertTrue(other.configure(EqConfig().packed()))
        } finally { other.close() }
    }

    @Test fun seekOpensAtRequestedPositionAndKeepsDuration() {
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrument.targetContext.cacheDir, "test-seek.wav")
        instrument.context.assets.open("seek-ramp.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            assertEquals("", nativeEngine.load(file.absolutePath))
            assertEquals(1f, nativeEngine.metrics()[13], .001f)
            assertEquals("", nativeEngine.seekTo(.375))
            assertEquals(.375f, nativeEngine.metrics()[6], .001f)
            assertTrue(nativeEngine.seekTo(Double.NaN).isNotBlank())
            assertTrue(nativeEngine.metrics()[0] > 0f)
        } finally { nativeEngine.close(); file.delete() }
    }
}
