package dev.rusteq.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rusteq.EqApplication
import dev.rusteq.ExternalEqController
import dev.rusteq.MainActivity
import dev.rusteq.R
import dev.rusteq.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class EqTargetApp(val packageName: String, val label: String, val launchable: Boolean)
private data class EqAppCatalog(val apps: List<EqTargetApp>, val failed: Boolean = false)

/** Package names are the saved identity; labels and launcher availability are refreshed on resume. */
private fun loadEqApps(context: Context, savedPackages: Set<String>): EqAppCatalog {
    val manager = context.packageManager
    val discovered = runCatching {
        manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo?.enabled == true && it.activityInfo.applicationInfo.enabled }
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName || packageName == MainActivity.UNKNOWN_EXTERNAL_PLAYER) return@mapNotNull null
                EqTargetApp(packageName, runCatching { info.loadLabel(manager).toString() }.getOrDefault(packageName), true)
            }.distinctBy { it.packageName }
    }
    val apps = discovered.getOrDefault(emptyList()).associateBy { it.packageName }.toMutableMap()
    savedPackages.filter { it != context.packageName && it != MainActivity.UNKNOWN_EXTERNAL_PLAYER }.forEach { packageName ->
        if (packageName !in apps) apps[packageName] = EqTargetApp(packageName, packageName, false)
    }
    return EqAppCatalog(apps.values.sortedWith(compareBy<EqTargetApp> { it.label.lowercase() }.thenBy { it.packageName }), discovered.isFailure)
}

