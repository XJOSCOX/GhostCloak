package org.ghostcloak.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.privacy.copySensitive
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.ghostcloak.protocol.GhostCloakIds

@Composable fun ProfilesScreen(state: AppState, rename: (String) -> Unit, showQr: () -> Unit = {},
    setAbout:(String)->Unit={},setPhoto:(android.net.Uri)->Unit={},removePhoto:()->Unit={},
    setSharing:(Boolean)->Unit={}) {
    var displayName by remember(state.identity?.displayName) { mutableStateOf(state.identity?.displayName.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var editingAbout by remember {mutableStateOf(false)}
    var aboutDraft by remember(state.ownProfile.about) {mutableStateOf(state.ownProfile.about)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri -> if(uri!=null) setPhoto(uri)}
    val context=LocalContext.current
    PageContent("Profiles") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(GhostDimensions.regular),verticalAlignment=Alignment.CenterVertically) {
            Avatar(state.identity?.displayName.orEmpty(),Modifier.size(GhostDimensions.profileAvatar),state.ownProfile.photo)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(GhostDimensions.tiny)) {
                Text(state.identity?.displayName.orEmpty(),style=MaterialTheme.typography.titleLarge)
                Text("Your Ghost Cloak identity",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick={editing=true}) {Text("Edit")}
        }
        ErrorNotice(state.error, important = state.errorImportant)
        SettingsGroup("Your profile") {
            DetailRow(Glyph.PERSON,"Display name",state.identity?.displayName.orEmpty())
            DetailRow(Glyph.PERSON,"About",state.ownProfile.about.ifBlank {"Not set"})
            TextButton(onClick={aboutDraft=state.ownProfile.about;editingAbout=true}) {Text("Edit About")}
            TextButton(onClick={picker.launch("image/*")},enabled=!state.loading) {Text(if(state.ownProfile.photo==null) "Add profile photo" else "Change profile photo")}
            if(state.ownProfile.photo!=null) TextButton(onClick=removePhoto,enabled=!state.loading) {Text("Remove profile photo")}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share with accepted contacts",style=MaterialTheme.typography.titleMedium)
                    Text("Your name, About and photo are sent only inside encrypted conversations.",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=state.ownProfile.sharing,onCheckedChange=setSharing,enabled=!state.loading)
            }
            state.ghostCloakId?.let { id ->
                DetailRow(Glyph.SHIELD,"Ghost Cloak ID",GhostCloakIds.display(id))
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(GhostDimensions.controlGap)) {
                    val actionModifier = Modifier.weight(1f).heightIn(min=GhostDimensions.touchTarget)
                    val actionPadding = PaddingValues(horizontal=GhostDimensions.tiny)
                    OutlinedButton(
                        onClick={copySensitive(context,GhostCloakIds.display(id))},
                        modifier=actionModifier,
                        contentPadding=actionPadding,
                    ) { Text("Copy ID", maxLines=1) }
                    OutlinedButton(
                        onClick={
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Add me on Ghost Cloak:\n${GhostCloakIds.display(id)}")
                            }
                            context.startActivity(Intent.createChooser(share, "Share Ghost Cloak ID"))
                        },
                        modifier=actionModifier,
                        contentPadding=actionPadding,
                    ) { Text("Share ID", maxLines=1) }
                    OutlinedButton(
                        onClick=showQr,
                        modifier=actionModifier,
                        contentPadding=actionPadding,
                    ) { Text("Show QR", maxLines=1) }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            }
            DetailRow(Glyph.SHIELD,"Device identity","Your profile belongs to this device. Editing your name keeps your keys and conversations.")
        }
    }
    if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("Edit your name")},text={
        Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.medium)) {
            OutlinedTextField(displayName,{if(it.length<=32) displayName=it},singleLine=true,label={Text("Display name")},modifier=Modifier.noSensitiveCopyCut())
            Text("This changes your local name and the name shared in future encrypted messages. Your Ghost Cloak ID and keys stay the same.")
        }
    },confirmButton={TextButton(onClick={rename(displayName);editing=false},enabled=!state.loading && displayName.isNotBlank() && displayName!=state.identity?.displayName) {Text("Save name")}},dismissButton={TextButton(onClick={editing=false}) {Text("Cancel")}})
    if(editingAbout) AlertDialog(onDismissRequest={editingAbout=false},title={Text("Edit About")},text={
        OutlinedTextField(aboutDraft,{if(it.encodeToByteArray().size<=org.ghostcloak.messaging.ProfileRules.MAX_ABOUT_BYTES) aboutDraft=it},
            label={Text("About")},maxLines=3,modifier=Modifier.noSensitiveCopyCut())
    },confirmButton={TextButton(onClick={setAbout(aboutDraft);editingAbout=false},enabled=!state.loading &&
        runCatching {org.ghostcloak.messaging.ProfileRules.about(aboutDraft)}.isSuccess) {Text("Save")}},
        dismissButton={TextButton(onClick={editingAbout=false}) {Text("Cancel")}})
}
