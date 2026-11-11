package dev.rusteq.audio

enum class ExternalPlaybackKind { PLAYING, PAUSED, BUFFERING, STOPPED }
data class ExternalTrack(val packageName:String,val title:String?,val artist:String?,val album:String?,val durationMs:Long?,val positionMs:Long?,val updatedAtMs:Long,val speed:Float,val kind:ExternalPlaybackKind,val remote:Boolean) {
    fun positionAt(nowMs:Long):Long? {
        val position=positionMs?.takeIf { it>=0 } ?: return null
        val elapsed=if (kind==ExternalPlaybackKind.PLAYING && updatedAtMs>0 && speed.isFinite()) {
            (nowMs.toDouble()-updatedAtMs).coerceAtLeast(0.0)*speed
        } else 0.0
        val estimated=(position.toDouble()+elapsed).coerceAtLeast(0.0).toLong()
        return durationMs?.takeIf { it>=0 }?.let { estimated.coerceAtMost(it) } ?: estimated
    }
}
internal fun preferredExternalTracks(tracks:List<ExternalTrack>, selected:Set<String>, ownPackage:String):List<ExternalTrack> =
    tracks.filter { it.packageName in selected && it.packageName!=ownPackage && it.kind!=ExternalPlaybackKind.STOPPED }
        .groupBy { it.packageName }.values.map { group ->
            group.maxWith(compareBy<ExternalTrack> { when(it.kind) {
                ExternalPlaybackKind.PLAYING->3
                ExternalPlaybackKind.BUFFERING->2
                ExternalPlaybackKind.PAUSED->1
                else->0
            } }.thenBy { it.updatedAtMs })
        }.sortedBy { it.packageName }

data class ExternalPlaybackState(val accessGranted:Boolean=false,val refreshing:Boolean=false,val tracks:List<ExternalTrack> = emptyList(),val unavailable:Boolean=false)
