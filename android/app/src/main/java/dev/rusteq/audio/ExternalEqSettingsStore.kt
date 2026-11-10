package dev.rusteq.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExternalEqSettings(
    val selectedPackages: Set<String> = emptySet(),
    val enabled: Boolean = false,
    val resumeAfterBoot: Boolean = true,
) {
    val shouldRun: Boolean get() = enabled && selectedPackages.isNotEmpty()
}

/** Only stable package names and user intent are persisted; session IDs are transient. */
class ExternalEqSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("external_eq", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<ExternalEqSettings> = mutable.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        synchronized(this) {
            val settings = read()
            mutable.value = settings
            if (settings.selectedPackages.isEmpty() && preferences.getBoolean("enabled", false)) {
                preferences.edit().putBoolean("enabled", false).apply()
            }
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        if (mutable.value.selectedPackages.isEmpty() && preferences.getBoolean("enabled", false)) {
            preferences.edit().putBoolean("enabled", false).apply()
        }
    }

    @Synchronized
    fun update(transform: (ExternalEqSettings) -> ExternalEqSettings) {
        val requested = transform(read())
        val packages = requested.selectedPackages.toSet()
        val normalized = requested.copy(selectedPackages = packages, enabled = requested.enabled && packages.isNotEmpty())
        preferences.edit()
            .putStringSet("selected_packages", packages)
            .putBoolean("enabled", normalized.enabled)
            .putBoolean("resume_after_boot", normalized.resumeAfterBoot)
            .apply()
        mutable.value = normalized
    }

    private fun read(): ExternalEqSettings {
        val packages = preferences.getStringSet("selected_packages", emptySet()).orEmpty().toSet()
        return ExternalEqSettings(
            selectedPackages = packages,
            enabled = preferences.getBoolean("enabled", false) && packages.isNotEmpty(),
            resumeAfterBoot = preferences.getBoolean("resume_after_boot", true),
        )
    }
}
