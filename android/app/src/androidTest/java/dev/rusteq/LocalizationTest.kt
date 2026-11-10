package dev.rusteq

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.LocaleList
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.rusteq.audio.BuiltinPresets
import dev.rusteq.audio.EqConfig
import dev.rusteq.ui.localizedDescription
import dev.rusteq.ui.localizedTitle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    private fun contextFor(language: String): Context {
        val base = instrument.targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(language))
        }
        return base.createConfigurationContext(configuration)
    }

    @Test fun androidResourcesSelectEnglishJapaneseAndEnglishFallback() {
        val expected = listOf(
            Triple(R.string.ui_tab_sound, "Sound", "音づくり"),
            Triple(R.string.ui_tab_space, "Space", "空間"),
            Triple(R.string.ui_tab_tools, "Tools", "ツール"),
            Triple(R.string.ui_space_heading, "Explore your soundstage", "音の広がり"),
            Triple(R.string.ui_tools_heading, "Keep your sound settings", "設定を持ち歩く"),
            Triple(R.string.ui_preset_vocal, "Vocal", "ボーカル"),
        )
        val english = contextFor("en")
        val japanese = contextFor("ja")
        val unsupported = contextFor("fr")
        expected.forEach { (id, en, ja) ->
            assertEquals(en, english.getString(id))
            assertEquals(ja, japanese.getString(id))
            assertEquals("Unsupported locales must use default English resources", en, unsupported.getString(id))
        }
    }

    @Test fun deferredStateTextFormatsInCurrentLanguageAndPreservesCustomNames() {
        val bands = uiQuantity(R.plurals.ui_bands, 10, 10)
        val singleBand = uiQuantity(R.plurals.ui_bands, 1, 1)
        val literal = UiText.Literal("My mix / 私のプリセット 🎧")
        val state = PlayerState(title = literal, message = bands, optimizationReport = bands)
        val english = contextFor("en")
        val japanese = contextFor("ja")

        // Resolve the same stored state after a locale change; no player event is needed.
        assertEquals("10 bands", state.message!!.resolve(english))
        assertEquals("10 バンド", state.message!!.resolve(japanese))
        assertEquals("10 bands", state.optimizationReport!!.resolve(english))
        assertEquals("10 バンド", state.optimizationReport!!.resolve(japanese))
        assertEquals("1 band", singleBand.resolve(english))
        assertEquals("1 バンド", singleBand.resolve(japanese))
        assertEquals(literal.value, state.title.resolve(english))
        assertEquals(literal.value, state.title.resolve(japanese))
        assertEquals(literal.value, state.title.resolve(contextFor("fr")))
    }

    @Test fun localizedPresetPresentationPreservesIdentifiersLegacyTitlesAndJson() {
        val legacy = linkedMapOf(
            "flat" to "フラット / Flat", "warm" to "ウォーム / Warm",
            "bass" to "ベースブースト", "deep" to "ディープベース",
            "woofer" to "疑似ウーファー", "vocal" to "ボーカル",
            "treble" to "クリア / Treble", "rock" to "ロック", "pop" to "ポップ",
            "jazz" to "ジャズ", "classical" to "クラシック", "acoustic" to "アコースティック",
            "electronic" to "エレクトロニック", "podcast" to "ポッドキャスト",
            "night" to "ナイト", "gaming" to "ゲーム",
        )
        assertEquals(legacy.keys.toList(), BuiltinPresets.all.map { it.id })
        val configs = BuiltinPresets.all.associate { it.id to it.config.toJson() }
        for (language in listOf("en", "ja", "fr")) {
            val context = contextFor(language)
            BuiltinPresets.all.forEach { preset ->
                assertTrue(preset.localizedTitle(context).isNotBlank())
                assertTrue(preset.localizedDescription(context).isNotBlank())
                assertEquals(legacy[preset.id], preset.title)
                assertEquals(configs[preset.id], preset.config.toJson())
                assertEquals(preset.config, EqConfig.fromJson(preset.config.toJson()))
            }
        }
        val vocal = requireNotNull(BuiltinPresets.find("vocal"))
        assertEquals("Vocal", vocal.localizedTitle(contextFor("en")))
        assertEquals("ボーカル", vocal.localizedTitle(contextFor("ja")))
        assertNotEquals(vocal.localizedDescription(contextFor("en")), vocal.localizedDescription(contextFor("ja")))
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun perAppLanguageNavigationSelectsTheSameVocalPreset() {
        val context = instrument.targetContext
        val app = context.applicationContext as EqApplication
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val originalLocales = localeManager.applicationLocales
        val originalSoundTab = context.getString(R.string.ui_tab_sound)
        val originalConfig = app.player.state.value.config
        val vocal = requireNotNull(BuiltinPresets.find("vocal"))
        var englishConfigJson: String? = null
        var activity: MainActivity? = null

        try {
            for (language in listOf("en", "ja")) {
                // Apply language with no activity alive, avoiding stale recreated activity handles.
                instrument.runOnMainSync {
                    localeManager.applicationLocales = LocaleList.forLanguageTags(language)
                    app.player.configure { originalConfig }
                }
                val localized = contextFor(language)
                await("Application resources must adopt $language") {
                    context.getString(R.string.ui_tab_sound) == localized.getString(R.string.ui_tab_sound)
                }
                activity = instrument.startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ) as MainActivity
                val currentActivity = requireNotNull(activity)
                await("Activity resources must adopt $language") {
                    currentActivity.getString(R.string.ui_tab_sound) == localized.getString(R.string.ui_tab_sound)
                }
                listOf(R.string.ui_tab_sound, R.string.ui_tab_space, R.string.ui_tab_tools).forEach {
                    awaitNode(localized.getString(it))
                }
                tap(localized.getString(R.string.ui_tab_space))
                awaitNode(localized.getString(R.string.ui_space_heading))
                tap(localized.getString(R.string.ui_tab_tools))
                awaitNode(localized.getString(R.string.ui_tools_heading))
                tap(localized.getString(R.string.ui_tab_sound))
                tap(localized.getString(R.string.ui_preset_vocal))
                await("Selecting localized Vocal must retain its legacy title and sound settings") {
                    app.player.state.value.presetTitle == "ボーカル" && app.player.state.value.config == vocal.config
                }
                val json = app.player.state.value.config.toJson()
                assertEquals("ボーカル", app.player.state.value.presetTitle)
                assertEquals(vocal.config.toJson(), json)
                if (language == "en") englishConfigJson = json else assertEquals(englishConfigJson, json)
                instrument.runOnMainSync { currentActivity.finish() }
                instrument.waitForIdleSync()
                await("Previous activity must finish before switching language") { currentActivity.isDestroyed }
                activity = null
            }
        } finally {
            val remainingActivity = activity
            instrument.runOnMainSync {
                remainingActivity?.finish()
                app.player.configure { originalConfig }
            }
            instrument.waitForIdleSync()
            if (remainingActivity != null) await("Activity must finish before restoring language") { remainingActivity.isDestroyed }
            instrument.runOnMainSync { localeManager.applicationLocales = originalLocales }
            await("Original per-app language must be restored") {
                localeManager.applicationLocales == originalLocales &&
                    context.getString(R.string.ui_tab_sound) == originalSoundTab
            }
            instrument.waitForIdleSync()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }

    private fun findVisible(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
        for (index in 0 until node.childCount) findVisible(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun awaitNode(label: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("Visible accessible control missing: $label") {
            found = findVisible(instrument.uiAutomation.rootInActiveWindow, label)
            found != null
        }
        return requireNotNull(found)
    }

    private fun tap(label: String) {
        val bounds = Rect().also { awaitNode(label).getBoundsInScreen(it) }
        assertFalse("Control must have a touch target: $label", bounds.isEmpty)
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrument.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrument.uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
        instrument.waitForIdleSync()
    }
}
