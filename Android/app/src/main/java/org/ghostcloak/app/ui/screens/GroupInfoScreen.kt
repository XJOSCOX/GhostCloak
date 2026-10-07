package org.ghostcloak.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostcloak.app.attachments.PhotoPreparation
import org.ghostcloak.app.attachments.ProfilePhotoPreparation
import org.ghostcloak.app.ui.components.Avatar
import org.ghostcloak.app.ui.privacy.noSensitiveCopyCut
import java.security.MessageDigest
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.PageHeader
import org.ghostcloak.app.ui.components.ErrorNotice
import org.ghostcloak.app.ui.theme.GhostLayout
import org.ghostcloak.messaging.*
import org.ghostcloak.identity.IdentityTrustState
import org.ghostcloak.identity.SessionLifecycle

class GroupInfoActions(
    val setup:()->Unit, val retrySetup:()->Unit, val posting:(GroupPostingModeV1)->Unit,
    val restrict:(String)->Unit, val unrestrict:(String)->Unit,
    val remove:(String)->Unit, val promote:(String)->Unit, val demote:(String)->Unit,
    val transfer:(String)->Unit, val transferDecision:(Boolean)->Unit,
    val leave:()->Unit, val dissolve:()->Unit, val invite:(String)->Unit,
    val openChat:(String)->Unit,
    val saveProfile:(ByteArray,String,String,GroupProfilePhotoRefV1?,ByteArray?)->Unit={_,_,_,_,_->},
)

private enum class MemberAction { RESTRICT, UNRESTRICT, REMOVE, PROMOTE, DEMOTE, TRANSFER }
private enum class GroupInfoPage { OVERVIEW, MEMBER, CONFIRM_MEMBER, POSTING, INVITE, CONFIRM_LEAVE, CONFIRM_END, EDIT_PROFILE }
private fun GroupInfoMember.label(state:AppState):String = if(isLocal) "You" else
    state.contacts.firstOrNull {it.contact.remoteDeviceId==deviceId &&
        !it.contact.request}?.contact?.visibleName ?: "Group member"
private fun GroupRole.label()=when(this) {
    GroupRole.OWNER -> "Owner"
    GroupRole.ADMIN -> "Admin"
    GroupRole.MEMBER -> "Member"
}
private fun GroupManagementStatus.description()=when(this) {
    GroupManagementStatus.NOT_CONFIGURED -> "Group management isn't enabled yet."
    GroupManagementStatus.SETTING_UP_BASELINE -> "Preparing secure group management…"
    GroupManagementStatus.BASELINE_READY -> "Member checks are complete. Continue setup to enable group management."
    GroupManagementStatus.ACTIVATING -> "Waiting for all members to update group security…"
    GroupManagementStatus.READY -> "Group management is ready."
    GroupManagementStatus.NEEDS_RESYNC -> "Syncing secure group state…"
    GroupManagementStatus.FORKED -> "Group state conflict. Create a new group to continue."
    GroupManagementStatus.LEGACY_INCOMPLETE ->
        "This group uses an earlier development version of group management. Create a new group to use management features."
}

