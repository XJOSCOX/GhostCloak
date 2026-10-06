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
import org.ghostcloak.app.BuildConfig
import org.ghostcloak.app.ui.components.*

@Composable fun SettingsScreen(state: AppState, developerAvailable: Boolean,
    demo: () -> Unit, leave: () -> Unit, connect:()->Unit={}, sync:()->Unit={}, publish:()->Unit={}, logout:()->Unit={}, appLock:()->Unit={}, requestPrivacy:(Boolean)->Unit={}, blockedContacts:()->Unit={}, emergencyWipe:()->Unit={}, inactiveProtection:()->Unit={}, privacy:()->Unit={}) {
    var advanced by remember { mutableStateOf(false) }
    PageContent("Settings") {
        ErrorNotice(state.error, important = state.errorImportant)
        SettingsGroup("Appearance") { AppearanceSelector() }
        SettingsGroup("Privacy") {
            Text("Messaging, media, profile, notifications and device protection in one place.")
            OutlinedButton(onClick=privacy,enabled=!state.loading) {Text("Open Privacy settings")}
        }
        if(!state.demo) SettingsGroup("Connection") {
            if(state.networkConfigured) {
                NetworkActions(state,connect,sync)
                Text("Background checks are best-effort and may be delayed by Android. Notification detail follows your local privacy setting and App Lock.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.networkConnected) TextButton(onClick=logout,enabled=!state.loading) {Text("Disconnect and revoke session")}
                Text("Disconnect revokes the current server session. Your local encrypted identity, conversations and contacts remain. Safe Exit is the destructive local action.",style=MaterialTheme.typography.bodySmall)
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
        SettingsGroup("Build information") {
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text("Source ${BuildProvenance.shortSha(BuildConfig.GIT_SHA)}")
            Text("Source status: ${BuildProvenance.status(BuildConfig.GIT_DIRTY)}", color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Ghost Cloak · Phase 1E.1\nExperimental. Not independently audited. Not anonymous.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
