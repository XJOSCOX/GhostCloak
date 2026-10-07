package org.ghostcloak.app.ui.screens

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
import org.ghostcloak.app.application.AppState
import org.ghostcloak.app.ui.components.PageHeader
import org.ghostcloak.app.ui.theme.GhostLayout
import org.ghostcloak.messaging.*
import org.ghostcloak.identity.IdentityTrustState
import org.ghostcloak.identity.SessionLifecycle

class GroupInfoActions(
    val setup:()->Unit, val posting:(GroupPostingModeV1)->Unit,
    val restrict:(String)->Unit, val unrestrict:(String)->Unit,
    val remove:(String)->Unit, val promote:(String)->Unit, val demote:(String)->Unit,
    val transfer:(String)->Unit, val transferDecision:(Boolean)->Unit,
    val leave:()->Unit, val dissolve:()->Unit, val invite:(String)->Unit,
    val openChat:(String)->Unit,
)

private enum class MemberAction { RESTRICT, UNRESTRICT, REMOVE, PROMOTE, DEMOTE, TRANSFER }
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
    GroupManagementStatus.ACTIVATING -> "Waiting for all members to update group security…"
    GroupManagementStatus.READY -> "Group management is ready."
    GroupManagementStatus.NEEDS_RESYNC -> "Syncing secure group state…"
    GroupManagementStatus.FORKED -> "Group state conflict. Create a new group to continue."
    GroupManagementStatus.LEGACY_INCOMPLETE ->
        "This group uses an earlier development version of group management. Create a new group to use management features."
}

