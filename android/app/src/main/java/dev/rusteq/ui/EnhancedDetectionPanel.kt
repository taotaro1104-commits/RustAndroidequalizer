package dev.rusteq.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rusteq.EqApplication
import dev.rusteq.ExternalEqController
import dev.rusteq.R
import dev.rusteq.audio.AndroidSessionDiscovery
import dev.rusteq.audio.DiscoveryStatus
import dev.rusteq.audio.ExternalEqSettings

@Composable
internal fun EnhancedDetectionPanel(settings: ExternalEqSettings, status: DiscoveryStatus, refresh: Int) {
    val context = LocalContext.current
    val runtime = context.applicationContext as EqApplication
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var localRefresh by remember { mutableIntStateOf(0) }
    val hasPermission = remember(refresh, localRefresh, status) { AndroidSessionDiscovery(context).hasPermission() }
    val command = "adb shell pm grant ${context.packageName} android.permission.DUMP"
    HorizontalDivider(color = AudioColors.Line)
    ToggleHeading(stringResource(R.string.discovery_heading), stringResource(R.string.discovery_toggle_description), settings.enhancedDetection, AudioColors.Blue) { enabled ->
        runtime.externalEqSettings.update { it.copy(enhancedDetection = enabled) }
        ExternalEqController.restore(context)
        if (enabled && !hasPermission) showSetup = true
    }
    Text(stringResource(R.string.discovery_scope), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
    if (settings.enhancedDetection) {
        val label = when {
            !hasPermission -> R.string.discovery_permission_missing
            !settings.shouldRun -> R.string.discovery_listener_needed
            status == DiscoveryStatus.FORMAT_UNSUPPORTED -> R.string.discovery_format_unsupported
            status == DiscoveryStatus.TIMED_OUT -> R.string.discovery_timeout
            status == DiscoveryStatus.UNAVAILABLE -> R.string.discovery_unavailable
            status == DiscoveryStatus.READY -> R.string.discovery_ready
            else -> R.string.discovery_scanning
        }
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = AudioColors.Blue)
        OutlinedButton(onClick = { showSetup = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.discovery_setup)) }
        OutlinedButton(onClick = { localRefresh++; ExternalEqController.refreshDiscovery(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.discovery_refresh)) }
    }
    if (showSetup) {
        AlertDialog(onDismissRequest = { showSetup = false }, title = { Text(stringResource(R.string.discovery_setup)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.discovery_setup_steps))
                SelectionContainer { Text(command, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.discovery_setup_limits), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.discovery_setup), command))
            }) { Text(stringResource(R.string.discovery_copy)) }
        }, dismissButton = { TextButton(onClick = { showSetup = false; localRefresh++; ExternalEqController.refreshDiscovery(context) }) { Text(stringResource(R.string.discovery_done)) } })
    }
}
