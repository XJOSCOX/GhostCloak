package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.ui.theme.GhostLayout
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*

@Composable fun ContactsScreen(state: AppState, add: () -> Unit, open: (String) -> Unit, connect: () -> Unit = {}, sync: () -> Unit = {}, directory: Boolean = false) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val matching = state.contacts.filter { (!it.contact.request || !it.contact.blocked) &&
        (!directory || !it.contact.request) && it.contact.displayName.contains(query,ignoreCase=true) }
    val chats = if(directory) matching.sortedBy {it.contact.displayName.lowercase()} else matching.sortedByDescending {state.previews[it.contact.remoteDeviceId]?.timestamp ?: 0}
    val hasUnread = !directory && state.unreadCount > 0
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(if (directory) "Contacts" else if (hasUnread) "" else "Chats",
                center = if (hasUnread) "${state.unreadCount} new ${if (state.unreadCount == 1) "message" else "messages"}" else null,
                leading = if (hasUnread) {{ HeaderAction(Glyph.SEARCH, "Search conversations") { searching = !searching; query = "" } }} else null) {
                if (!directory && !hasUnread) HeaderAction(Glyph.SEARCH, "Search conversations") { searching = !searching; query = "" }
                HeaderAction(Glyph.COMPOSE, if (directory) "Add contact" else "New chat", add)
            }
            Column(Modifier.weight(1f).padding(horizontal = GhostLayout.pageInset)) {
            if(directory || searching) OutlinedTextField(query,{if(it.length<=64) query=it},singleLine=true,placeholder={Text(if(directory) "Search contacts" else "Search conversations")},
                leadingIcon={AppIcon(Glyph.SEARCH)},trailingIcon=if(query.isEmpty()) null else {{IconButton(onClick={query=""}) {AppIcon(Glyph.CLOSE,"Clear search")}}},
                shape=RoundedCornerShape(GhostDimensions.fieldCorner),modifier=Modifier.fillMaxWidth(),
                colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Color.Transparent,focusedBorderColor=MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surface))
            Spacer(Modifier.height(GhostLayout.dividerGap))
            if(state.networkConfigured && !state.demo && !state.networkConnected) NetworkActions(state,connect,sync)
            if(state.error!=null) ErrorNotice(state.error)
            if(chats.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(GhostDimensions.roomy),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(GhostDimensions.emptyStateIcon),contentAlignment=Alignment.Center) {AppIcon(Glyph.CHAT,modifier=Modifier.size(GhostDimensions.featureIcon),tint=MaterialTheme.colorScheme.primary)}
                    }
                    Spacer(Modifier.height(GhostDimensions.large))
                    Text(when {query.isNotEmpty()->"No matches found"; directory->"Your people, here"; else->"A little more private."},style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(GhostDimensions.compact))
                    Text(when {query.isNotEmpty()->"Try another name."; else->"Start with someone's Ghost Cloak username."},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=GhostLayout.pageInset)) {
                if(directory) item {Text("YOUR CONTACTS",Modifier.padding(start=GhostDimensions.tiny,bottom=GhostDimensions.controlGap),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(chats,key={it.contact.contactId}) { status ->
                    ChatRow(status,if(directory) null else state.previews[status.contact.remoteDeviceId],open,
                        unreadCount = if(directory) 0 else state.unreadByConversation[status.contact.remoteDeviceId] ?: 0)
                    Spacer(Modifier.height(GhostDimensions.micro))
                }
            }
        }
        }
    }
}
