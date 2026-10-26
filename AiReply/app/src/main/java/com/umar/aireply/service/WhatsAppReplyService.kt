package com.umar.aireply.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.umar.aireply.AiReplyApp
import com.umar.aireply.data.ActivityLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Listens to WhatsApp / WhatsApp Business notifications and hands them to [ReplyEngine].
 * Only the notification handling lives here; all decisions are made in the engine.
 */
class WhatsAppReplyService : NotificationListenerService() {

    private val app: AiReplyApp get() = application as AiReplyApp

    override fun onListenerConnected() {
        super.onListenerConnected()
        ServiceState.setListenerConnected(true)
        ActivityLog.success("Notification listener connected")

        ReplyEngine.get(app)
        app.contactWatcher.start()
        app.appScope.launch { app.contacts.refreshApproved() }
        if (app.settings.current.masterEnabled) ServiceController.startKeepAlive(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ServiceState.setListenerConnected(false)
        ActivityLog.warn("Notification listener disconnected - reconnecting")
        ServiceController.requestListenerRebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in NotificationParser.PACKAGES) return
        if (!app.settings.current.masterEnabled) return

        val parsed = try {
            NotificationParser.parse(sbn)
        } catch (e: Exception) {
            ActivityLog.error("Could not read a notification: ${e.message}")
            null
        } ?: return

        app.appScope.launch {
            try {
                ReplyEngine.get(app).onNotification(parsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActivityLog.error("Handler error: ${e.message}")
            }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        if (sbn == null || sbn.packageName !in NotificationParser.PACKAGES) return
        ReplyEngine.get(app).onNotificationRemoved(sbn.key, reason)
    }
}
