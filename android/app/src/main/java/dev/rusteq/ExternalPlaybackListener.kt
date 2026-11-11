package dev.rusteq

import android.service.notification.NotificationListenerService

/** Notification access authorizes MediaSessionManager; notification bodies are not inspected. */
class ExternalPlaybackListener:NotificationListenerService() {
    private val runtime get()=application as EqApplication
    override fun onListenerConnected() { super.onListenerConnected(); runtime.externalPlayback.refresh() }
    override fun onListenerDisconnected() { runtime.externalPlayback.disconnected(); super.onListenerDisconnected() }
    override fun onDestroy() { runtime.externalPlayback.disconnected(); super.onDestroy() }
}
