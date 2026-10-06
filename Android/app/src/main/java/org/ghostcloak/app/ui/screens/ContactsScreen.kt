package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut
import org.ghostcloak.messaging.ChatOrganization
import org.ghostcloak.identity.IdentityTrustState

@Composable fun ContactsScreen(state: AppState, add: () -> Unit, open: (String) -> Unit, connect: () -> Unit = {}, sync: () -> Unit = {}, directory: Boolean = false,
    archived:Boolean=false,showArchived:()->Unit={},unarchive:(String)->Unit={},back:()->Unit={},
    acceptGroupInvite:(String)->Unit={},declineGroupInvite:(String)->Unit={},
    openGroup:(String)->Unit={},showCreateGroup:()->Unit={}) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var chatOptions by remember { mutableStateOf(false) }
    val source=when {
        directory -> state.contacts.filter { !it.contact.request && !it.contact.blocked }.sortedBy {it.contact.visibleName.lowercase()}
        archived -> ChatOrganization.archived(state.contacts,state.previews)
        else -> ChatOrganization.main(state.contacts,state.previews)
    }
    val chats=source.filter { it.contact.visibleName.contains(query,ignoreCase=true) }
    val pinnedCount=if(!directory && !archived) chats.count {it.contact.pinned && !it.contact.request} else 0
    val hasUnread = !directory && !archived && state.unreadCount > 0
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(if (directory) "Contacts" else if(archived) "Archived chats" else if (hasUnread) "" else "Chats",
                back=if(archived) back else null,
                center = if (hasUnread) "${state.unreadCount} new ${if (state.unreadCount == 1) "message" else "messages"}" else null,
                leading = if (hasUnread) {{ HeaderAction(Glyph.SEARCH, "Search conversations") { searching = !searching; query = "" } }} else null) {
                if (!directory && !hasUnread) HeaderAction(Glyph.SEARCH, "Search conversations") { searching = !searching; query = "" }
                if(!archived) HeaderAction(if(directory) Glyph.ADD_CONTACT else Glyph.COMPOSE, if (directory) "Add contact" else "New chat", add)
                if(!directory && !archived) Box {
                    HeaderAction(Glyph.MORE,"Chat options") { chatOptions=true }
                    DropdownMenu(expanded=chatOptions,onDismissRequest={chatOptions=false}) {
                        DropdownMenuItem(text={Text("Archived chats")},onClick={chatOptions=false;showArchived()})
                        DropdownMenuItem(text={Text("Create group")},onClick={chatOptions=false;showCreateGroup()})
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = GhostLayout.pageInset)) {
            if(directory || searching) OutlinedTextField(query,{if(it.length<=64) query=it},singleLine=true,placeholder={Text(if(directory) "Search contacts" else "Search conversations")},
                leadingIcon={AppIcon(Glyph.SEARCH)},trailingIcon=if(query.isEmpty()) null else {{IconButton(onClick={query=""}) {AppIcon(Glyph.CLOSE,"Clear search")}}},
                shape=RoundedCornerShape(GhostDimensions.fieldCorner),modifier=Modifier.fillMaxWidth().noSensitiveCopyCut(),
                colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Color.Transparent,focusedBorderColor=MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor=MaterialTheme.colorScheme.surface,focusedContainerColor=MaterialTheme.colorScheme.surface))
            Spacer(Modifier.height(GhostLayout.dividerGap))
            if(state.networkConfigured && !state.demo && !state.networkConnected) NetworkActions(state,connect,sync)
            if(state.error!=null) ErrorNotice(state.error, important = state.errorImportant)
            if(directory && state.forkedGroupCount>0) Text(
                "Group state conflict — resync required. Start a new group to continue safely.",
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
            if(directory && state.groupInvitations.isNotEmpty()) {
                Text("GROUP INVITATIONS",style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                state.groupInvitations.forEach { invitation ->
                    val inviter=state.contacts.firstOrNull {
                        it.contact.remoteDeviceId==invitation.senderDeviceId &&
                            !it.contact.request && !it.contact.blocked
                    }
                    if(inviter!=null) Surface(shape=MaterialTheme.shapes.large,
                        color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().padding(vertical=GhostDimensions.small)) {
                        Column(Modifier.padding(GhostDimensions.regular)) {
                            Text("Group invitation",style=MaterialTheme.typography.titleMedium)
                            Text("From ${inviter.contact.visibleName} · ${invitation.memberCount} current members",
                                style=MaterialTheme.typography.bodyMedium)
                            Text(if(inviter.identity?.trustState==IdentityTrustState.VERIFIED) "Inviter verified" else "Inviter unverified",
                                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(invitation.accepting) Text("Waiting for membership confirmation…",
                                style=MaterialTheme.typography.bodySmall)
                            else Row(horizontalArrangement=Arrangement.spacedBy(GhostDimensions.small)) {
                                TextButton(onClick={acceptGroupInvite(invitation.id)},enabled=!state.loading) {Text("Accept")}
                                TextButton(onClick={declineGroupInvite(invitation.id)},enabled=!state.loading) {Text("Decline")}
                            }
                        }
                    }
                }
            }
            if(chats.isEmpty() && (directory || archived || state.groups.isEmpty())) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(GhostDimensions.roomy),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(GhostDimensions.emptyStateIcon),contentAlignment=Alignment.Center) {AppIcon(Glyph.CHAT,modifier=Modifier.size(GhostDimensions.featureIcon),tint=MaterialTheme.colorScheme.primary)}
                    }
                    Spacer(Modifier.height(GhostDimensions.large))
                    Text(when {query.isNotEmpty()->"No matches found"; directory->"Your people, here";archived->"No archived chats"; else->"A little more private."},style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(GhostDimensions.compact))
                    Text(when {query.isNotEmpty()->"Try another name."; else->"Start with someone's Ghost Cloak ID."},style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=GhostLayout.pageInset)) {
                if(!directory && !archived && state.groups.isNotEmpty()) {
                    item {Text("GROUPS",Modifier.padding(start=GhostDimensions.tiny,bottom=GhostDimensions.controlGap),
                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(state.groups.size) {index ->
                        val group=state.groups[index]
                        Surface(onClick={openGroup(group.groupId)},shape=MaterialTheme.shapes.large,
                            color=MaterialTheme.colorScheme.surface,
                            modifier=Modifier.fillMaxWidth().padding(bottom=GhostDimensions.micro)) {
                            Column(Modifier.padding(GhostDimensions.regular)) {
                                Text("Group conversation",style=MaterialTheme.typography.titleMedium)
                                Text("${group.memberCount} members · ${group.status.name.lowercase().replaceFirstChar(Char::uppercase)}",
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                group.messages.lastOrNull()?.let {Text(it.text,maxLines=1,
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            }
                        }
                    }
                }
                if(directory) item {Text("YOUR CONTACTS",Modifier.padding(start=GhostDimensions.tiny,bottom=GhostDimensions.controlGap),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                itemsIndexed(chats,key={_,status->status.contact.contactId}) { index,status ->
                    if(pinnedCount>0 && index==0) Text("Pinned",Modifier.padding(
                        start=GhostDimensions.tiny,top=GhostDimensions.compact,bottom=GhostDimensions.controlGap),
                        style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(pinnedCount>0 && index==pinnedCount) Text("Other chats",Modifier.padding(
                        start=GhostDimensions.tiny,top=GhostDimensions.compact,bottom=GhostDimensions.controlGap),
                        style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    ChatRow(status,if(directory) null else state.previews[status.contact.remoteDeviceId],open,
                        unreadCount = if(directory) 0 else state.unreadByConversation[status.contact.remoteDeviceId] ?: 0)
                    if(archived) TextButton(onClick={unarchive(status.contact.remoteDeviceId)}) {Text("Unarchive ${status.contact.visibleName}")}
                    Spacer(Modifier.height(GhostDimensions.micro))
                }
            }
        }
        }
    }
}
