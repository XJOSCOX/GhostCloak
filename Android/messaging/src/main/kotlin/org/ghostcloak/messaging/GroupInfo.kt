package org.ghostcloak.messaging

/** UI-safe local projection. Member IDs remain internal navigation keys, never labels. */
enum class GroupManagementStatus {
    NOT_CONFIGURED, SETTING_UP_BASELINE, BASELINE_READY, ACTIVATING, READY, NEEDS_RESYNC,
    FORKED, LEGACY_INCOMPLETE
}

data class GroupInfoMember(
    val memberId:String, val deviceId:String, val role:GroupRole,
    val restricted:Boolean, val isLocal:Boolean, val isCoordinator:Boolean,
)

data class GroupInfo(
    val groupId:String, val localRole:GroupRole?, val status:GroupLocalStatus,
    val managementStatus:GroupManagementStatus, val members:List<GroupInfoMember>,
    val postingMode:GroupPostingModeV1, val pending:Boolean,
    val journalFull:Boolean, val invitationPending:Boolean, val capable:Boolean=true,
    val waitingForMemberCheck:Boolean=false,val moderationCapable:Boolean=true,
    val messageControlsCapable:Boolean=false,val mediaCapable:Boolean=false,
    val profile:GroupProfileV1=GroupProfileV1(),val verifiedPhoto:ByteArray?=null,
    val photoPending:Boolean=false,val profileCapable:Boolean=false,
    val governanceSequence:Long=0,val governanceHeadDigest:ByteArray?=null,
) {
    val active get()=status==GroupLocalStatus.ACTIVE
    val ready get()=active && managementStatus==GroupManagementStatus.READY
    val canManage get()=ready && !pending && !journalFull && capable
    val canInvite get()=active && !pending && !journalFull && capable &&
        !invitationPending && members.size<GroupStatements.MAX_MEMBERS &&
        managementStatus in setOf(GroupManagementStatus.NOT_CONFIGURED,GroupManagementStatus.READY) &&
        localRole in setOf(GroupRole.OWNER,GroupRole.ADMIN)
    val canChangePosting get()=canManage && localRole in setOf(GroupRole.OWNER,GroupRole.ADMIN)
    val canModerate get()=canManage && moderationCapable &&
        localRole in setOf(GroupRole.OWNER,GroupRole.ADMIN)
    val canUseMessageControls get()=ready && messageControlsCapable && !pending
    val canUseMedia get()=ready && mediaCapable && !pending
    val canEditProfile get()=canManage && profileCapable &&
        localRole in setOf(GroupRole.OWNER,GroupRole.ADMIN)
    fun canRestrict(target:GroupInfoMember)=canManage && !target.isLocal &&
        target.role!=GroupRole.OWNER && (localRole==GroupRole.OWNER ||
            localRole==GroupRole.ADMIN && target.role==GroupRole.MEMBER)
    fun canRemove(target:GroupInfoMember)=canRestrict(target) && !target.isCoordinator
    fun canPromote(target:GroupInfoMember)=canManage && localRole==GroupRole.OWNER &&
        target.role==GroupRole.MEMBER && !target.isLocal
    fun canDemote(target:GroupInfoMember)=canManage && localRole==GroupRole.OWNER &&
        target.role==GroupRole.ADMIN && !target.isLocal && !target.isCoordinator
    fun canTransfer(target:GroupInfoMember)=canManage && localRole==GroupRole.OWNER &&
        !target.isLocal && target.role!=GroupRole.OWNER
    val canLeave get()=canManage && localRole!=GroupRole.OWNER &&
        members.firstOrNull {it.isLocal}?.isCoordinator==false
    val canDissolve get()=canManage && localRole==GroupRole.OWNER
}
