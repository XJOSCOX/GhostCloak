package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*

@Composable fun SettingsScreen(state: AppState, rename: (String) -> Unit, developerAvailable: Boolean,
    demo: () -> Unit, leave: () -> Unit, connect:()->Unit={}, sync:()->Unit={}, publish:()->Unit={}, logout:()->Unit={}) {
    var username by remember(state.identity?.username) { mutableStateOf(state.identity?.username.orEmpty()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Wordmark()
        ScreenHeader("Settings", "Your identity, on your terms.")
        SectionLabel("PROFILE")
        OutlinedTextField(username, onValueChange = { if (it.length <= 32) username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FullButton("Save username", !state.loading && username != state.identity?.username) { rename(username) }
        Text(if (state.networkConfigured && !state.demo) "Before connecting, this name is used to register. After registration, this changes only your local display name. Your keys and safety numbers stay the same." else "This changes your display name only. Your device identity, keys and safety numbers stay the same.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ErrorNotice(state.error)
        if(!state.demo) {
            SectionLabel("NETWORK")
            if(!state.networkConfigured) Text("A server is not configured in this build.")
            else {
                InfoPanel("Encrypted network messaging", "Your messages are encrypted on your device. The service still sees routing metadata. Connected means your last authentication succeeded; tap Sync to check for new messages.")
                NetworkActions(state, connect, sync)
                if(state.networkConnected) {
                    FullButton("Sync messages and retry pending",!state.loading,sync)
                    OutlinedButton(onClick=publish,enabled=!state.loading) {Text("Publish another contact prekey")}
                    TextButton(onClick=logout,enabled=!state.loading) {Text("Disconnect and revoke session")}
                }
            }
        }
        SectionLabel("ON THIS DEVICE")
        InfoPanel("Encrypted local history", "Contacts and messages stay inside the SQLCipher database. An unlocked or compromised endpoint can still access them.")
        Text("Keystore: ${state.protection.lowercase()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        InfoPanel("Privacy controls under review", "App lock and screenshot protection are not enabled. Be mindful of screen sharing, your keyboard and device access.")
        if (developerAvailable) {
            HorizontalDivider(); SectionLabel("DEVELOPER TOOLS · DEBUG ONLY")
            Text("An isolated two-endpoint sandbox demonstrates encrypted text delivery on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = if (state.demo) leave else demo, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text(if (state.demo) "Return to my identity" else "Open local demo")
            }
        }
        Text("Ghost Cloak · Phase 1E.1\nNot independently audited. Not anonymous.\nNo notifications or Ghost Mode.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
