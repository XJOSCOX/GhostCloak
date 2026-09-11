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

@Composable fun ProfilesScreen(state: AppState, rename: (String) -> Unit) {
    var username by remember(state.identity?.username) { mutableStateOf(state.identity?.username.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Text("Profiles",style=MaterialTheme.typography.headlineLarge)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Avatar(state.identity?.username.orEmpty(),Modifier.size(64.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(state.identity?.username.orEmpty(),style=MaterialTheme.typography.titleLarge)
                Text("Your Ghost Cloak identity",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick={editing=true}) {Text("Edit")}
        }
        ErrorNotice(state.error)
        SettingsGroup("Your profile") {
            DetailRow(Glyph.PERSON,"Display name",state.identity?.username.orEmpty())
            DetailRow(Glyph.SHIELD,"Device identity","Your profile belongs to this device. Editing your name keeps your keys and conversations.")
        }
    }
    if(editing) AlertDialog(onDismissRequest={editing=false},title={Text("Edit your name")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(username,{if(it.length<=32) username=it},singleLine=true,label={Text("Username")})
            Text(if(state.networkConfigured && !state.demo) "Before registration, this is your network username. After registration, only your local display name changes. Your keys stay the same." else "This changes your display name only. Your keys stay the same.")
        }
    },confirmButton={TextButton(onClick={rename(username);editing=false},enabled=!state.loading && username.isNotBlank() && username!=state.identity?.username) {Text("Save username")}},dismissButton={TextButton(onClick={editing=false}) {Text("Cancel")}})
}