/** IDs are used only for callbacks. No cryptographic identifiers become on-screen labels. */
@Composable fun GroupInfoScreen(state:AppState,info:GroupInfo,back:()->Unit,actions:GroupInfoActions) {
    var selected by remember(info.groupId) {mutableStateOf<GroupInfoMember?>(null)}
    var confirm by remember(info.groupId) {mutableStateOf<Pair<MemberAction,GroupInfoMember>?>(null)}
    var confirmLeave by remember(info.groupId) {mutableStateOf(false)}
    var confirmEnd by remember(info.groupId) {mutableStateOf(false)}
    var postingPicker by remember(info.groupId) {mutableStateOf(false)}
    var inviting by remember(info.groupId) {mutableStateOf(false)}
    val busy=state.loading || info.pending
    val transferRequest=state.groupOwnershipRequests.firstOrNull {it.groupId==info.groupId}
    Column(Modifier.fillMaxSize().imePadding().testTag("group-info")) {
        PageHeader("Group info","${info.members.size} members",back,avatarName="Group")
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(
            horizontal=GhostLayout.pageInset,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Group security",style=MaterialTheme.typography.titleMedium)
                        Text(info.managementStatus.description(),color=MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier=Modifier.testTag("group-management-status"))
                        if(info.ready && !info.capable) Text("Waiting for updated group members.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(info.pending) Text("Group update pending…",color=MaterialTheme.colorScheme.primary)
                        if(info.journalFull) Text(
                            "Group management history is full. Create a new group to make additional management changes.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(info.managementStatus==GroupManagementStatus.NOT_CONFIGURED &&
                            info.localRole in setOf(GroupRole.OWNER,GroupRole.ADMIN) &&
                            info.members.firstOrNull {it.isLocal}?.isCoordinator==true && info.active)
                            Button(onClick=actions.setup,enabled=!busy,modifier=Modifier.testTag("group-setup")) {
                                Text("Enable group management")
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
                        postingPicker=true
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
                    Row(Modifier.fillMaxWidth().clickable {selected=member}
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
                OutlinedButton(onClick={inviting=true},enabled=!busy,
                    modifier=Modifier.fillMaxWidth().testTag("group-add-member")) {Text("Add member")}
            }
            if(info.ready) item {
                when {
                    info.localRole==GroupRole.OWNER -> {
                        Text("Transfer ownership before leaving this group.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(info.canDissolve) TextButton(onClick={confirmEnd=true},enabled=!busy,
                            modifier=Modifier.testTag("group-end")) {Text("End group")}
                    }
                    info.members.firstOrNull {it.isLocal}?.isCoordinator==true ->
                        Text("The group coordinator must change before you can leave.",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    info.canLeave -> TextButton(onClick={confirmLeave=true},enabled=!busy,
                        modifier=Modifier.testTag("group-leave")) {Text("Leave group")}
                }
            }
        }
    }
    if(selected!=null) {
        val member=selected!!
        AlertDialog(onDismissRequest={selected=null},title={Text(member.label(state))},
            text={Column {
                Text(member.role.label())
                if(member.isCoordinator && member.role==GroupRole.ADMIN)
                    Text("This admin is currently coordinating group updates.")
                if(!member.isLocal && state.contacts.any {it.contact.remoteDeviceId==member.deviceId &&
                    !it.contact.request && !it.contact.blocked})
                    TextButton(onClick={selected=null;actions.openChat(member.deviceId)}) {Text("Open direct chat")}
                fun choose(action:MemberAction) {selected=null;confirm=action to member}
                if(info.canRestrict(member)) TextButton(onClick={choose(if(member.restricted)
                    MemberAction.UNRESTRICT else MemberAction.RESTRICT)}) {
                    Text(if(member.restricted) "Allow sending" else "Restrict from sending")
                }
                if(info.canPromote(member)) TextButton(onClick={choose(MemberAction.PROMOTE)}) {Text("Make admin")}
                if(info.canDemote(member)) TextButton(onClick={choose(MemberAction.DEMOTE)}) {Text("Remove admin role")}
                if(info.canTransfer(member)) TextButton(onClick={choose(MemberAction.TRANSFER)}) {Text("Transfer ownership")}
                if(info.canRemove(member)) TextButton(onClick={choose(MemberAction.REMOVE)}) {Text("Remove from group")}
            }},confirmButton={TextButton(onClick={selected=null}) {Text("Close")}})
    }
    if(confirm!=null) {
        val (action,member)=confirm!!
        val name=member.label(state)
        val title=when(action) {
            MemberAction.RESTRICT -> "Restrict $name from sending?"
            MemberAction.UNRESTRICT -> "Allow $name to send?"
            MemberAction.REMOVE -> "Remove $name from this group?"
            MemberAction.PROMOTE -> "Make $name an admin?"
            MemberAction.DEMOTE -> "Remove $name's admin role?"
            MemberAction.TRANSFER -> "Transfer ownership to $name?"
        }
        val body=when(action) {
            MemberAction.RESTRICT -> "They'll remain in the group and continue receiving messages, but won't be able to send until allowed again."
            MemberAction.REMOVE -> "They will stop receiving new group messages after the signed group update is applied. Messages they already received cannot be recalled."
            MemberAction.TRANSFER -> "After transfer, $name will control owner-only group settings. You will become an admin. They must accept first."
            else -> "This change will be shared with the group after the signed update is accepted."
        }
        AlertDialog(onDismissRequest={confirm=null},title={Text(title)},text={Text(body)},
            confirmButton={TextButton(onClick={
                confirm=null
                when(action) {
                    MemberAction.RESTRICT -> actions.restrict(member.memberId)
                    MemberAction.UNRESTRICT -> actions.unrestrict(member.memberId)
                    MemberAction.REMOVE -> actions.remove(member.memberId)
                    MemberAction.PROMOTE -> actions.promote(member.memberId)
                    MemberAction.DEMOTE -> actions.demote(member.memberId)
                    MemberAction.TRANSFER -> actions.transfer(member.memberId)
                }
            },enabled=!busy) {Text("Confirm")}},
            dismissButton={TextButton(onClick={confirm=null}) {Text("Cancel")}})
    }
    if(postingPicker) AlertDialog(onDismissRequest={postingPicker=false},title={Text("Who can send messages")},
        text={Column {
            for(mode in GroupPostingModeV1.entries) TextButton(onClick={
                postingPicker=false
                if(mode!=info.postingMode) actions.posting(mode)
            },enabled=!busy) {Text(if(mode==GroupPostingModeV1.EVERYONE) "Everyone" else "Admins only")}
        }},confirmButton={TextButton(onClick={postingPicker=false}) {Text("Cancel")}})
    if(inviting) {
        val existing=info.members.map {it.deviceId}.toSet()
        val candidates=state.contacts.filter {!it.contact.request && !it.contact.blocked &&
            it.contact.remoteDeviceId !in existing && it.groupCapable &&
            it.session==SessionLifecycle.ACTIVE &&
            it.identity?.trustState!=IdentityTrustState.CHANGED}
        AlertDialog(onDismissRequest={inviting=false},title={Text("Add one member")},
            text={LazyColumn(Modifier.heightIn(max=320.dp)) {
                if(candidates.isEmpty()) item {Text("No accepted contacts are ready for a group invitation.")}
                items(candidates,key={it.contact.remoteDeviceId}) {candidate ->
                    TextButton(onClick={inviting=false;actions.invite(candidate.contact.remoteDeviceId)},
                        enabled=!busy) {Text(candidate.contact.visibleName)}
                }
            }},confirmButton={TextButton(onClick={inviting=false}) {Text("Close")}})
    }
    if(confirmLeave) AlertDialog(onDismissRequest={confirmLeave=false},title={Text("Leave group?")},
        text={Text("You will no longer receive new group messages. Your existing history stays on this device.")},
        confirmButton={TextButton(onClick={confirmLeave=false;actions.leave()},enabled=!busy) {Text("Leave group")}},
        dismissButton={TextButton(onClick={confirmLeave=false}) {Text("Cancel")}})
    if(confirmEnd) AlertDialog(onDismissRequest={confirmEnd=false},title={Text("End this group?")},
        text={Text("No one will be able to send new group messages. Existing messages already stored on members' devices are not erased.")},
        confirmButton={TextButton(onClick={confirmEnd=false;actions.dissolve()},enabled=!busy) {Text("End group")}},
        dismissButton={TextButton(onClick={confirmEnd=false}) {Text("Cancel")}})
}
