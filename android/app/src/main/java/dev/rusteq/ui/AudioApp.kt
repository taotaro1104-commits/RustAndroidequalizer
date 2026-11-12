@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.rusteq.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rusteq.*
import dev.rusteq.R
import dev.rusteq.audio.*
import java.util.Locale
import kotlin.math.*

@Composable
fun AudioApp(vm: PlayerController) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val externalSettings by (context.applicationContext as EqApplication).externalEqSettings.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    val scrolls = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState())
    val pageState = rememberSaveableStateHolder()
    val largeText = LocalDensity.current.fontScale > 1.3f
    val wideHeader = LocalConfiguration.current.screenWidthDp >= 380 && !largeText
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var deletePreset by rememberSaveable { mutableStateOf<String?>(null) }
    var optimizerBands by rememberSaveable { mutableIntStateOf(10) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.loadQueue(it) }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importProfile) }
    val profileExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportProfile) }
    val measurementPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.optimizeMeasurement(uri, optimizerBands) } }
    val sofaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importEffect(uri, true) } }
    val irPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importEffect(uri, false) } }
    Scaffold(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        containerColor = AudioColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).background(AudioColors.Blue, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_rusteq_mark), null, Modifier.fillMaxSize(), tint = Color.Unspecified)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    if (!largeText) Text(stringResource(R.string.ui_tagline), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                }
                if (wideHeader) Text(if (state.config.bypass) stringResource(R.string.ui_eq_off) else stringResource(R.string.ui_eq_on), style = MaterialTheme.typography.labelLarge, color = AudioColors.Blue)
                Switch(!state.config.bypass, { enabled -> vm.configure { it.copy(bypass = !enabled) } }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.ui_enable_eq) })
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                listOf(stringResource(R.string.ui_tab_sound) to AudioSymbol.Sound, stringResource(R.string.ui_tab_space) to AudioSymbol.Space, stringResource(R.string.ui_tab_tools) to AudioSymbol.Tools).forEachIndexed { i, (label, symbol) ->
                    NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { AudioIcon(symbol, tint = if (page == i) AudioColors.Blue else AudioColors.Muted) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = AudioColors.BlueTint, selectedTextColor = AudioColors.Blue, unselectedTextColor = AudioColors.Muted))
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(scrolls[page]).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                state.message?.let { message ->
                    Surface(color = AudioColors.BassTint, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message.resolve(context), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AudioColors.Bass)
                            TextButton(onClick = vm::dismissMessage) { Text(stringResource(R.string.ui_close), color = AudioColors.Bass) }
                        }
                    }
                }
                pageState.SaveableStateProvider(page) {
                when (page) {
                    0 -> {
                        PlayerPanel(state, vm) { filePicker.launch(arrayOf("audio/*", "application/ogg")) }
                        if (externalSettings.selectedPackages.isNotEmpty()) AudioPanel { ExternalPlaybackInfo(state.config.bypass) }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SectionTitle(stringResource(R.string.ui_preset_heading), Modifier.weight(1f))
                                TextButton(onClick = { showPresets = true }) { Text(stringResource(R.string.ui_view_all)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf("flat", "vocal", "woofer").forEach { id ->
                                    val preset = BuiltinPresets.find(id)!!
                                    PresetTile(preset, state.presetTitle == preset.title, Modifier.weight(1f), compact = true) { vm.applyBuiltinPreset(id) }
                                }
                            }
                            state.presetTitle?.let { title -> Text(stringResource(R.string.ui_selected_preset, friendlyPresetTitle((BuiltinPresets.all + BuiltinPresets31.all).firstOrNull { it.title == title }) ?: title), style = MaterialTheme.typography.bodySmall, color = AudioColors.Blue) }
                        }
                        EqualizerPanel(state, vm)
                        BassPanel(state.config, vm)
                        LoudnessPanel(state.config, vm)
                        Text(stringResource(R.string.ui_listen_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                    }
                    1 -> {
                        SectionTitle(stringResource(R.string.ui_space_heading))
                        SpatialPanels(state, vm, { sofaPicker.launch(arrayOf("*/*")) }, { irPicker.launch(arrayOf("audio/*", "application/octet-stream")) })
                        EchoPanel(state.config, vm)
                        DynamicsPanel(state.config, vm)
                    }
                    else -> {
                        SectionTitle(stringResource(R.string.ui_tools_heading))
                        AudioPanel {
                            PanelHeading(stringResource(R.string.ui_presets_heading), stringResource(R.string.ui_presets_hint), AudioSymbol.Save)
                            ActionRow(stringResource(R.string.ui_save_sound), stringResource(R.string.ui_save_sound_hint), AudioSymbol.Save) { showSave = true }
                            ActionRow(stringResource(R.string.ui_choose_saved), stringResource(R.string.ui_choose_saved_hint), AudioSymbol.Sound) { showPresets = true }
                            HorizontalDivider(color = AudioColors.Line)
                            ActionRow(stringResource(R.string.ui_import_profile), stringResource(R.string.ui_import_profile_hint), AudioSymbol.Import) { profilePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }
                            ActionRow(stringResource(R.string.ui_export_profile), stringResource(R.string.ui_export_profile_hint), AudioSymbol.Export) { profileExport.launch("rust-eq-preset.json") }
                        }
                        AudioPanel {
                            PanelHeading(stringResource(R.string.ui_measurement_heading), stringResource(R.string.ui_measurement_hint), AudioSymbol.Sound)
                            Text(stringResource(R.string.ui_csv_columns), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(5, 10).forEach { count -> FilterChip(optimizerBands == count, { optimizerBands = count }, enabled = !state.optimizing, label = { Text(pluralStringResource(R.plurals.ui_bands, count, count)) }, modifier = Modifier.heightIn(min = 48.dp)) }
                            }
                            Button(onClick = { measurementPicker.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")) }, enabled = !state.optimizing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.optimizing) stringResource(R.string.ui_optimizing) else stringResource(R.string.ui_select_csv)) }
                            state.optimizationReport?.let { Text(it.resolve(context), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted) }
                        }
                        AbComparePanel(state, vm)
                        ExternalEqPanel()
                        DiagnosticsPanel(state)
                    }
                }
                }
            }
        }
    }
    if (showPresets) PresetChooser(state, { showPresets = false }, { vm.applyBuiltinPreset(it); showPresets = false }, { vm.applyPreset(it); showPresets = false }, { deletePreset = it; showPresets = false })
    if (showSave) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showSave = false }, title = { Text(stringResource(R.string.ui_save_dialog)) },
            text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.ui_preset_name)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.savePreset(name); showSave = false }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ui_save)) } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.ui_cancel)) } })
    }
    deletePreset?.let { name -> AlertDialog(onDismissRequest = { deletePreset = null }, title = { Text(stringResource(R.string.ui_delete_dialog)) }, text = { Text(stringResource(R.string.ui_delete_body, name)) },
        confirmButton = { TextButton(onClick = { vm.deletePreset(name); deletePreset = null }) { Text(stringResource(R.string.ui_delete_confirm)) } }, dismissButton = { TextButton(onClick = { deletePreset = null }) { Text(stringResource(R.string.ui_cancel)) } }) }
}

