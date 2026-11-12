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
    "flat31" -> context.getString(R.string.ui_preset31_flat)
    "rock31" -> context.getString(R.string.ui_preset31_rock)
    "classical31" -> context.getString(R.string.ui_preset31_classical)
    "pop31" -> context.getString(R.string.ui_preset31_pop)
    "jazz31" -> context.getString(R.string.ui_preset31_jazz)
    "vocal31" -> context.getString(R.string.ui_preset31_vocal)
    "acoustic31" -> context.getString(R.string.ui_preset31_acoustic)
    "electronic31" -> context.getString(R.string.ui_preset31_electronic)
    "treble31" -> context.getString(R.string.ui_preset31_treble)
    "bass31" -> context.getString(R.string.ui_preset31_bass)
    "megabass31" -> context.getString(R.string.ui_preset31_megabass)
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
    "flat31" -> context.getString(R.string.ui_preset31_flat_description)
    "rock31" -> context.getString(R.string.ui_preset31_rock_description)
    "classical31" -> context.getString(R.string.ui_preset31_classical_description)
    "pop31" -> context.getString(R.string.ui_preset31_pop_description)
    "jazz31" -> context.getString(R.string.ui_preset31_jazz_description)
    "vocal31" -> context.getString(R.string.ui_preset31_vocal_description)
    "acoustic31" -> context.getString(R.string.ui_preset31_acoustic_description)
    "electronic31" -> context.getString(R.string.ui_preset31_electronic_description)
    "treble31" -> context.getString(R.string.ui_preset31_treble_description)
    "bass31" -> context.getString(R.string.ui_preset31_bass_description)
    "megabass31" -> context.getString(R.string.ui_preset31_megabass_description)
    else -> description
}
