package dev.rusteq.audio

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import dev.rusteq.ExternalPlaybackListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Reads only selected players' media metadata. No notification text or history is retained. */
class ExternalPlaybackMonitor(context:Context, private val selection:StateFlow<ExternalEqSettings>) {
    private val app=context.applicationContext
    private val manager=app.getSystemService(MediaSessionManager::class.java)
    private val component=ComponentName(app,ExternalPlaybackListener::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable=MutableStateFlow(ExternalPlaybackState())
    val state:StateFlow<ExternalPlaybackState> = mutable.asStateFlow()
    private val entries=linkedMapOf<MediaSession.Token,Entry>()
    private var registered=false
    private var generation=0L
    private var request:Job?=null
    private var fetching=false
    private var queryFailed=false
    private val listener=MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

    private data class Details(val title:String?=null,val artist:String?=null,val album:String?=null,val duration:Long?=null)
    private inner class Entry(val controller:MediaController) {
        val token=controller.sessionToken
        var details=Details()
        var playback:PlaybackState?=null
        var remote=false
        var metadataRevision=0
        var playbackRevision=0
        var infoRevision=0
        var loading=true
        var failed=false
        var load:Job?=null
        val callback=object:MediaController.Callback() {
            override fun onMetadataChanged(metadata:MediaMetadata?) { if (current()) { metadataRevision++; details=this@ExternalPlaybackMonitor.details(metadata); publish() } }
            override fun onPlaybackStateChanged(state:PlaybackState?) { if (current()) { playbackRevision++; playback=state; publish() } }
            override fun onAudioInfoChanged(info:MediaController.PlaybackInfo) { if (current()) { infoRevision++; remote=info.playbackType==MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE; publish() } }
            override fun onSessionDestroyed() { if (entries[token]===this@Entry) { entries.remove(token); dispose(this@Entry); publish() } }
        }
        fun current()=entries[token]===this && controller.packageName in selection.value.selectedPackages
    }

    init {
        scope.launch {
            selection.map { it.selectedPackages }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun hasAccess():Boolean = runCatching {
        app.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
    }.getOrDefault(false)

    fun refresh() { scope.launch { refreshOnMain() } }
    fun disconnected() { scope.launch { reset(hasAccess()) } }

    private fun refreshOnMain() {
        if (!hasAccess()) { reset(false); return }
        val selected=selection.value.selectedPackages
        if (selected.isEmpty()) { reset(true); return }
        entries.values.filter { it.controller.packageName !in selected }.toList().forEach { entries.remove(it.token); dispose(it) }
        if (!registered) {
            try { manager.addOnActiveSessionsChangedListener(listener,component,handler); registered=true }
            catch (_:Exception) { reset(hasAccess()); mutable.value=mutable.value.copy(unavailable=true); return }
        }
        val current=++generation
        request?.cancel()
        fetching=true; queryFailed=false; publish()
        request=scope.launch {
            try {
                val controllers=withContext(Dispatchers.IO) { manager.getActiveSessions(component) }
                if (current!=generation || selected!=selection.value.selectedPackages) return@launch
                if (!hasAccess()) { reset(false); return@launch }
                val wanted=controllers.filter { it.packageName in selected && it.packageName!=app.packageName }.take(16).associateBy { it.sessionToken }
                entries.values.filter { it.token !in wanted }.toList().forEach { entries.remove(it.token); dispose(it) }
                wanted.values.forEach { controller -> if (controller.sessionToken !in entries) add(controller) }
            } catch (e:CancellationException) { throw e }
            catch (_:Exception) { if (current==generation) { clearEntries(); queryFailed=true } }
            finally { if (current==generation) { fetching=false; publish() } }
        }
    }

    private fun add(controller:MediaController) {
        val entry=Entry(controller)
        entries[entry.token]=entry
        try { controller.registerCallback(entry.callback,handler) }
        catch (_:Exception) { entries.remove(entry.token); dispose(entry); queryFailed=true; return }
        entry.load=scope.launch {
            try {
                // Register first. A callback arriving during this initial read
                // wins per field, preventing an old title/state from replacing it.
                val initial=withContext(Dispatchers.IO) {
                    Triple(details(controller.metadata),controller.playbackState,controller.playbackInfo?.playbackType==MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE)
                }
                if (!entry.current()) return@launch
                if (entry.metadataRevision==0) entry.details=initial.first
                if (entry.playbackRevision==0) entry.playback=initial.second
                if (entry.infoRevision==0) entry.remote=initial.third
            } catch (e:CancellationException) { throw e }
            catch (_:Exception) { entry.failed=true }
            finally { entry.loading=false; if (entry.current()) publish() }
        }
    }

    private fun details(metadata:MediaMetadata?):Details {
        fun text(key:String)=metadata?.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.take(512)
        return Details(text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: text(MediaMetadata.METADATA_KEY_TITLE),
            text(MediaMetadata.METADATA_KEY_ARTIST) ?: text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            text(MediaMetadata.METADATA_KEY_ALBUM),metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it>0 })
    }

    private fun publish() {
        if (!hasAccess()) { reset(false); return }
        val tracks=entries.values.map { entry ->
            val playback=entry.playback
            val kind=when (playback?.state) {
                PlaybackState.STATE_PLAYING,PlaybackState.STATE_FAST_FORWARDING,PlaybackState.STATE_REWINDING->ExternalPlaybackKind.PLAYING
                PlaybackState.STATE_PAUSED->ExternalPlaybackKind.PAUSED
                PlaybackState.STATE_BUFFERING,PlaybackState.STATE_CONNECTING,PlaybackState.STATE_SKIPPING_TO_NEXT,PlaybackState.STATE_SKIPPING_TO_PREVIOUS,PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM->ExternalPlaybackKind.BUFFERING
                else->ExternalPlaybackKind.STOPPED
            }
            ExternalTrack(entry.controller.packageName,entry.details.title,entry.details.artist,entry.details.album,entry.details.duration,
                playback?.position?.takeIf { it>=0 },playback?.lastPositionUpdateTime ?: 0,playback?.playbackSpeed ?: 0f,kind,entry.remote)
        }
        mutable.value=ExternalPlaybackState(true,fetching || entries.values.any { it.loading },
            preferredExternalTracks(tracks,selection.value.selectedPackages,app.packageName),queryFailed || entries.values.any { it.failed })
    }

    private fun dispose(entry:Entry) { entry.load?.cancel(); runCatching { entry.controller.unregisterCallback(entry.callback) } }
    private fun clearEntries() { val previous=entries.values.toList(); entries.clear(); previous.forEach(::dispose) }
    private fun reset(granted:Boolean) {
        generation++; request?.cancel(); request=null; fetching=false; queryFailed=false
        if (registered) { registered=false; runCatching { manager.removeOnActiveSessionsChangedListener(listener) } }
        clearEntries(); mutable.value=ExternalPlaybackState(accessGranted=granted)
    }
}
