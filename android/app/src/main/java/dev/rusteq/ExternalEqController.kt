package dev.rusteq

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/** Coordinates saved intent and Android service requests without opening other apps. */
object ExternalEqController {
    fun start(context: Context, request: Pair<Int, String>? = null) {
        val app = context.applicationContext as EqApplication
        app.externalEqSettings.update { it.copy(enabled = true) }
        if (!app.externalEqSettings.state.value.shouldRun) {
            app.systemEqState.value = app.systemEqState.value.copy(message = uiText(R.string.external_service_select_apps))
            return
        }
        launch(context, Intent(context, SystemEqService::class.java).setAction(
            if (request == null) SystemEqService.START else SystemEqService.ATTACH,
        ).apply { request?.let { putExtra(AudioEffect.EXTRA_AUDIO_SESSION, it.first); putExtra(AudioEffect.EXTRA_PACKAGE_NAME, it.second) } })
    }

    fun connect(context: Context, packageName: String, request: Pair<Int, String>? = null): Boolean {
        val app = context.applicationContext as EqApplication
        if (packageName !in app.externalEqSettings.state.value.selectedPackages) {
            app.systemEqState.value = app.systemEqState.value.copy(message = uiText(R.string.external_service_not_selected))
            return false
        }
        app.externalEqSettings.update { it.copy(enabled = true) }
        return launch(context, Intent(context, SystemEqService::class.java).setAction(SystemEqService.CONNECT_PACKAGE)
            .putExtra(SystemEqService.EXTRA_TARGET_PACKAGE, packageName).apply {
                request?.takeIf { it.second == packageName }?.let {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, it.first)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, it.second)
                }
            })
    }

    fun stop(context: Context) {
        val app = context.applicationContext as EqApplication
        app.externalEqSettings.update { it.copy(enabled = false) }
        runCatching { context.stopService(Intent(context, SystemEqService::class.java)) }
            .onFailure { app.systemEqState.value = app.systemEqState.value.copy(message = it.asUiText(R.string.external_service_stop_failed)) }
    }

    fun restore(context: Context, afterBoot: Boolean = false) {
        val app = context.applicationContext as EqApplication
        val settings = app.externalEqSettings.state.value
        if (!settings.shouldRun || (afterBoot && !settings.resumeAfterBoot) || app.systemEqState.value.running) return
        launch(context, Intent(context, SystemEqService::class.java).setAction(SystemEqService.START))
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        val app = context.applicationContext as EqApplication
        return runCatching {
            if (context.startForegroundService(intent) == null) {
                throw UiTextException(uiText(R.string.external_service_start_failed))
            }
            true
        }.getOrElse {
            app.systemEqState.value = app.systemEqState.value.copy(message = it.asUiText(R.string.external_service_start_failed))
            false
        }
    }
}
