package dev.rusteq.audio

data class DetectedAudioSession(val id: Int, val uid: Int)
enum class DiscoveryFailure { PERMISSION, FORMAT, TIMEOUT, OUTPUT_LIMIT, PROCESS }
sealed interface SessionDumpResult {
    data class Success(val sessions: Set<DetectedAudioSession>) : SessionDumpResult
    data class Failure(val reason: DiscoveryFailure) : SessionDumpResult
}
sealed interface ProcessDumpResult {
    data class Success(val output: String) : ProcessDumpResult
    data class Failure(val reason: DiscoveryFailure) : ProcessDumpResult
}
internal object AudioSessionDumpParser {
    private val uid = Regex("\\bu/pid:(-?\\d+)/-?\\d+\\b")
    private val session = Regex("\\bsessionId:(-?\\d+)\\b")
    private val state = Regex("\\bstate:(\\w+)\\b")
    private val usage = Regex("\\busage=(\\w+)\\b")
    private val mediaUsages = setOf("USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN")

    fun parse(text: String): SessionDumpResult {
        if (text.contains("Permission Denial", ignoreCase = true) || text.contains("Permission denied", ignoreCase = true)) {
            return SessionDumpResult.Failure(DiscoveryFailure.PERMISSION)
        }
        var monitor = false
        var players = false
        val sessions = linkedSetOf<DetectedAudioSession>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!players) {
                if (line.startsWith("PlaybackActivityMonitor dump time:")) monitor = true
                if (monitor && line == "players:") players = true
                continue
            }
            // An explicit end of the current-player table prevents historical
            // log entries and incomplete/truncated dumps from becoming sessions.
            if (line == "ducked players piids:") return SessionDumpResult.Success(sessions)
            if (line.isEmpty()) continue
            // Some Android 13 builds prefix an inactive player's current row.
            // Normalize that exact marker, preserving all other validation.
            val row = line.removePrefix("(not logged)").trimStart()
            if (!row.startsWith("AudioPlaybackConfiguration ")) return SessionDumpResult.Failure(DiscoveryFailure.FORMAT)
            val id = session.find(row)?.groupValues?.get(1)?.toIntOrNull()
            val owner = uid.find(row)?.groupValues?.get(1)?.toIntOrNull()
            val playback = state.find(row)?.groupValues?.get(1)
            val kind = usage.find(row)?.groupValues?.get(1)
            if (id == null || owner == null || playback == null || kind == null) return SessionDumpResult.Failure(DiscoveryFailure.FORMAT)
            if (id > 0 && owner > 0 && playback == "started" && kind in mediaUsages) sessions += DetectedAudioSession(id, owner)
        }
        return SessionDumpResult.Failure(DiscoveryFailure.FORMAT)
    }
}
internal object SelectedAudioSessions {
    fun resolve(sessions: Set<DetectedAudioSession>, selected: Map<String, Int>, owners: Map<Int, Set<String>>, ownUid: Int): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        // A shared UID cannot be attributed to one selected application safely.
        val selectedOwners = selected.entries.filter { (name, uid) -> uid != ownUid && owners[uid] == setOf(name) }.associate { it.value to it.key }
        for ((id, grouped) in sessions.groupBy { it.id }) {
            val uid = grouped.map { it.uid }.distinct().singleOrNull() ?: continue
            if (id > 0) selectedOwners[uid]?.let { result[id] = it }
        }
        return result
    }
}
