package org.ghostcloak.app.ui.screens

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
import androidx.compose.ui.platform.LocalContext
import org.ghostcloak.protocol.GhostCloakIds

@Composable fun ProfilesScreen(state: AppState, rename: (String) -> Unit) {
    var displayName by remember(state.identity?.displayName) { mutableStateOf(state.identity?.displayName.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    val context=LocalContext.current
    PageContent("Profiles") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(GhostDimensions.regular),verticalAlignment=Alignment.CenterVertically) {
            Avatar(state.identity?.displayName.orEmpty(),Modifier.size(GhostDimensions.profileAvatar))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(GhostDimensions.tiny)) {
                Text(state.identity?.displayName.orEmpty(),style=MaterialTheme.typography.titleLarge)
                Text("Your Ghost Cloak identity",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick={editing=true}) {Text("Edit")}
        }
        ErrorNotice(state.error, important = state.errorImportant)
        SettingsGroup("Your profile") {
            DetailRow(Glyph.PERSON,"Display name",state.identity?.displayName.orEmpty())
            state.ghostCloakId?.let { id ->
                DetailRow(Glyph.SHIELD,"Ghost Cloak ID",GhostCloakIds.display(id))
                TextButton(onClick={copySensitive(context,GhostCloakIds.display(id))}) {Text("Copy ID")}
            }
            DetailRow(Glyph.SHIELD,"Device identity","Your profile belongs to this device. Editing your name keeps your keys and conversations.")
        }
    }
    if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("Edit your name")},text={
        Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.medium)) {
            OutlinedTextField(displayName,{if(it.length<=32) displayName=it},singleLine=true,label={Text("Display name")})
            Text("This changes your local name and the name shared in future encrypted messages. Your Ghost Cloak ID and keys stay the same.")
        }
    },confirmButton={TextButton(onClick={rename(displayName);editing=false},enabled=!state.loading && displayName.isNotBlank() && displayName!=state.identity?.displayName) {Text("Save name")}},dismissButton={TextButton(onClick={editing=false}) {Text("Cancel")}})
}
