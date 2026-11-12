package dev.rusteq.audio

import android.content.SharedPreferences
import android.media.AudioDeviceInfo

enum class OutputKind { BLUETOOTH, WIRED, SPEAKER, OTHER }

/** Maps the currently connected output device types to one kind; headsets win over the built-in speaker. */
object OutputClassifier {
    private val bluetooth = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST)
    private val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL)

    fun classify(types: Collection<Int>): OutputKind = when {
        types.any { it in bluetooth } -> OutputKind.BLUETOOTH
        types.any { it in wired } -> OutputKind.WIRED
        types.any { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } -> OutputKind.SPEAKER
        else -> OutputKind.OTHER
    }
}

/** Profile keys, in priority order: this track, night time, this kind of output. */
object ProfileResolver {
    const val NIGHT_START = 22
    const val NIGHT_END = 6
    const val NIGHT_KEY = "night"

    fun isNight(hour: Int) = hour >= NIGHT_START || hour < NIGHT_END
    fun deviceKey(kind: OutputKind) = "device:${kind.name}"
    fun trackKey(id: String) = "track:" + Integer.toHexString(id.hashCode())

    fun candidates(trackId: String?, hour: Int, kind: OutputKind): List<String> =
        listOfNotNull(trackId?.let(::trackKey), NIGHT_KEY.takeIf { isNight(hour) }, deviceKey(kind))

    fun resolve(candidates: List<String>, saved: Set<String>): String? = candidates.firstOrNull { it in saved }
}

/** Saved sound per profile key, plus the on/off switch, in SharedPreferences. */
class ProfileStore(private val preferences: SharedPreferences) {
    var enabled: Boolean
        get() = preferences.getBoolean("autoprofile_on", false)
        set(value) { preferences.edit().putBoolean("autoprofile_on", value).apply() }

    fun keys(): Set<String> = preferences.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()
    fun get(key: String): EqConfig? = preferences.getString(PREFIX + key, null)?.let { runCatching { EqConfig.fromJson(it) }.getOrNull() }
    fun put(key: String, config: EqConfig) { preferences.edit().putString(PREFIX + key, config.copy(bypass = false).toJson()).apply() }
    fun remove(key: String) { preferences.edit().remove(PREFIX + key).apply() }

    private companion object { const val PREFIX = "autoprofile:" }
}
