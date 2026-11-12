@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.rusteq.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rusteq.*
import dev.rusteq.R
import dev.rusteq.audio.OutputKind
import dev.rusteq.audio.ProfileResolver

@Composable
private fun profileName(key: String): String = when {
    key == ProfileResolver.NIGHT_KEY -> stringResource(R.string.ui_profile_night)
    key.startsWith("track:") -> stringResource(R.string.ui_profile_track)
    key == ProfileResolver.deviceKey(OutputKind.BLUETOOTH) -> stringResource(R.string.ui_profile_bluetooth)
    key == ProfileResolver.deviceKey(OutputKind.WIRED) -> stringResource(R.string.ui_profile_wired)
    key == ProfileResolver.deviceKey(OutputKind.SPEAKER) -> stringResource(R.string.ui_profile_speaker)
    else -> stringResource(R.string.ui_profile_other)
}

/** Switches the whole sound automatically by track, night time, or output device (headphones, Bluetooth, speaker). */
@Composable
internal fun AutoProfilePanel(state: PlayerState, vm: PlayerController) {
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_profile_heading), stringResource(R.string.ui_profile_summary), state.profilesOn, AudioColors.Blue) { vm.setProfilesEnabled(it) }
        Text(stringResource(R.string.ui_profile_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (state.profilesOn) {
            Text(stringResource(R.string.ui_profile_now, profileName(vm.deviceKeyNow()), state.activeProfile?.let { profileName(it) } ?: stringResource(R.string.ui_profile_none)), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ui_profile_save_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            val device = vm.deviceKeyNow()
            val track = vm.trackKeyNow()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.saveProfile(device) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_profile_save_device, profileName(device))) }
                OutlinedButton({ vm.saveProfile(ProfileResolver.NIGHT_KEY) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_profile_save_night)) }
                if (track != null) OutlinedButton({ vm.saveProfile(track) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_profile_save_track)) }
            }
            if (state.profileKeys.isNotEmpty()) {
                HorizontalDivider(color = AudioColors.Line)
                state.profileKeys.sorted().forEach { key ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(profileName(key) + if (key == state.activeProfile) " ✓" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ vm.deleteProfile(key) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_profile_delete)) }
                    }
                }
            }
        }
    }
}
