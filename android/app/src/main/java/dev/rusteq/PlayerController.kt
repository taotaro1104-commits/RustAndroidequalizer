package dev.rusteq

import android.app.Application
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import dev.rusteq.audio.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first

data class PlayerState(
    val config: EqConfig = EqConfig(), val selected: Int = 0,
    val title: UiText = uiText(R.string.player_choose_audio), val loading: Boolean = false,
    val loaded: Boolean = false, val playing: Boolean = false, val finished: Boolean = false,
    val metrics: List<Float> = List(14) { 0f }, val message: UiText? = null,
    val presetNames: List<String> = emptyList(),
    val spatialSettings: SpatialSettings = SpatialSettings(),
    val preparingEffect: Boolean = false, val effectLatencyMs: Float = 0f,
    val queueSize: Int = 0, val queueIndex: Int = 0,
    val optimizing: Boolean = false, val optimizationReport: UiText? = null,
    val presetTitle: String? = null,
    val ab: AbState? = null,
    val matching: Boolean = false, val canUndoMatch: Boolean = false,
    val bpm: Float? = null, val bpmBusy: Boolean = false,
    val hearing: HearingUi? = null, val room: RoomUi? = null,
    val profilesOn: Boolean = false, val profileKeys: Set<String> = emptySet(), val activeProfile: String? = null, val outputKind: OutputKind = OutputKind.OTHER,
)

/** Hearing-test progress for the UI; [gains] is set once finished. */
data class HearingUi(val frequency: Float, val levelDb: Float, val step: Int, val total: Int, val finished: Boolean = false, val gains: List<Float>? = null)

/** Room-calibration progress: measuring, or finished with proposed [gains]. */
data class RoomUi(val busy: Boolean, val gains: List<Float>? = null)

/** Which sound the A/B (ABX) panel is currently playing. X is a hidden A or B. */
enum class AbSlot { A, B, X }

/** Immutable snapshot for the UI; the hidden ABX assignment is never part of it. */
data class AbState(
    val listening: AbSlot = AbSlot.A,
    /** How many dB the louder side was lowered so both sides match in estimated loudness. */
    val trimDb: Float = 0f, val trimmedIsA: Boolean = true,
    val abxRunning: Boolean = false, val abxDone: Int = 0, val abxTotal: Int = 0,
    val abxCorrect: Int = 0, val abxP: Double = 1.0, val lastRight: Boolean? = null,
)