@Composable
private fun PlayerPanel(state: PlayerState, vm: PlayerController, openMusic: () -> Unit) {
    val context = LocalContext.current
    val roomyControls = LocalConfiguration.current.screenWidthDp >= 380 && LocalDensity.current.fontScale <= 1.3f
    AudioPanel(tint = AudioColors.BlueTint) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).background(Color.White, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { AudioIcon(AudioSymbol.Music) }
            Column(Modifier.weight(1f)) {
                Text(if (state.loaded) state.title.resolve(context) else stringResource(R.string.ui_player_empty), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when { state.loading -> stringResource(R.string.ui_player_loading); state.playing -> stringResource(R.string.ui_playing); state.loaded -> stringResource(R.string.ui_paused); else -> stringResource(R.string.ui_open_hint) }, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            }
        }
        if (!state.loaded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.queueSize > 0) {
                    Button(onClick = vm::togglePlay, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { AudioIcon(AudioSymbol.Play, tint = Color.White); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ui_resume_queue)) }
                    TextButton(onClick = openMusic, enabled = !state.loading) { Text(stringResource(R.string.ui_open_music)) }
                } else {
                    Button(onClick = openMusic, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { AudioIcon(AudioSymbol.Folder, tint = Color.White); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ui_open_music)) }
                }
                TextButton(onClick = vm::demo, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_play_demo)) }
            }
        } else {
            if (!roomyControls) TransportButton(state, vm, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (roomyControls) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousTrack, enabled = !state.loading, modifier = Modifier.size(48.dp)) { AudioIcon(AudioSymbol.Previous, stringResource(R.string.ui_previous_track)) }
                if (roomyControls) TransportButton(state, vm, Modifier.weight(1f))
                IconButton(onClick = vm::nextTrack, enabled = !state.loading && state.queueIndex < state.queueSize - 1, modifier = Modifier.size(48.dp)) { AudioIcon(AudioSymbol.Next, stringResource(R.string.ui_next_track)) }
                IconButton(onClick = openMusic, enabled = !state.loading, modifier = Modifier.size(48.dp)) { AudioIcon(AudioSymbol.Folder, stringResource(R.string.ui_open_music)) }
            }
            if (state.metrics[13] > 0f) {
                var seekPosition by remember { mutableStateOf<Float?>(null) }
                val duration = state.metrics[13]
                Slider((seekPosition ?: state.metrics[6]).coerceIn(0f, duration), { seekPosition = it }, onValueChangeFinished = { seekPosition?.let(vm::seek); seekPosition = null }, valueRange = 0f..duration, enabled = !state.loading, modifier = Modifier.semantics { contentDescription = context.getString(R.string.ui_playback_position) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playTime(seekPosition ?: state.metrics[6]), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                    Text(playTime(duration), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.seek(state.metrics[6] - 10f) }, enabled = !state.loading) { Text(stringResource(R.string.ui_seek_back)) }
                    TextButton(onClick = { vm.seek(state.metrics[6] + 10f) }, enabled = !state.loading) { Text(stringResource(R.string.ui_seek_forward)) }
                    TextButton(onClick = vm::demo, enabled = !state.loading) { Text(stringResource(R.string.ui_play_demo)) }
                }
            }
        }
        if (state.queueSize > 0) TextButton(onClick = vm::clearQueue, enabled = !state.loading) { Text(stringResource(R.string.ui_queue_position, state.queueIndex + 1, state.queueSize)) }
    }
}

