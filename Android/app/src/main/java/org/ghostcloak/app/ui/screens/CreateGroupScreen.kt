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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.Avatar
import org.ghostcloak.app.ui.components.ErrorNotice
import org.ghostcloak.app.ui.components.PageHeader
import org.ghostcloak.app.ui.components.AppIcon
import org.ghostcloak.app.ui.components.Glyph
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.ui.theme.GhostLayout
import org.ghostcloak.identity.IdentityTrustState
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.messaging.ContactStatus
import org.ghostcloak.messaging.GroupStatements

/** Invite the intended roster together; accepted memberships commit one at a time. */
@Composable fun CreateGroupScreen(state:AppState,back:()->Unit,createGroup:(String,List<String>)->Unit,
    openChat:(String)->Unit) {
    var query by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    val resolvedName=groupName.trim().ifEmpty {"Group"}
    val nameValid=resolvedName.encodeToByteArray().size<=64 &&
        resolvedName.none {Character.isISOControl(it) || it=='\u061c' ||
            it in '\u200e'..'\u200f' || it in '\u202a'..'\u202e' ||
            it in '\u2066'..'\u2069'} &&
        resolvedName.encodeToByteArray().decodeToString()==resolvedName
    val selected=remember { mutableStateListOf<String>() }
    val accepted=state.contacts.filter { !it.contact.request && !it.contact.blocked }
    val visible=accepted.filter { it.contact.visibleName.contains(query,ignoreCase=true) }
        .sortedWith(compareByDescending<ContactStatus> {groupInviteReady(it)}
            .thenBy {it.contact.visibleName.lowercase()})
    val ready=accepted.filter(::groupInviteReady)
    val maxInvitees=GroupStatements.MAX_MEMBERS-1
    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader("Create group","Select up to four accepted contacts.",back)
        OutlinedTextField(groupName,{groupName=it},
            modifier=Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset)
                .testTag("group-name"),
            label={Text("Group name")},placeholder={Text("Group")},singleLine=true,
            isError=!nameValid,
            supportingText=if(!nameValid) {{Text("Use a name of at most 64 bytes without control characters.")}} else null)
        Spacer(Modifier.height(GhostDimensions.small))
        OutlinedTextField(query,{if(it.length<=64) query=it},
            modifier=Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset).noSensitiveCopyCut(),
            placeholder={Text("Search contacts")},singleLine=true,
            leadingIcon={AppIcon(Glyph.SEARCH)},
            trailingIcon=if(query.isEmpty()) null else {{IconButton(onClick={query=""}) {
                AppIcon(Glyph.CLOSE,"Clear search")
            }}},
            shape=RoundedCornerShape(GhostDimensions.fieldCorner),
            colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Color.Transparent,
                focusedBorderColor=MaterialTheme.colorScheme.primary,
                unfocusedContainerColor=MaterialTheme.colorScheme.surface,
                focusedContainerColor=MaterialTheme.colorScheme.surface))
        Spacer(Modifier.height(GhostDimensions.regular))
        if(ready.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset),
            verticalAlignment=Alignment.CenterVertically) {
            Text("${selected.size} selected",Modifier.weight(1f),
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick={
                selected.clear()
                selected.addAll(visible.filter(::groupInviteReady).take(maxInvitees)
                    .map {it.contact.remoteDeviceId})
            }) {Text("Select all")}
            if(selected.isNotEmpty()) TextButton(onClick={selected.clear()}) {Text("Clear")}
        }
        if(state.error!=null) Box(Modifier.padding(horizontal=GhostLayout.pageInset)) {
            ErrorNotice(state.error,important=state.errorImportant)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding=PaddingValues(start=GhostLayout.pageInset,end=GhostLayout.pageInset,
                bottom=GhostLayout.pageInset),
            verticalArrangement=Arrangement.spacedBy(GhostDimensions.small)) {
            item {
                Text("Everyone selected receives an invitation now. People can accept in any order; each membership is confirmed securely.",
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(accepted.isEmpty()) item {
                Text("Add and accept a contact before creating a group.",
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else if(visible.isEmpty()) item {
                Text("No contacts match your search.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else if(accepted.none(::groupInviteReady)) item {
                Text("No contacts are ready yet. Ask a contact using the updated app to send you a new encrypted message. Older messages cannot confirm group support.",
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(visible,key={it.contact.remoteDeviceId}) {contact ->
                val row:@Composable ()->Unit = {
                    Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(GhostDimensions.regular),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(GhostDimensions.regular)) {
                        Avatar(contact.contact.visibleName,photo=contact.sharedPhoto)
                        Column(Modifier.weight(1f)) {
                            Text(contact.contact.visibleName,style=MaterialTheme.typography.titleMedium)
                            Text(groupInviteReason(contact) ?: "Tap to select",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(groupInviteReady(contact)) Checkbox(
                            checked=contact.contact.remoteDeviceId in selected,onCheckedChange=null)
                        if(!groupInviteReady(contact)) TextButton(onClick={openChat(contact.contact.remoteDeviceId)}) {
                            Text("Open chat")
                        }
                    }
                }
                if(groupInviteReady(contact)) Surface(onClick={
                    val id=contact.contact.remoteDeviceId
                    if(id in selected) selected.remove(id)
                    else if(selected.size<maxInvitees) selected.add(id)
                },
                    enabled=!state.loading,shape=MaterialTheme.shapes.large,
                    color=MaterialTheme.colorScheme.surface) {row()}
                else Surface(shape=MaterialTheme.shapes.large,
                    color=MaterialTheme.colorScheme.surface) {row()}
            }
        }
        Button(onClick={createGroup(resolvedName,selected.toList())},enabled=selected.isNotEmpty() &&
            nameValid && !state.loading && selected.all {id -> ready.any {it.contact.remoteDeviceId==id} },
            modifier=Modifier.fillMaxWidth().padding(GhostLayout.pageInset)) {
            Text("Create group with ${selected.size} ${if(selected.size==1) "contact" else "contacts"}")
        }
    }
}

private fun groupInviteReady(contact:ContactStatus)=contact.groupCapable &&
    contact.session==SessionLifecycle.ACTIVE && contact.identity?.trustState!=IdentityTrustState.CHANGED

private fun groupInviteReason(contact:ContactStatus)=when {
    contact.identity?.trustState==IdentityTrustState.CHANGED -> "Identity changed — review contact security"
    contact.session!=SessionLifecycle.ACTIVE -> "Secure session unavailable"
    !contact.groupCapable -> "Waiting for group support confirmation"
    else -> null
}
