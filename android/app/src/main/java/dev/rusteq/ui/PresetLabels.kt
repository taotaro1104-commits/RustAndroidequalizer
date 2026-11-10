package dev.rusteq.ui

import android.content.Context
import dev.rusteq.R
import dev.rusteq.audio.BuiltinPreset

// Labels are localized separately from stable preset IDs and legacy saved titles.
fun BuiltinPreset.localizedTitle(context: Context): String = when (id) {
    "flat" -> context.getString(R.string.ui_preset_flat)
    "warm" -> context.getString(R.string.ui_preset_warm)
    "bass" -> context.getString(R.string.ui_preset_bass)
    "deep" -> context.getString(R.string.ui_preset_deep)
    "woofer" -> context.getString(R.string.ui_preset_woofer)
    "vocal" -> context.getString(R.string.ui_preset_vocal)
    "treble" -> context.getString(R.string.ui_preset_treble)
    "rock" -> context.getString(R.string.ui_preset_rock)
    "pop" -> context.getString(R.string.ui_preset_pop)
    "jazz" -> context.getString(R.string.ui_preset_jazz)
    "classical" -> context.getString(R.string.ui_preset_classical)
    "acoustic" -> context.getString(R.string.ui_preset_acoustic)
    "electronic" -> context.getString(R.string.ui_preset_electronic)
    "podcast" -> context.getString(R.string.ui_preset_podcast)
    "night" -> context.getString(R.string.ui_preset_night)
    "gaming" -> context.getString(R.string.ui_preset_gaming)
    else -> title
}

fun BuiltinPreset.localizedDescription(context: Context): String = when (id) {
    "flat" -> context.getString(R.string.ui_preset_flat_description)
    "warm" -> context.getString(R.string.ui_preset_warm_description)
    "bass" -> context.getString(R.string.ui_preset_bass_description)
    "deep" -> context.getString(R.string.ui_preset_deep_description)
    "woofer" -> context.getString(R.string.ui_preset_woofer_description)
    "vocal" -> context.getString(R.string.ui_preset_vocal_description)
    "treble" -> context.getString(R.string.ui_preset_treble_description)
    "rock" -> context.getString(R.string.ui_preset_rock_description)
    "pop" -> context.getString(R.string.ui_preset_pop_description)
    "jazz" -> context.getString(R.string.ui_preset_jazz_description)
    "classical" -> context.getString(R.string.ui_preset_classical_description)
    "acoustic" -> context.getString(R.string.ui_preset_acoustic_description)
    "electronic" -> context.getString(R.string.ui_preset_electronic_description)
    "podcast" -> context.getString(R.string.ui_preset_podcast_description)
    "night" -> context.getString(R.string.ui_preset_night_description)
    "gaming" -> context.getString(R.string.ui_preset_gaming_description)
    else -> description
}