@Composable
private fun TransportButton(state: PlayerState, vm: PlayerController, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = vm::togglePlay, enabled = !state.loading, modifier = modifier.heightIn(min = 52.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = AudioColors.Blue, contentColor = Color.White)) {
        AudioIcon(if (state.playing) AudioSymbol.Pause else AudioSymbol.Play, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(if (state.loading) stringResource(R.string.ui_preparing) else if (state.playing) stringResource(R.string.ui_pause) else if (state.finished) stringResource(R.string.ui_replay) else stringResource(R.string.ui_play))
    }
}

@Composable
private fun EqualizerPanel(state: PlayerState, vm: PlayerController) {
    val c = state.config
    var expanded by rememberSaveable { mutableStateOf(false) }
    AudioPanel {
        PanelHeading(stringResource(R.string.ui_eq_heading), if (c.bypass) stringResource(R.string.ui_eq_disabled) else stringResource(R.string.ui_active_bands, c.bands.size), AudioSymbol.Sound)
        SoundCurve(c, state.selected, state.metrics[0].takeIf { it > 0f } ?: 48000f, vm::select) { index, hz, db -> vm.select(index); vm.band(hz, db) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(stringResource(R.string.ui_lows), stringResource(R.string.ui_mids), stringResource(R.string.ui_highs)).forEach { Text(it, style = MaterialTheme.typography.labelLarge, color = AudioColors.Muted) }
        }
        Text(stringResource(R.string.ui_curve_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (expanded) stringResource(R.string.ui_close_details) else stringResource(R.string.ui_open_details)); Spacer(Modifier.width(8.dp)); AudioIcon(AudioSymbol.Chevron) }
        if (expanded) {
            HorizontalDivider(color = AudioColors.Line)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 31).forEach { count -> FilterChip(c.bands.size == count, { vm.bandCount(count) }, label = { Text(pluralStringResource(R.plurals.ui_bands, count, count)) }, modifier = Modifier.heightIn(min = 48.dp)) }
                TextButton(onClick = { vm.configure { EqConfig(bands = EqConfig.defaultBands(it.bands.size)) }; vm.select(0) }) { Text(stringResource(R.string.ui_reset)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.bands.forEachIndexed { i, b -> FilterChip(i == state.selected, { vm.select(i) }, label = { Text(formatHz(b.hz)) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            val band = c.bands[state.selected.coerceIn(c.bands.indices)]
            Text(stringResource(R.string.ui_band_number, state.selected + 1), style = MaterialTheme.typography.titleMedium)
            SettingSlider(stringResource(R.string.ui_frequency), "${formatHz(band.hz)} Hz", log10(band.hz), log10(20f)..log10(20000f)) { vm.band(hz = 10f.pow(it)) }
            SettingSlider(stringResource(R.string.ui_gain), "${decimal(band.db)} dB", band.db, -24f..24f) { vm.band(db = it) }
            SettingSlider(stringResource(R.string.ui_bandwidth), decimal(band.q), band.q, .1f..18f) { vm.band(q = it) }
            SettingSlider(stringResource(R.string.ui_preamp), "${decimal(c.preamp)} dB", c.preamp, -60f..12f) { value -> vm.configure { it.copy(preamp = value) } }
        }
    }
}

@Composable
private fun BassPanel(c: EqConfig, vm: PlayerController) {
    var rememberedAmount by rememberSaveable { mutableFloatStateOf(.5f) }
    AudioPanel(tint = AudioColors.BassTint) {
        ToggleHeading(stringResource(R.string.ui_bass_heading), stringResource(R.string.ui_virtual_bass), c.virtualBassAmount > 0f, AudioColors.Bass) { enabled ->
            if (!enabled) rememberedAmount = c.virtualBassAmount
            vm.configure { it.copy(virtualBassAmount = if (enabled) rememberedAmount.coerceAtLeast(.1f) else 0f) }
        }
        Text(stringResource(R.string.ui_bass_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.virtualBassAmount > 0f) {
            SettingSlider(stringResource(R.string.ui_bass_amount), "${(c.virtualBassAmount * 100).roundToInt()}%", c.virtualBassAmount, .01f..EqConfig.MAX_BASS_AMOUNT, AudioColors.Bass) { value -> vm.configure { it.copy(virtualBassAmount = value) } }
            SettingSlider(stringResource(R.string.ui_bass_cutoff), "${c.virtualBassHz.roundToInt()} Hz", c.virtualBassHz, 60f..180f, AudioColors.Bass) { value -> vm.configure { it.copy(virtualBassHz = value) } }
        }
    }
}

@Composable
private fun LoudnessPanel(c: EqConfig, vm: PlayerController) {
    var remembered by rememberSaveable { mutableFloatStateOf(.7f) }
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_loudness_heading), stringResource(R.string.ui_loudness), c.loudnessStrength > 0f, AudioColors.Blue) { enabled ->
            if (!enabled) remembered = c.loudnessStrength
            vm.configure { it.copy(loudnessStrength = if (enabled) remembered.coerceAtLeast(.1f) else 0f) }
        }
        Text(stringResource(R.string.ui_loudness_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.loudnessStrength > 0f) {
            SettingSlider(stringResource(R.string.ui_loudness_strength), "${(c.loudnessStrength * 100).roundToInt()}%", c.loudnessStrength, .05f..1f) { value -> vm.configure { it.copy(loudnessStrength = value) } }
        }
    }
}

@Composable
private fun SpatialPanels(state: PlayerState, vm: PlayerController, chooseSofa: () -> Unit, chooseIr: () -> Unit) {
    val c = state.config
    val spatial = state.spatialSettings
    AudioPanel(tint = AudioColors.GreenTint) {
        PanelHeading(stringResource(R.string.ui_stereo_heading), stringResource(R.string.ui_stereo_hint), AudioSymbol.Space, AudioColors.Green)
        StereoScene(c.spatial, c.width)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(stringResource(R.string.ui_stereo_original), stringResource(R.string.ui_stereo_wide), stringResource(R.string.ui_stereo_crossfeed)).forEachIndexed { i, label ->
                FilterChip(c.spatial == i && !spatial.hrtf, { vm.configure { it.copy(spatial = i) }; vm.spatial { it.copy(hrtf = false) } }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        when (c.spatial) {
            1 -> SettingSlider(stringResource(R.string.ui_stereo_width), decimal(c.width), c.width, 0f..2f, AudioColors.Green) { value -> vm.configure { it.copy(width = value) } }
            2 -> SettingSlider(stringResource(R.string.ui_crossfeed_mix), "${(c.crossfeed * 100).roundToInt()}%", c.crossfeed, 0f..0.5f, AudioColors.Green) { value -> vm.configure { it.copy(crossfeed = value) } }
        }
        Text(when (c.spatial) { 1 -> stringResource(R.string.ui_wide_hint); 2 -> stringResource(R.string.ui_crossfeed_hint); else -> stringResource(R.string.ui_original_hint) }, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
    }
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_hrtf_heading), stringResource(R.string.ui_hrtf_hint), spatial.hrtf, AudioColors.Green) { enabled ->
            if (enabled) vm.configure { it.copy(spatial = 0) }
            if (enabled && spatial.sofaPath == null) chooseSofa() else vm.spatial { it.copy(hrtf = enabled) }
        }
        ActionRow(spatial.sofaName ?: stringResource(R.string.ui_choose_sofa), stringResource(R.string.ui_sofa_hint), AudioSymbol.Folder, chooseSofa)
        if (spatial.hrtf) SettingSlider(stringResource(R.string.ui_speaker_angle), "±${spatial.azimuth.roundToInt()}°", spatial.azimuth, 10f..80f, AudioColors.Green) { value -> vm.spatial { it.copy(azimuth = value) } }
    }
    AudioPanel {
        PanelHeading(stringResource(R.string.ui_reverb_heading), stringResource(R.string.ui_reverb), AudioSymbol.Space, AudioColors.Green)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(stringResource(R.string.ui_reverb_none), stringResource(R.string.ui_reverb_room), stringResource(R.string.ui_reverb_ir)).forEachIndexed { i, label ->
                FilterChip(spatial.reverb == i, { if (i == 2 && spatial.irPath == null) chooseIr() else vm.spatial { it.copy(reverb = i) } }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (spatial.reverb != 0) {
            SettingSlider(stringResource(R.string.ui_reverb_amount), "${(spatial.wet * 100).roundToInt()}%", spatial.wet, 0f..1f, AudioColors.Green) { value -> vm.spatial { it.copy(wet = value) } }
            ActionRow(spatial.irName ?: stringResource(R.string.ui_choose_ir), stringResource(R.string.ui_ir_hint), AudioSymbol.Folder, chooseIr)
        }
        if (spatial.hrtf || spatial.reverb != 0) {
            Text(stringResource(R.string.ui_quality), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(stringResource(R.string.ui_quality_eco), stringResource(R.string.ui_quality_standard), stringResource(R.string.ui_quality_low_latency)).forEachIndexed { i, label -> FilterChip(spatial.quality == i, { vm.spatial { it.copy(quality = i) } }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            Text(stringResource(R.string.ui_quality_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            Text(when { state.preparingEffect -> stringResource(R.string.ui_effect_preparing); !state.loaded -> stringResource(R.string.ui_effect_on_play); else -> stringResource(R.string.ui_effect_latency, decimal(state.effectLatencyMs)) }, style = MaterialTheme.typography.bodySmall, color = AudioColors.Green)
        }
    }
}

@Composable
private fun EchoPanel(c: EqConfig, vm: PlayerController) {
    var rememberedMix by rememberSaveable { mutableFloatStateOf(.3f) }
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_echo_heading), stringResource(R.string.ui_echo), c.echoMix > 0f, AudioColors.Blue) { enabled ->
            if (!enabled) rememberedMix = c.echoMix
            vm.configure { it.copy(echoMix = if (enabled) rememberedMix.coerceAtLeast(.05f) else 0f) }
        }
        Text(stringResource(R.string.ui_echo_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.echoMix > 0f) {
            SettingSlider(stringResource(R.string.ui_echo_mix), "${(c.echoMix * 100).roundToInt()}%", c.echoMix, .01f..1f) { value -> vm.configure { it.copy(echoMix = value) } }
            SettingSlider(stringResource(R.string.ui_echo_time), "${c.echoTimeMs.roundToInt()} ms", c.echoTimeMs, EqConfig.MIN_ECHO_MS..EqConfig.MAX_ECHO_MS) { value -> vm.configure { it.copy(echoTimeMs = value) } }
            SettingSlider(stringResource(R.string.ui_echo_feedback), "${(c.echoFeedback * 100).roundToInt()}%", c.echoFeedback, 0f..EqConfig.MAX_ECHO_FEEDBACK) { value -> vm.configure { it.copy(echoFeedback = value) } }
        }
    }
}

@Composable
private fun DynamicsPanel(c: EqConfig, vm: PlayerController) {
    AudioPanel {
        ToggleHeading(stringResource(R.string.ui_dynamics_heading), stringResource(R.string.ui_compressor), c.compressor, AudioColors.Blue) { value -> vm.configure { it.copy(compressor = value) } }
        Text(stringResource(R.string.ui_compressor_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.compressor) {
            SettingSlider(stringResource(R.string.ui_threshold), "${decimal(c.threshold)} dB", c.threshold, -60f..0f) { value -> vm.configure { it.copy(threshold = value) } }
            SettingSlider(stringResource(R.string.ui_ratio), "${decimal(c.ratio)} : 1", c.ratio, 1f..20f) { value -> vm.configure { it.copy(ratio = value) } }
        }
        Text(stringResource(R.string.ui_limiter_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
    }
}

@Composable
private fun DiagnosticsPanel(state: PlayerState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AudioPanel {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) stringResource(R.string.ui_close_diagnostics) else stringResource(R.string.ui_open_diagnostics)); Spacer(Modifier.width(8.dp)); AudioIcon(AudioSymbol.Chevron) }
        if (expanded) {
            ExternalPlaybackInfo(state.config.bypass)
            HorizontalDivider(color=AudioColors.Line)
            SectionTitle(stringResource(R.string.external_info_local_heading))
            if (!state.loaded) Text(stringResource(R.string.external_info_local_empty),style=MaterialTheme.typography.bodySmall,color=AudioColors.Muted)
            else {
            val m = state.metrics
            Text(stringResource(R.string.ui_sample_rate, m[0].toInt()), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ui_buffer_details, m[1].toInt(), m[2].toInt(), m[12].toInt()), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            Text(stringResource(R.string.ui_dsp_details, timeBound(m[10]), timeBound(m[11])), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            Text(stringResource(R.string.ui_underrun_details, m[3].toInt(), m[4].toInt()), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            Text(stringResource(R.string.ui_timing_hint), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            }
        }
    }
}

@Composable
private fun PresetChooser(state: PlayerState, dismiss: () -> Unit, factory: (String) -> Unit, saved: (String) -> Unit, delete: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.ui_choose_preset)) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ui_preset_curve_hint), style = MaterialTheme.typography.bodySmall)
                BuiltinPresets.all.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { preset -> PresetTile(preset, state.presetTitle == preset.title, Modifier.weight(1f)) { factory(preset.id) } }
                    }
                }
                Text(stringResource(R.string.ui_presets31_heading), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ui_presets31_hint), style = MaterialTheme.typography.bodySmall)
                BuiltinPresets31.all.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { preset -> PresetTile(preset, state.presetTitle == preset.title, Modifier.weight(1f)) { factory(preset.id) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (state.presetNames.isNotEmpty()) {
                    Text(stringResource(R.string.ui_saved_presets), style = MaterialTheme.typography.titleMedium)
                    state.presetNames.forEach { name ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { saved(name) }, modifier = Modifier.weight(1f)) { Text(name) }
                            TextButton(onClick = { delete(name) }) { Text(stringResource(R.string.ui_delete)) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.ui_close)) } })
}

@Composable
private fun friendlyPresetTitle(preset: BuiltinPreset?): String? = preset?.localizedTitle(LocalContext.current)

@Composable
private fun PresetTile(preset: BuiltinPreset, selected: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, click: () -> Unit) {
    val bass = preset.id in listOf("woofer", "bass", "deep", "electronic", "bass31", "megabass31", "electronic31")
    val color = if (bass) AudioColors.Bass else AudioColors.Blue
    Surface(onClick = click, modifier = modifier, shape = RoundedCornerShape(18.dp), color = if (selected) color.copy(alpha = .10f) else Color.White, border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else AudioColors.Line)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(friendlyPresetTitle(preset)!!, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = color)
                if (selected) AudioIcon(AudioSymbol.Check, stringResource(R.string.ui_selected), Modifier.size(16.dp), color)
            }
            PresetCurve(preset.previewBands.map { it.db }, color)
            if (!compact) Text(preset.localizedDescription(LocalContext.current), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        }
    }
}

@Composable
internal fun AudioPanel(tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = tint, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) { Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }

@Composable
private fun PanelHeading(title: String, description: String, symbol: AudioSymbol, color: Color = AudioColors.Blue) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AudioIcon(symbol, tint = color)
        Column(Modifier.weight(1f)) { SectionTitle(title); Text(description, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted) }
    }
}

