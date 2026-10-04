package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import org.ghostcloak.app.ui.theme.SafetyNumberStyle
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.identity.*
import org.ghostcloak.messaging.ContactStatus
import org.ghostcloak.protocol.GhostCloakIds

@Composable fun ContactSecurityScreen(state: AppState, contact: ContactStatus, back: () -> Unit,
    load: (Boolean) -> Unit, verify: (String) -> Unit, trust: (String) -> Unit, block: (Boolean) -> Unit,
    clear: () -> Unit = {}, remove: () -> Unit = {}, importUpdatedCard: (() -> Unit)? = null) {
    val changed = contact.identity?.trustState == IdentityTrustState.CHANGED
    val verified = contact.identity?.trustState == IdentityTrustState.VERIFIED
    val previouslyVerified = contact.identity?.previousTrustState == IdentityTrustState.VERIFIED
    val fingerprint = state.safetyNumber?.visibleFor(contact.contact.remoteDeviceId, contact.contact.contactId, changed).orEmpty()
    var confirmation by remember(contact.contact.remoteDeviceId, contact.contact.contactId, changed) { mutableStateOf<String?>(null) }
    var confirmUnblock by remember(contact.contact.remoteDeviceId, contact.contact.contactId) {mutableStateOf(false)}
    var manageAction by remember(contact.contact.remoteDeviceId, contact.contact.contactId) {mutableStateOf<String?>(null)}
    LaunchedEffect(contact.contact.remoteDeviceId, contact.contact.contactId, changed) { load(changed) }
    PageContent("Contact details", "Verify the person behind the name.", back) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(GhostDimensions.regular),verticalAlignment=Alignment.CenterVertically) {
            Avatar(contact.contact.visibleName)
            Column(verticalArrangement = Arrangement.spacedBy(GhostDimensions.small)) {
                Text(contact.contact.visibleName, style = MaterialTheme.typography.titleLarge)
                TrustBadge(contact.identity?.trustState)
            }
        }
        contact.contact.ghostCloakId?.let { id ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(GhostDimensions.regular),
                    verticalArrangement = Arrangement.spacedBy(GhostDimensions.small)) {
                    SectionLabel("GHOST CLOAK ID")
                    Text(GhostCloakIds.display(id), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        contact.contact.localAlias?.takeIf { !contact.contact.request }?.let { alias ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(GhostDimensions.regular)) {
                    SectionLabel("LOCAL ALIAS")
                    Text(alias, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (changed) InfoPanel(if (previouslyVerified) "! Previously verified identity changed" else "! Security identity changed",
            "Sending is blocked. The key differs from the one you knew. Compare the new safety number with this person in person or through another trusted channel.", warning = true, urgent = previouslyVerified)
        else InfoPanel(if (contact.session == SessionLifecycle.ACTIVE) "Encrypted session active" else "Session unavailable",
            if (verified) "You marked this identity verified after comparing its safety number. Ghost Cloak will warn you if the identity changes."
            else "Unverified means you have not independently confirmed this identity. Successful encryption does not verify a person.")
        SectionLabel(if (changed) "NEW SAFETY NUMBER" else "SAFETY NUMBER")
        Surface(shape = MaterialTheme.shapes.large, color=MaterialTheme.colorScheme.primaryContainer) {
            Text(if (fingerprint.isEmpty()) "Loading safety number…" else fingerprint.split(" ").chunked(3).joinToString("\n") { it.joinToString("  ") },
                Modifier.fillMaxWidth().padding(GhostDimensions.spacious), style = SafetyNumberStyle, color=MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("Both people should see exactly the same number. Compare every group.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!verified) FullButton(if (changed) "Review new identity" else "Mark verified", !state.loading && fingerprint.isNotEmpty()) { confirmation = fingerprint }
        if (changed && importUpdatedCard != null) TextButton(onClick = importUpdatedCard) { Text("Import updated contact card") }
        ErrorNotice(state.error, important = state.errorImportant)
        HorizontalDivider()
        SectionLabel(if(contact.contact.request || contact.contact.blocked) "LOCAL CONTROLS" else "MANAGE CONTACT")
        if(!contact.contact.request && !contact.contact.blocked) {
            OutlinedButton(onClick={manageAction="delete"},enabled=!state.loading,modifier=Modifier.fillMaxWidth().heightIn(min=GhostDimensions.avatar)) {
                Text("Delete conversation")
            }
            OutlinedButton(onClick={manageAction="remove"},enabled=!state.loading,modifier=Modifier.fillMaxWidth().heightIn(min=GhostDimensions.avatar)) {
                Text("Remove contact")
            }
            OutlinedButton(onClick={manageAction="block"},enabled=!state.loading,modifier=Modifier.fillMaxWidth().heightIn(min=GhostDimensions.avatar)) {
                Text("Block contact")
            }
        } else {
            OutlinedButton(onClick = { if(contact.contact.blocked) confirmUnblock=true else manageAction="block" }, enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = GhostDimensions.avatar)) {
                Text(if (contact.contact.blocked) "Unblock contact" else "Block contact")
            }
        }
    }
    confirmation?.let { expected ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (changed) "Trust this new identity?" else "Did you compare the number?") },
            text = { Text(if (changed) "Continue only after independently comparing this new safety number. This replaces the previous pin and leaves the new identity unverified until you mark it verified."
                else "Only mark this contact verified if you compared every group with them in person or through another trusted channel.") },
            confirmButton = { TextButton(onClick = { confirmation = null; if (expected == fingerprint && expected.isNotEmpty()) {
                if (changed) trust(expected) else verify(expected)
            } }, enabled = expected == fingerprint && expected.isNotEmpty()) { Text(if (changed) "Trust new identity" else "I compared it · Verify") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
    if(confirmUnblock) UnblockConfirmation({confirmUnblock=false}) {confirmUnblock=false;block(false)}
    manageAction?.let { action ->
        AlertDialog(onDismissRequest={manageAction=null},
            title={Text(when(action) {"delete"->"Delete this conversation?";"remove"->"Remove this contact?";else->"Block this contact?"})},
            text={Text(when(action) {
                "delete"->"This removes the local message history from this device."
                "remove"->"Future messages from this person will arrive as new message requests."
                else->"Future messages will be hidden and discarded on this device."
            })},
            confirmButton={TextButton(onClick={manageAction=null;when(action) {"delete"->clear();"remove"->remove();else->block(true)}}) {
                Text(when(action) {"delete"->"Delete";"remove"->"Remove";else->"Block"})
            }},
            dismissButton={TextButton(onClick={manageAction=null}) {Text("Cancel")}})
    }
}
