package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.application.NetworkStatus

@Composable fun NetworkActions(state: AppState, connect: () -> Unit, sync: () -> Unit) {
    if (!state.networkConfigured || state.demo) return
    if (state.networkConnected) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Connected", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = sync, enabled = !state.loading) { Text("Sync") }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when (state.networkStatus) {
            NetworkStatus.CONNECTED -> "Connected"
            NetworkStatus.CONNECTING -> "Connecting…"
            NetworkStatus.SYNCING -> "Syncing…"
            NetworkStatus.OFFLINE -> "Offline · Try Sync"
            NetworkStatus.ERROR -> "Needs attention · Try Sync"
            else -> "Connect to start messaging"
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.networkRequiresConnect) TextButton(onClick = connect, enabled = !state.loading, modifier = Modifier.weight(1f, fill = false)) { Text("Connect to Ghost Cloak") }
            OutlinedButton(onClick = sync, enabled = !state.loading) { Text("Sync") }
        }
    }
}
