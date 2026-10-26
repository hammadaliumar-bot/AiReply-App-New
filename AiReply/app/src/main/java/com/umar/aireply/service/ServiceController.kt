package com.umar.aireply.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.umar.aireply.data.ActivityLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live state the dashboard can observe. */
object ServiceState {
    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }
}

/** Helpers for starting/stopping the background services and checking permissions. */
object ServiceController {

    fun startKeepAlive(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        } catch (e: Exception) {
            ActivityLog.warn("Could not start background service: ${e.message}")
        }
    }

    fun stopKeepAlive(context: Context) {
        try {
            context.stopService(Intent(context, KeepAliveService::class.java))
        } catch (e: Exception) {
            ActivityLog.warn("Could not stop background service: ${e.message}")
        }
    }

    /** Called whenever the master switch changes. */
    fun onMasterChanged(context: Context, enabled: Boolean) {
        if (enabled) {
            startKeepAlive(context)
            requestListenerRebind(context)
        } else {
            stopKeepAlive(context)
        }
    }

    fun isNotificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openNotificationAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            ActivityLog.error("Could not open notification access settings")
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(direct)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                ActivityLog.error("Could not open battery optimisation settings")
            }
        }
    }

    fun openContactsPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            ActivityLog.error("Could not open app settings")
        }
    }

    /** Asks Android to reconnect the notification listener if it was disconnected. */
    fun requestListenerRebind(context: Context) {
        try {
            NotificationListenerService.requestRebind(ComponentName(context, WhatsAppReplyService::class.java))
        } catch (e: Exception) {
            ActivityLog.warn("Listener rebind request failed: ${e.message}")
        }
    }

    /** Last-resort kick: disables and re-enables the listener component so Android binds it again. */
    fun nudgeListener(context: Context) {
        try {
            val pm = context.packageManager
            val component = ComponentName(context, WhatsAppReplyService::class.java)
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            ActivityLog.warn("Could not restart the listener: ${e.message}")
        }
    }
}
