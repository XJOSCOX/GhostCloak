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

@Composable fun ContactsScreen(state: AppState, add: () -> Unit, open: (String) -> Unit, connect: () -> Unit = {}, sync: () -> Unit = {}) {
    val requests = state.contacts.filter { it.contact.request && !it.contact.blocked }
    val chats = state.contacts.filter { !it.contact.request }.sortedByDescending { state.previews[it.contact.remoteDeviceId]?.timestamp ?: 0 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Chats", style = MaterialTheme.typography.headlineLarge)
                    Text("@${state.identity?.username.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = add, enabled = !state.loading) { Text("+ New chat") }
            }
        }
        item { NetworkActions(state, connect, sync) }
        if (state.error != null) item { ErrorNotice(state.error) }
        if (state.demo) item { InfoPanel("Local simulator", "Replies are simulated on this device.") }
        if (requests.isNotEmpty()) {
            item { SectionLabel("MESSAGE REQUESTS - ${requests.size}") }
            items(requests, key = { it.contact.contactId }) { status -> ChatRow(status, state.previews[status.contact.remoteDeviceId], open) }
            item { HorizontalDivider() }
        }
        if (chats.isEmpty() && requests.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                BrandMark(Modifier.size(72.dp))
                Text("Your conversations start here", style = MaterialTheme.typography.titleLarge)
                Text(if (state.networkConfigured && !state.demo) "Find someone by username. Keep the conversation private."
                    else "Exchange a public contact card to start chatting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FullButton("+  Add a contact", !state.loading, add)
            }
        }
        items(chats, key = { it.contact.contactId }) { status -> ChatRow(status, state.previews[status.contact.remoteDeviceId], open) }
    }
}