class PlayerController(private val app: Application) {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nativeEngine by lazy { NativeEngine() }
    private val preferences = app.getSharedPreferences("eq", Context.MODE_PRIVATE)
    private val soundSettings = SoundSettingsStore(preferences)
    private val restoredSound = soundSettings.read()
    private var requestedSpatial = restoredSound.spatial
    private var queue: List<Uri> = runCatching { val a = org.json.JSONArray(preferences.getString("queue", "[]")); List(a.length()) { Uri.parse(a.getString(it)) } }.getOrDefault(emptyList())
    private var queueIndex = preferences.getInt("queueIndex", 0).coerceIn(0, (queue.size-1).coerceAtLeast(0))
    private val audio = app.getSystemService(AudioManager::class.java)
    private val nativeLock = kotlinx.coroutines.sync.Mutex()
    private val mutable = MutableStateFlow(PlayerState(
        config = restoredSound.config,
        spatialSettings = restoredSound.spatial,
        presetTitle = restoredSound.presetTitle,
        selected = restoredSound.selectedBand,
        message = if (restoredSound.restoreFailed) uiText(R.string.player_settings_restore_failed) else null,
        presetNames = preferences.all.keys.filter { it.startsWith("preset:") }.map { it.removePrefix("preset:") }.sorted(),
        queueSize = queue.size, queueIndex = queueIndex,
    ))
    val state = mutable.asStateFlow()
    private var dirty = true
    private var lastVolume = 1f
    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else 1f
    }
    private var presetRevision = 0
    private var lastPath: String? = null
    private var lastWasDemo = true
    private var foreground = true
    private var effectJob: Job? = null
    private var sourceRevision = 0
    private var cpuStrikes = 0
    private var effectSettledAt = 0L
    private var sourceJob: Job? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    private val effectDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change -> if (change != AudioManager.AUDIOFOCUS_GAIN) pause() }.build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }

    init {
        ContextCompat.registerReceiver(app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(20)
                if (ticks % 10 == 0 && mutable.value.config.loudnessStrength > 0f) {
                    // Volume buttons change the system stream, so poll cheaply (every 200 ms) and re-derive the EQ.
                    val volume = currentVolume()
                    if (volume != lastVolume) { lastVolume = volume; dirty = true }
                }
                if (ticks % 3000 == 2999) reevaluateProfile() // the night window can start while playing
                if (dirty) {
                    val config = mutable.value.config
                    val sent = LoudnessCompensation.apply(config, lastVolume)
                    val accepted = native { nativeEngine.configure(sent.packed()) }
                    if (accepted && mutable.value.config == config) dirty = false
                }
                if (++ticks % 10 == 0 && mutable.value.loaded && !mutable.value.loading) {
                    val metrics = native { nativeEngine.metrics().toList() }
                    if (metrics.size >= 14) {
                        mutable.update { it.copy(metrics = metrics, playing = metrics[7] > .5f, finished = metrics[8] > .5f) }
                        if (metrics[8] > .5f && !lastWasDemo && queueIndex < queue.lastIndex) nextTrack()
                        val settings = mutable.value.spatialSettings
                        if (metrics[7] > .5f && (settings.hrtf || settings.reverb != 0) && !mutable.value.preparingEffect && android.os.SystemClock.elapsedRealtime() >= effectSettledAt && metrics[0] > 0f && metrics[12] > 0f) {
                            val budget = metrics[12] / metrics[0] * 1_000_000f * if (settings.quality == 0) 1f else .5f
                            cpuStrikes = if (metrics[11] > budget) cpuStrikes+1 else 0
                            if (cpuStrikes >= if (settings.quality == 0) 5 else 3) {
                                cpuStrikes = 0
                                if (settings.quality > 0) {
                                    adaptSpatialForLoad { it.copy(quality = it.quality-1) }
                                    mutable.update { it.copy(message = uiText(R.string.player_effect_load_reduced)) }
                                } else {
                                    adaptSpatialForLoad { it.copy(hrtf = false, reverb = 0) }
                                    mutable.update { it.copy(message = uiText(R.string.player_effects_stopped)) }
                                }
                            }
                        } else cpuStrikes = 0
                        if (metrics[9] > .5f) {
                            mutable.update { it.copy(playing = false, message = uiText(R.string.player_output_disconnected)) }
                        }
                    }
                    val error = native { nativeEngine.error() }
                    if (error.isNotBlank()) { pause(); mutable.update { it.copy(message = IllegalStateException(error).asUiText(R.string.player_playback_failed)) } }
                }
            }
        }
    }

    private suspend fun <T> native(action: () -> T): T = withContext(Dispatchers.IO) {
        nativeLock.lock()
        try { action() } finally { nativeLock.unlock() }
    }

    fun configure(change: (EqConfig) -> EqConfig) {
        if (abActive) return
        presetRevision++
        mutable.update { it.copy(config = change(it.config), presetTitle = null) }; dirty = true
        persistSoundSettings()
    }

    private fun persistSoundSettings() {
        if (abActive) return // temporary A/B sounds must never overwrite the saved settings
        val value = mutable.value
        soundSettings.save(value.config, requestedSpatial, value.presetTitle, value.selected)
    }

    /** Drain asynchronous preference saves from the application IO scope. */
    fun flushPendingSettings() {
        if (!soundSettings.flush()) {
            mutable.update { it.copy(message = uiText(R.string.player_settings_save_failed)) }
        }
    }

    fun applyBuiltinPreset(id: String) {
        val preset = BuiltinPresets.find(id) ?: return
        if (abActive) return
        val revision = ++presetRevision
        viewModelScope.launch {
            val config = withContext(Dispatchers.Default) { preset.config }
            if (revision != presetRevision) return@launch
            mutable.update { it.copy(config = config, selected = 0, presetTitle = preset.title) }
            dirty = true
            persistSoundSettings()
        }
    }

    // ---- Hearing test: ascending-level thresholds -> personal 31-band compensation ----
    private var hearingJob: Job? = null
    @Volatile private var hearingHeard = false

    fun hearingStart() {
        if (abActive || hearingJob?.isActive == true) return
        hearingJob = viewModelScope.launch {
            pauseNow()
            val session = HearingTestSession()
            val total = HearingProfile.testFrequencies.size
            try {
                while (!session.finished) {
                    mutable.update { it.copy(hearing = HearingUi(session.frequency, session.levelDb, session.index, total)) }
                    hearingHeard = false
                    ToneOutput.pulse(session.frequency, session.levelDb)
                    delay(700)
                    if (hearingHeard) session.heard() else session.notHeard()
                }
                val gains = HearingProfile.gains31(session.thresholds).toList()
                mutable.update { it.copy(hearing = HearingUi(0f, 0f, total, total, finished = true, gains = gains)) }
            } catch (e: CancellationException) {
                mutable.update { it.copy(hearing = null) }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(hearing = null, message = uiText(R.string.player_hearing_failed)) }
            }
        }
    }

    /** The listener pressed "I hear it"; consumed after the current tone and its pause. */
    fun hearingHeard() { hearingHeard = true }

    fun hearingCancel() { hearingJob?.cancel(); mutable.update { it.copy(hearing = null) } }

    fun hearingApply() {
        val gains = mutable.value.hearing?.gains ?: return
        if (abActive) return
        val before = mutable.value.config
        configure { EqMatch.toConfig(it, gains.toFloatArray()) }; select(0)
        matchUndo = before
        mutable.update { it.copy(hearing = null, canUndoMatch = true, message = uiText(R.string.player_hearing_applied)) }
    }

    // ---- Room calibration: pink noise out, microphone in, conservative corrective EQ ----
    private var roomJob: Job? = null

    fun roomMeasure() {
        if (abActive || roomJob?.isActive == true || mutable.value.hearing != null) return
        roomJob = viewModelScope.launch {
            pauseNow()
            mutable.update { it.copy(room = RoomUi(busy = true), message = null) }
            runCatching {
                val result = RoomMeasurement.run(app)
                if (!RoomCalibration.isUsable(result.signalDb, result.ambientDb)) throw UiTextException(uiText(R.string.player_room_too_quiet))
                RoomCalibration.gains(result.signalDb).toList()
            }.onSuccess { gains ->
                mutable.update { it.copy(room = RoomUi(busy = false, gains = gains)) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                mutable.update { it.copy(room = null, message = e.asUiText(R.string.player_room_failed)) }
            }
        }
    }

    fun roomDenied() { mutable.update { it.copy(message = uiText(R.string.player_room_permission_denied)) } }

    fun roomClose() { roomJob?.cancel(); mutable.update { it.copy(room = null) } }

    fun roomApply() {
        val gains = mutable.value.room?.gains ?: return
        if (abActive) return
        val before = mutable.value.config
        configure { EqMatch.addTo(it, gains.toFloatArray()) }
        matchUndo = before
        mutable.update { it.copy(room = null, canUndoMatch = true, message = uiText(R.string.player_room_applied)) }
    }

    // ---- Automatic profiles: this track > night > output device ----
    private val profiles = ProfileStore(preferences)
    private var profileBase: EqConfig? = null
    private var lastResolved: String? = null

    private fun outputKind(): OutputKind = OutputClassifier.classify(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
    private fun currentTrackId(): String? = queue.getOrNull(queueIndex)?.toString()?.takeIf { !lastWasDemo }
    private fun hourNow() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    fun deviceKeyNow() = ProfileResolver.deviceKey(outputKind())
    fun trackKeyNow() = currentTrackId()?.let(ProfileResolver::trackKey)

    private fun publishProfiles(active: String?) {
        mutable.update { it.copy(profilesOn = profiles.enabled, profileKeys = profiles.keys(), activeProfile = active, outputKind = outputKind()) }
    }

    /** Edge-triggered: only acts when the winning profile changes, so manual edits are never fought. */
    fun reevaluateProfile() {
        if (!profiles.enabled) { publishProfiles(null); return }
        if (abActive) return
        val resolved = ProfileResolver.resolve(ProfileResolver.candidates(currentTrackId(), hourNow(), outputKind()), profiles.keys())
        if (resolved == lastResolved) { publishProfiles(resolved); return }
        lastResolved = resolved
        val target = resolved?.let(profiles::get) ?: if (resolved == null) profileBase else null
        if (resolved != null && target != null && profileBase == null) profileBase = mutable.value.config
        if (resolved == null) profileBase = null
        if (target != null) {
            presetRevision++
            mutable.update { it.copy(config = target, selected = 0, presetTitle = null) }; dirty = true
            persistSoundSettings()
        }
        publishProfiles(resolved)
    }

    fun setProfilesEnabled(on: Boolean) {
        profiles.enabled = on
        if (!on) profileBase?.let { b -> presetRevision++; mutable.update { it.copy(config = b, selected = 0, presetTitle = null) }; dirty = true; persistSoundSettings() }
        profileBase = null; lastResolved = null
        reevaluateProfile()
    }

    /** Saves the current sound under [key]; it takes effect the next time that condition wins. */
    fun saveProfile(key: String) {
        if (abActive) return
        profiles.put(key, mutable.value.config)
        publishProfiles(mutable.value.activeProfile)
    }

    fun deleteProfile(key: String) {
        profiles.remove(key)
        reevaluateProfile()
    }

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { reevaluateProfile() }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { reevaluateProfile() }
    }

    init { // after the property initializers above
        audio.registerAudioDeviceCallback(deviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        publishProfiles(null)
    }

    // ---- Tempo-synced echo ----
    fun detectTempo() {
        val path = lastPath?.takeIf { !lastWasDemo }
        if (path == null || !mutable.value.loaded) { mutable.update { it.copy(message = uiText(R.string.player_match_need_track)) }; return }
        if (mutable.value.bpmBusy) return
        mutable.update { it.copy(bpmBusy = true) }
        viewModelScope.launch {
            val bpm = runCatching { withContext(Dispatchers.Default) {
                val d = BpmDetector(); PcmDecoder.decode(path) { m, n, sr -> d.add(m, n, sr) }; d.bpm()
            } }.getOrNull()
            mutable.update { it.copy(bpmBusy = false, bpm = bpm, message = if (bpm == null) uiText(R.string.player_tempo_failed) else it.message) }
        }
    }

    fun echoNote(note: EchoNote) {
        val bpm = mutable.value.bpm ?: return
        configure { it.copy(echoMix = it.echoMix.coerceAtLeast(.05f), echoTimeMs = TempoSync.echoMs(bpm, note)) }
    }

    // ---- EQ match: move the current track's tonal balance toward a reference track ----
    private var matchUndo: EqConfig? = null
    private var currentSpectrum: Pair<String, DoubleArray>? = null

    fun matchToReference(reference: Uri, strength: Float) {
        val path = lastPath?.takeIf { !lastWasDemo }
        val s = mutable.value
        if (abActive || s.matching || s.loading) return
        if (path == null || !s.loaded) { mutable.update { it.copy(message = uiText(R.string.player_match_need_track)) }; return }
        mutable.update { it.copy(matching = true, message = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val current = currentSpectrum?.takeIf { it.first == path }?.second
                        ?: TrackSpectrum.analyze(path)?.also { currentSpectrum = path to it }
                        ?: throw UiTextException(uiText(R.string.player_match_analyze_failed))
                    val target = TrackSpectrum.analyze(app, reference) ?: throw UiTextException(uiText(R.string.player_match_analyze_failed))
                    EqMatch.gains(target, current, strength)
                }
            }.onSuccess { gains ->
                val before = mutable.value.config
                if (gains.all { it == 0f }) { mutable.update { it.copy(matching = false, message = uiText(R.string.player_match_already_close)) }; return@onSuccess }
                configure { EqMatch.toConfig(it, gains) }; select(0)
                matchUndo = before
                mutable.update { it.copy(matching = false, canUndoMatch = true, message = uiText(R.string.player_match_done)) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                mutable.update { it.copy(matching = false, message = e.asUiText(R.string.player_match_analyze_failed)) }
            }
        }
    }

    fun undoMatch() {
        val before = matchUndo ?: return
        matchUndo = null
        configure { before }
        mutable.update { it.copy(canUndoMatch = false) }
    }

    // ---- Level-matched A/B and ABX (A = current sound, B = same sound with a flat EQ curve) ----
    private val abActive get() = abOriginal != null
    private var abOriginal: EqConfig? = null
    private var abPair: Pair<EqConfig, EqConfig>? = null
    private var abx: AbxSession? = null

    fun abStart() {
        if (abActive) return
        val original = mutable.value.config.copy(bypass = false, loudnessStrength = 0f) // fixed level: volume-linked boost would bias the comparison
        val flat = original.copy(bands = original.bands.map { it.copy(db = 0f) }, preamp = 0f)
        val pair = LoudnessMatch.matchedPair(original, flat)
        val trim = if (LoudnessMatch.meanDb(original) > LoudnessMatch.meanDb(flat)) pair.first.preamp - original.preamp else pair.second.preamp - flat.preamp
        abOriginal = mutable.value.config; abPair = pair; abx = null
        mutable.update { it.copy(config = pair.first, ab = AbState(AbSlot.A, trimDb = trim, trimmedIsA = LoudnessMatch.meanDb(original) > LoudnessMatch.meanDb(flat))) }
        dirty = true
    }

    fun abListen(slot: AbSlot) {
        val pair = abPair ?: return
        val session = abx
        val useA = when (slot) { AbSlot.A -> true; AbSlot.B -> false; AbSlot.X -> session?.takeIf { !it.finished }?.hiddenIsA ?: return }
        mutable.update { it.copy(config = if (useA) pair.first else pair.second, ab = it.ab?.copy(listening = slot)) }
        dirty = true
    }

    fun abxBegin() {
        if (!abActive) return
        abx = AbxSession().also { abxSnapshot(it, null) }
        abListen(AbSlot.X)
    }

    fun abxAnswer(guessA: Boolean) {
        val session = abx ?: return
        if (session.finished) return
        val right = session.answer(guessA)
        abxSnapshot(session, right)
        if (!session.finished) abListen(AbSlot.X)
    }

    private fun abxSnapshot(session: AbxSession, right: Boolean?) {
        mutable.update { it.copy(ab = it.ab?.copy(abxRunning = !session.finished, abxDone = session.done, abxTotal = session.totalTrials,
            abxCorrect = session.correct, abxP = session.pValue, lastRight = right)) }
    }

    fun abEnd() {
        val original = abOriginal ?: return
        abOriginal = null; abPair = null; abx = null
        mutable.update { it.copy(config = original, ab = null) }
        dirty = true
    }

    fun select(index: Int) {
        mutable.update { it.copy(selected = index.coerceIn(0, it.config.bands.lastIndex)) }
        persistSoundSettings()
    }
    fun band(hz: Float? = null, db: Float? = null, q: Float? = null) {
        val selected = mutable.value.selected
        configure { c -> c.copy(bands = c.bands.mapIndexed { i, b -> if (i == selected) b.copy(hz = hz?.coerceIn(10f, 20000f) ?: b.hz, db = db ?: b.db, q = q ?: b.q) else b }) }
    }

    fun bandCount(count: Int) {
        configure { it.copy(bands = EqConfig.defaultBands(count)) }; select(0)
    }

    fun loadQueue(uris: List<Uri>) {
        if (uris.isEmpty() || mutable.value.loading) return
        val next = uris.distinct()
        for (uri in queue.filter { it !in next }) runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        for (uri in next) runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        queue = next; queueIndex = 0; saveQueue(); load(queue[0])
    }
    private fun saveQueue() {
        preferences.edit().putString("queue", org.json.JSONArray(queue.map(Uri::toString)).toString()).putInt("queueIndex", queueIndex).apply()
        mutable.update { it.copy(queueSize = queue.size, queueIndex = queueIndex) }
    }
    fun clearQueue() {
        for (uri in queue) runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        queue = emptyList(); queueIndex = 0; saveQueue()
    }
    fun nextTrack() {
        if (mutable.value.loading || queue.isEmpty()) return
        if (!lastWasDemo) { if (queueIndex >= queue.lastIndex) return; queueIndex++ }
        saveQueue(); load(queue[queueIndex])
    }
    fun previousTrack() {
        if (mutable.value.loading) return
        if (!lastWasDemo && mutable.value.metrics[6] > 3f) { seek(0f); return }
        if (queue.isEmpty()) return
        queueIndex = (queueIndex-1).coerceAtLeast(0); saveQueue(); load(queue[queueIndex])
    }

    fun load(uri: Uri) {
        if (mutable.value.loading) return
        effectJob?.cancel(); sourceRevision++
        sourceJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = null) }
            pauseNow()
            runCatching {
                val title = withContext(Dispatchers.IO) {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment
                }
                // SAF files are copied once; Rust performs all decode/PCM/SRC work.
                val extension = title?.substringAfterLast('.', "bin")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,6}")) } ?: "bin"
                val destination = withContext(Dispatchers.IO) {
                    val target = File.createTempFile("track-", ".$extension", app.cacheDir)
                    try {
                        app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                total += n
                                if (total > 1024L * 1024 * 1024) throw UiTextException(uiText(R.string.player_audio_too_large))
                                output.write(buffer, 0, n)
                            }
                        } } ?: throw UiTextException(uiText(R.string.player_file_open_failed))
                        target
                    } catch (e: Exception) { target.delete(); throw e }
                }
                val error = native { nativeEngine.load(destination.absolutePath) }
                if (error.isNotBlank()) { destination.delete(); error(error) }
                lastPath?.let { File(it).delete() }
                lastPath = destination.absolutePath; lastWasDemo = false
                val loadedMetrics = native { nativeEngine.metrics().toList() }
                mutable.update { it.copy(title = title?.let { name -> UiText.Literal(name) } ?: uiText(R.string.player_local_audio), loaded = true, finished = false, metrics = loadedMetrics, effectLatencyMs = 0f, bpm = null) }
                reevaluateProfile()
                startWithFocus()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                mutable.update { it.copy(message = error.asUiText(R.string.player_load_failed), loaded = false, playing = false) }
            }
            mutable.update { it.copy(loading = false) }; dirty = true
            sourceRevision++; scheduleEffect()
        }
    }

    fun demo() {
        if (mutable.value.loading) return
        effectJob?.cancel(); sourceRevision++
        sourceJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = null) }
            pauseNow()
            val error = native { nativeEngine.load(null) }
            if (error.isBlank()) {
                lastPath?.let { File(it).delete() }; lastPath = null; lastWasDemo = true
                val loadedMetrics = native { nativeEngine.metrics().toList() }
                mutable.update { it.copy(title = uiText(R.string.player_demo_title), loaded = true, finished = false, metrics = loadedMetrics, effectLatencyMs = 0f, bpm = null) }
                startWithFocus()
            } else mutable.update { it.copy(message = IllegalStateException(error).asUiText(R.string.player_load_failed), loaded = false) }
            mutable.update { it.copy(loading = false) }; dirty = true
            sourceRevision++; scheduleEffect()
        }
    }

    fun togglePlay() {
        val s = mutable.value
        if (s.loading) return
        if (!s.loaded) { if (queue.isNotEmpty()) load(queue[queueIndex]) else demo(); return }
        if (s.playing) { pause(); return }
        sourceJob = viewModelScope.launch {
            if (s.finished || s.metrics[9] > .5f) {
                val error = native { nativeEngine.load(if (lastWasDemo) null else lastPath) }
                if (error.isNotBlank()) { mutable.update { it.copy(message = IllegalStateException(error).asUiText(R.string.player_load_failed)) }; return@launch }
                dirty = true
                sourceRevision++; scheduleEffect()
            }
            startWithFocus()
        }
    }

    fun seek(seconds: Float) {
        val before = mutable.value
        if (!before.loaded || before.loading || !seconds.isFinite()) return
        effectJob?.cancel(); sourceRevision++
        sourceJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = null) }
            val error = native { nativeEngine.seekTo(seconds.coerceAtLeast(0f).toDouble()) }
            if (error.isEmpty()) {
                mutable.update { it.copy(finished = false) }
                if (before.playing) startWithFocus()
            } else mutable.update { it.copy(message = IllegalStateException(error).asUiText(R.string.player_seek_failed), loaded = false, playing = false) }
            mutable.update { it.copy(loading = false) }; dirty = true; scheduleEffect()
        }
    }

    private suspend fun startWithFocus() {
        val runtime = app as EqApplication
        if (!foreground && !runtime.playbackServiceRunning.value) { native { nativeEngine.setPlaying(false) }; return }
        if (!runtime.playbackServiceRunning.value) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java).setAction(PlaybackService.PREPARE))
                withTimeout(5000) { runtime.playbackServiceRunning.first { it } }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                native { nativeEngine.setPlaying(false) }
                mutable.update { it.copy(playing = false, message = e.asUiText(R.string.player_service_start_failed)) }
                return
            }
        }
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            native { nativeEngine.setPlaying(true) }; mutable.update { it.copy(playing = true, message = null) }
        } else {
            native { nativeEngine.setPlaying(false) }; mutable.update { it.copy(playing = false, message = uiText(R.string.player_audio_focus_failed)) }
        }
    }
    private suspend fun pauseNow() { native { nativeEngine.setPlaying(false) }; audio.abandonAudioFocusRequest(focus); mutable.update { it.copy(playing = false) } }
    fun pause() { viewModelScope.launch { pauseNow() } }
    fun setForeground(value: Boolean) {
        val wasForeground = foreground
        foreground = value
        if (!value) persistSoundSettings()
        else if (!wasForeground && mutable.value.spatialSettings != requestedSpatial) {
            mutable.update { it.copy(spatialSettings = requestedSpatial) }
            scheduleEffect()
        }
    }
    fun resumeFromNotification() { if (mutable.value.loaded && !mutable.value.playing) togglePlay() }
    fun stopPlayback() {
        sourceJob?.cancel(); effectJob?.cancel(); sourceRevision++
        viewModelScope.launch {
            native { nativeEngine.close() }
            audio.abandonAudioFocusRequest(focus)
            mutable.update { it.copy(loaded = false, playing = false, finished = false, loading = false, metrics = List(14) { 0f }, preparingEffect = false, effectLatencyMs = 0f) }
            app.stopService(Intent(app, PlaybackService::class.java))
        }
    }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }

    fun spatial(change: (SpatialSettings) -> SpatialSettings) {
        val next = change(requestedSpatial)
        if (next == requestedSpatial && next == mutable.value.spatialSettings) return
        requestedSpatial = next
        mutable.update { it.copy(spatialSettings = next) }
        persistSoundSettings()
        scheduleEffect()
    }

    /** Performance protection changes the current effect, never the user's saved profile. */
    private fun adaptSpatialForLoad(change: (SpatialSettings) -> SpatialSettings) {
        mutable.update { it.copy(spatialSettings = change(it.spatialSettings)) }
        scheduleEffect()
    }

    private fun scheduleEffect() {
        effectJob?.cancel()
        mutable.update { it.copy(preparingEffect = false) }
        if (!mutable.value.loaded || mutable.value.loading) return
        val settings = mutable.value.spatialSettings
        val revision = sourceRevision
        effectJob = viewModelScope.launch {
            delay(250)
            mutable.update { it.copy(preparingEffect = true) }
            var token = 0L
            try {
                val rate = native { nativeEngine.metrics()[0].toInt() }
                val ir = when (settings.reverb) { 1 -> ""; 2 -> settings.irPath ?: throw UiTextException(uiText(R.string.player_choose_ir_first)); else -> null }
                val sofa = if (settings.hrtf) settings.sofaPath ?: throw UiTextException(uiText(R.string.player_choose_sofa_first)) else null
                withContext(effectDispatcher + NonCancellable) { token = nativeEngine.prepareEffect(rate, ir, sofa, settings.azimuth, settings.wet, settings.quality) }
                ensureActive()
                if (revision != sourceRevision) return@launch
                val latency = nativeEngine.effectLatency(token)
                var installed = false
                repeat(100) {
                    ensureActive()
                    if (!installed) { installed = native { val accepted = nativeEngine.installEffect(token); if (accepted) token = 0L; accepted }; if (!installed) delay(20) }
                }
                if (!installed) throw UiTextException(uiText(R.string.player_effect_apply_failed))
                token = 0
                effectSettledAt = android.os.SystemClock.elapsedRealtime() + 1000
                mutable.update { it.copy(effectLatencyMs = latency * 1000f / rate, preparingEffect = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ensureActive(); mutable.update { it.copy(preparingEffect = false, message = e.asUiText(R.string.player_effect_prepare_failed)) } }
            finally {
                if (token != 0L) withContext(NonCancellable + Dispatchers.IO) { nativeEngine.releaseEffect(token) }
            }
        }
    }

    fun importEffect(uri: Uri, isSofa: Boolean) {
        viewModelScope.launch {
            runCatching {
                val copied = withContext(Dispatchers.IO) {
                    val title = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: if (isSofa) "HRTF.sofa" else "IR.wav"
                    val ext = if (isSofa) "sofa" else title.substringAfterLast('.', "wav").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,6}")) } ?: "wav"
                    val directory = File(app.filesDir, "effects")
                    check(directory.isDirectory || directory.mkdirs())
                    val file = File.createTempFile(if (isSofa) "hrtf-" else "ir-", ".$ext", directory)
                    try {
                        app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                            val bytes = ByteArray(65536); var total = 0
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(bytes)
                                if (n < 0) break
                                total += n
                                if (total > 64*1024*1024) throw UiTextException(uiText(R.string.player_effect_too_large))
                                output.write(bytes, 0, n)
                            }
                        } } ?: throw UiTextException(uiText(R.string.player_file_open_failed))
                        file.absolutePath to title
                    } catch (e: Exception) { file.delete(); throw e }
                }
                // Saved profiles must not depend on evictable cache files.
                val previous = if (isSofa) requestedSpatial.sofaPath else requestedSpatial.irPath
                if (isSofa) configure { it.copy(spatial = 0) }
                spatial { if (isSofa) it.copy(sofaPath = copied.first, sofaName = copied.second, hrtf = true) else it.copy(irPath = copied.first, irName = copied.second, reverb = 2) }
                withContext(Dispatchers.IO) {
                    previous?.let { path ->
                        val old = File(path).canonicalFile
                        val directory = File(app.filesDir, "effects").canonicalFile
                        if (old.parentFile == directory && old.path != File(copied.first).canonicalPath) old.delete()
                    }
                }
            }.onFailure { e -> if (e is CancellationException) throw e; mutable.update { it.copy(message = e.asUiText(R.string.player_effect_import_failed)) } }
        }
    }

    fun savePreset(name: String) {
        val clean = name.trim().take(40); if (clean.isEmpty()) return
        preferences.edit().putString("preset:$clean", mutable.value.config.toJson()).apply()
        mutable.update { it.copy(presetNames = (it.presetNames + clean).distinct().sorted(), message = uiText(R.string.player_preset_saved, UiText.Literal(clean))) }
    }
    fun deletePreset(name: String) {
        preferences.edit().remove("preset:$name").apply()
        mutable.update { it.copy(presetNames = it.presetNames.filter { n -> n != name }) }
    }
    fun exportProfile(uri: Uri) {
        val text = mutable.value.config.toJson()
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: throw UiTextException(uiText(R.string.player_destination_open_failed))
            } }.onSuccess { mutable.update { it.copy(message = uiText(R.string.player_profile_exported)) } }
                .onFailure { e -> if (e is CancellationException) throw e; mutable.update { it.copy(message = e.asUiText(R.string.player_profile_export_failed)) } }
        }
    }
    fun applyPreset(name: String) {
        if (abActive) return
        runCatching { EqConfig.fromJson(preferences.getString("preset:$name", "")!!) }
            .onSuccess { cfg ->
                presetRevision++
                mutable.update { it.copy(config = cfg, selected = 0, presetTitle = name) }
                dirty = true
                persistSoundSettings()
            }
            .onFailure { e -> mutable.update { it.copy(message = e.asUiText(R.string.player_preset_load_failed)) } }
    }
    fun importProfile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use {
                    val bytes = ByteArray(256 * 1024 + 1)
                    var used = 0
                    while (used < bytes.size) { val n = it.read(bytes, used, bytes.size - used); if (n < 0) break; used += n }
                    if (used > 256 * 1024) throw UiTextException(uiText(R.string.player_profile_too_large))
                    String(bytes, 0, used, Charsets.UTF_8)
                } ?: throw UiTextException(uiText(R.string.player_profile_open_failed)) }
                val cfg = if (text.trimStart().startsWith("{")) EqConfig.fromJson(text) else EqConfig.fromAutoEq(text)
                configure { cfg }; select(0)
                mutable.update { it.copy(message = uiText(R.string.player_profile_imported, cfg.bands.size)) }
            }.onFailure { e -> if (e is CancellationException) throw e; mutable.update { it.copy(message = e.asUiText(R.string.player_profile_import_failed)) } }
        }
    }

    fun optimizeMeasurement(uri: Uri, bands: Int) {
        if (mutable.value.optimizing) return
        val original = mutable.value.config
        viewModelScope.launch {
            mutable.update { it.copy(optimizing = true, optimizationReport = null, message = null) }
            try {
                val text = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArray(512 * 1024 + 1)
                        var used = 0
                        while (used < bytes.size) { val count = input.read(bytes, used, bytes.size-used); if (count < 0) break; used += count }
                        if (used > 512 * 1024) throw UiTextException(uiText(R.string.player_measurement_too_large))
                        String(bytes, 0, used, Charsets.UTF_8)
                    } ?: throw UiTextException(uiText(R.string.player_measurement_open_failed))
                }
                val rate = mutable.value.metrics[0].toInt().takeIf { it in 32000..192000 } ?: 48000
                val settings = withContext(effectDispatcher) { nativeEngine.optimize(text, bands, rate) }
                ensureActive()
                // A user edit made while fitting must never be silently overwritten.
                if (mutable.value.config != original) throw UiTextException(uiText(R.string.player_optimization_changed))
                val result = EqConfig.fromAutoEq(settings)
                configure { it.copy(bands = result.bands, preamp = result.preamp) }; select(0)
                mutable.update { it.copy(optimizationReport = optimizationSummary(settings), message = uiQuantity(R.plurals.player_optimization_generated, result.bands.size, result.bands.size)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.asUiText(R.string.player_optimization_failed)) } }
            finally { mutable.update { it.copy(optimizing = false) } }
        }
    }

    private fun optimizationSummary(settings: String): UiText {
        val header = settings.lineSequence().firstOrNull().orEmpty().removePrefix("# ")
        val rmse = Regex("level-normalized shape RMSE: ([0-9.]+) -> ([0-9.]+) dB").find(header)
        val before = rmse?.groupValues?.get(1)?.toDoubleOrNull()
        val after = rmse?.groupValues?.get(2)?.toDoubleOrNull()
        return if (before != null && after != null) uiText(R.string.player_optimization_summary, before, after)
        else uiText(R.string.player_optimization_summary_fallback)
    }

    fun release() {
        viewModelScope.cancel()
        audio.unregisterAudioDeviceCallback(deviceCallback)
        app.unregisterReceiver(noisyReceiver)
        audio.abandonAudioFocusRequest(focus)
        // Close/join outside the main thread; C++ serializes this with pending JNI calls.
        Thread({ nativeEngine.close(); lastPath?.let { File(it).delete() } }, "eq-close").start()
    }
}
