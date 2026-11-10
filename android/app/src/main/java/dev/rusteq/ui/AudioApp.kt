@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.rusteq.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rusteq.*
import dev.rusteq.audio.*
import java.util.Locale
import kotlin.math.*

@Composable
fun AudioApp(vm: PlayerController) {
    val state by vm.state.collectAsStateWithLifecycle()
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
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.setForeground(false)
            if (event == Lifecycle.Event.ON_START) vm.setForeground(true)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        containerColor = AudioColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).background(AudioColors.Blue, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { AudioIcon(AudioSymbol.Sound, tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("Rust EQ", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    if (!largeText) Text("音を、いい感じに。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                }
                if (wideHeader) Text(if (state.config.bypass) "EQ オフ" else "EQ オン", style = MaterialTheme.typography.labelLarge, color = AudioColors.Blue)
                Switch(!state.config.bypass, { enabled -> vm.configure { it.copy(bypass = !enabled) } }, modifier = Modifier.semantics { contentDescription = "イコライザーを有効にする" })
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                listOf("音づくり" to AudioSymbol.Sound, "空間" to AudioSymbol.Space, "ツール" to AudioSymbol.Tools).forEachIndexed { i, (label, symbol) ->
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
                            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AudioColors.Bass)
                            TextButton(onClick = vm::dismissMessage) { Text("閉じる", color = AudioColors.Bass) }
                        }
                    }
                }
                pageState.SaveableStateProvider(page) {
                when (page) {
                    0 -> {
                        PlayerPanel(state, vm) { filePicker.launch(arrayOf("audio/*", "application/ogg")) }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SectionTitle("気分で、音を選ぶ", Modifier.weight(1f))
                                TextButton(onClick = { showPresets = true }) { Text("すべて見る") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                listOf("flat", "vocal", "woofer").forEach { id ->
                                    val preset = BuiltinPresets.find(id)!!
                                    PresetTile(preset, state.presetTitle == preset.title, Modifier.weight(1f), compact = true) { vm.applyBuiltinPreset(id) }
                                }
                            }
                            state.presetTitle?.let { title -> Text("選択中：${friendlyPresetTitle(BuiltinPresets.all.firstOrNull { it.title == title }) ?: title}", style = MaterialTheme.typography.bodySmall, color = AudioColors.Blue) }
                        }
                        EqualizerPanel(state, vm)
                        BassPanel(state.config, vm)
                        Text("音の変化は、再生しながら確かめられます。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                    }
                    1 -> {
                        SectionTitle("音の広がり")
                        SpatialPanels(state, vm, { sofaPicker.launch(arrayOf("*/*")) }, { irPicker.launch(arrayOf("audio/*", "application/octet-stream")) })
                        DynamicsPanel(state.config, vm)
                    }
                    else -> {
                        SectionTitle("設定を持ち歩く")
                        AudioPanel {
                            PanelHeading("プリセットの保存と読み込み", "お気に入りの音を、いつでも。", AudioSymbol.Save)
                            ActionRow("今の音を保存", "名前を付けて、この端末に保存", AudioSymbol.Save) { showSave = true }
                            ActionRow("保存した音を選ぶ", "標準16種類と自分のプリセット", AudioSymbol.Sound) { showPresets = true }
                            HorizontalDivider(color = AudioColors.Line)
                            ActionRow("プロファイルを読み込む", "AutoEQ の PK 設定、または JSON", AudioSymbol.Import) { profilePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }
                            ActionRow("設定を書き出す", "JSON ファイルに保存", AudioSymbol.Export) { profileExport.launch("rust-eq-preset.json") }
                        }
                        AudioPanel {
                            PanelHeading("測定値から音を補正", "測定 CSV から EQ を作ります。", AudioSymbol.Sound)
                            Text("frequency・measured_db・target_db の列に対応。target_db は省略できます。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(5, 10).forEach { count -> FilterChip(optimizerBands == count, { optimizerBands = count }, enabled = !state.optimizing, label = { Text("$count バンド") }, modifier = Modifier.heightIn(min = 48.dp)) }
                            }
                            Button(onClick = { measurementPicker.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")) }, enabled = !state.optimizing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.optimizing) "補正を計算中…" else "測定 CSV を選ぶ") }
                            state.optimizationReport?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted) }
                        }
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
        AlertDialog(onDismissRequest = { showSave = false }, title = { Text("お気に入りの音を保存") },
            text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("プリセット名") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.savePreset(name); showSave = false }, enabled = name.isNotBlank()) { Text("保存する") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("キャンセル") } })
    }
    deletePreset?.let { name -> AlertDialog(onDismissRequest = { deletePreset = null }, title = { Text("プリセットを削除") }, text = { Text("「$name」を削除します。") },
        confirmButton = { TextButton(onClick = { vm.deletePreset(name); deletePreset = null }) { Text("削除する") } }, dismissButton = { TextButton(onClick = { deletePreset = null }) { Text("キャンセル") } }) }
}

@Composable
private fun PlayerPanel(state: PlayerState, vm: PlayerController, openMusic: () -> Unit) {
    val roomyControls = LocalConfiguration.current.screenWidthDp >= 380 && LocalDensity.current.fontScale <= 1.3f
    AudioPanel(tint = AudioColors.BlueTint) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).background(Color.White, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { AudioIcon(AudioSymbol.Music) }
            Column(Modifier.weight(1f)) {
                Text(if (state.loaded) state.title else "好きな曲で、音づくり。", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when { state.loading -> "音楽を準備しています…"; state.playing -> "再生中"; state.loaded -> "一時停止中"; else -> "端末の音楽を開いてはじめましょう" }, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            }
        }
        if (!state.loaded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.queueSize > 0) {
                    Button(onClick = vm::togglePlay, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { AudioIcon(AudioSymbol.Play, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("再生リストを再生") }
                    TextButton(onClick = openMusic, enabled = !state.loading) { Text("音楽を開く") }
                } else {
                    Button(onClick = openMusic, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { AudioIcon(AudioSymbol.Folder, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("音楽を開く") }
                }
                TextButton(onClick = vm::demo, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("デモを聴く") }
            }
        } else {
            if (!roomyControls) TransportButton(state, vm, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (roomyControls) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousTrack, enabled = !state.loading, modifier = Modifier.size(48.dp)) { AudioIcon(AudioSymbol.Previous, "前の曲") }
                if (roomyControls) TransportButton(state, vm, Modifier.weight(1f))
                IconButton(onClick = vm::nextTrack, enabled = !state.loading && state.queueIndex < state.queueSize - 1, modifier = Modifier.size(48.dp)) { AudioIcon(AudioSymbol.Next, "次の曲") }
                IconButton(onClick = openMusic, enabled = !state.loading, modifier = Modifier.size(48.dp)) { AudioIcon(AudioSymbol.Folder, "音楽を開く") }
            }
            if (state.metrics[13] > 0f) {
                var seekPosition by remember { mutableStateOf<Float?>(null) }
                val duration = state.metrics[13]
                Slider((seekPosition ?: state.metrics[6]).coerceIn(0f, duration), { seekPosition = it }, onValueChangeFinished = { seekPosition?.let(vm::seek); seekPosition = null }, valueRange = 0f..duration, enabled = !state.loading, modifier = Modifier.semantics { contentDescription = "再生位置" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playTime(seekPosition ?: state.metrics[6]), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                    Text(playTime(duration), style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.seek(state.metrics[6] - 10f) }, enabled = !state.loading) { Text("−10 秒") }
                    TextButton(onClick = { vm.seek(state.metrics[6] + 10f) }, enabled = !state.loading) { Text("＋10 秒") }
                    TextButton(onClick = vm::demo, enabled = !state.loading) { Text("デモを聴く") }
                }
            }
        }
        if (state.queueSize > 0) TextButton(onClick = vm::clearQueue, enabled = !state.loading) { Text("${state.queueIndex + 1} / ${state.queueSize} 曲　キューをクリア") }
    }
}

@Composable
private fun TransportButton(state: PlayerState, vm: PlayerController, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = vm::togglePlay, enabled = !state.loading, modifier = modifier.heightIn(min = 52.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = AudioColors.Blue, contentColor = Color.White)) {
        AudioIcon(if (state.playing) AudioSymbol.Pause else AudioSymbol.Play, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(if (state.loading) "準備中…" else if (state.playing) "一時停止" else if (state.finished) "もう一度再生" else "再生")
    }
}

@Composable
private fun EqualizerPanel(state: PlayerState, vm: PlayerController) {
    val c = state.config
    var expanded by rememberSaveable { mutableStateOf(false) }
    AudioPanel {
        PanelHeading("音のバランス", if (c.bypass) "EQ はオフです" else "${c.bands.size} バンドで調整中", AudioSymbol.Sound)
        SoundCurve(c, state.selected, state.metrics[0].takeIf { it > 0f } ?: 48000f, vm::select) { index, hz, db -> vm.select(index); vm.band(hz, db) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("低音", "中音", "高音").forEach { Text(it, style = MaterialTheme.typography.labelLarge, color = AudioColors.Muted) }
        }
        Text("点を動かして調整。曲線は EQ とプリアンプの静的な応答です。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (expanded) "詳細調整を閉じる" else "バンドを詳しく調整"); Spacer(Modifier.width(8.dp)); AudioIcon(AudioSymbol.Chevron) }
        if (expanded) {
            HorizontalDivider(color = AudioColors.Line)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 31).forEach { count -> FilterChip(c.bands.size == count, { vm.bandCount(count) }, label = { Text("$count バンド") }, modifier = Modifier.heightIn(min = 48.dp)) }
                TextButton(onClick = { vm.configure { EqConfig(bands = EqConfig.defaultBands(it.bands.size)) }; vm.select(0) }) { Text("リセット") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.bands.forEachIndexed { i, b -> FilterChip(i == state.selected, { vm.select(i) }, label = { Text(formatHz(b.hz)) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            val band = c.bands[state.selected.coerceIn(c.bands.indices)]
            Text("バンド ${state.selected + 1}", style = MaterialTheme.typography.titleMedium)
            SettingSlider("周波数", "${formatHz(band.hz)} Hz", log10(band.hz), log10(20f)..log10(20000f)) { vm.band(hz = 10f.pow(it)) }
            SettingSlider("ゲイン", "${decimal(band.db)} dB", band.db, -24f..24f) { vm.band(db = it) }
            SettingSlider("帯域の狭さ（Q）", decimal(band.q), band.q, .1f..18f) { vm.band(q = it) }
            SettingSlider("全体の音量（プリアンプ）", "${decimal(c.preamp)} dB", c.preamp, -60f..12f) { value -> vm.configure { it.copy(preamp = value) } }
        }
    }
}

@Composable
private fun BassPanel(c: EqConfig, vm: PlayerController) {
    var rememberedAmount by rememberSaveable { mutableFloatStateOf(.5f) }
    AudioPanel(tint = AudioColors.BassTint) {
        ToggleHeading("低音に、もうひと押し", "疑似 BASS", c.virtualBassAmount > 0f, AudioColors.Bass) { enabled ->
            if (!enabled) rememberedAmount = c.virtualBassAmount
            vm.configure { it.copy(virtualBassAmount = if (enabled) rememberedAmount.coerceAtLeast(.1f) else 0f) }
        }
        Text("低音の倍音を足して、存在感をプラス。アプリ内の再生に使えます。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.virtualBassAmount > 0f) {
            SettingSlider("低音の強さ", "${(c.virtualBassAmount * 100).roundToInt()}%", c.virtualBassAmount, .01f..1f, AudioColors.Bass) { value -> vm.configure { it.copy(virtualBassAmount = value) } }
            SettingSlider("低音を取り出す周波数", "${c.virtualBassHz.roundToInt()} Hz", c.virtualBassHz, 60f..180f, AudioColors.Bass) { value -> vm.configure { it.copy(virtualBassHz = value) } }
        }
    }
}

@Composable
private fun SpatialPanels(state: PlayerState, vm: PlayerController, chooseSofa: () -> Unit, chooseIr: () -> Unit) {
    val c = state.config
    val spatial = state.spatialSettings
    AudioPanel(tint = AudioColors.GreenTint) {
        PanelHeading("ステレオの聴こえ方", "広がり方を選んでみましょう。", AudioSymbol.Space, AudioColors.Green)
        StereoScene(c.spatial, c.width)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("そのまま", "ワイド", "クロスフィード").forEachIndexed { i, label ->
                FilterChip(c.spatial == i && !spatial.hrtf, { vm.configure { it.copy(spatial = i) }; vm.spatial { it.copy(hrtf = false) } }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        when (c.spatial) {
            1 -> SettingSlider("左右の広がり", decimal(c.width), c.width, 0f..2f, AudioColors.Green) { value -> vm.configure { it.copy(width = value) } }
            2 -> SettingSlider("左右の混ざり具合", "${(c.crossfeed * 100).roundToInt()}%", c.crossfeed, 0f..0.5f, AudioColors.Green) { value -> vm.configure { it.copy(crossfeed = value) } }
        }
        Text(when (c.spatial) { 1 -> "左右の距離を広げて、開放感のある音に。"; 2 -> "左右の音を少し混ぜて、ヘッドホンの分離感を穏やかに。"; else -> "元のステレオ感をそのまま楽しめます。" }, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
    }
    AudioPanel {
        ToggleHeading("ヘッドホンで前方の音を", "HRTF 仮想スピーカー", spatial.hrtf, AudioColors.Green) { enabled ->
            if (enabled) vm.configure { it.copy(spatial = 0) }
            if (enabled && spatial.sofaPath == null) chooseSofa() else vm.spatial { it.copy(hrtf = enabled) }
        }
        ActionRow(spatial.sofaName ?: "SOFA データを選ぶ", "ヘッドホン用の音響データ", AudioSymbol.Folder, chooseSofa)
        if (spatial.hrtf) SettingSlider("スピーカーの角度", "±${spatial.azimuth.roundToInt()}°", spatial.azimuth, 10f..80f, AudioColors.Green) { value -> vm.spatial { it.copy(azimuth = value) } }
    }
    AudioPanel {
        PanelHeading("部屋の響きを足す", "リバーブ", AudioSymbol.Space, AudioColors.Green)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("なし", "小さな部屋", "IR ファイル").forEachIndexed { i, label ->
                FilterChip(spatial.reverb == i, { if (i == 2 && spatial.irPath == null) chooseIr() else vm.spatial { it.copy(reverb = i) } }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (spatial.reverb != 0) {
            SettingSlider("響きの量", "${(spatial.wet * 100).roundToInt()}%", spatial.wet, 0f..1f, AudioColors.Green) { value -> vm.spatial { it.copy(wet = value) } }
            ActionRow(spatial.irName ?: "IR ファイルを選ぶ", "8 秒以内のインパルス応答", AudioSymbol.Folder, chooseIr)
        }
        if (spatial.hrtf || spatial.reverb != 0) {
            Text("処理の品質", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("省エネ", "標準", "低遅延").forEachIndexed { i, label -> FilterChip(spatial.quality == i, { vm.spatial { it.copy(quality = i) } }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            Text("余韻の上限：省エネ 0.25 秒／標準 2 秒／低遅延 8 秒。負荷に応じて自動調整します。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            Text(when { state.preparingEffect -> "音響データを準備しています…"; !state.loaded -> "音楽を再生すると適用します。"; else -> "処理の遅延 ${decimal(state.effectLatencyMs)} ms" }, style = MaterialTheme.typography.bodySmall, color = AudioColors.Green)
        }
    }
}

@Composable
private fun DynamicsPanel(c: EqConfig, vm: PlayerController) {
    AudioPanel {
        ToggleHeading("音量差をなめらかに", "コンプレッサー", c.compressor, AudioColors.Blue) { value -> vm.configure { it.copy(compressor = value) } }
        Text("大きな音を抑えて、小さな音との聴こえ方を整えます。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        if (c.compressor) {
            SettingSlider("抑えはじめる音量", "${decimal(c.threshold)} dB", c.threshold, -60f..0f) { value -> vm.configure { it.copy(threshold = value) } }
            SettingSlider("圧縮比", "${decimal(c.ratio)} : 1", c.ratio, 1f..20f) { value -> vm.configure { it.copy(ratio = value) } }
        }
        Text("EQ オン時は、出力のピーク制限も常に働きます。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
    }
}

@Composable
private fun ExternalEqPanel() {
    val context = LocalContext.current
    val runtime = context.applicationContext as EqApplication
    val state by runtime.systemEqState.collectAsStateWithLifecycle()
    val request by runtime.requestedEffectSession.collectAsStateWithLifecycle()
    fun start() {
        runCatching {
            val intent = Intent(context, SystemEqService::class.java).setAction(if (request != null) SystemEqService.ATTACH else SystemEqService.START)
            request?.let { (id, player) -> intent.putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, id); intent.putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, player) }
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { error -> runtime.systemEqState.value = state.copy(message = error.message ?: "接続サービスを開始できませんでした") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    AudioPanel {
        ToggleHeading("他アプリの音にも EQ を", "対応するプレーヤーのセッションのみ", state.running, AudioColors.Blue) { enabled ->
            if (!enabled) context.stopService(Intent(context, SystemEqService::class.java))
            else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else start()
        }
        Text("有効にした後、対応プレーヤーで曲を開き直してください。すべてのアプリに適用できる機能ではありません。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        Text("EQ とプリアンプを端末 EQ に近似します。疑似 BASS・コンプレッサー・HRTF・IR は適用しません。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        request?.let { Text("接続要求：${it.second}（session ${it.first}）", style = MaterialTheme.typography.bodySmall, color = AudioColors.Blue) }
        if (state.running && state.sessions.isEmpty()) Text("対応プレーヤーの接続を待っています", style = MaterialTheme.typography.bodyMedium, color = AudioColors.Blue)
        state.sessions.forEach { session -> Text("${session.player}\nSession ${session.id}／${session.bands} バンド\n${session.status}", style = MaterialTheme.typography.bodySmall) }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AudioColors.Bass) }
        if (state.running) Text("停止するときは、このスイッチか OS の実行中アプリ画面を使えます。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
    }
}

@Composable
private fun DiagnosticsPanel(state: PlayerState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AudioPanel {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "再生情報を閉じる" else "再生情報を見る"); Spacer(Modifier.width(8.dp)); AudioIcon(AudioSymbol.Chevron) }
        if (expanded) {
            val m = state.metrics
            Text("${m[0].toInt()} Hz／ステレオ", style = MaterialTheme.typography.titleMedium)
            Text("Burst ${m[1].toInt()} / Buffer ${m[2].toInt()} / Callback ${m[12].toInt()} frames", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            Text("DSP p95 ${timeBound(m[10])} / p99 ${timeBound(m[11])}", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            Text("XRun ${m[3].toInt()} / PCM underrun ${m[4].toInt()}", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
            Text("処理時間は 100 µs 刻みの概算です。12.8 ms 以上は最終区間に集計します。", style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        }
    }
}

@Composable
private fun PresetChooser(state: PlayerState, dismiss: () -> Unit, factory: (String) -> Unit, saved: (String) -> Unit, delete: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("気分に合う音を選ぶ") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("小さな曲線は、各バンドの強弱を表しています。", style = MaterialTheme.typography.bodySmall)
                BuiltinPresets.all.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { preset -> PresetTile(preset, state.presetTitle == preset.title, Modifier.weight(1f)) { factory(preset.id) } }
                    }
                }
                if (state.presetNames.isNotEmpty()) {
                    Text("保存した音", style = MaterialTheme.typography.titleMedium)
                    state.presetNames.forEach { name ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { saved(name) }, modifier = Modifier.weight(1f)) { Text(name) }
                            TextButton(onClick = { delete(name) }) { Text("削除") }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text("閉じる") } })
}

private fun friendlyPresetTitle(preset: BuiltinPreset?): String? = when (preset?.id) { "flat" -> "フラット"; "warm" -> "ウォーム"; "woofer" -> "低音リッチ"; "treble" -> "クリア"; else -> preset?.title }

@Composable
private fun PresetTile(preset: BuiltinPreset, selected: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, click: () -> Unit) {
    val bass = preset.id in listOf("woofer", "bass", "deep", "electronic")
    val color = if (bass) AudioColors.Bass else AudioColors.Blue
    Surface(onClick = click, modifier = modifier, shape = RoundedCornerShape(18.dp), color = if (selected) color.copy(alpha = .10f) else Color.White, border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) color else AudioColors.Line)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(friendlyPresetTitle(preset)!!, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = color)
                if (selected) AudioIcon(AudioSymbol.Check, "選択中", Modifier.size(16.dp), color)
            }
            PresetCurve(preset.previewBands.map { it.db }, color)
            if (!compact) Text(preset.description, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted)
        }
    }
}

@Composable
private fun AudioPanel(tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = tint, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) { Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }

@Composable
private fun PanelHeading(title: String, description: String, symbol: AudioSymbol, color: Color = AudioColors.Blue) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AudioIcon(symbol, tint = color)
        Column(Modifier.weight(1f)) { SectionTitle(title); Text(description, style = MaterialTheme.typography.bodySmall, color = AudioColors.Muted) }
    }
}

@Composable
private fun ToggleHeading(title: String, description: String, checked: Boolean, color: Color, change: (Boolean) -> Unit) {
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
