package dev.rusteq

import android.os.Bundle
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rusteq.audio.*
import java.util.Locale
import kotlin.math.*

private val Ink = Color(0xFF0B1018)
private val PanelColor = Color(0xFF141E2A)
private val Accent = Color(0xFF65E1C4)
private val Muted = Color(0xFF91A5B9)
private val Amber = Color(0xFFF4BF76)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptSessionRequest(intent)
        val player = (application as EqApplication).player
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Amber, background = Ink, surface = PanelColor, onPrimary = Ink)) {
                Surface(color = Ink, modifier = Modifier.fillMaxSize()) { EqualizerScreen(player) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptSessionRequest(intent) }
    private fun acceptSessionRequest(intent: Intent) {
        if (intent.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        if (id > 0) (application as EqApplication).requestedEffectSession.value = id to (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: callingPackage ?: "外部プレーヤー")
    }
}

@Composable
private fun EqualizerScreen(vm: PlayerController) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = state.config
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.loadQueue(it) }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importProfile) }
    val profileExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportProfile) }
    var optimizerBands by remember { mutableIntStateOf(10) }
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
    var showSave by remember { mutableStateOf(false) }
    var presetMenu by remember { mutableStateOf(false) }
    var showMetrics by remember { mutableStateOf(false) }
    var deletePreset by remember { mutableStateOf<String?>(null) }
    val band = c.bands[state.selected.coerceAtMost(c.bands.lastIndex)]

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("RUST / AUDIO", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("音を、自分らしく。", fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
            }
            Text("EQ", color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Light)
        }

        Panel {
            Text("LOCAL PLAYER", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            Text(state.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.queueSize > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = vm::previousTrack, enabled = !state.loading) { Text("前の曲", fontSize = 12.sp) }
                TextButton(onClick = vm::clearQueue, enabled = !state.loading) { Text("${state.queueIndex+1} / ${state.queueSize} · クリア", color = Muted, fontSize = 12.sp) }
                TextButton(onClick = vm::nextTrack, enabled = !state.loading && state.queueIndex < state.queueSize-1) { Text("次の曲", fontSize = 12.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::togglePlay, enabled = !state.loading, modifier = Modifier.weight(1f)) {
                    Text(if (state.loading) "読み込み中…" else if (state.playing) "一時停止" else if (state.finished) "もう一度再生" else "再生")
                }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*", "application/ogg")) }, enabled = !state.loading) { Text("音楽を開く") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::demo, enabled = !state.loading, contentPadding = PaddingValues(0.dp)) { Text("デモを聴く", fontSize = 12.sp) }
                val seconds = state.metrics[6].toInt()
                Text("%02d:%02d".format(seconds / 60, seconds % 60), color = Muted, fontSize = 12.sp)
            }
            LinearProgressIndicator(progress = { state.metrics[5].coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Accent, trackColor = Color(0xFF263444))
            if (state.loaded && state.metrics[13] > 0f) {
                var seekPosition by remember { mutableStateOf<Float?>(null) }
                val duration = state.metrics[13]
                Slider(value = (seekPosition ?: state.metrics[6]).coerceIn(0f, duration), onValueChange = { seekPosition = it },
                    onValueChangeFinished = { seekPosition?.let(vm::seek); seekPosition = null }, valueRange = 0f..duration, enabled = !state.loading)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { vm.seek(state.metrics[6]-10f) }, enabled = !state.loading) { Text("−10 秒", fontSize = 12.sp) }
                    Text("${duration.toInt()/60}:${(duration.toInt()%60).toString().padStart(2, '0')}", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { vm.seek(state.metrics[6]+10f) }, enabled = !state.loading) { Text("＋10 秒", fontSize = 12.sp) }
                }
            }
        }

        if (state.message != null) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF2B2830), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.message!!, modifier = Modifier.weight(1f), color = Amber, fontSize = 12.sp)
                TextButton(onClick = vm::dismissMessage) { Text("閉じる") }
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PARAMETRIC EQ", fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                    Text(if (c.bypass) "バイパス中" else "${c.bands.size} bands · Stereo", color = Muted, fontSize = 12.sp)
                }
                Switch(checked = !c.bypass, onCheckedChange = { enabled -> vm.configure { it.copy(bypass = !enabled) } })
            }
            ResponseGraph(c, state.selected, state.metrics[0].takeIf { it > 0f } ?: 48000f, vm::select) { index, hz, db ->
                vm.select(index); vm.band(hz = hz, db = db)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("20", "100", "1k", "10k", "20k Hz").forEach { Text(it, fontSize = 10.sp, color = Muted) }
            }
            Text("ノードを選択・ドラッグして調整", color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(10, 31).forEach { count -> FilterChip(selected = c.bands.size == count, onClick = { vm.bandCount(count) }, label = { Text("$count bands") }) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.configure { EqConfig(bands = EqConfig.defaultBands(it.bands.size)) }; vm.select(0) }) { Text("リセット", fontSize = 12.sp) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                c.bands.forEachIndexed { i, b -> FilterChip(selected = i == state.selected, onClick = { vm.select(i) }, label = { Text(formatHz(b.hz), fontSize = 11.sp) }) }
            }
            Text("BAND ${state.selected + 1}", color = Accent, fontSize = 11.sp, letterSpacing = 2.sp)
            LabeledSlider("周波数", formatHz(band.hz) + " Hz", log10(band.hz), log10(20f)..log10(20000f)) { vm.band(hz = 10f.pow(it)) }
            LabeledSlider("ゲイン", decimal(band.db) + " dB", band.db, -24f..24f) { vm.band(db = it) }
            LabeledSlider("Q", decimal(band.q), band.q, .1f..18f) { vm.band(q = it) }
            HorizontalDivider(color = Color(0xFF2A3848))
            LabeledSlider("プリアンプ", decimal(c.preamp) + " dB", c.preamp, -30f..12f) { value -> vm.configure { it.copy(preamp = value) } }
        }

        Panel {
            Text("PRESETS", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { presetMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("プリセットを選ぶ") }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        listOf("Flat", "Warm", "Vocal").forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                vm.configure { current -> current.copy(bands = EqConfig.defaultBands(10).map { b ->
                                    val db = when (name) { "Warm" -> (4.0 * exp(-((ln(b.hz / 100f)).pow(2)) / 2.0)).toFloat()
                                        "Vocal" -> (3.0 * exp(-((ln(b.hz / 1800f)).pow(2)) / 1.5)).toFloat(); else -> 0f }
                                    b.copy(db = db)
                                }) }; vm.select(0); presetMenu = false
                            })
                        }
                        state.presetNames.forEach { name -> DropdownMenuItem(text = { Text(name) }, trailingIcon = { TextButton(onClick = { deletePreset = name; presetMenu = false }) { Text("削除", fontSize = 11.sp) } }, onClick = { vm.applyPreset(name); presetMenu = false }) }
                    }
                }
                OutlinedButton(onClick = { showSave = true }) { Text("保存") }
            }
            TextButton(onClick = { profilePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }, contentPadding = PaddingValues(0.dp)) { Text("AutoEQ / JSON プロファイルを読み込む", fontSize = 12.sp) }
            TextButton(onClick = { profileExport.launch("rust-eq-preset.json") }, contentPadding = PaddingValues(0.dp)) { Text("EQ 設定を JSON に書き出す", fontSize = 12.sp) }
            HorizontalDivider(color = Color(0xFF2A3848))
            Text("測定値から補正を作成", fontSize = 14.sp)
            Text("CSV: frequency, measured_db, target_db。target がない場合はフラットな目標で補正します。", color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10).forEach { count -> FilterChip(selected = optimizerBands == count, onClick = { optimizerBands = count }, enabled = !state.optimizing, label = { Text("$count bands") }) }
            }
            OutlinedButton(onClick = { measurementPicker.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")) }, enabled = !state.optimizing) { Text(if (state.optimizing) "補正を計算中…" else "測定 CSV を選んで最適化") }
            state.optimizationReport?.let { Text(it, color = Muted, fontSize = 10.sp) }
        }

        Panel {
            Text("DYNAMICS", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("コンプレッサー"); Text("大きな音と小さな音の差を整える", color = Muted, fontSize = 11.sp) }
                Switch(c.compressor, { value -> vm.configure { it.copy(compressor = value) } })
            }
            if (c.compressor) {
                LabeledSlider("しきい値", decimal(c.threshold) + " dB", c.threshold, -60f..0f) { v -> vm.configure { it.copy(threshold = v) } }
                LabeledSlider("圧縮比", decimal(c.ratio) + ":1", c.ratio, 1f..20f) { v -> vm.configure { it.copy(ratio = v) } }
            }
            Text("出力ピーク制限は EQ 有効時に常時動作します。", color = Muted, fontSize = 11.sp)
        }

        Panel {
            Text("SPATIAL", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Off", "Wide", "Crossfeed").forEachIndexed { i, label -> FilterChip(selected = c.spatial == i, onClick = { vm.configure { it.copy(spatial = i) }; vm.spatial { it.copy(hrtf = false) } }, label = { Text(label) }) }
            }
            when (c.spatial) {
                1 -> { Text("左右の広がりを調整します。", color = Muted, fontSize = 12.sp); LabeledSlider("広がり", decimal(c.width), c.width, 0f..2f) { v -> vm.configure { it.copy(width = v) } } }
                2 -> { Text("ヘッドホンで左右の分離を穏やかにします。", color = Muted, fontSize = 12.sp); LabeledSlider("混合量", "${(c.crossfeed * 100).roundToInt()}%", c.crossfeed, 0f..0.5f) { v -> vm.configure { it.copy(crossfeed = v) } } }
                else -> Text("元のステレオ像を保ちます。", color = Muted, fontSize = 12.sp)
            }
        }

        Panel {
            val spatial = state.spatialSettings
            Text("HEADPHONES / ROOM", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("HRTF 仮想スピーカー"); Text("ヘッドホン向け · 左右の音源を前方に配置", color = Muted, fontSize = 11.sp) }
                Switch(spatial.hrtf, { enabled -> if (enabled) vm.configure { it.copy(spatial = 0) }; if (enabled && spatial.sofaPath == null) sofaPicker.launch(arrayOf("*/*")) else vm.spatial { it.copy(hrtf = enabled) } })
            }
            TextButton(onClick = { sofaPicker.launch(arrayOf("*/*")) }, contentPadding = PaddingValues(0.dp)) { Text(spatial.sofaName ?: "SOFA データを選ぶ", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (spatial.hrtf) LabeledSlider("スピーカー角度", "±${spatial.azimuth.roundToInt()}°", spatial.azimuth, 10f..80f) { v -> vm.spatial { it.copy(azimuth = v) } }
            HorizontalDivider(color = Color(0xFF2A3848))
            Text("リバーブ", fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Off", "Small Room", "IR ファイル").forEachIndexed { i, label ->
                    FilterChip(selected = spatial.reverb == i, onClick = {
                        if (i == 2 && spatial.irPath == null) irPicker.launch(arrayOf("audio/*", "application/octet-stream")) else vm.spatial { it.copy(reverb = i) }
                    }, label = { Text(label) })
                }
            }
            if (spatial.reverb != 0) {
                LabeledSlider("リバーブ量", "${(spatial.wet * 100).roundToInt()}%", spatial.wet, 0f..1f) { v -> vm.spatial { it.copy(wet = v) } }
                TextButton(onClick = { irPicker.launch(arrayOf("audio/*", "application/octet-stream")) }, contentPadding = PaddingValues(0.dp)) { Text(spatial.irName ?: "IR ファイルを読み込む（8 秒以内）", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (spatial.hrtf || spatial.reverb != 0) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Eco", "Standard", "Low latency").forEachIndexed { i, label -> FilterChip(selected = spatial.quality == i, onClick = { vm.spatial { it.copy(quality = i) } }, label = { Text(label) }) }
                }
                Text("余韻の上限：Eco 0.25 秒 / Standard 2 秒 / Low latency 8 秒。サンプル数上限と実測負荷でも短縮・調整します。", color = Muted, fontSize = 10.sp)
                Text(when { state.preparingEffect -> "音響フィルターを準備しています…"; !state.loaded -> "音楽またはデモを再生すると適用します。"; else -> "処理バッファの遅延 ${decimal(state.effectLatencyMs)} ms · EQ のバイパスで全処理を比較できます。" }, color = Muted, fontSize = 11.sp)
            }
        }

        ExternalEqPanel()
        TextButton(onClick = { showMetrics = !showMetrics }, modifier = Modifier.fillMaxWidth()) { Text(if (showMetrics) "再生情報を閉じる" else "再生情報", fontSize = 12.sp) }
        if (showMetrics) Panel {
            val m = state.metrics
            Text("${m[0].toInt()} Hz · stereo f32", color = Accent)
            Text("Burst ${m[1].toInt()} / Buffer ${m[2].toInt()} / Callback ${m[12].toInt()} frames", color = Muted, fontSize = 12.sp)
            Text("DSP p95 ${timeBound(m[10])} / p99 ${timeBound(m[11])}", color = Muted, fontSize = 12.sp)
            Text("XRun ${m[3].toInt()} · PCM underrun ${m[4].toInt()}", color = Muted, fontSize = 12.sp)
            Text("DSP 時間は 100 µs 刻みの概算。12.8 ms 以上は最終区間に集計されます。", color = Muted, fontSize = 10.sp)
        }
        Text("Rust DSP はアプリ内の音楽に適用します。他アプリ EQ は別経路で、対応セッションのみが対象です。", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showSave = false }, title = { Text("プリセットを保存") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("名前") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.savePreset(name); showSave = false }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("キャンセル") } })
    }
    deletePreset?.let { name -> AlertDialog(onDismissRequest = { deletePreset = null }, title = { Text("プリセットを削除") }, text = { Text("「$name」を削除します。") }, confirmButton = { TextButton(onClick = { vm.deletePreset(name); deletePreset = null }) { Text("削除") } }, dismissButton = { TextButton(onClick = { deletePreset = null }) { Text("キャンセル") } }) }
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
            request?.let { (id, player) -> intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id); intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, player) }
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { error -> runtime.systemEqState.value = state.copy(message = error.message ?: "接続サービスを開始できませんでした") }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    Panel {
        Text("OTHER APPS / SESSION EQ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("他アプリ EQ（対応セッションのみ）", modifier = Modifier.weight(1f), fontSize = 14.sp)
            Switch(state.running, { enable ->
                if (!enable) context.stopService(Intent(context, SystemEqService::class.java))
                else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else start()
            })
        }
        Text("全アプリ共通の EQ は保証されません。有効にした後、対応プレーヤーで曲を開き直してください。Spotify / YouTube などが対応しているとは限りません。", color = Muted, fontSize = 11.sp)
        Text("PEQ とプリアンプを端末 EQ のバンドへ近似します。コンプレッサー・HRTF・IR は他アプリには適用しません。", color = Muted, fontSize = 11.sp)
        request?.let { Text("プレーヤーからの接続要求: ${it.second} · session ${it.first}", color = Amber, fontSize = 11.sp) }
        if (state.running && state.sessions.isEmpty()) Text("対応プレーヤーからの接続待ち", color = Accent, fontSize = 12.sp)
        state.sessions.forEach { session -> Text("${session.player}\nSession ${session.id} · ${session.bands} bands · ${session.status}", color = Muted, fontSize = 11.sp) }
        state.message?.let { Text(it, color = Amber, fontSize = 11.sp) }
        if (state.running) Text("通知権限がない場合は、このスイッチまたは OS の実行中アプリ画面から停止できます。", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(PanelColor, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun LabeledSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 12.sp); Text(valueLabel, color = Accent, fontSize = 12.sp)
        }
        Slider(value.coerceIn(range), onValueChange = change, valueRange = range, modifier = Modifier.height(34.dp))
    }
}

