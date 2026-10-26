package com.umar.aireply.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umar.aireply.data.ContactRepository
import com.umar.aireply.data.PhoneContact
import com.umar.aireply.data.db.ApprovedContactEntity
import com.umar.aireply.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ContactsScreen(
    vm: MainViewModel,
    onRequestContactsPermission: () -> Unit,
    onPickChatFile: () -> Unit
) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<ApprovedContactEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Approved contacts", style = MaterialTheme.typography.headlineMedium, color = AppColors.TextPrimary)
            Text(
                "Only these saved contacts get automatic replies. Groups, unsaved numbers and everyone else are ignored. " +
                    "${contacts.size} of ${ContactRepository.MAX_CONTACTS} used.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )
        }

        Button(
            onClick = { showPicker = true },
            enabled = contacts.size < ContactRepository.MAX_CONTACTS,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Text("  Add from phone contacts")
        }

        if (contacts.isEmpty()) {
            GlassCard {
                Text("No contacts yet", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
                Text(
                    "Add a contact, import your old chat with them so the AI can copy your style, then switch them on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary
                )
            }
        }

        contacts.forEach { contact ->
            ContactCard(
                contact = contact,
                vm = vm,
                onRemove = { toRemove = contact }
            )
        }

        BrandFooter(settings.brandingName)
    }

    if (showPicker) {
        ContactPickerDialog(
            vm = vm,
            existingKeys = contacts.map { it.lookupKey }.toSet(),
            hasPermission = perms.contacts,
            onRequestPermission = onRequestContactsPermission,
            onDismiss = { showPicker = false }
        )
    }

    toRemove?.let { contact ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove ${contact.savedName}?") },
            text = { Text("Their style profile and remembered messages will be deleted. They will stop getting replies.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeContact(contact)
                    toRemove = null
                }) { Text("Remove", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Keep") } },
            containerColor = AppColors.SurfaceHigh
        )
    }

    importState?.let { ImportDialog(state = it, vm = vm, onPickFile = onPickChatFile) }
}

@Composable
private fun ContactCard(
    contact: ApprovedContactEntity,
    vm: MainViewModel,
    onRemove: () -> Unit
) {
    var showProfile by remember { mutableStateOf(false) }
    val numberCount = contact.phoneNumbers.split("|").count { it.isNotBlank() }
    val live = contact.enabled && contact.isReady

    GlassCard(accent = if (live) AppColors.Mint else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(contact.savedName, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
                Text(
                    if (numberCount == 1) "1 saved number" else "$numberCount saved numbers",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )
            }
            when {
                live -> StatusChip("Live", AppColors.Mint)
                contact.isReady -> StatusChip("Paused", AppColors.Warning)
                else -> StatusChip("Needs chat", AppColors.Info)
            }
        }

        SwitchRow(
            title = "Reply to this contact",
            subtitle = if (contact.isReady) "Replies are sent in your style." else "Import a chat first.",
            checked = contact.enabled,
            onChange = { vm.setContactEnabled(contact, it) }
        )

        SettingTextField(
            label = "What the AI calls them (optional)",
            initial = contact.callName,
            onCommit = { vm.setCallName(contact, it) },
            supporting = "Empty means the name saved in your phone."
        )
        SettingTextField(
            label = "Persona and instructions",
            initial = contact.persona,
            onCommit = { vm.setPersona(contact, it) },
            singleLine = false,
            minLines = 3,
            supporting = "For example: warm, a little playful, never promise to meet."
        )

        if (contact.isReady) {
            val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(contact.styleUpdatedAt))
            Text(
                "Style learned on $date from ${contact.importedMessageCount} messages.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
            TextButton(onClick = { showProfile = !showProfile }) {
                Text(if (showProfile) "Hide style profile" else "View style profile")
            }
            if (showProfile) {
                Text(
                    contact.styleProfile,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextPrimary
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { vm.openImport(contact) }, modifier = Modifier.weight(1f)) {
                Text(if (contact.isReady) "Re-import chat" else "Import chat")
            }
            OutlinedButton(onClick = { vm.clearMemory(contact) }, modifier = Modifier.weight(1f)) {
                Text("Clear memory")
            }
        }
        TextButton(onClick = onRemove) { Text("Remove contact", color = AppColors.Danger) }
    }
}

