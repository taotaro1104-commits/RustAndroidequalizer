package dev.rusteq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ExternalEqRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ExternalEqController.restore(context, afterBoot = true)
            Intent.ACTION_MY_PACKAGE_REPLACED -> ExternalEqController.restore(context)
        }
    }
}
