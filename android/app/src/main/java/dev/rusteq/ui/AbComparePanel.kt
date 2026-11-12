@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.rusteq.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** Reference-track EQ matching: analyse both tracks and set the 31 bands to close the tonal-balance gap. */
@Composable
internal fun EqMatchPanel(state: PlayerState, vm: PlayerController) {
    var strength by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(.7f) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.matchToReference(uri, strength) } }
    AudioPanel {
        SectionTitle(stringResource(R.string.ui_match_heading))
        Text(stringResource(R.string.ui_match_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(.4f to "40%", .7f to "70%", 1f to "100%").forEach { (v, label) ->
                FilterChip(strength == v, { strength = v }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Button(onClick = { picker.launch(arrayOf("audio/*", "application/ogg")) }, enabled = state.loaded && !state.matching && state.ab == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (state.matching) R.string.ui_match_working else R.string.ui_match_choose))
        }
        if (state.canUndoMatch) TextButton(vm::undoMatch, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_match_undo)) }
    }
}
