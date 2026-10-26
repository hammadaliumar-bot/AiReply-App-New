package com.umar.aireply

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.umar.aireply.ui.AiReplyRoot
import com.umar.aireply.ui.MainViewModel
import com.umar.aireply.ui.theme.AiReplyTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refreshPermissions()
        if (granted) vm.loadPhoneContacts()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshPermissions()
    }

    private val chatFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadImportFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            AiReplyTheme {
                AiReplyRoot(
                    vm = vm,
                    onRequestContactsPermission = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onPickChatFile = { chatFilePicker.launch(arrayOf("*/*")) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }
}