@Composable
internal fun ToggleHeading(title: String, description: String, checked: Boolean, color: Color, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { SectionTitle(title); Text(description, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted) }
        Switch(checked, change, modifier = Modifier.semantics { contentDescription = description }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = color))
    }
}

@Composable
private fun SettingSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, color: Color = AudioColors.Blue, change: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = color)
        }
        Slider(value.coerceIn(range), change, valueRange = range, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = color.copy(alpha = .15f)))
    }
}

@Composable
private fun ActionRow(title: String, description: String, symbol: AudioSymbol, action: () -> Unit) {
    Surface(onClick = action, shape = RoundedCornerShape(12.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AudioIcon(symbol, tint = AudioColors.Blue)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted) }
        }
    }
}

private fun decimal(value: Float) = String.format(Locale.ROOT, "%.1f", value)
private fun formatHz(hz: Float) = if (hz >= 1000f) decimal(hz / 1000f) + "k" else hz.roundToInt().toString()
private fun playTime(seconds: Float): String = seconds.coerceAtLeast(0f).toInt().let { "%02d:%02d".format(Locale.ROOT, it / 60, it % 60) }
private fun timeBound(value: Float) = if (value.isFinite()) "≤ ${value.toInt()} µs" else "≥ 12,800 µs"