@Composable
internal fun ExternalEqPanel() {
    val context = LocalContext.current
    val runtime = context.applicationContext as EqApplication
    val settings by runtime.externalEqSettings.state.collectAsStateWithLifecycle()
    val state by runtime.systemEqState.collectAsStateWithLifecycle()
    val request by runtime.requestedEffectSession.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var enableAfterSave by rememberSaveable { mutableStateOf(false) }
    var pendingLaunchPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLaunchLabel by rememberSaveable { mutableStateOf("") }
    var permissionPending by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
            if (event == Lifecycle.Event.ON_STOP && !permissionPending) pendingLaunchPackage = null
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var catalog by remember { mutableStateOf<EqAppCatalog?>(null) }
    LaunchedEffect(settings.selectedPackages, refresh, configuration) {
        catalog = withContext(Dispatchers.IO) { loadEqApps(context.applicationContext, settings.selectedPackages) }
    }
    fun launchTarget(packageName: String, label: String) {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalStateException()
            context.startActivity(launch)
        }.onFailure {
            runtime.systemEqState.value = runtime.systemEqState.value.copy(message = uiText(R.string.external_ui_launch_failed, label))
        }
    }
    // Saving and starting do not depend on the result. Wait for the prompt to close before opening a target.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionPending = false
    }
    fun offerNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return true
        }
        return false
    }
    fun matchingRequest(packageName: String? = null): Pair<Int, String>? = request?.takeIf {
        it.first > 0 && it.second in runtime.externalEqSettings.state.value.selectedPackages && (packageName == null || it.second == packageName)
    }
    fun start() {
        ExternalEqController.start(context, matchingRequest())
        offerNotificationPermission()
    }
    fun connect(app: EqTargetApp, attached: Boolean) {
        pendingLaunchPackage = null
        if (!ExternalEqController.connect(context, app.packageName, matchingRequest(app.packageName))) return
        if (!attached) {
            pendingLaunchPackage = app.packageName
            pendingLaunchLabel = app.label
        }
        offerNotificationPermission()
    }
    // A new player can announce its session immediately. Open it only after
    // the service has registered its receiver and the permission prompt closes.
    LaunchedEffect(pendingLaunchPackage, permissionPending, state.running, settings, refresh) {
        val packageName = pendingLaunchPackage ?: return@LaunchedEffect
        if (!settings.shouldRun || packageName !in settings.selectedPackages) {
            pendingLaunchPackage = null
        } else if (!permissionPending && state.running && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingLaunchPackage = null
            launchTarget(packageName, pendingLaunchLabel)
        }
    }
    LaunchedEffect(pendingLaunchPackage, permissionPending) {
        val packageName = pendingLaunchPackage ?: return@LaunchedEffect
        if (permissionPending) return@LaunchedEffect
        delay(15000)
        if (pendingLaunchPackage == packageName && !runtime.systemEqState.value.running) {
            pendingLaunchPackage = null
            runtime.systemEqState.value = runtime.systemEqState.value.copy(message = uiText(R.string.external_ui_listener_start_timeout))
        }
    }
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_external_heading), stringResource(R.string.external_ui_enable), settings.enabled, AudioColors.Blue) { enabled ->
            if (!enabled) ExternalEqController.stop(context)
            else if (settings.selectedPackages.isEmpty()) { enableAfterSave = true; showPicker = true }
            else { runtime.externalEqSettings.update { it.copy(enabled = true) }; start() }
        }
        Text(stringResource(R.string.external_ui_supported_sessions), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        Text(stringResource(R.string.ui_external_scope), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        OutlinedButton(onClick = { enableAfterSave = false; showPicker = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.external_ui_choose_apps))
        }
        Text(pluralStringResource(R.plurals.external_ui_saved_apps, settings.selectedPackages.size, settings.selectedPackages.size), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (state.running) R.string.external_ui_listening else if (settings.enabled) R.string.external_ui_listener_paused else R.string.external_ui_listener_off), style = MaterialTheme.typography.bodyMedium, color = AudioColors.Blue)
        if (settings.enabled && !state.running) {
            Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.external_ui_resume)) }
        }
        if (catalog == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (catalog?.failed == true) Text(stringResource(R.string.external_ui_load_failed), style = MaterialTheme.typography.bodySmall, color = AudioColors.Bass)
        val knownApps = catalog?.apps.orEmpty().associateBy { it.packageName }
        settings.selectedPackages.sorted().forEach { packageName ->
            val app = knownApps[packageName] ?: EqTargetApp(packageName, packageName, false)
            val sessions = state.sessions.filter { it.player == packageName }
            val attached = state.running && sessions.any { it.connected }
            HorizontalDivider(color = AudioColors.Line)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                if (app.label != app.packageName) Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                val status = when {
                    catalog == null -> R.string.external_ui_loading
                    !app.launchable -> R.string.external_ui_unavailable
                    pendingLaunchPackage == packageName && !state.running -> R.string.external_ui_starting
                    !settings.enabled -> R.string.external_ui_target_off
                    !state.running -> R.string.external_ui_listener_paused
                    attached -> R.string.external_ui_connected
                    sessions.isNotEmpty() -> R.string.external_ui_connection_failed
                    else -> R.string.external_ui_waiting
                }
                Text(stringResource(status), style = MaterialTheme.typography.bodyMedium, color = if (attached) AudioColors.Green else AudioColors.Muted)
                if (packageName == "com.google.android.youtube" && !attached) {
                    Text(stringResource(R.string.external_ui_youtube_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                }
                sessions.forEach { session ->
                    Text(stringResource(R.string.external_ui_session_status, session.id, session.status.resolve(context)), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { connect(app, attached) }, enabled = app.launchable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (attached) R.string.external_ui_reconnect else R.string.external_ui_connect))
                }
            }
        }
        request?.takeIf { it.second != MainActivity.UNKNOWN_EXTERNAL_PLAYER && it.second != context.packageName }?.let {
            Text(stringResource(R.string.ui_external_request, it.second, it.first), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        }
        HorizontalDivider(color = AudioColors.Line)
        ToggleHeading(stringResource(R.string.external_ui_boot_title), stringResource(R.string.external_ui_boot_description), settings.resumeAfterBoot, AudioColors.Blue) { enabled ->
            runtime.externalEqSettings.update { it.copy(resumeAfterBoot = enabled) }
        }
        Text(stringResource(R.string.external_ui_restore_limits), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Text(stringResource(R.string.external_ui_notification_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        }
        state.message?.let { Text(it.resolve(context), style = MaterialTheme.typography.bodySmall, color = AudioColors.Bass) }
    }
    if (showPicker) {
        ExternalAppPicker(catalog, settings.selectedPackages, dismiss = { showPicker = false; enableAfterSave = false }) { selected ->
            runtime.externalEqSettings.update { it.copy(selectedPackages = selected, enabled = selected.isNotEmpty() && (it.enabled || enableAfterSave)) }
            showPicker = false
            enableAfterSave = false
            if (runtime.externalEqSettings.state.value.shouldRun) start() else ExternalEqController.stop(context)
        }
    }
}

@Composable
private fun ExternalAppPicker(catalog: EqAppCatalog?, selectedPackages: Set<String>, dismiss: () -> Unit, save: (Set<String>) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf<List<String>>(selectedPackages.sorted()) }
    val visible = catalog?.apps.orEmpty().filter { it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.external_ui_choose_apps)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.external_ui_search_apps)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(pluralStringResource(R.plurals.external_ui_selected_apps, draft.size, draft.size), style = MaterialTheme.typography.bodySmall)
                if (catalog == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.external_ui_loading))
                } else {
                    if (catalog.failed) Text(stringResource(R.string.external_ui_load_failed), color = AudioColors.Bass)
                    if (visible.isEmpty()) Text(stringResource(R.string.external_ui_no_matches))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            val checked = app.packageName in draft
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = Role.Checkbox) {
                                draft = if (checked) draft - app.packageName else draft + app.packageName
                            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    if (app.label != app.packageName) Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                                    if (!app.launchable) Text(stringResource(R.string.external_ui_unavailable), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(draft.toSet()) }) { Text(stringResource(R.string.external_ui_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.external_ui_cancel)) } })
}
