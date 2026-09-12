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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*

@Composable fun ContactsScreen(state: AppState, add: () -> Unit, open: (String) -> Unit, connect: () -> Unit = {}, sync: () -> Unit = {}, directory: Boolean = false) {
    var query by remember { mutableStateOf("") }
    val matching = state.contacts.filter { (!it.contact.request || !it.contact.blocked) &&
        (!directory || !it.contact.request) && (!directory || it.contact.displayName.contains(query,ignoreCase=true)) }
    val chats = if(directory) matching.sortedBy {it.contact.displayName.lowercase()} else matching.sortedByDescending {state.previews[it.contact.remoteDeviceId]?.timestamp ?: 0}
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().drawWithCache {
        val wash = Brush.verticalGradient(listOf(accent.copy(alpha=0.06f), Color.Transparent),endY=220.dp.toPx())
        onDrawBehind { if(!directory) drawRect(wash) }
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal=if(directory) 16.dp else 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=if(directory) 4.dp else 0.dp,vertical=if(directory) 20.dp else 6.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(if(directory) "Contacts" else "Ghost Cloak",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
                    if(directory) Text("People you know",
                        style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                }
                if(!directory) {
                    IconButton(onClick=add,modifier=Modifier.size(48.dp)) {
                        Surface(Modifier.size(32.dp),shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.primary,
                            contentColor=MaterialTheme.colorScheme.onPrimary) {
                            Box(contentAlignment=Alignment.Center) { AppIcon(Glyph.PLUS,"New chat",Modifier.size(18.dp)) }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    BrandMark(Modifier.size(20.dp))
                }
            }
            if(!directory) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(
                    listOf(accent.copy(alpha=0.22f),accent.copy(alpha=0.08f),Color.Transparent))))
                Text("Chats",Modifier.padding(top=10.dp),style=MaterialTheme.typography.labelLarge,color=accent)
            }
            if(directory) OutlinedTextField(query,{if(it.length<=64) query=it},singleLine=true,placeholder={Text(if(directory) "Search contacts" else "Search chats")},
                leadingIcon={AppIcon(Glyph.SEARCH)},trailingIcon=if(query.isEmpty()) null else {{IconButton(onClick={query=""}) {AppIcon(Glyph.CLOSE,"Clear search")}}},
                shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth(),
                colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Color.Transparent,focusedBorderColor=MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surface))
            Spacer(Modifier.height(if(directory) 16.dp else 12.dp))
            if(state.networkConfigured && !state.demo && !state.networkConnected) NetworkActions(state,connect,sync)
            if(state.error!=null) ErrorNotice(state.error)
            if(chats.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(72.dp),contentAlignment=Alignment.Center) {AppIcon(Glyph.CHAT,modifier=Modifier.size(30.dp),tint=MaterialTheme.colorScheme.primary)}
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(when {query.isNotEmpty()->"No matches found"; directory->"Your people, here"; else->"A little more private."},style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(when {query.isNotEmpty()->"Try another name."; else->"Start with someone's Ghost Cloak username."},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=if(directory) 100.dp else 24.dp)) {
                if(directory) item {Text("YOUR CONTACTS",Modifier.padding(start=4.dp,bottom=10.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(chats,key={it.contact.contactId}) { status ->
                    ChatRow(status,if(directory) null else state.previews[status.contact.remoteDeviceId],open)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
        if(directory) ExtendedFloatingActionButton(onClick=add,modifier=Modifier.align(Alignment.BottomEnd).padding(20.dp),
            icon={AppIcon(Glyph.PLUS)},text={Text(if(directory) "Add contact" else "New chat")},
            containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)
    }
}
