package org.ghostcloak.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut
import org.ghostcloak.messaging.GroupMembershipTransport
import org.ghostcloak.messaging.GroupLocalStatus
import org.ghostcloak.messaging.GroupRecipientState
import org.ghostcloak.messaging.GroupTextCodec

/** P13.3 deliberately exposes only text and sequential invitation management. */
@Composable fun GroupConversationScreen(state:AppState,group:GroupMembershipTransport.Conversation,
    back:()->Unit,send:(String,()->Unit)->Unit,invite:(String)->Unit) {
    var draft by remember(group.groupId) {mutableStateOf("")}
    var inviting by remember {mutableStateOf(false)}
    val active=group.status==GroupLocalStatus.ACTIVE && group.memberCount>=2
    val candidates=state.contacts.filter {it.groupCapable && !it.contact.request && !it.contact.blocked &&
        it.contact.remoteDeviceId !in group.memberDevices.values}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=back) {Text("Back")}
            Column(Modifier.weight(1f).padding(start=8.dp)) {
                Text("Group conversation",style=MaterialTheme.typography.titleLarge)
                Text("${group.memberCount} of 5 members",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(group.status==GroupLocalStatus.ACTIVE && !group.invitationPending && group.memberCount<5 && candidates.isNotEmpty())
                TextButton(onClick={inviting=true}) {Text("Invite")}
        }
        if(!active) Text(when(group.status) {
            GroupLocalStatus.FORKED -> "Group state conflict. Create a new group to continue."
            GroupLocalStatus.DISSOLVED -> "This group is dissolved. History is read-only."
            GroupLocalStatus.REMOVED,GroupLocalStatus.LEFT -> "You are no longer in this group. History is read-only."
            else -> "Waiting for confirmed group membership."
        },Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(group.messages,key={it.logicalId}) { message ->
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(message.outgoing) Alignment.End else Alignment.Start) {
                    Surface(color=if(message.outgoing) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.large) {
                        Column(Modifier.widthIn(max=320.dp).padding(12.dp)) {
                            if(!message.outgoing) {
                                val sender=group.memberDevices[message.senderMemberId]
                                Text(state.contacts.firstOrNull {it.contact.remoteDeviceId==sender}?.contact?.visibleName
                                    ?: "Group member",style=MaterialTheme.typography.labelSmall)
                            }
                            Text(message.text)
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
        if(active) Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.Bottom) {
            OutlinedTextField(draft,{value ->
                if(value.encodeToByteArray().size<=GroupTextCodec.MAX_TEXT_BYTES) draft=value
            },modifier=Modifier.weight(1f).noSensitiveCopyCut(),placeholder={Text("Message group")},
                minLines=1,maxLines=5)
            TextButton(onClick={val text=draft;send(text) {draft=""}},enabled=draft.isNotBlank() && !state.loading) {
                Text("Send")
            }
        }
    }
    if(inviting) AlertDialog(onDismissRequest={inviting=false},title={Text("Invite one contact")},
        text={Column {
            Text("Add members one at a time after each membership is confirmed.")
            candidates.forEach {candidate ->
                TextButton(onClick={inviting=false;invite(candidate.contact.remoteDeviceId)}) {
                    Text(candidate.contact.visibleName)
                }
            }
        }},confirmButton={TextButton(onClick={inviting=false}) {Text("Close")}})
}
