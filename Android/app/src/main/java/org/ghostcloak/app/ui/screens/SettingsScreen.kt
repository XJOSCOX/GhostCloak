package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*

@Composable fun SettingsScreen(state: AppState, developerAvailable: Boolean,
    demo: () -> Unit, leave: () -> Unit, connect:()->Unit={}, sync:()->Unit={}, publish:()->Unit={}, logout:()->Unit={}) {
    var advanced by remember { mutableStateOf(false) }
    PageContent("Settings") {
        ErrorNotice(state.error, important = state.errorImportant)
        SettingsGroup("Appearance") { AppearanceSelector() }
        SettingsGroup("Privacy") {
            DetailRow(Glyph.SHIELD,"Encrypted on this device","Your conversations and keys are kept in encrypted local storage.")
            Text("App lock and screenshot protection are not enabled. An unlocked device can still expose messages.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(!state.demo) SettingsGroup("Connection") {
            if(state.networkConfigured) {
                NetworkActions(state,connect,sync)
                Text("Messages refresh while the app is open. No background notifications yet.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.networkConnected) TextButton(onClick=logout,enabled=!state.loading) {Text("Disconnect and revoke session")}
                TextButton(onClick={advanced=!advanced}) {Text(if(advanced) "Hide connection details" else "Connection details")}
                if(advanced) {
                    Text("The service can see routing metadata. Keystore: ${state.protection.lowercase()}.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.networkConnected) OutlinedButton(onClick=publish,enabled=!state.loading) {Text("Publish another contact prekey")}
                }
            } else Text("A server is not configured in this build.",style=MaterialTheme.typography.bodyMedium)
        }
        if(developerAvailable) SettingsGroup("Developer tools") {
            Text("Try a conversation using two isolated endpoints on this device.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick=if(state.demo) leave else demo,enabled=!state.loading,modifier=Modifier.fillMaxWidth()) {
                Text(if(state.demo) "Return to my identity" else "Open local demo")
            }
        }
        Text("Ghost Cloak · Phase 1E.1\nExperimental. Not independently audited. Not anonymous.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