/** IDs are used only for callbacks. No cryptographic identifiers become on-screen labels. */
@Composable fun GroupInfoScreen(state:AppState,info:GroupInfo,back:()->Unit,actions:GroupInfoActions) {
    var page by remember(info.groupId) {mutableStateOf(GroupInfoPage.OVERVIEW)}
    var selectedMemberId by remember(info.groupId) {mutableStateOf<String?>(null)}
    var selectedAction by remember(info.groupId) {mutableStateOf<MemberAction?>(null)}
    var draftName by remember(info.groupId) {mutableStateOf("")}
    var draftAbout by remember(info.groupId) {mutableStateOf("")}
    var draftHead by remember(info.groupId) {mutableStateOf<ByteArray?>(null)}
    var draftPhoto by remember(info.groupId) {mutableStateOf<ByteArray?>(null)}
    var removePhoto by remember(info.groupId) {mutableStateOf(false)}
    var photoError by remember(info.groupId) {mutableStateOf<String?>(null)}
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri ->
        if(uri!=null) scope.launch {
            photoError=null
            try {
                val bytes=withContext(Dispatchers.IO) {
                    val scratch=ProfilePhotoPreparation.newScratch(context.noBackupFilesDir)
                    try {
                        context.contentResolver.openInputStream(uri)?.use {input ->
                            scratch.outputStream().use {output ->
                                PhotoPreparation.boundedCopy(input,output,PhotoPreparation.SOURCE_CAP)
                            }
                        } ?: throw IllegalArgumentException("Unable to read photo")
                        ProfilePhotoPreparation.prepare(scratch)
                    } finally {scratch.delete()}
                }
                draftPhoto?.fill(0)
                draftPhoto=bytes
                removePhoto=false
            } catch(_:Exception) {photoError="This photo could not be prepared for the group."}
        }
    }
    val busy=state.loading || info.pending
    val selected=info.members.firstOrNull {it.memberId==selectedMemberId}
    val goBack:()->Unit={
        if(page==GroupInfoPage.EDIT_PROFILE) {
            draftPhoto?.fill(0);draftPhoto=null;draftHead=null;photoError=null
        }
        page=when(page) {
            GroupInfoPage.OVERVIEW -> {back();GroupInfoPage.OVERVIEW}
            GroupInfoPage.CONFIRM_MEMBER -> GroupInfoPage.MEMBER
            else -> GroupInfoPage.OVERVIEW
        }
    }
    BackHandler(page!=GroupInfoPage.OVERVIEW) {goBack()}
    if(page==GroupInfoPage.EDIT_PROFILE) {
        val preview=if(removePhoto) null else draftPhoto ?: info.verifiedPhoto
        val invalidName=draftName.trim().let {it.isEmpty() || it.encodeToByteArray().size>64 ||
            it.any {c -> Character.isISOControl(c) || c=='\u061c' || c in '\u200e'..'\u200f' ||
                c in '\u202a'..'\u202e' ||
                c in '\u2066'..'\u2069'}} ||
            draftName.any {Character.isISOControl(it)}
        val invalidAbout=draftAbout.trim().let {it.encodeToByteArray().size>256 ||
            it.any {c -> Character.isISOControl(c) || c=='\u061c' || c in '\u200e'..'\u200f' ||
                c in '\u202a'..'\u202e' ||
                c in '\u2066'..'\u2069'}} ||
            draftAbout.any {Character.isISOControl(it)}
        Column(Modifier.fillMaxSize().imePadding().testTag("group-profile-edit")) {
            PageHeader("Edit group",back=goBack)
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(
                horizontal=GhostLayout.pageInset,vertical=16.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item {Avatar(draftName.ifBlank {"Group"},Modifier.size(88.dp),preview)}
                item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={picker.launch("image/*")},enabled=!busy) {Text("Change photo")}
                    if(preview!=null || info.profile.photo!=null) TextButton(onClick={
                        draftPhoto?.fill(0);draftPhoto=null;removePhoto=true
                    },enabled=!busy) {Text("Remove photo")}
                }}
                if(photoError!=null) item {Text(checkNotNull(photoError),
                    color=MaterialTheme.colorScheme.error)}
                item {OutlinedTextField(draftName,{draftName=it},label={Text("Group name")},
                    isError=invalidName,singleLine=true,
                    modifier=Modifier.fillMaxWidth().noSensitiveCopyCut().testTag("group-name"))}
                item {OutlinedTextField(draftAbout,{draftAbout=it},label={Text("About")},
                    isError=invalidAbout,maxLines=4,
                    modifier=Modifier.fillMaxWidth().noSensitiveCopyCut().testTag("group-about"))}
                if(info.governanceHeadDigest?.contentEquals(draftHead ?: byteArrayOf())!=true)
                    item {Text("Group state changed. Go back and reopen Edit group to use the latest profile.",
                        color=MaterialTheme.colorScheme.error)}
                if(state.error!=null) item {ErrorNotice(state.error,important=state.errorImportant)}
                item {Button(onClick={
                    val bytes=draftPhoto?.copyOf()
                    val ref=when {
                        removePhoto -> null
                        bytes!=null -> GroupProfilePhotoRefV1(digest=MessageDigest.getInstance("SHA-256")
                            .digest(bytes),length=bytes.size)
                        else -> info.profile.photo
                    }
                    actions.saveProfile(checkNotNull(draftHead).copyOf(),draftName,draftAbout,ref,bytes)
                    draftPhoto?.fill(0);draftPhoto=null;draftHead=null;page=GroupInfoPage.OVERVIEW
                },enabled=!busy && info.canEditProfile && !invalidName && !invalidAbout &&
                    info.governanceHeadDigest?.contentEquals(draftHead ?: byteArrayOf())==true,
                    modifier=Modifier.fillMaxWidth().testTag("group-profile-save")) {Text("Save")}}
                item {OutlinedButton(onClick=goBack,modifier=Modifier.fillMaxWidth()) {Text("Cancel")}}
            }
        }
        return
    }
    val transferRequest=state.groupOwnershipRequests.firstOrNull {it.groupId==info.groupId}
    if(page!=GroupInfoPage.OVERVIEW) {
        GroupInfoDetailPage(state,info,page,selected,selectedAction,busy,goBack,
            onPage={page=it},onAction={selectedAction=it;page=GroupInfoPage.CONFIRM_MEMBER},
            actions=actions)
        return
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("group-info")) {
        PageHeader(info.profile.name,"${info.members.size} members",back,
            avatarName=info.profile.name,avatarPhoto=info.verifiedPhoto)
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(
            horizontal=GhostLayout.pageInset,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(state.error!=null) item {ErrorNotice(state.error,important=state.errorImportant)}
            if(info.profile.about.isNotBlank()) item {
                Text(info.profile.about,style=MaterialTheme.typography.bodyMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(info.photoPending) item {Text("Updating group photo…",
                color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(info.canEditProfile) item {OutlinedButton(onClick={
                draftName=info.profile.name;draftAbout=info.profile.about
                draftHead=info.governanceHeadDigest?.copyOf()
                draftPhoto?.fill(0);draftPhoto=null;removePhoto=false;photoError=null
                page=GroupInfoPage.EDIT_PROFILE
            },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("edit-group")) {Text("Edit group")}}
            if(info.ready && !info.profileCapable) item {Text(
                "Group profile editing becomes available after everyone updates Ghost Cloak.",
                color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item {
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Group security",style=MaterialTheme.typography.titleMedium)
                        Text(if(info.managementStatus==GroupManagementStatus.SETTING_UP_BASELINE)
                            if(info.waitingForMemberCheck)
                                "Waiting for another member to confirm group support. Open the updated app and Sync on both devices."
                            else "Waiting for the other member's signed group-security approval. Sync both devices."
                            else info.managementStatus.description(),color=MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier=Modifier.testTag("group-management-status"))
                        if(info.managementStatus==GroupManagementStatus.SETTING_UP_BASELINE &&
                            info.members.firstOrNull {it.isLocal}?.isCoordinator==true && info.active)
                            OutlinedButton(onClick=actions.retrySetup,enabled=!busy,
                                modifier=Modifier.testTag("group-setup-retry")) {Text("Retry member check")}
                        if(info.ready && !info.capable) Text("Waiting for updated group members.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(info.pending) Text("Group update pending…",color=MaterialTheme.colorScheme.primary)
                        if(info.journalFull) Text(
                            "Group management history is full. Create a new group to make additional management changes.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(info.invitationPending && info.managementStatus in setOf(
                                GroupManagementStatus.NOT_CONFIGURED,GroupManagementStatus.BASELINE_READY))
                            Text("Finish the pending invitation before setting up group management.",
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(info.managementStatus in setOf(GroupManagementStatus.NOT_CONFIGURED,
                                GroupManagementStatus.BASELINE_READY) && !info.invitationPending &&
                            info.localRole in setOf(GroupRole.OWNER,GroupRole.ADMIN) &&
                            info.members.firstOrNull {it.isLocal}?.isCoordinator==true && info.active)
                            Button(onClick=actions.setup,enabled=!busy,modifier=Modifier.testTag("group-setup")) {
                                Text(if(info.managementStatus==GroupManagementStatus.BASELINE_READY)
                                    "Continue group setup" else "Enable group management")
                            }
                    }
                }
            }
            if(transferRequest!=null && info.ready) item {
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val owner=info.members.firstOrNull {it.role==GroupRole.OWNER}
                        Text("Ownership request",style=MaterialTheme.typography.titleMedium)
                        Text("${owner?.label(state) ?: "The owner"} wants to make you the group owner. Accepting gives you control of owner-only settings.")
                        Row {
                            Button(onClick={actions.transferDecision(true)},enabled=!busy,
                                modifier=Modifier.testTag("accept-ownership")) {Text("Accept")}
                            TextButton(onClick={actions.transferDecision(false)},enabled=!busy,
                                modifier=Modifier.testTag("decline-ownership")) {Text("Decline")}
                        }
                    }
                }
            }
            if(info.ready) item {
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().clickable(enabled=info.canChangePosting && !state.loading) {
                        page=GroupInfoPage.POSTING
                    }.padding(16.dp).testTag("group-posting-mode"),
                        verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Who can send messages",style=MaterialTheme.typography.titleMedium)
                            Text(if(info.postingMode==GroupPostingModeV1.EVERYONE) "Everyone" else "Admins only",
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(info.canChangePosting) Text("Change",color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {Text("Members",style=MaterialTheme.typography.titleMedium)}
            items(info.members,key={it.memberId}) {member ->
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().clickable {selectedMemberId=member.memberId;page=GroupInfoPage.MEMBER}
                        .padding(16.dp).testTag("group-member-${if(member.isLocal) "you" else member.role.label().lowercase()}"),
                        verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(member.label(state),style=MaterialTheme.typography.bodyLarge)
                            Text(buildString {
                                append(member.role.label())
                                if(member.restricted) append(" · Restricted from sending")
                            },color=MaterialTheme.colorScheme.onSurfaceVariant,
                                style=MaterialTheme.typography.bodySmall)
                        }
                        if(!member.isLocal) Text("Options",color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if(info.canInvite) item {
                OutlinedButton(onClick={page=GroupInfoPage.INVITE},enabled=!busy,
                    modifier=Modifier.fillMaxWidth().testTag("group-add-member")) {Text("Add member")}
            }
            if(info.ready) item {
                when {
                    info.localRole==GroupRole.OWNER -> {
                        Text("Transfer ownership before leaving this group.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(info.canDissolve) TextButton(onClick={page=GroupInfoPage.CONFIRM_END},enabled=!busy,
                            modifier=Modifier.testTag("group-end")) {Text("End group")}
                    }
                    info.members.firstOrNull {it.isLocal}?.isCoordinator==true ->
                        Text("The group coordinator must change before you can leave.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    info.canLeave -> TextButton(onClick={page=GroupInfoPage.CONFIRM_LEAVE},enabled=!busy,
                        modifier=Modifier.testTag("group-leave")) {Text("Leave group")}
                }
            }
        }
    }
}

@Composable private fun GroupInfoDetailPage(state:AppState,info:GroupInfo,page:GroupInfoPage,
    member:GroupInfoMember?,action:MemberAction?,busy:Boolean,back:()->Unit,
    onPage:(GroupInfoPage)->Unit,onAction:(MemberAction)->Unit,actions:GroupInfoActions) {
    val title=when(page) {
        GroupInfoPage.MEMBER -> member?.label(state) ?: "Group member"
        GroupInfoPage.CONFIRM_MEMBER -> "Confirm change"
        GroupInfoPage.POSTING -> "Who can send messages"
        GroupInfoPage.INVITE -> "Add member"
        GroupInfoPage.CONFIRM_LEAVE -> "Leave group"
        GroupInfoPage.CONFIRM_END -> "End group"
        GroupInfoPage.EDIT_PROFILE -> "Edit group"
        GroupInfoPage.OVERVIEW -> "Group info"
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("group-info-detail")) {
        PageHeader(title,back=back)
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(
            horizontal=GhostLayout.pageInset,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(state.error!=null) item {ErrorNotice(state.error,important=state.errorImportant)}
            when(page) {
                GroupInfoPage.MEMBER -> if(member!=null) {
                    item {
                        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(member.label(state),style=MaterialTheme.typography.titleLarge)
                                Text(member.role.label()+if(member.restricted) " · Restricted from sending" else "",
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(member.isCoordinator && member.role==GroupRole.ADMIN)
                                    Text("This admin is currently coordinating group updates.",
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if(!member.isLocal && state.contacts.any {it.contact.remoteDeviceId==member.deviceId &&
                        !it.contact.request && !it.contact.blocked}) item {
                        GroupInfoOption("Open direct chat",true) {actions.openChat(member.deviceId)}
                    }
                    if(info.canRestrict(member)) item {GroupInfoOption(
                        if(member.restricted) "Allow sending" else "Restrict from sending",!busy) {
                        onAction(if(member.restricted) MemberAction.UNRESTRICT else MemberAction.RESTRICT)
                    }}
                    if(info.canPromote(member)) item {GroupInfoOption("Make admin",!busy) {
                        onAction(MemberAction.PROMOTE)
                    }}
                    if(info.canDemote(member)) item {GroupInfoOption("Remove admin role",!busy) {
                        onAction(MemberAction.DEMOTE)
                    }}
                    if(info.canTransfer(member)) item {GroupInfoOption("Transfer ownership",!busy) {
                        onAction(MemberAction.TRANSFER)
                    }}
                    if(info.canRemove(member)) item {GroupInfoOption("Remove from group",!busy) {
                        onAction(MemberAction.REMOVE)
                    }}
                }
                GroupInfoPage.CONFIRM_MEMBER -> if(member!=null && action!=null) {
                    val name=member.label(state)
                    val question=when(action) {
                        MemberAction.RESTRICT -> "Restrict $name from sending?"
                        MemberAction.UNRESTRICT -> "Allow $name to send?"
                        MemberAction.REMOVE -> "Remove $name from this group?"
                        MemberAction.PROMOTE -> "Make $name an admin?"
                        MemberAction.DEMOTE -> "Remove $name's admin role?"
                        MemberAction.TRANSFER -> "Transfer ownership to $name?"
                    }
                    val explanation=when(action) {
                        MemberAction.RESTRICT -> "They'll remain in the group and continue receiving messages, but won't be able to send until allowed again."
                        MemberAction.REMOVE -> "They will stop receiving new group messages after the signed group update is applied. Messages they already received cannot be recalled."
                        MemberAction.TRANSFER -> "After transfer, $name will control owner-only group settings. You will become an admin. They must accept first."
                        else -> "This change will be shared with the group after the signed update is accepted."
                    }
                    item {GroupInfoExplanation(question,explanation)}
                    item {Button(onClick={
                        onPage(GroupInfoPage.OVERVIEW)
                        when(action) {
                            MemberAction.RESTRICT -> actions.restrict(member.memberId)
                            MemberAction.UNRESTRICT -> actions.unrestrict(member.memberId)
                            MemberAction.REMOVE -> actions.remove(member.memberId)
                            MemberAction.PROMOTE -> actions.promote(member.memberId)
                            MemberAction.DEMOTE -> actions.demote(member.memberId)
                            MemberAction.TRANSFER -> actions.transfer(member.memberId)
                        }
                    },enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("group-confirm-action")) {
                        Text("Confirm")
                    }}
                    item {OutlinedButton(onClick=back,modifier=Modifier.fillMaxWidth()) {Text("Cancel")}}
                }
                GroupInfoPage.POSTING -> {
                    item {Text("Choose who can send messages to this group.",
                        color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(GroupPostingModeV1.entries) {mode ->
                        val label=if(mode==GroupPostingModeV1.EVERYONE) "Everyone" else "Admins only"
                        GroupInfoOption(label+if(mode==info.postingMode) " · Current" else "",!busy) {
                            onPage(GroupInfoPage.OVERVIEW)
                            if(mode!=info.postingMode) actions.posting(mode)
                        }
                    }
                }
                GroupInfoPage.EDIT_PROFILE -> Unit
                GroupInfoPage.INVITE -> {
                    val existing=info.members.map {it.deviceId}.toSet()
                    val candidates=state.contacts.filter {!it.contact.request && !it.contact.blocked &&
                        it.contact.remoteDeviceId !in existing && it.groupCapable &&
                        it.session==SessionLifecycle.ACTIVE &&
                        it.identity?.trustState!=IdentityTrustState.CHANGED}
                    if(candidates.isEmpty()) item {GroupInfoExplanation("No contacts ready",
                        "No accepted contacts are ready for a group invitation.")}
                    items(candidates,key={it.contact.remoteDeviceId}) {candidate ->
                        GroupInfoOption(candidate.contact.visibleName,!busy) {
                            onPage(GroupInfoPage.OVERVIEW)
                            actions.invite(candidate.contact.remoteDeviceId)
                        }
                    }
                }
                GroupInfoPage.CONFIRM_LEAVE -> {
                    item {GroupInfoExplanation("Leave this group?",
                        "You will no longer receive new group messages. Your existing history stays on this device.")}
                    item {Button(onClick={onPage(GroupInfoPage.OVERVIEW);actions.leave()},enabled=!busy,
                        modifier=Modifier.fillMaxWidth()) {Text("Leave group")}}
                    item {OutlinedButton(onClick=back,modifier=Modifier.fillMaxWidth()) {Text("Cancel")}}
                }
                GroupInfoPage.CONFIRM_END -> {
                    item {GroupInfoExplanation("End this group?",
                        "No one will be able to send new group messages. Existing messages already stored on members' devices are not erased.")}
                    item {Button(onClick={onPage(GroupInfoPage.OVERVIEW);actions.dissolve()},enabled=!busy,
                        modifier=Modifier.fillMaxWidth()) {Text("End group")}}
                    item {OutlinedButton(onClick=back,modifier=Modifier.fillMaxWidth()) {Text("Cancel")}}
                }
                GroupInfoPage.OVERVIEW -> Unit
            }
        }
    }
}

@Composable private fun GroupInfoOption(label:String,enabled:Boolean,onClick:()->Unit) {
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onClick).padding(16.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Text(label,style=MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable private fun GroupInfoExplanation(title:String,body:String) {
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
