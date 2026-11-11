package dev.rusteq.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rusteq.EqApplication
import dev.rusteq.ExternalPlaybackListener
import dev.rusteq.R
import dev.rusteq.audio.ExternalPlaybackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun ExternalPlaybackInfo(bypass:Boolean) {
    val context=LocalContext.current
    val runtime=context.applicationContext as EqApplication
    val playback by runtime.externalPlayback.state.collectAsStateWithLifecycle()
    val settings by runtime.externalEqSettings.state.collectAsStateWithLifecycle()
    val eq by runtime.systemEqState.collectAsStateWithLifecycle()
    val configuration=LocalConfiguration.current
    var labels by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var settingsFailed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playback.tracks) {
        now=SystemClock.elapsedRealtime()
        while (playback.tracks.any { it.kind==ExternalPlaybackKind.PLAYING }) { delay(1000); now=SystemClock.elapsedRealtime() }
    }
    LaunchedEffect(settings.selectedPackages,configuration) {
        labels=withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            settings.selectedPackages.associateWith { name -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(name,0)).toString() }.getOrDefault(name) }
        }
    }
    fun openAccessSettings() {
        val component=ComponentName(context,ExternalPlaybackListener::class.java)
        val intent=if (Build.VERSION.SDK_INT>=30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,component.flattenToString()) else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        settingsFailed=runCatching { context.startActivity(intent) }.recoverCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.isFailure
    }
    SectionTitle(stringResource(R.string.external_info_heading))
    if (settings.selectedPackages.isEmpty()) Text(stringResource(R.string.external_info_choose),style=MaterialTheme.typography.bodySmall,color=AudioColors.Muted)
    if (!playback.accessGranted) {
        Text(stringResource(R.string.external_info_permission),style=MaterialTheme.typography.bodySmall,color=AudioColors.Muted)
        OutlinedButton(onClick=::openAccessSettings,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(stringResource(R.string.external_info_allow)) }
        if (Build.VERSION.SDK_INT>=33) {
            Text(stringResource(R.string.external_info_restricted),style=MaterialTheme.typography.bodySmall,color=AudioColors.Muted)
            TextButton(onClick={ settingsFailed=runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",context.packageName,null))) }.isFailure }) { Text(stringResource(R.string.external_info_app_settings)) }
        }
    }
    if (settingsFailed) Text(stringResource(R.string.external_info_settings_failed),color=AudioColors.Bass,style=MaterialTheme.typography.bodySmall)
    if (playback.accessGranted && playback.tracks.isEmpty()) {
        Text(stringResource(when { playback.refreshing->R.string.external_info_loading; playback.unavailable->R.string.external_info_unavailable; else->R.string.external_info_no_session }),style=MaterialTheme.typography.bodySmall,color=AudioColors.Muted)
    }
    playback.tracks.forEach { track ->
        HorizontalDivider(color=AudioColors.Line)
        Text(labels[track.packageName] ?: track.packageName,style=MaterialTheme.typography.labelLarge,color=AudioColors.Blue)
        Text(track.title ?: stringResource(R.string.external_info_no_title),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
        track.artist?.let { Text(it,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis) }
        track.album?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=AudioColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis) }
        val connected=eq.running && eq.sessions.any { it.player==track.packageName && it.connected }
        val status=when(track.kind) { ExternalPlaybackKind.PLAYING->R.string.external_info_playing; ExternalPlaybackKind.PAUSED->R.string.external_info_paused; else->R.string.external_info_buffering }
        Text(stringResource(status),color=if(track.kind==ExternalPlaybackKind.PLAYING) AudioColors.Green else AudioColors.Muted)
        Text(stringResource(when { track.remote->R.string.external_info_remote; connected && bypass->R.string.external_info_eq_bypass; connected->R.string.external_info_eq_connected; else->R.string.external_info_eq_not_connected }),style=MaterialTheme.typography.bodySmall,color=AudioColors.Muted)
        val position=track.positionAt(now)
        val duration=track.durationMs
        Text((position?.let(::externalTime) ?: stringResource(R.string.external_info_unknown_position))+" / "+(duration?.let(::externalTime) ?: stringResource(R.string.external_info_unknown_duration)),style=MaterialTheme.typography.bodySmall)
        if (duration!=null && duration>0 && position!=null) LinearProgressIndicator(progress={ (position.toDouble()/duration).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
    }
    // An attached EQ handle has no song metadata by itself. Keep that useful
    // connection visible without falsely labelling it as an active track.
    eq.sessions.filter { it.player in settings.selectedPackages }.groupBy { it.player }
        .filterKeys { pkg -> playback.tracks.none { it.packageName==pkg } }.forEach { (pkg,sessions) ->
            HorizontalDivider(color=AudioColors.Line)
            Text(labels[pkg] ?: pkg,style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.external_info_eq_only),style=MaterialTheme.typography.bodySmall,color=AudioColors.Muted)
            val connected=eq.running && sessions.any { it.connected }
            Text(stringResource(if(connected && bypass) R.string.external_info_eq_bypass else if(connected) R.string.external_info_eq_connected else R.string.external_info_eq_not_connected),style=MaterialTheme.typography.bodySmall)
        }
    TextButton(onClick={runtime.externalPlayback.refresh()}) { Text(stringResource(R.string.external_info_refresh)) }
}

private fun externalTime(ms:Long):String {
    val seconds=ms.coerceAtLeast(0)/1000
    return if(seconds>=3600) String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        else String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60)
}
