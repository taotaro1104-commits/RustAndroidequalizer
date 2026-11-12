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

/** Level-matched A/B comparison and an ABX blind test, on the Tools page. */
@Composable
internal fun AbComparePanel(state: PlayerState, vm: PlayerController) {
    val ab = state.ab
    AudioPanel {
        SectionTitle(stringResource(R.string.ui_ab_heading))
        Text(stringResource(R.string.ui_ab_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (ab == null) {
            Button(onClick = vm::abStart, enabled = state.loaded, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_ab_start)) }
            return@AudioPanel
        }
        val nameA = stringResource(R.string.ui_ab_sound_a); val nameB = stringResource(R.string.ui_ab_sound_b)
        val trimmed = if (ab.trimmedIsA) nameA else nameB
        if (ab.trimDb < -0.05f) Text(stringResource(R.string.ui_ab_trim, trimmed, String.format(Locale.ROOT, "%.1f", -ab.trimDb)), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        Text(stringResource(R.string.ui_ab_locked), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        val playing = when (ab.listening) { AbSlot.A -> nameA; AbSlot.B -> nameB; AbSlot.X -> stringResource(R.string.ui_ab_x) }
        Text(stringResource(R.string.ui_ab_playing, playing), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ vm.abListen(AbSlot.A) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("A") }
            Button({ vm.abListen(AbSlot.B) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("B") }
            if (ab.abxRunning) Button({ vm.abListen(AbSlot.X) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("X") }
        }
        when {
            ab.abxRunning -> {
                Text(stringResource(R.string.ui_ab_abx_question, ab.abxDone + 1, ab.abxTotal), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton({ vm.abxAnswer(true) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_ab_x_is_a)) }
                    OutlinedButton({ vm.abxAnswer(false) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_ab_x_is_b)) }
                }
                ab.lastRight?.let { Text(stringResource(if (it) R.string.ui_ab_right else R.string.ui_ab_wrong), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted) }
            }
            ab.abxTotal > 0 -> {
                Text(stringResource(R.string.ui_ab_result, ab.abxCorrect, ab.abxTotal, String.format(Locale.ROOT, "%.1f%%", ab.abxP * 100)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(if (ab.abxP < .05) R.string.ui_ab_verdict_clear else R.string.ui_ab_verdict_unclear), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                OutlinedButton(vm::abxBegin, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_ab_abx_start)) }
            }
            else -> OutlinedButton(vm::abxBegin, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_ab_abx_start)) }
        }
        TextButton(vm::abEnd, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_ab_end)) }
    }
}
