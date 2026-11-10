package dev.rusteq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import dev.rusteq.R
import dev.rusteq.UiText
import dev.rusteq.asUiText
import dev.rusteq.uiText

data class ExternalSession(val id: Int, val player: String, val bands: Int = 0, val status: UiText, val connected: Boolean = false)
data class SystemEqState(val running: Boolean = false, val sessions: List<ExternalSession> = emptyList(), val message: UiText? = null)

/** Control-thread only. Platform EQ on cooperating players' nonzero sessions.
 * No PCM capture, session-zero effect, or Rust dynamics/spatial processing. */
class SessionEqualizers(private val changed: (List<ExternalSession>) -> Unit) {
    private data class Entry(val player: String, val effect: Equalizer?, var state: ExternalSession)
    private val sessions = linkedMapOf<Int, Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private var config = EqConfig()
    fun update(value: EqConfig) { config = value; sessions.keys.toList().forEach(::apply) }
    fun open(id: Int, player: String): Boolean {
        if (id <= 0 || player.isBlank() || player.length > 200) return false
        if (sessions.containsKey(id)) return sessions[id]?.player == player
        if (sessions.size >= 8) return false
        val effect = runCatching { Equalizer(0, id) }.getOrNull()
        val entry = Entry(player, effect, ExternalSession(id, player, status = if (effect == null) uiText(R.string.player_session_refused) else uiText(R.string.player_session_connecting)))
        sessions[id] = entry
        runCatching {
            effect?.setControlStatusListener { _, granted ->
                handler.post {
                    // A callback queued by a released effect must not update its replacement.
                    if (sessions[id] === entry) {
                        if (granted) apply(id) else publish(id, uiText(R.string.player_session_other_control))
                    }
                }
            }
        }.onFailure {
            close(id, player)
            sessions[id] = Entry(player, null, ExternalSession(id, player, status = it.asUiText(R.string.player_session_apply_failed)))
        }
        apply(id)
        return true
    }
    /** Explicitly retry even when the previous attempt was refused or lost control. */
    fun reconnect(id: Int, player: String): Boolean {
        val entry = sessions[id]
        if (entry != null && entry.player != player) return false
        close(id, player)
        return open(id, player)
    }
    fun retainPackages(packages: Set<String>) {
        sessions.entries.filter { it.value.player !in packages }
            .map { it.key to it.value.player }.forEach { (id, player) -> close(id, player) }
    }
    fun close(id: Int, player: String) {
        val entry = sessions[id] ?: return
        if (entry.player != player) return
        sessions.remove(id)
        dispose(entry); emit()
    }
    fun release() {
        val entries = sessions.values.toList(); sessions.clear()
        entries.forEach(::dispose); emit()
    }
    private fun dispose(entry: Entry) {
        runCatching { entry.effect?.setControlStatusListener(null) }
        runCatching { entry.effect?.release() }
    }
    private fun emit() = changed(sessions.values.map { it.state })
    private fun publish(id: Int, text: UiText, bands: Int? = null, connected: Boolean = false) {
        val entry = sessions[id] ?: return
        entry.state = entry.state.copy(status = text, bands = bands ?: entry.state.bands, connected = connected); emit()
    }
    private fun apply(id: Int) {
        val effect = sessions[id]?.effect ?: run { emit(); return }
        runCatching {
            if (!effect.hasControl()) { publish(id, uiText(R.string.player_session_other_control)); return }
            if (config.bypass) {
                check(effect.setEnabled(false) == AudioEffect.SUCCESS)
                publish(id, uiText(R.string.player_session_bypass), effect.numberOfBands.toInt(), connected = true); return
            }
            val range = effect.bandLevelRange
            var limited = false
            for (band in 0 until effect.numberOfBands.toInt()) {
                val center = effect.getCenterFreq(band.toShort()) / 1000f
                // This is a sampled approximation; hardware band shapes/count
                // differ from the app's PEQ. Spatial/dynamics are not translated.
                val requested = (config.responseDb(center, 48000f) * 100).toInt()
                val level = requested.coerceIn(range[0].toInt(), range[1].toInt())
                limited = limited || level != requested
                effect.setBandLevel(band.toShort(), level.toShort())
            }
            check(effect.setEnabled(true) == AudioEffect.SUCCESS)
            publish(id, if (limited) uiText(R.string.player_session_limited) else uiText(R.string.player_session_applied), effect.numberOfBands.toInt(), connected = true)
        }.onFailure { publish(id, it.asUiText(R.string.player_session_apply_failed)) }
    }
}
