package dev.rusteq

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rusteq.audio.ExternalEqSettingsStore
import dev.rusteq.audio.SpatialSettings
import dev.rusteq.audio.EqBand
import dev.rusteq.audio.EqConfig
import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundSettingsTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as EqApplication
    private fun await(predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        while (!predicate() && SystemClock.elapsedRealtime() < until) Thread.sleep(30)
        assertTrue("Timed out waiting for sound settings", predicate())
    }

    @Test fun minimumQSettingRoundTripsWithoutResettingTheProfile() {
        val expected = EqConfig(bands = listOf(EqBand(1000f, db = 4f, q = .1f)), preamp = -9f)
        val restored = runCatching { EqConfig.fromJson(expected.toJson()) }.getOrNull()
        assertEquals("The minimum Q slider value must remain a valid saved profile", expected, restored)
    }

    @Test fun importedEffectFilesSurviveCacheEvictionAndRecreation() {
        val originalSound = app.player.state.value.config
        val originalSpatial = app.player.state.value.spatialSettings
        val input = java.io.File(app.cacheDir, "background-effect.wav")
        var restored: PlayerController? = null
        var imported: java.io.File? = null
        instrument.context.assets.open("seek-ramp.wav").use { source -> input.outputStream().use { source.copyTo(it) } }
        try {
            instrument.runOnMainSync { app.player.importEffect(android.net.Uri.fromFile(input), false) }
            await { app.player.state.value.spatialSettings.irPath != originalSpatial.irPath }
            val expected = app.player.state.value.spatialSettings
            imported = java.io.File(expected.irPath!!)
            assertTrue("Imported effect assets must be kept outside evictable cache", imported!!.canonicalPath.startsWith(app.filesDir.canonicalPath + java.io.File.separator))
            input.delete()
            instrument.runOnMainSync { app.player.setForeground(false); restored = PlayerController(app) }
            assertEquals(expected, restored!!.state.value.spatialSettings)
            assertTrue(imported!!.isFile)
        } finally {
            instrument.runOnMainSync { restored?.release(); app.player.spatial { originalSpatial }; app.player.configure { originalSound } }
            input.delete()
            imported?.delete()
        }
    }

    @Test fun aLegacyRoundedFrequencyDoesNotDiscardTheSavedEq() {
        val original = app.player.state.value.config
        var restored: PlayerController? = null
        try {
            val expected = EqConfig(bands = EqConfig.defaultBands(10).map { it.copy(db = 3f) }, preamp = -8f)
            val json = org.json.JSONObject(expected.toJson())
            json.getJSONArray("bands").getJSONArray(9).put(0, 20000.008)
            app.getSharedPreferences("eq", android.content.Context.MODE_PRIVATE).edit().putString("last", json.toString()).commit()
            instrument.runOnMainSync { restored = PlayerController(app) }
            assertEquals("Legacy slider roundoff must not discard all EQ values", expected, restored!!.state.value.config)
        } finally {
            instrument.runOnMainSync { restored?.release(); app.player.configure { original } }
        }
    }

    @Test fun upperFrequencySliderEndpointRemainsRestorable() {
        val original = app.player.state.value.config
        var first: PlayerController? = null
        var restored: PlayerController? = null
        try {
            instrument.runOnMainSync {
                first = PlayerController(app)
                first!!.bandCount(10)
                first!!.select(9)
                first!!.band(hz = 10f.pow(log10(20000f)), db = 5f)
                first!!.setForeground(false)
                first!!.release()
                first = null
                restored = PlayerController(app)
            }
            assertEquals("A maximum-frequency edit must not reset the saved EQ band count", 10, restored!!.state.value.config.bands.size)
            assertEquals(20000f, restored!!.state.value.config.bands.last().hz, .01f)
            assertEquals(5f, restored!!.state.value.config.bands.last().db, 0f)
        } finally {
            instrument.runOnMainSync { first?.release(); restored?.release(); app.player.configure { original } }
        }
    }

    @Test fun builtinPresetSelectionSurvivesControllerRecreation() {
        val original = app.player.state.value.config
        var first: PlayerController? = null
        var restored: PlayerController? = null
        try {
            instrument.runOnMainSync { first = PlayerController(app); first!!.applyBuiltinPreset("rock") }
            await { first!!.state.value.presetTitle != null }
            val expected = first!!.state.value
            instrument.runOnMainSync {
                first!!.setForeground(false)
                first!!.release()
                first = null
                restored = PlayerController(app)
            }
            assertEquals("EQ values survive recreation", expected.config, restored!!.state.value.config)
            assertEquals("The selected preset must survive background process recreation", expected.presetTitle, restored!!.state.value.presetTitle)
        } finally {
            instrument.runOnMainSync { first?.release(); restored?.release(); app.player.configure { original } }
        }
    }

    @Test fun spatialSettingsSurviveControllerRecreation() {
        val original = app.player.state.value.config
        var first: PlayerController? = null
        var restored: PlayerController? = null
        val expected = SpatialSettings(reverb = 1, wet = .63f, azimuth = 55f, quality = 2)
        try {
            instrument.runOnMainSync {
                first = PlayerController(app)
                first!!.spatial { expected }
                first!!.setForeground(false)
                first!!.release()
                first = null
                restored = PlayerController(app)
            }
            assertEquals("Spatial sound settings must survive background process recreation", expected, restored!!.state.value.spatialSettings)
        } finally {
            instrument.runOnMainSync { first?.release(); restored?.release(); app.player.configure { original } }
        }
    }

    @Test fun eqAndTargetSelectionSurviveGoingHomeAndControllerRecreation() {
        val phase = InstrumentationRegistry.getArguments().getString("process_probe")
        if (phase != null) { runProcessProbe(phase); return }
        val sound = app.player.state.value.config
        val targets = app.externalEqSettings.state.value
        app.externalEqSettings.update { it.copy(enabled = false) }
        val activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var restored: PlayerController? = null
        try {
            instrument.runOnMainSync {
                app.player.configure { it.copy(preamp = -9f, virtualBassAmount = .42f, compressor = true) }
                app.externalEqSettings.update { it.copy(selectedPackages = setOf("com.google.android.youtube", "dev.rusteq.fixture"), enabled = true, resumeAfterBoot = false) }
            }
            val expectedSound = app.player.state.value.config
            val expectedTargets = app.externalEqSettings.state.value
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrument.waitForIdleSync()
            instrument.runOnMainSync { restored = PlayerController(app) }
            assertEquals(expectedSound, restored!!.state.value.config)
            assertEquals("Saved targets and enabled intent must survive backgrounding", expectedTargets, ExternalEqSettingsStore(app).state.value)
        } finally {
            instrument.runOnMainSync { restored?.release(); ExternalEqController.stop(app); app.player.configure { sound }; activity.finish() }
            app.externalEqSettings.update { targets }
        }
    }

    /** Invoked in two separate instrumentation processes by the release smoke runner. */
    private fun runProcessProbe(phase: String) {
        val expectedTargets = setOf("com.google.android.youtube", "dev.rusteq.fixture")
        val expectedSpatial = SpatialSettings(reverb = 1, wet = .63f, azimuth = 55f, quality = 2)
        if (phase == "prepare") {
            val activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrument.runOnMainSync {
                app.player.bandCount(10)
                app.player.select(9)
                app.player.band(hz = 10f.pow(log10(20000f)), db = 5f, q = .1f)
                app.player.configure { it.copy(preamp = -9f) }
                app.player.savePreset("__background_probe__")
                app.player.applyPreset("__background_probe__")
                app.player.select(9)
                app.player.spatial { expectedSpatial }
                app.externalEqSettings.update { it.copy(selectedPackages = expectedTargets, enabled = true, resumeAfterBoot = false) }
                ExternalEqController.start(app)
            }
            await { app.systemEqState.value.running }
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrument.waitForIdleSync()
            assertFalse(activity.isDestroyed)
            return // Keep only this private emulator's settings for the next process.
        }
        require(phase == "verify")
        val restored = app.player.state.value
        assertEquals(10, restored.config.bands.size)
        assertEquals(-9f, restored.config.preamp, 0f)
        assertEquals(20000f, restored.config.bands.last().hz, 0f)
        assertEquals(5f, restored.config.bands.last().db, 0f)
        assertEquals(.1f, restored.config.bands.last().q, 0f)
        assertEquals(9, restored.selected)
        assertEquals("__background_probe__", restored.presetTitle)
        assertEquals(expectedSpatial, restored.spatialSettings)
        val external = app.externalEqSettings.state.value
        assertEquals(expectedTargets, external.selectedPackages)
        assertTrue(external.enabled)
        assertFalse(external.resumeAfterBoot)
        val activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { await { app.systemEqState.value.running } }
        finally {
            instrument.runOnMainSync {
                ExternalEqController.stop(app)
                app.player.spatial { SpatialSettings() }
                app.player.configure { EqConfig() }
                app.player.select(0)
                app.player.deletePreset("__background_probe__")
                app.externalEqSettings.update { it.copy(selectedPackages = emptySet(), enabled = false, resumeAfterBoot = true) }
                activity.finish()
            }
        }
    }
}
