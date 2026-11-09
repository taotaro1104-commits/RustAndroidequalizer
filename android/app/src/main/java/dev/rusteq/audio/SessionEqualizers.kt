package dev.rusteq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer

data class ExternalSession(val id: Int, val player: String, val bands: Int = 0, val status: String)
data class SystemEqState(val running: Boolean = false, val sessions: List<ExternalSession> = emptyList(), val message: String? = null)

/** Control-thread only. Platform EQ on cooperating players' nonzero sessions.
 * No PCM capture, session-zero effect, or Rust dynamics/spatial processing. */
class SessionEqualizers(private val changed: (List<ExternalSession>) -> Unit) {
    private data class Entry(val player: String, val effect: Equalizer?, var state: ExternalSession)
    private val sessions = linkedMapOf<Int, Entry>()
    private var config = EqConfig()
    fun update(value: EqConfig) { config = value; sessions.keys.toList().forEach(::apply) }
    fun open(id: Int, player: String): Boolean {
        if (id <= 0 || player.isBlank() || player.length > 200) return false
        if (sessions.containsKey(id)) return sessions[id]?.player == player
        if (sessions.size >= 8) return false
        val effect = runCatching { Equalizer(0, id) }.getOrNull()
        sessions[id] = Entry(player, effect, ExternalSession(id, player, status = if (effect == null) "端末が EQ の接続を拒否しました" else "接続中"))
        effect?.setControlStatusListener { _, granted -> if (granted) apply(id) else publish(id, "ほかの EQ が制御中") }
        apply(id)
        return true
    }
    fun close(id: Int, player: String) {
        val entry = sessions[id] ?: return
        if (entry.player != player) return
        sessions.remove(id)
        runCatching { entry.effect?.release() }; emit()
    }
    fun release() {
        val entries = sessions.values.toList(); sessions.clear()
        entries.forEach { runCatching { it.effect?.release() } }; emit()
    }
    private fun emit() = changed(sessions.values.map { it.state })
    private fun publish(id: Int, text: String, bands: Int? = null) {
        val entry = sessions[id] ?: return
        entry.state = entry.state.copy(status = text, bands = bands ?: entry.state.bands); emit()
    }
    private fun apply(id: Int) {
        val effect = sessions[id]?.effect ?: run { emit(); return }
        runCatching {
            if (!effect.hasControl()) { publish(id, "ほかの EQ が制御中"); return }
            if (config.bypass) {
                check(effect.setEnabled(false) == AudioEffect.SUCCESS)
                publish(id, "バイパス", effect.numberOfBands.toInt()); return
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
            publish(id, if (limited) "適用中 · 端末の gain 範囲に制限" else "適用中 · 端末 EQ へ近似", effect.numberOfBands.toInt())
        }.onFailure { publish(id, "適用できません: ${it.message ?: "音声経路が終了しました"}") }
    }
}
