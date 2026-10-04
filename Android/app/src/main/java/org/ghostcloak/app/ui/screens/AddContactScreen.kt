package org.ghostcloak.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.privacy.copySensitive
import org.ghostcloak.messaging.ContactCardCodec
import org.ghostcloak.protocol.GhostCloakIds
import org.ghostcloak.protocol.GhostCloakContactQr
import org.ghostcloak.app.ui.qr.SecureQrCaptureActivity

@Composable fun AddContactScreen(state: AppState, back: () -> Unit, export: () -> Unit, lookup:((String)->Unit)?=null,
    unblock:(String)->Unit={}, manageBlocked:()->Unit={}, import: (String) -> Unit,
    openExisting:(String)->Unit={}) {
    var draft by remember { mutableStateOf("") }; var tooLarge by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var unblockTarget by remember {mutableStateOf<String?>(null)}
    val context = LocalContext.current
    var ghostCloakId by rememberSaveable { mutableStateOf("") }
    var invalidQr by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { payload ->
            val id = GhostCloakContactQr.decode(payload)
            if (id == null) invalidQr = true else { ghostCloakId = id; invalidQr = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied = !granted
        if (granted) scanner.launch(contactScanOptions())
    }
    PageContent("New chat", if (state.networkConfigured && !state.demo) "Find someone on Ghost Cloak." else "Exchange a public contact card.", back) {
        if(state.networkConfigured && !state.demo && lookup!=null) {
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.fillMaxWidth().padding(GhostDimensions.heroInset),contentAlignment=androidx.compose.ui.Alignment.Center) { AppIcon(Glyph.PERSON,modifier=Modifier.size(GhostDimensions.touchTarget),tint=MaterialTheme.colorScheme.primary) }
            }
            OutlinedTextField(ghostCloakId,{if(it.length<=14) ghostCloakId=it},label={Text("Ghost Cloak ID")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                invalidQr = false; cameraDenied = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    scanner.launch(contactScanOptions())
                else permission.launch(Manifest.permission.CAMERA)
            }, modifier = Modifier.fillMaxWidth()) { Text("Scan QR") }
            if (cameraDenied) Text("Camera access is needed to scan. You can still enter an ID manually.")
            if (invalidQr) Text("Invalid Ghost Cloak QR code.", color = MaterialTheme.colorScheme.error)
            val canonical=runCatching {GhostCloakIds.normalize(ghostCloakId)}.getOrNull()
            val blocked=state.blockedContacts.firstOrNull {it.ghostCloakId==canonical && canonical!=null}
            val existing=state.contacts.firstOrNull {it.contact.ghostCloakId==canonical && canonical!=null}
            if(canonical!=null && canonical==state.ghostCloakId) {
                Text("This is your Ghost Cloak ID.")
            } else if(blocked!=null) {
                Text("This contact is blocked.")
                FullButton("Unblock",!state.loading) {unblockTarget=blocked.remoteDeviceId}
            } else if(existing!=null) {
                Text("This contact is already on this device.")
                FullButton("Open contact",!state.loading) {openExisting(existing.contact.remoteDeviceId)}
            } else FullButton("Find and add contact",!state.loading && canonical!=null) {lookup(canonical!!)}
            if(ghostCloakId.isNotBlank() && canonical==null) Text("Use 12 ID characters, optionally grouped 4-4-4.",color=MaterialTheme.colorScheme.error)
            Text("Your first message arrives as a request. Safety-number verification is optional; use it to confirm who you are talking to.",style=MaterialTheme.typography.bodyMedium)
        }
        ErrorNotice(state.error, important = state.errorImportant)
        if(state.error=="This contact is blocked on this device.") TextButton(onClick=manageBlocked){Text("Blocked contacts")}
        if (!state.networkConfigured || state.demo) {
            InfoPanel("Exchange public material", "Cards contain public identity and prekeys only. Share through a trusted channel, then compare your safety number.")
            SectionLabel("IMPORT THEIR CARD")
            OutlinedTextField(draft, onValueChange = { tooLarge = it.length > ContactCardCodec.MAX_TEXT; if (!tooLarge) draft = it },
                label = { Text("Paste contact card") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                isError = tooLarge, supportingText = { Text(if (tooLarge) "Card exceeds 8,192 characters. Nothing was imported." else "GHOSTCLOAK:2:…") })
            FullButton("Import contact", !state.loading && draft.isNotBlank() && !tooLarge) { import(draft) }

            HorizontalDivider()
            SectionLabel("SHARE YOUR CARD")
            Text("Your private keys never leave encrypted storage.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.card.isEmpty()) OutlinedButton(onClick = export, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = GhostDimensions.avatar)) { Text("Generate public contact card") }
            else {
                InfoPanel("Public contact card ready", "This card is for one contact exchange. It does not connect devices over a network.")
                FullButton(if (copied) "Copy again" else "Copy public card") { copySensitive(context, state.card); copied = true }
                if (copied) Text("Copied to clipboard", color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { copied = false; export() }, enabled = !state.loading) { Text("Generate a fresh card for another contact") }
            }
            Text("Copying is optional. Clipboard content may be accessible to your keyboard, other software or synced devices.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    unblockTarget?.let {id->UnblockConfirmation({unblockTarget=null}) {unblockTarget=null;unblock(id)}}
}

private fun contactScanOptions() = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setBeepEnabled(false)
    .setBarcodeImageEnabled(false)
    .setPrompt("Scan a Ghost Cloak contact QR")
    .setCaptureActivity(SecureQrCaptureActivity::class.java)
