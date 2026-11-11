package dev.rusteq.audio

import android.content.SharedPreferences
import org.json.JSONObject

data class SavedSoundSettings(
    val config: EqConfig = EqConfig(),
    val spatial: SpatialSettings = SpatialSettings(),
    val presetTitle: String? = null,
    val selectedBand: Int = 0,
    val restoreFailed: Boolean = false,
)

/** Keep the legacy EQ JSON and the rest of the sound profile in one preference transaction. */
class SoundSettingsStore(private val preferences: SharedPreferences) {
    fun read(): SavedSoundSettings {
        val rawEq = preferences.getString("last", null)
        val eq = rawEq?.let { runCatching { decodeSavedEq(it) } }
        val config = eq?.getOrNull() ?: EqConfig()
        val rawSpatial = preferences.getString("spatial_settings", null)
        val spatial = rawSpatial?.let { runCatching { decodeSpatial(it) } }
        return SavedSoundSettings(
            config = config,
            spatial = spatial?.getOrNull() ?: SpatialSettings(),
            presetTitle = if (eq?.isFailure == true) null else preferences.getString("selected_preset_title", null),
            selectedBand = preferences.getInt("selected_band", 0).coerceIn(config.bands.indices),
            restoreFailed = eq?.isFailure == true || spatial?.isFailure == true,
        )
    }

    private fun decodeSavedEq(text: String): EqConfig = runCatching { EqConfig.fromJson(text) }.getOrElse { original ->
        // Older logarithmic sliders could serialize 20000.008 Hz at their
        // endpoint. Repair only this sub-0.02 Hz legacy rounding error; imports
        // continue to use the strict EqConfig parser.
        val json = JSONObject(text)
        val bands = json.getJSONArray("bands")
        var repaired = false
        for (index in 0 until bands.length()) {
            val band = bands.getJSONArray(index)
            val hz = band.getDouble(0)
            if (hz > 20000.0 && hz <= 20000.02) { band.put(0, 20000f); repaired = true }
        }
        if (!repaired) throw original
        EqConfig.fromJson(json.toString())
    }

    fun save(config: EqConfig, spatial: SpatialSettings, presetTitle: String?, selectedBand: Int) {
        val editor = preferences.edit()
            .putString("last", config.toJson())
            .putString("spatial_settings", encodeSpatial(spatial))
            .putString("selected_preset_title", presetTitle)
            .putInt("selected_band", selectedBand.coerceIn(config.bands.indices))
        editor.apply()
    }

    /** Called by the application IO scope, never by activity lifecycle callbacks. */
    fun flush(): Boolean = preferences.edit().commit()

    private fun encodeSpatial(value: SpatialSettings): String = JSONObject().apply {
        put("version", 1)
        put("hrtf", value.hrtf); put("sofaPath", value.sofaPath); put("sofaName", value.sofaName)
        put("reverb", value.reverb); put("irPath", value.irPath); put("irName", value.irName)
        put("wet", value.wet); put("azimuth", value.azimuth); put("quality", value.quality)
    }.toString()

    private fun decodeSpatial(text: String): SpatialSettings {
        val json = JSONObject(text)
        require(json.getInt("version") == 1)
        fun optional(key: String): String? = if (json.isNull(key)) null else json.getString(key)
        fun number(key: String, min: Float, max: Float): Float = json.getDouble(key).toFloat().also {
            require(it.isFinite() && it in min..max)
        }
        return SpatialSettings(
            hrtf = json.getBoolean("hrtf"), sofaPath = optional("sofaPath"), sofaName = optional("sofaName"),
            reverb = json.getInt("reverb").also { require(it in 0..2) },
            irPath = optional("irPath"), irName = optional("irName"),
            wet = number("wet", 0f, 1f), azimuth = number("azimuth", 10f, 80f),
            quality = json.getInt("quality").also { require(it in 0..2) },
        )
    }
}
