package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*

@Composable fun ContactsScreen(state: AppState, add: () -> Unit, open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Wordmark() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(if (state.demo) "DEVELOPER SANDBOX" else "YOUR PRIVATE SPACE")
                ScreenHeader("Contacts", "${state.identity?.username.orEmpty()} · ${state.contacts.size} local contacts")
            }
        }
        item { ErrorNotice(state.error) }
        if (state.demo) item { InfoPanel("Local simulator", "Two separate encrypted endpoints on this device. Replies are simulated; no messages leave the app.") }
        if (state.contacts.isEmpty()) item {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    BrandMark(Modifier.size(60.dp))
                    Text("Start with someone\nyou trust.", style = MaterialTheme.typography.headlineSmall)
                    Text("Exchange a public contact card to create your first encrypted conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FullButton("+  Add a contact", !state.loading, add)
                }
            }
        }
        items(state.contacts, key = { it.contact.contactId }) { status ->
            Surface(onClick = { open(status.contact.remoteDeviceId) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(status.contact.displayName)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(status.contact.displayName, style = MaterialTheme.typography.titleMedium)
                        if (state.contacts.count { it.contact.displayName == status.contact.displayName } > 1)
                            Text("Device · ${status.contact.remoteDeviceId.takeLast(8)}", style = MaterialTheme.typography.bodyMedium)
                        if (status.contact.blocked) Text("Blocked on this device", color = MaterialTheme.colorScheme.error)
                        else TrustBadge(status.identity?.trustState)
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.contacts.isNotEmpty()) item { FullButton("+  Add a contact", !state.loading, add) }
        item { Text("Encryption protects messages. Verification helps you confirm who is on the other end.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
