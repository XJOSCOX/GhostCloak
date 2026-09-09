package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.ContactStatus

@Composable fun ContactSecurityScreen(state: AppState, contact: ContactStatus, back: () -> Unit,
    load: (Boolean) -> Unit, verify: (String) -> Unit, trust: (String) -> Unit, block: (Boolean) -> Unit,
    importUpdatedCard: (() -> Unit)? = null) {
    val changed = contact.identity?.trustState == IdentityTrustState.CHANGED
    val previouslyVerified = contact.identity?.previousTrustState == IdentityTrustState.VERIFIED
    var confirmation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(contact.contact.remoteDeviceId, changed) { load(changed) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ScreenHeader("Contact security", "Verify the person behind the name.", back)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(contact.contact.displayName)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(contact.contact.displayName, style = MaterialTheme.typography.titleLarge)
                TrustBadge(contact.identity?.trustState)
            }
        }
        if (changed) InfoPanel(if (previouslyVerified) "! Previously verified identity changed" else "! Security identity changed",
            "Sending is blocked. The key differs from the one you knew. Compare the new safety number with this person in person or through another trusted channel.", warning = true, urgent = previouslyVerified)
        else InfoPanel(if (contact.session == SessionLifecycle.ACTIVE) "Encrypted session active" else "Session unavailable",
            "Unverified means you have not independently confirmed this identity. Successful encryption does not verify a person.")
        SectionLabel(if (changed) "NEW SAFETY NUMBER" else "SAFETY NUMBER")
        Surface(shape = MaterialTheme.shapes.medium) {
            Text(if (state.fingerprint.isEmpty()) "Loading safety number…" else state.fingerprint.split(" ").chunked(3).joinToString("\n") { it.joinToString("  ") },
                Modifier.fillMaxWidth().padding(24.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
        }
        Text("Both people should see exactly the same number. Compare every group.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FullButton(if (changed) "Review new identity" else "Mark verified", !state.loading && state.fingerprint.isNotEmpty()) { confirmation = state.fingerprint }
        if (changed && importUpdatedCard != null) TextButton(onClick = importUpdatedCard) { Text("Import updated contact card") }
        ErrorNotice(state.error)
        HorizontalDivider()
        SectionLabel("LOCAL CONTROLS")
        Text("Blocking stops local conversation delivery. It keeps the contact and cryptographic identity.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { block(!contact.contact.blocked) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (contact.contact.blocked) "Unblock contact" else "Block on this device")
        }
    }
    confirmation?.let { expected ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (changed) "Trust this new identity?" else "Did you compare the number?") },
            text = { Text(if (changed) "Continue only after independently comparing this new safety number. This replaces the previous pin and leaves the new identity unverified until you mark it verified."
                else "Only mark this contact verified if you compared every group with them in person or through another trusted channel.") },
            confirmButton = { TextButton(onClick = { confirmation = null; if (changed) trust(expected) else verify(expected) }) { Text(if (changed) "Trust new identity" else "I compared it · Verify") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}
