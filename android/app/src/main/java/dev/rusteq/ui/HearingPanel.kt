package dev.rusteq.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rusteq.*
import dev.rusteq.R
import java.util.Locale

@Composable
internal fun HearingPanel(state: PlayerState, vm: PlayerController) {
    val h = state.hearing
    AudioPanel {
        SectionTitle(stringResource(R.string.ui_hearing_heading))
        Text(stringResource(R.string.ui_hearing_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        when {
            h == null -> {
                Button(vm::hearingStart, enabled = state.ab == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_hearing_start)) }
                if (state.canUndoMatch) TextButton(vm::undoMatch, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_hearing_undo)) }
            }
            !h.finished -> {
                val hz = if (h.frequency >= 1000f) String.format(Locale.ROOT, "%.0f kHz", h.frequency / 1000f) else "${h.frequency.toInt()} Hz"
                Text(stringResource(R.string.ui_hearing_progress, h.step + 1, h.total, hz), style = MaterialTheme.typography.titleMedium)
                Button(vm::hearingHeard, Modifier.fillMaxWidth().heightIn(min = 72.dp)) { Text(stringResource(R.string.ui_hearing_heard)) }
                TextButton(vm::hearingCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_hearing_cancel)) }
            }
            else -> {
                val peak = h.gains?.maxOrNull() ?: 0f
                Text(
                    if (peak > 0f) stringResource(R.string.ui_hearing_result, String.format(Locale.ROOT, "%.1f", peak)) else stringResource(R.string.ui_hearing_result_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (peak > 0f) Button(vm::hearingApply, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_hearing_apply)) }
                TextButton(vm::hearingCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_hearing_close)) }
            }
        }
    }
}
