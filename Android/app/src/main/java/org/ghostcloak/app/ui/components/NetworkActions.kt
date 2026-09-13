package org.ghostcloak.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.application.NetworkStatus

@Composable fun NetworkActions(state: AppState, connect: () -> Unit, sync: () -> Unit) {
    if (!state.networkConfigured || state.demo) return
    if (state.networkStatus in setOf(NetworkStatus.RECOVERY_REQUIRED,NetworkStatus.RECOVERING)) {
        Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.compact)) {
            Text("Ghost Cloak found an existing device identity but its account connection needs to be restored.",
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=connect,enabled=!state.loading && state.networkStatus!=NetworkStatus.RECOVERING) {
                Text(if(state.networkStatus==NetworkStatus.RECOVERING) "Restoring account…" else "Recover account")
            }
        }
        return
    }
    if (state.networkConnected) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Connected", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = sync, enabled = !state.loading) { Text("Sync") }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GhostDimensions.tiny)) {
        Text(when (state.networkStatus) {
            NetworkStatus.CONNECTED -> "Connected"
            NetworkStatus.CONNECTING -> "Connecting…"
            NetworkStatus.SYNCING -> "Syncing…"
            NetworkStatus.RATE_LIMITED -> "Sync temporarily delayed"
            NetworkStatus.OFFLINE -> "Offline · Try Sync"
            NetworkStatus.ERROR -> "Needs attention · Try Sync"
            else -> "Connect to start messaging"
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(GhostDimensions.compact)) {
            if (state.networkRequiresConnect) TextButton(onClick = connect, enabled = !state.loading, modifier = Modifier.weight(1f, fill = false)) { Text("Connect to Ghost Cloak") }
            OutlinedButton(onClick = sync, enabled = !state.loading) { Text("Sync") }
        }
    }
}
