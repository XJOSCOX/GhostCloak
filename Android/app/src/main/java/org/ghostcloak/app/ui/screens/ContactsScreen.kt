package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*

@Composable fun ContactsScreen(state: AppState, add: () -> Unit, open: (String) -> Unit, connect: () -> Unit = {}, sync: () -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var requestsOnly by remember { mutableStateOf(false) }
    val requests = state.contacts.count { it.contact.request && !it.contact.blocked }
    val chats = state.contacts.filter { (!it.contact.request || !it.contact.blocked) &&
        (!requestsOnly || it.contact.request) && it.contact.displayName.contains(query, ignoreCase=true) }
        .sortedByDescending { state.previews[it.contact.remoteDeviceId]?.timestamp ?: 0 }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=16.dp,bottom=16.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chats", style=MaterialTheme.typography.headlineLarge)
                if(state.demo) Text("Local demo",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledIconButton(onClick=add,enabled=!state.loading) { AppIcon(Glyph.PLUS,"New chat") }
        }
        OutlinedTextField(query,{if(it.length<=64) query=it}, singleLine=true, placeholder={Text("Search chats")},
            leadingIcon={AppIcon(Glyph.SEARCH)}, trailingIcon=if(query.isEmpty()) null else {{IconButton(onClick={query=""}) {AppIcon(Glyph.CLOSE,"Clear search")}}},
            shape=RoundedCornerShape(18.dp), modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),
            colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Color.Transparent,focusedBorderColor=MaterialTheme.colorScheme.primary,
                unfocusedContainerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surface))
        Row(Modifier.padding(horizontal=20.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=!requestsOnly,onClick={requestsOnly=false},label={Text("All")})
            FilterChip(selected=requestsOnly,onClick={requestsOnly=true},label={Text(if(requests>0) "Requests $requests" else "Requests")})
        }
        if(state.networkConfigured && !state.demo && !state.networkConnected) Box(Modifier.padding(horizontal=20.dp)) { NetworkActions(state,connect,sync) }
        if(state.error!=null) Box(Modifier.padding(horizontal=20.dp,vertical=8.dp)) {ErrorNotice(state.error)}
        if(chats.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                AppIcon(if(requestsOnly) Glyph.SHIELD else Glyph.CHAT,modifier=Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text(when {query.isNotEmpty()->"No matching chats"; requestsOnly->"No message requests"; else->"Say hello, privately."},style=MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(when {query.isNotEmpty()->"Try another name."; requestsOnly->"New people will appear here."; else->"Start a conversation with a username."},color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(query.isEmpty() && !requestsOnly) TextButton(onClick=add) {Text("Start a new chat")}
            }
        } else LazyColumn(Modifier.weight(1f), contentPadding=PaddingValues(horizontal=20.dp,vertical=4.dp)) {
            items(chats,key={it.contact.contactId}) { status ->
                ChatRow(status,state.previews[status.contact.remoteDeviceId],open)
                HorizontalDivider(Modifier.padding(start=68.dp),color=MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
