package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.*
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut
import org.ghostcloak.app.ui.theme.GhostDimensions
import org.ghostcloak.app.ui.theme.GhostLayout
import org.ghostcloak.messaging.GroupMembershipTransport
import org.ghostcloak.messaging.GroupLocalStatus
import org.ghostcloak.messaging.GroupRecipientState
import org.ghostcloak.messaging.GroupTextCodec
import org.ghostcloak.identity.IdentityTrustState
import org.ghostcloak.identity.SessionLifecycle

/** P13.3 deliberately exposes only text and sequential invitation management. */
@Composable fun GroupConversationScreen(state:AppState,group:GroupMembershipTransport.Conversation,
    back:()->Unit,send:(String,()->Unit)->Unit,invite:(String)->Unit,openChat:(String)->Unit={},
    acceptInvitation:(String)->Unit={},declineInvitation:(String)->Unit={}) {
    var draft by remember(group.groupId) {mutableStateOf("")}
    var inviting by remember {mutableStateOf(false)}
    val active=group.status==GroupLocalStatus.ACTIVE && group.memberCount>=2
    val sendRestriction=group.sendRestriction
    val invitation=state.groupInvitations.firstOrNull {it.groupId==group.groupId}
    val inviter=invitation?.let {offer -> state.contacts.firstOrNull {
        !it.contact.request && !it.contact.blocked && it.contact.remoteDeviceId==offer.senderDeviceId
    }}
    val candidates=state.contacts.filter {!it.contact.request && !it.contact.blocked &&
        it.contact.remoteDeviceId !in group.memberDevices.values}
    val readyCandidates=candidates.filter {it.groupCapable && it.session==SessionLifecycle.ACTIVE &&
        it.identity?.trustState!=IdentityTrustState.CHANGED}
    Column(Modifier.fillMaxSize().imePadding().testTag("group-page")) {
        PageHeader(if(invitation!=null) "Group invitation" else "Group conversation",
            subtitle=if(invitation!=null) "Not a member yet" else "${group.memberCount} of 5 members",
            back=back,avatarName="Group") {
            if(group.status==GroupLocalStatus.ACTIVE && !group.invitationPending && group.memberCount<5 && candidates.isNotEmpty())
                HeaderAction(Glyph.ADD_CONTACT,"Invite member") {inviting=true}
        }
        if(!active) Column(Modifier.padding(horizontal=GhostLayout.pageInset),
            verticalArrangement=Arrangement.spacedBy(GhostDimensions.compact)) {
            val statusText=when {
                group.status==GroupLocalStatus.FORKED -> "Group state conflict. Create a new group to continue."
                group.status==GroupLocalStatus.DISSOLVED -> "This group is dissolved. History is read-only."
                group.status==GroupLocalStatus.REMOVED || group.status==GroupLocalStatus.LEFT ->
                    "You are no longer in this group. History is read-only."
                invitation?.accepting==true -> "Acceptance sent. Waiting for signed membership confirmation."
                invitation!=null -> "You were invited to this group. Accept to join."
                group.invitationPending -> "Invitation sent. Waiting for the contact to accept."
                else -> "Waiting for confirmed group membership."
            }
            InfoPanel(if(invitation!=null) "Invitation to join" else "Group status",statusText)
            if(inviter!=null) {
                Text("From ${inviter.contact.visibleName}",style=MaterialTheme.typography.bodyMedium)
                Text(if(inviter.identity?.trustState==IdentityTrustState.VERIFIED) "Inviter verified"
                    else "Inviter unverified",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(invitation!=null && !invitation.accepting) Row(verticalAlignment=Alignment.CenterVertically) {
                Button(onClick={acceptInvitation(invitation.id)},enabled=!state.loading) {Text("Accept invitation")}
                TextButton(onClick={declineInvitation(invitation.id)},enabled=!state.loading) {Text("Decline")}
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(
            horizontal=GhostLayout.pageInset,vertical=GhostDimensions.regular),
            verticalArrangement=Arrangement.spacedBy(GhostDimensions.medium)) {
            if(active && group.messages.isEmpty()) item {
                InfoPanel("Your group is ready",
                    "Messages in this group are end-to-end encrypted. Invite more members from the top of this conversation.")
            }
            items(group.messages,key={it.logicalId}) { message ->
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(message.outgoing) Alignment.End else Alignment.Start) {
                    Surface(color=if(message.outgoing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.large) {
                        Column(Modifier.widthIn(max=GhostDimensions.previewWidth).padding(GhostDimensions.medium),
                            verticalArrangement=Arrangement.spacedBy(GhostDimensions.tiny)) {
                            if(!message.outgoing) {
                                val sender=group.memberDevices[message.senderMemberId]
                                Text(state.contacts.firstOrNull {it.contact.remoteDeviceId==sender}?.contact?.visibleName
                                    ?: "Group member",style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.primary)
                            }
                            Text(message.text,color=if(message.outgoing) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if(message.outgoing) {
                        val sent=message.recipients.count {it.state==GroupRecipientState.SENT}
                        val failed=message.recipients.count {it.state==GroupRecipientState.UNAVAILABLE}
                        Text(when {
                            failed>0 -> "Sent to $sent of ${message.recipients.size}; $failed unavailable"
                            sent==message.recipients.size -> "Sent to ${message.recipients.size}"
                            else -> "Sending to $sent of ${message.recipients.size}"
                        },style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if(active && sendRestriction!=null) Surface(color=MaterialTheme.colorScheme.surface) {
            Text(sendRestriction,Modifier.fillMaxWidth().padding(GhostLayout.pageInset),
                style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(active && sendRestriction==null) Surface(color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset,
                vertical=GhostDimensions.controlGap),verticalArrangement=Arrangement.spacedBy(GhostDimensions.compact)) {
                Row(verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(GhostDimensions.controlGap)) {
                    OutlinedTextField(draft,{value ->
                        if(value.encodeToByteArray().size<=GroupTextCodec.MAX_TEXT_BYTES) draft=value
                    },modifier=Modifier.weight(1f).testTag("group-composer").noSensitiveCopyCut(),
                        placeholder={Text("Message group")},minLines=1,maxLines=5,
                        colors=OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor=MaterialTheme.colorScheme.surface,
                            focusedContainerColor=MaterialTheme.colorScheme.surface),
                        shape=RoundedCornerShape(GhostDimensions.spacious))
                    FilledIconButton(onClick={val text=draft;send(text) {draft=""}},
                        enabled=draft.isNotBlank() && !state.loading,
                        modifier=Modifier.size(GhostDimensions.avatar)) {AppIcon(Glyph.SEND,"Send")}
                }
                Text("End-to-end encrypted",Modifier.padding(start=GhostDimensions.controlGap,
                    bottom=GhostDimensions.medium),style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if(inviting) AlertDialog(onDismissRequest={inviting=false},title={Text("Invite one contact")},
        text={Column {
            Text("Add members one at a time after each membership is confirmed.")
            if(readyCandidates.isEmpty()) Text(
                "No other contacts are ready yet. Group invites require an active, unchanged identity and a recent group-support message from the updated app.",
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max=320.dp)) {items(candidates,key={it.contact.remoteDeviceId}) {candidate ->
                val ready=candidate in readyCandidates
                val reason=when {
                    candidate.identity?.trustState==IdentityTrustState.CHANGED -> "Identity changed — review contact security"
                    candidate.session!=SessionLifecycle.ACTIVE -> "Secure session unavailable"
                    !candidate.groupCapable -> "Waiting for group support confirmation"
                    else -> null
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(candidate.contact.visibleName)
                        if(reason!=null) Text(reason,style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick={
                        inviting=false
                        if(ready) invite(candidate.contact.remoteDeviceId)
                        else openChat(candidate.contact.remoteDeviceId)
                    }) {Text(if(ready) "Invite" else "Open chat")}
                }
            }}
        }},confirmButton={TextButton(onClick={inviting=false}) {Text("Close")}})
}
