package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.NetworkCodec

/** Versioned application controls carried only inside an authenticated one-to-one Signal envelope. */
enum class GroupControlKind { INVITE, ACCEPT, INVITE_EXPIRED, STATE_UPDATE, RESYNC_REQUEST,
    RESYNC_RESPONSE, ADMISSION_REQUEST, ADMISSION_RESPONSE }

@Serializable data class GroupControl(
    val version: Int = 1,
    val kind: GroupControlKind,
    val groupId: String,
    val inviteId: String? = null,
    val state: GroupState? = null,
    val genesis: GroupGenesis? = null,
    val admission: GroupAdmission? = null,
    val invite: GroupInvite? = null,
    val transition: GroupTransition? = null,
    val chain: List<GroupTransition> = emptyList(),
    val fromRevision: Long? = null,
    val fromDigest: ByteArray? = null,
    val headRevision: Long? = null,
) {
    override fun toString() = "GroupControl(redacted)"
}

@Serializable data class PendingGroupControl(val senderDeviceId: String, val control: GroupControl) {
    override fun toString() = "PendingGroupControl(redacted)"
}

object GroupControlCodec {
    // Leaves at least 4 KiB below the current 16 KiB application body cap.
    const val MAX_BYTES = 12_000
    fun encode(control: GroupControl): ByteArray {
        validate(control)
        return NetworkCodec.encode(control).also { require(it.size in 1..MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): GroupControl {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupControl>(bytes, MAX_BYTES).also(::validate)
    }
    private fun validate(control: GroupControl) {
        require(control.version == 1 && GroupIds.valid(control.groupId))
        control.inviteId?.let { require(GroupIds.valid(it)) }
        control.fromDigest?.let { require(it.size == 32) }
        control.state?.let { GroupStatements.validate(it); require(it.groupId == control.groupId) }
        control.genesis?.let { GroupStatements.validate(it.state); require(it.state.groupId == control.groupId) }
        control.admission?.let { GroupStatements.validate(it.state); require(it.state.groupId == control.groupId) }
        control.invite?.let { require(it.groupId == control.groupId) }
        control.transition?.let { GroupStatements.validateEventShape(it); require(it.next.groupId == control.groupId) }
        require(control.chain.size <= GroupStatements.MAX_EVENTS)
        control.chain.forEach { GroupStatements.validateEventShape(it); require(it.next.groupId == control.groupId) }
        when (control.kind) {
            GroupControlKind.INVITE_EXPIRED -> require(control.inviteId!=null && control.state==null &&
                control.genesis==null && control.admission==null && control.invite==null &&
                control.transition==null && control.chain.isEmpty() && control.fromRevision==null &&
                control.fromDigest==null && control.headRevision==null)
            GroupControlKind.ADMISSION_REQUEST, GroupControlKind.ADMISSION_RESPONSE -> require(
                control.state != null && control.admission != null && control.invite != null &&
                control.inviteId == control.invite.inviteId && control.genesis == null &&
                control.transition == null && control.chain.isEmpty() && control.fromRevision == null &&
                control.fromDigest == null && control.headRevision == null &&
                control.admission.inviteId == control.inviteId &&
                GroupStatements.digest(control.admission.state).contentEquals(GroupStatements.digest(control.state)) &&
                GroupStatements.digestMember(control.admission.target).contentEquals(
                    GroupStatements.digestMember(control.invite.target)) &&
                runCatching {GroupStatements.validateOfferShape(control.invite)}.isSuccess)
            GroupControlKind.INVITE -> require(control.invite != null && control.admission != null &&
                control.state != null && control.genesis == null && control.inviteId == control.invite.inviteId &&
                control.admission.inviteId == control.inviteId &&
                GroupStatements.digestMember(control.admission.target).contentEquals(
                    GroupStatements.digestMember(control.invite.target)) &&
                GroupStatements.digest(control.admission.state).contentEquals(GroupStatements.digest(control.state)) &&
                control.invite.parentDigest.contentEquals(GroupStatements.digest(control.state)) &&
                control.transition == null && control.fromRevision == null && control.fromDigest == null &&
                control.headRevision == null && control.chain.isEmpty() &&
                runCatching {GroupStatements.validateOfferShape(control.invite)}.isSuccess)
            GroupControlKind.ACCEPT -> require(control.invite != null && control.inviteId == control.invite.inviteId &&
                control.state == null && control.genesis == null && control.admission == null &&
                control.transition == null && control.fromRevision == null && control.fromDigest == null &&
                control.headRevision == null && control.chain.isEmpty() &&
                runCatching {GroupStatements.decodeInvite(NetworkCodec.encode(control.invite))}.isSuccess)
            GroupControlKind.STATE_UPDATE -> require(control.transition != null && control.state == null &&
                control.genesis == null && control.admission == null && control.invite == null &&
                control.inviteId == null && control.fromRevision == null && control.fromDigest == null &&
                control.headRevision == null && control.chain.isEmpty())
            GroupControlKind.RESYNC_REQUEST -> require(control.fromRevision != null && control.fromRevision >= 1 &&
                control.fromDigest != null && control.state == null && control.genesis == null && control.admission == null &&
                control.invite == null && control.inviteId == null && control.transition == null &&
                control.headRevision == null && control.chain.isEmpty())
            GroupControlKind.RESYNC_RESPONSE -> require(control.state != null && control.chain.isNotEmpty() &&
                control.genesis == null && control.admission == null && control.invite == null &&
                control.inviteId == null && control.transition == null && control.fromRevision == null &&
                control.fromDigest == null && control.headRevision != null &&
                control.headRevision >= control.state.revision &&
                GroupStatements.digest(control.chain.last().next).contentEquals(GroupStatements.digest(control.state)))
        }
    }
}
