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

/** Select the first member; the canonical membership transition still gates group chat. */
@Composable fun CreateGroupScreen(state:AppState,back:()->Unit,createGroup:(String)->Unit,
    openChat:(String)->Unit) {
    var query by remember { mutableStateOf("") }
    val accepted=state.contacts.filter { !it.contact.request && !it.contact.blocked }
    val visible=accepted.filter { it.contact.visibleName.contains(query,ignoreCase=true) }
        .sortedWith(compareByDescending<ContactStatus> {groupInviteReady(it)}
            .thenBy {it.contact.visibleName.lowercase()})
    Column(Modifier.fillMaxSize().imePadding()) {
        PageHeader("Create group","Choose the first contact to invite.",back)
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
        if(state.error!=null) Box(Modifier.padding(horizontal=GhostLayout.pageInset)) {
            ErrorNotice(state.error,important=state.errorImportant)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding=PaddingValues(start=GhostLayout.pageInset,end=GhostLayout.pageInset,
                bottom=GhostLayout.pageInset),
            verticalArrangement=Arrangement.spacedBy(GhostDimensions.small)) {
            item {
                Text("Add more members after the first invitation is accepted.",
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
                            Text(groupInviteReason(contact) ?: "Tap to invite",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(!groupInviteReady(contact)) TextButton(onClick={openChat(contact.contact.remoteDeviceId)}) {
                            Text("Open chat")
                        }
                    }
                }
                if(groupInviteReady(contact)) Surface(onClick={createGroup(contact.contact.remoteDeviceId)},
                    enabled=!state.loading,shape=MaterialTheme.shapes.large,
                    color=MaterialTheme.colorScheme.surface) {row()}
                else Surface(shape=MaterialTheme.shapes.large,
                    color=MaterialTheme.colorScheme.surface) {row()}
            }
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
