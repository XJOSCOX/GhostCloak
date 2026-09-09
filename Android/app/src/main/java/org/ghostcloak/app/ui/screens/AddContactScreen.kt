package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.privacy.copySensitive
import org.ghostcloak.messaging.ContactCardCodec

@Composable fun AddContactScreen(state: AppState, back: () -> Unit, export: () -> Unit, import: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }; var tooLarge by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ScreenHeader("Add a contact", "A public card. A direct connection.", back)
        InfoPanel("Exchange public material", "Cards contain public identity and prekeys only. Share through a trusted channel, then compare your safety number.")
        SectionLabel("IMPORT THEIR CARD")
        OutlinedTextField(draft, onValueChange = { tooLarge = it.length > ContactCardCodec.MAX_TEXT; if (!tooLarge) draft = it },
            label = { Text("Paste contact card") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
            isError = tooLarge, supportingText = { Text(if (tooLarge) "Card exceeds 8,192 characters. Nothing was imported." else "GHOSTCLOAK:1:…") })
        FullButton("Import contact", !state.loading && draft.isNotBlank() && !tooLarge) { import(draft) }
        ErrorNotice(state.error)
        HorizontalDivider()
        SectionLabel("SHARE YOUR CARD")
        Text("Your private keys never leave encrypted storage.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.card.isEmpty()) OutlinedButton(onClick = export, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Generate public contact card") }
        else {
            InfoPanel("Public contact card ready", "This card is for one contact exchange. It does not connect devices over a network.")
            FullButton(if (copied) "Copy again" else "Copy public card") { copySensitive(context, state.card); copied = true }
            if (copied) Text("Copied to clipboard", color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { copied = false; export() }, enabled = !state.loading) { Text("Generate a fresh card for another contact") }
        }
        Text("Copying is optional. Clipboard content may be accessible to your keyboard, other software or synced devices.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