private fun decimal(value: Float) = String.format(Locale.ROOT, "%.1f", value)
private fun timeBound(value: Float) = if (value.isFinite()) "≤ ${value.toInt()} µs" else "≥ 12,800 µs"
private fun formatHz(hz: Float) = if (hz >= 1000f) decimal(hz / 1000f) + "k" else hz.roundToInt().toString()

@Composable
private fun ResponseGraph(config: EqConfig, selected: Int, sampleRate: Float, onSelect: (Int) -> Unit, onEdit: (Int, Float, Float) -> Unit) {
    val current by rememberUpdatedState(config)
    val select by rememberUpdatedState(onSelect)
    val edit by rememberUpdatedState(onEdit)
    val curve = remember(config, sampleRate) { List(240) { i -> config.responseDb(20f * 1000f.pow(i / 239f), sampleRate) } }
    var dragging by remember { mutableIntStateOf(0) }
    fun nearest(position: Offset, width: Int, height: Int): Int = current.bands.indices.minBy { i ->
        val b = current.bands[i]; val x = log10(b.hz / 20f) / 3f * width; val y = (24f - b.db) / 48f * height
        (position.x - x).pow(2) + (position.y - y).pow(2)
    }
    Canvas(Modifier.fillMaxWidth().height(190.dp)
        .pointerInput(Unit) { detectTapGestures { p -> select(nearest(p, size.width, size.height)) } }
        .pointerInput(Unit) { detectDragGestures(onDragStart = { p -> dragging = nearest(p, size.width, size.height); select(dragging) }) { change, _ ->
            change.consume()
            val hz = 20f * 1000f.pow((change.position.x / size.width).coerceIn(0f, 1f))
            val db = (24f - change.position.y / size.height * 48f).coerceIn(-24f, 24f)
            edit(dragging, hz, db)
        } }
    ) {
        val grid = Color(0xFF2A3848)
        listOf(-24f, -12f, 0f, 12f, 24f).forEach { db ->
            val y = (24f - db) / 48f * size.height
            drawLine(if (db == 0f) Muted.copy(alpha = .5f) else grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        listOf(100f, 1000f, 10000f).forEach { hz -> val x = log10(hz / 20f) / 3f * size.width; drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f) }
        val path = Path()
        curve.forEachIndexed { i, db ->
            val x = i / 239f * size.width; val y = ((24f - db) / 48f).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (config.bypass) Muted else Accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        config.bands.forEachIndexed { i, b ->
            val p = Offset(log10(b.hz / 20f).coerceIn(0f, 3f) / 3f * size.width, (24f - b.db) / 48f * size.height)
            if (i == selected) drawCircle(Amber.copy(alpha = .15f), 13.dp.toPx(), p)
            drawCircle(if (i == selected) Amber else Accent.copy(alpha = .6f), if (i == selected) 5.dp.toPx() else 3.dp.toPx(), p)
        }
    }
}
