package org.ghostcloak.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import org.ghostcloak.messaging.GroupModerationState
import org.ghostcloak.identity.IdentityTrustState
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.app.application.GhostApplication
import org.ghostcloak.messaging.GroupChatMessage
import org.ghostcloak.messaging.DownloadPreference

/** P13.3 deliberately exposes only text and sequential invitation management. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GroupConversationScreen(state:AppState,group:GroupMembershipTransport.Conversation,
    back:()->Unit,send:(String,()->Unit)->Unit,invite:(String)->Unit,openChat:(String)->Unit={},
    acceptInvitation:(String)->Unit={},declineInvitation:(String)->Unit={},openInfo:()->Unit={},
    removeMessage:(String)->Unit={},
    sendReply:(String,String,()->Unit)->Unit={_,_,_->},
    react:(String,String?)->Unit={_,_->},
    editOwn:(String,String,()->Unit)->Unit={_,_,_->},
    deleteOwn:(String)->Unit={},refreshMedia:()->Unit={}) {
    var draft by remember(group.groupId) {mutableStateOf("")}
    var inviting by remember {mutableStateOf(false)}
    var selectedMessage by remember(group.groupId) {mutableStateOf<String?>(null)}
    var confirmingRemoval by remember(group.groupId) {mutableStateOf<String?>(null)}
    var confirmingSenderDelete by remember(group.groupId) {mutableStateOf<String?>(null)}
    var replyingTo by remember(group.groupId) {mutableStateOf<String?>(null)}
    var editing by remember(group.groupId) {mutableStateOf<String?>(null)}
    val canModerate=group.info?.canModerate==true
    val canUseControls=group.info?.canUseMessageControls==true
    val canSend=group.status==GroupLocalStatus.ACTIVE && group.sendRestriction==null
    if(selectedMessage!=null && !canModerate && !canUseControls) selectedMessage=null
    if(confirmingRemoval!=null && !canModerate) confirmingRemoval=null
    if(confirmingSenderDelete!=null && !canUseControls) confirmingSenderDelete=null
    if(selectedMessage!=null) ModalBottomSheet(onDismissRequest={selectedMessage=null}) {
        val target=group.messages.firstOrNull {it.logicalId==selectedMessage}
        if(target!=null && target.moderationState==GroupModerationState.NONE) {
            if(canUseControls && canSend) TextButton(onClick={
                replyingTo=target.logicalId;editing=null;selectedMessage=null
            },modifier=Modifier.fillMaxWidth().testTag("group-reply-action")) {Text("Reply")}
            if(canUseControls && (!target.outgoing ||
                target.recipients.all {it.state==GroupRecipientState.SENT}))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                listOf("👍","❤️","😂","😮","😢","🙏").forEach {emoji ->
                    TextButton(onClick={
                        val mine=target.reactions.any {it.emoji==emoji && it.mine}
                        react(target.logicalId,if(mine) null else emoji)
                        selectedMessage=null
                    }) {Text(emoji)}
                }
            }
            if(canUseControls && target.outgoing && target.mediaKind==null &&
                target.recipients.all {it.state==GroupRecipientState.SENT}) {
                TextButton(onClick={editing=target.logicalId;replyingTo=null;draft=target.text
                    selectedMessage=null},modifier=Modifier.fillMaxWidth().testTag("group-edit-action")) {
                    Text("Edit")
                }
            }
            if(canUseControls && target.outgoing &&
                target.recipients.all {it.state==GroupRecipientState.SENT})
                TextButton(onClick={confirmingSenderDelete=target.logicalId;selectedMessage=null},
                    modifier=Modifier.fillMaxWidth().testTag("group-delete-action")) {
                    Text("Delete for everyone")
                }
            if(canModerate) TextButton(onClick={
                confirmingRemoval=target.logicalId;selectedMessage=null
            },modifier=Modifier.fillMaxWidth().testTag("group-remove-message-action")) {
                Text("Remove message")
            }
        }
        Spacer(Modifier.height(GhostDimensions.medium))
    }
    if(confirmingSenderDelete!=null) AlertDialog(onDismissRequest={confirmingSenderDelete=null},
        title={Text("Delete this message?")},
        text={Text("It will be replaced with a deletion notice on updated group members' devices. Copies already seen or saved cannot be recalled.")},
        confirmButton={TextButton(onClick={
            val id=confirmingSenderDelete;confirmingSenderDelete=null
            if(canUseControls && id!=null) deleteOwn(id)
        }) {Text("Delete for everyone")}},
        dismissButton={TextButton(onClick={confirmingSenderDelete=null}) {Text("Cancel")}})
    if(confirmingRemoval!=null) AlertDialog(onDismissRequest={confirmingRemoval=null},
        title={Text("Remove this message?")},
        text={Text("It will be replaced with a moderation notice on updated group members' devices. Copies already seen or saved cannot be recalled.")},
        confirmButton={TextButton(onClick={
            val id=confirmingRemoval
            confirmingRemoval=null
            if(canModerate && id!=null) removeMessage(id)
        }) {Text("Remove message")}},
        dismissButton={TextButton(onClick={confirmingRemoval=null}) {Text("Cancel")}})
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
    BackHandler(inviting) {inviting=false}
    if(inviting) {
        var query by remember(group.groupId) {mutableStateOf("")}
        Column(Modifier.fillMaxSize().imePadding().testTag("group-invite-page")) {
            PageHeader("Invite member",back={inviting=false})
            OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth().padding(
                horizontal=GhostLayout.pageInset),placeholder={Text("Search contacts")},singleLine=true)
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(GhostLayout.pageInset),
                verticalArrangement=Arrangement.spacedBy(GhostDimensions.medium)) {
                item {Text("Add members one at a time after each membership is confirmed.",
                    color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(readyCandidates.isEmpty()) item {Text(
                    "No other contacts are ready yet. Group invites require an active, unchanged identity and a recent group-support message from the updated app.",
                    color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(candidates.filter {it.contact.visibleName.contains(query,ignoreCase=true)},
                    key={it.contact.remoteDeviceId}) {candidate ->
                    val ready=candidate in readyCandidates
                    val reason=when {
                        candidate.identity?.trustState==IdentityTrustState.CHANGED -> "Identity changed — review contact security"
                        candidate.session!=SessionLifecycle.ACTIVE -> "Secure session unavailable"
                        !candidate.groupCapable -> "Waiting for group support confirmation"
                        else -> null
                    }
                    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(GhostDimensions.medium),
                            verticalAlignment=Alignment.CenterVertically) {
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
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("group-page")) {
        PageHeader(if(invitation!=null) "Group invitation" else "Group conversation",
            back=back,avatarName="Group",leading={
                Column(Modifier.clickable(enabled=invitation==null && group.info!=null) {openInfo()}
                    .testTag("group-info-entry")) {
                    Text(if(invitation!=null) "Group invitation" else "Group conversation",
                        style=MaterialTheme.typography.titleMedium)
                    Text(if(invitation!=null) "Not a member yet" else "${group.memberCount} of 5 members · Group info",
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }) {
            if(group.info?.canInvite==true && candidates.isNotEmpty())
                HeaderAction(Glyph.ADD_CONTACT,"Invite member") {inviting=true}
        }
        if(group.info?.ready==true && state.groupOwnershipRequests.any {it.groupId==group.groupId})
            Surface(color=MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset),
                    verticalAlignment=Alignment.CenterVertically) {
                    Text("Group ownership request",Modifier.weight(1f),
                        style=MaterialTheme.typography.bodyMedium)
                    TextButton(onClick=openInfo) {Text("Review")}
                }
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
                    Surface(modifier=Modifier.combinedClickable(onClick={},onLongClick={
                        if((canModerate || canUseControls) &&
                            message.moderationState==GroupModerationState.NONE)
                            selectedMessage=message.logicalId
                    }),color=if(message.outgoing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.large) {
                        Column(Modifier.widthIn(max=GhostDimensions.previewWidth).padding(GhostDimensions.medium),
                            verticalArrangement=Arrangement.spacedBy(GhostDimensions.tiny)) {
                            if(!message.outgoing) {
                                val sender=group.memberDevices[message.senderMemberId]
                                Text(state.contacts.firstOrNull {it.contact.remoteDeviceId==sender}?.contact?.visibleName
                                    ?: "Group member",style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.primary)
                            }
                            if(message.replyToLogicalId!=null) {
                                val original=group.messages.firstOrNull {
                                    it.logicalId==message.replyToLogicalId
                                }
                                val preview=when(original?.moderationState) {
                                    GroupModerationState.REMOVED_BY_ADMIN -> "Message removed by an admin"
                                    GroupModerationState.DELETED_BY_SENDER -> "Original message deleted"
                                    GroupModerationState.NONE -> if(original.mediaKind!=null)
                                        groupMediaLabel(original) + original.mediaCaption?.takeIf {it.isNotBlank()}
                                            ?.let {": ${it.take(60)}"}.orEmpty()
                                        else original.text.take(80)
                                    null -> "Original message unavailable"
                                }
                                val label=if(original?.outgoing==true) "You" else
                                    original?.senderMemberId?.let {memberId ->
                                        group.memberDevices[memberId]?.let {device ->
                                            state.contacts.firstOrNull {it.contact.remoteDeviceId==device}
                                                ?.contact?.visibleName
                                        }
                                    } ?: "Group member"
                                Text("$label: $preview",style=MaterialTheme.typography.labelSmall,
                                    color=if(message.outgoing) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(message.moderationState==GroupModerationState.NONE && message.mediaKind!=null)
                                GroupMediaContent(group.groupId,message,active)
                            else Text(if(message.moderationState==GroupModerationState.REMOVED_BY_ADMIN)
                                "Message removed by an admin" else if(message.moderationState==
                                GroupModerationState.DELETED_BY_SENDER) "This message was deleted" else message.text,
                                color=if(message.outgoing) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface)
                            if(message.moderationState==GroupModerationState.NONE && message.editRevision>0)
                                Text("Edited",style=MaterialTheme.typography.labelSmall,
                                    color=if(message.outgoing) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(message.moderationState==GroupModerationState.NONE && message.reactions.isNotEmpty())
                        Row(horizontalArrangement=Arrangement.spacedBy(GhostDimensions.tiny)) {
                            message.reactions.forEach {badge ->
                                Text("${badge.emoji} ${badge.count}",style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    if(message.outgoing && message.moderationState==GroupModerationState.NONE) {
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
        if(active) SendingPhoto(group.groupId,refreshMedia)
        if(active && sendRestriction!=null) Surface(color=MaterialTheme.colorScheme.surface) {
            Text(sendRestriction,Modifier.fillMaxWidth().padding(GhostLayout.pageInset),
                style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(!active || sendRestriction!=null)
            AttachmentComposer(group.groupId,true,group=true,showButton=false,refresh=refreshMedia)
        if(active && group.info?.ready==true && group.info?.messageControlsCapable==false)
            Text("Group message controls become available after everyone updates Ghost Cloak.",
                Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset),
                style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(active && sendRestriction==null) Surface(color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset,
                vertical=GhostDimensions.controlGap),verticalArrangement=Arrangement.spacedBy(GhostDimensions.compact)) {
                if(replyingTo!=null || editing!=null) Row(Modifier.fillMaxWidth(),
                    verticalAlignment=Alignment.CenterVertically) {
                    val target=group.messages.firstOrNull {it.logicalId==(editing ?: replyingTo)}
                    Text(if(editing!=null) "Editing message" else "Replying to ${if(target?.outgoing==true) "You" else "group member"}: ${target?.text?.take(60) ?: "Original message unavailable"}",
                        Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={replyingTo=null;editing=null;draft=""}) {Text("Cancel")}
                }
                Row(verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(GhostDimensions.controlGap)) {
                    AttachmentComposer(group.groupId,editing==null && !state.loading,
                        group=true,showButton=editing==null && state.networkConfigured && !state.demo &&
                            group.info?.canUseMedia==true,refresh=refreshMedia)
                    OutlinedTextField(draft,{value ->
                        if(value.encodeToByteArray().size<=GroupTextCodec.MAX_TEXT_BYTES) draft=value
                    },modifier=Modifier.weight(1f).testTag("group-composer").noSensitiveCopyCut(),
                        placeholder={Text("Message group")},minLines=1,maxLines=5,
                        colors=OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor=MaterialTheme.colorScheme.surface,
                            focusedContainerColor=MaterialTheme.colorScheme.surface),
                        shape=RoundedCornerShape(GhostDimensions.spacious))
                    FilledIconButton(onClick={
                        val text=draft
                        val editId=editing
                        val replyId=replyingTo
                        val done={draft="";editing=null;replyingTo=null}
                        when {
                            editId!=null -> editOwn(editId,text,done)
                            replyId!=null -> sendReply(text,replyId,done)
                            else -> send(text,done)
                        }
                    },
                        enabled=draft.isNotBlank() && !state.loading,
                        modifier=Modifier.size(GhostDimensions.avatar)) {AppIcon(Glyph.SEND,"Send")}
                }
                Text("End-to-end encrypted",Modifier.padding(start=GhostDimensions.controlGap,
                    bottom=GhostDimensions.medium),style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(active && sendRestriction==null && group.info?.ready==true &&
            group.info?.mediaCapable==false)
            Text("Group media becomes available after everyone updates Ghost Cloak.",
                Modifier.fillMaxWidth().padding(horizontal=GhostLayout.pageInset),
                style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun groupMediaLabel(message:GroupChatMessage):String=when(message.mediaKind) {
    AttachmentKind.IMAGE -> "Photo"
    AttachmentKind.DOCUMENT -> "Document"
    AttachmentKind.VOICE_NOTE -> "Voice note"
    else -> "Attachment"
}

@Composable private fun GroupMediaContent(groupId:String,message:GroupChatMessage,enabled:Boolean) {
    val owner=(LocalContext.current.applicationContext as GhostApplication).media
    val kind=message.mediaKind ?: return
    Column(verticalArrangement=Arrangement.spacedBy(GhostDimensions.tiny)) {
        Text(groupMediaLabel(message))
        if(kind==AttachmentKind.DOCUMENT)
            Text(message.mediaFilename ?: "Document",style=MaterialTheme.typography.bodyMedium)
        if(message.mediaBytes>0)
            Text("${(message.mediaBytes+1023)/1024} KiB",style=MaterialTheme.typography.labelSmall)
        val duration=message.mediaDurationMillis
        if(kind==AttachmentKind.VOICE_NOTE && duration!=null) {
            val seconds=duration/1000
            Text("${seconds/60}:${(seconds%60).toString().padStart(2,'0')}",
                style=MaterialTheme.typography.labelSmall)
        }
        message.mediaCaption?.takeIf {it.isNotBlank()}?.let {Text(it)}
        if(enabled) when(kind) {
            AttachmentKind.VOICE_NOTE -> {
                val playback by owner.voicePlayback.collectAsState()
                val current=playback.conversation==groupId && playback.message==message.logicalId
                TextButton(onClick={owner.toggleVoicePlayback(groupId,message.logicalId)}) {
                    Text(if(current && playback.playing) "Pause" else "Play voice note")
                }
                if(current && playback.error) Text("Voice note unavailable")
            }
            AttachmentKind.IMAGE,AttachmentKind.DOCUMENT ->
                if(kind==AttachmentKind.IMAGE)
                    GroupInlinePhoto(groupId,message.logicalId,enabled)
                else TextButton(onClick={owner.download(groupId,message.logicalId,
                    false,message.mediaFilename ?: "Document")}) {Text("Download / open")}
            else -> Unit
        }
    }
}

@Composable private fun GroupInlinePhoto(groupId:String,messageId:String,enabled:Boolean) {
    val app=LocalContext.current.applicationContext as GhostApplication
    val owner=app.media
    val photos by owner.photos.state.collectAsState()
    val session by owner.presentationSession.collectAsState()
    var automatic by remember(groupId,messageId) {mutableStateOf(false)}
    var requested by remember(groupId,messageId) {mutableStateOf(false)}
    LaunchedEffect(groupId,messageId,enabled) {
        automatic=enabled && runCatching {
            app.runtime.privacyDefaults().photos==DownloadPreference.AUTOMATIC
        }.getOrDefault(false)
    }
    val load=enabled && (automatic || requested)
    DisposableEffect(groupId,messageId,load,session) {
        if(load) owner.photos.request(groupId,messageId,true,true)
        onDispose {owner.photos.release(groupId,messageId)}
    }
    if(!load) TextButton(onClick={requested=true}) {Text("Load photo")}
    else InlinePhotoContent(photos[groupId to messageId],enabled,
        retry={owner.photos.request(groupId,messageId,true,enabled,retry=true)},
        open={owner.download(groupId,messageId,true,"Photo")})
}
