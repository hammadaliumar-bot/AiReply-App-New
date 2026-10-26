package com.umar.aireply.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.umar.aireply.ui.theme.AppColors

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun AiReplyRoot(
    vm: MainViewModel,
    onRequestContactsPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onPickChatFile: () -> Unit
) {
    val tabs = remember {
        listOf(
            Tab("Home", Icons.Rounded.Home),
            Tab("Contacts", Icons.Rounded.People),
            Tab("Settings", Icons.Rounded.Settings)
        )
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = AppColors.InkSoft) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppColors.Ink,
                                selectedTextColor = AppColors.Mint,
                                indicatorColor = AppColors.Mint,
                                unselectedIconColor = AppColors.TextSecondary,
                                unselectedTextColor = AppColors.TextSecondary
                            )
                        )
                    }
                }
            }
        ) { inner ->
            Box(Modifier.padding(inner)) {
                when (selected) {
                    0 -> HomeScreen(
                        vm = vm,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onRequestContactsPermission = onRequestContactsPermission
                    )
                    1 -> ContactsScreen(
                        vm = vm,
                        onRequestContactsPermission = onRequestContactsPermission,
                        onPickChatFile = onPickChatFile
                    )
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}