@Composable
private fun ContactPickerDialog(
    vm: MainViewModel,
    existingKeys: Set<String>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val phoneContacts by vm.phoneContacts.collectAsStateWithLifecycle()
    val loading by vm.loadingContacts.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.loadPhoneContacts()
    }

    val filtered = remember(phoneContacts, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) phoneContacts else phoneContacts.filter { it.name.lowercase(Locale.ROOT).contains(q) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Ink) {
            Column(
                modifier = Modifier.systemBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Choose a saved contact",
                        style = MaterialTheme.typography.titleLarge,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = AppColors.TextPrimary)
                    }
                }

                if (!hasPermission) {
                    GlassCard {
                        Text(
                            "Allow access to your contacts so you can pick people by the name saved in your phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextPrimary
                        )
                        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow contacts")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Search by name") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        colors = appFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppColors.Mint)
                    if (!loading && filtered.isEmpty()) {
                        Text(
                            "No saved contacts found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextSecondary
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.lookupKey }) { pc ->
                            PickerRow(pc, added = pc.lookupKey in existingKeys, onAdd = { vm.addContact(pc) })
                            HorizontalDivider(color = AppColors.Outline.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(contact: PhoneContact, added: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary)
            Text(
                if (contact.numbers.size == 1) "1 number" else "${contact.numbers.size} numbers",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }
        if (added) {
            StatusChip("Added", AppColors.Mint)
        } else {
            FilledTonalButton(onClick = onAdd) { Text("Add") }
        }
    }
}

@Composable
private fun ImportDialog(state: ImportUiState, vm: MainViewModel, onPickFile: () -> Unit) {
    val largeText = state.rawText.length > 20_000

    Dialog(onDismissRequest = { vm.closeImport() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Ink) {
            Column(
                modifier = Modifier
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Teach the AI how you write to ${state.contactName}",
                        style = MaterialTheme.typography.titleLarge,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.closeImport() }, enabled = !state.busy) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = AppColors.TextPrimary)
                    }
                }

                GlassCard {
                    SectionTitle("How to export the chat")
                    Text(
                        "In WhatsApp open the chat, tap the contact name, then Export chat, then Without media. " +
                            "Save the .txt file and pick it here, or paste its text below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary
                    )
                    Text(
                        "The text is sent to Gemini once to learn your style. Remove passwords, money amounts and other private details first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Warning
                    )
                }

                if (largeText) {
                    GlassCard {
                        Text(
                            "Chat loaded: ${"%,d".format(state.rawText.length)} characters",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.TextPrimary
                        )
                        TextButton(onClick = { vm.setImportText("") }, enabled = !state.busy) { Text("Remove it") }
                    }
                } else {
                    OutlinedTextField(
                        value = state.rawText,
                        onValueChange = { vm.setImportText(it) },
                        label = { Text("Paste chat text") },
                        minLines = 5,
                        maxLines = 8,
                        enabled = !state.busy,
                        colors = appFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onPickFile, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text("Pick .txt file")
                    }
                    Button(
                        onClick = { vm.scanImport() },
                        enabled = state.rawText.isNotBlank() && !state.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Scan chat") }
                }

                if (state.scanned) {
                    GlassCard {
                        SectionTitle("Which name is you?", "Pick the sender that is you in this chat.")
                        state.senderCounts.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = state.mySender == name,
                                        enabled = !state.busy,
                                        role = Role.RadioButton,
                                        onClick = { vm.setMySender(name) }
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.mySender == name,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = AppColors.Mint)
                                )
                                Text(
                                    "  $name ($count messages)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AppColors.TextPrimary
                                )
                            }
                        }
                        Button(
                            onClick = { vm.runImport() },
                            enabled = state.mySender != null && !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Build style profile") }
                    }
                }

                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppColors.Mint)
                    Text(
                        "Working. This can take up to a minute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary
                    )
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.Danger)
                }
            }
        }
    }
}
