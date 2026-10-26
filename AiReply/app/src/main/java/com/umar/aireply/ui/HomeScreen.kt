package com.umar.aireply.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umar.aireply.data.LogEntry
import com.umar.aireply.data.LogLevel
import com.umar.aireply.service.ServiceController
import com.umar.aireply.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
    onRequestContactsPermission: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val log by vm.activityLog.collectAsStateWithLifecycle()
    val connected by vm.listenerConnected.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val liveCount = contacts.count { it.enabled && it.isReady }
    val live = settings.masterEnabled && connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- header ----
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI Reply", style = MaterialTheme.typography.headlineMedium, color = AppColors.TextPrimary)
            BrandBadge(settings.brandingName)
        }

        // ---- master switch ----
        GlassCard(accent = if (live) AppColors.Mint else null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PulseOrb(active = live)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Auto-reply", style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
                    Text(
                        text = when {
                            !settings.masterEnabled -> "Off. Nobody gets automatic replies."
                            !perms.notificationAccess -> "On, but Notification Access is missing."
                            !connected -> "On, waiting for the listener to connect."
                            liveCount == 0 -> "Listening, but no contact is switched on."
                            liveCount == 1 -> "Listening for 1 approved contact."
                            else -> "Listening for $liveCount approved contacts."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary
                    )
                }
                androidx.compose.material3.Switch(
                    checked = settings.masterEnabled,
                    onCheckedChange = { vm.setMaster(it) },
                    colors = appSwitchColors()
                )
            }
            if (settings.masterEnabled && perms.notificationAccess && !connected) {
                OutlinedButton(onClick = { vm.reconnectListener() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reconnect listener")
                }
            }
        }

        // ---- permissions hub ----
        GlassCard {
            SectionTitle("Permissions", "Everything here must be green for replies to work in the background.")
            PermissionRow(
                title = "Notification Access",
                detail = "Lets the app read WhatsApp messages.",
                granted = perms.notificationAccess,
                actionLabel = "Open settings",
                onAction = { ServiceController.openNotificationAccessSettings(context) }
            )
            PermissionRow(
                title = "Battery optimisation",
                detail = "Stops Android from putting the app to sleep.",
                granted = perms.batteryExempt,
                actionLabel = "Allow",
                onAction = { ServiceController.requestIgnoreBatteryOptimizations(context) }
            )
            PermissionRow(
                title = "Contacts",
                detail = "Needed to pick saved contacts and follow name changes.",
                granted = perms.contacts,
                actionLabel = "Allow",
                onAction = onRequestContactsPermission
            )
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionRow(
                    title = "Notifications",
                    detail = "Shows the small \"running\" notice that keeps the app alive.",
                    granted = perms.postNotifications,
                    actionLabel = "Allow",
                    onAction = onRequestNotificationPermission
                )
            }
        }

        // ---- live activity ----
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("Live activity", "The last 10 events.") }
                TextButton(onClick = { vm.clearLog() }, enabled = log.isNotEmpty()) { Text("Clear") }
            }
            if (log.isEmpty()) {
                Text(
                    "Nothing yet. Events show up here as soon as a message arrives.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    log.forEach { LogRow(it) }
                }
            }
        }

        BrandFooter(settings.brandingName)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        if (granted) {
            StatusChip("Granted", AppColors.Mint)
        } else {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Warning,
                    contentColor = AppColors.Ink
                )
            ) { Text(actionLabel) }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.INFO -> AppColors.Info
        LogLevel.SUCCESS -> AppColors.Mint
        LogLevel.WARNING -> AppColors.Warning
        LogLevel.ERROR -> AppColors.Danger
    }
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.time))
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextPrimary)
            Text(time, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary.copy(alpha = 0.8f))
        }
    }
}
