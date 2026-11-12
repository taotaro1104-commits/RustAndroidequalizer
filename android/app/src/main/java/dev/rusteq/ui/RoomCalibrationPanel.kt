package dev.rusteq.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.rusteq.*
import dev.rusteq.R
import java.util.Locale

/** Measures the speaker/room with the microphone and proposes a conservative corrective EQ. */
@Composable
internal fun RoomCalibrationPanel(state: PlayerState, vm: PlayerController) {
    val context = LocalContext.current
    val room = state.room
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.roomMeasure() else vm.roomDenied() }
    AudioPanel {
        SectionTitle(stringResource(R.string.ui_room_heading))
        Text(stringResource(R.string.ui_room_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        when {
            room == null -> {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.roomMeasure()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    enabled = state.ab == null && state.hearing == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.ui_room_start)) }
                if (state.canUndoMatch) TextButton(vm::undoMatch, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_room_undo)) }
            }
            room.busy -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.ui_room_measuring), style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                val gains = room.gains.orEmpty()
                val cut = gains.minOrNull() ?: 0f
                val boost = gains.maxOrNull() ?: 0f
                Text(
                    if (cut < 0f || boost > 0f) stringResource(R.string.ui_room_result, String.format(Locale.ROOT, "%.1f", -cut), String.format(Locale.ROOT, "%.1f", boost))
                    else stringResource(R.string.ui_room_result_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (cut < 0f || boost > 0f) Button(vm::roomApply, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_room_apply)) }
                TextButton(vm::roomClose, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_room_close)) }
            }
        }
    }
}
