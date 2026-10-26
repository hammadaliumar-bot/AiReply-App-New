package com.umar.aireply.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.umar.aireply.AiReplyApp

/** Restarts the background service after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext as? AiReplyApp ?: return
        if (app.settings.current.masterEnabled) {
            ServiceController.startKeepAlive(context)
            ServiceController.requestListenerRebind(context)
        }
    }
}
