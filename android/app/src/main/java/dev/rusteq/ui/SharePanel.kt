package dev.rusteq.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rusteq.*
import dev.rusteq.R
import dev.rusteq.audio.PresetShare

/** Shares the current sound as a text link and imports one pasted from a chat or note. Nothing is imported silently. */
@Composable
internal fun SharePanel(state: PlayerState, vm: PlayerController) {
    val context = LocalContext.current
    var showImport by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    AudioPanel {
        SectionTitle(stringResource(R.string.ui_share_heading))
        Text(stringResource(R.string.ui_share_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        ActionRow(stringResource(R.string.ui_share_send), stringResource(R.string.ui_share_send_hint), AudioSymbol.Export) {
            val text = context.getString(R.string.ui_share_message, PresetShare.encode(state.config))
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, context.getString(R.string.ui_share_send)))
        }
        ActionRow(stringResource(R.string.ui_share_import), stringResource(R.string.ui_share_import_hint), AudioSymbol.Import) { showImport = true }
    }
    if (showImport) {
        val valid = PresetShare.decode(pasted) != null
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text(stringResource(R.string.ui_share_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pasted, { pasted = it.take(20000) }, label = { Text(stringResource(R.string.ui_share_paste)) }, minLines = 3, maxLines = 6)
                    if (pasted.isNotBlank()) Text(stringResource(if (valid) R.string.ui_share_valid else R.string.ui_share_invalid), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                }
            },
            confirmButton = { TextButton(onClick = { vm.importShared(pasted); showImport = false; pasted = "" }, enabled = valid) { Text(stringResource(R.string.ui_share_apply)) } },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}
