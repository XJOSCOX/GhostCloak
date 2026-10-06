package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.SecureRandom
import java.security.Signature
import java.security.GeneralSecurityException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Dormant P13.1 domain model. Nothing here routes or exposes group content. */
object GroupIds {
    private val random=SecureRandom()
    private val format=Regex("[0-9a-f]{64}")
    fun create():String=ByteArray(32).also { random.nextBytes(it) }.joinToString("") {"%02x".format(it)}
    fun valid(value:String)=format.matches(value)
}

enum class GroupRole { OWNER, ADMIN, MEMBER }
enum class GroupLifecycle { ACTIVE, DISSOLVED }
enum class GroupLocalStatus { INVITED, ACTIVE, FORKED, REMOVED, LEFT, DISSOLVED }
enum class GroupAction { ADD, REMOVE, LEAVE, PROMOTE, DEMOTE, TRANSFER_OWNER, DELEGATE_COORDINATOR, PROFILE, TIMER, DISSOLVE }
enum class GroupApply { ACCEPTED, DUPLICATE, STALE, NEEDS_RESYNC, FORKED, REJECTED, REMOVED }

/** accountId and deviceId are distinct today even though one account has one device. */
@Serializable data class GroupMember(val memberId:String,val accountId:String,val deviceId:String,
    val authPublicKey:ByteArray,val signalIdentityDigest:ByteArray,val role:GroupRole,val joinedEpoch:Long) {
    override fun toString()="GroupMember(redacted)"
}

@Serializable data class GroupState(val groupId:String,val revision:Long,val epoch:Long,
    val ownerId:String,val coordinatorId:String,val members:List<GroupMember>,
    val previousDigest:ByteArray,val lifecycle:GroupLifecycle=GroupLifecycle.ACTIVE,
    val profileRevision:Long=0,val profileDigest:ByteArray=ByteArray(32),val disappearingSeconds:Int=0) {
    override fun toString()="GroupState(redacted)"
}

@Serializable data class GroupInvite(val inviteId:String,val groupId:String,val parentDigest:ByteArray,
    val revision:Long,val epoch:Long,val inviterId:String,val target:GroupMember,
    val inviterSignature:ByteArray,val targetAcceptance:ByteArray) {
    override fun toString()="GroupInvite(redacted)"
}

@Serializable data class GroupChange(val eventId:String,val action:GroupAction,val actorId:String,
    val targetId:String?=null,val added:GroupMember?=null,val invite:GroupInvite?=null,
    val newProfileRevision:Long?=null,val newProfileDigest:ByteArray?=null,val newTimer:Int?=null) {
    override fun toString()="GroupChange(redacted)"
}

@Serializable data class GroupTransition(val change:GroupChange,val next:GroupState,
    val actorSignature:ByteArray,val coordinatorSignature:ByteArray,val targetSignature:ByteArray=byteArrayOf()) {
    override fun toString()="GroupTransition(redacted)"
}

@Serializable data class GroupGenesis(val state:GroupState,val ownerSignature:ByteArray) {
    override fun toString()="GroupGenesis(redacted)"
}

/** A new member's bounded current-state anchor; it conveys no earlier roster history. */
@Serializable data class GroupAdmission(val state:GroupState,val inviteId:String,
    val target:GroupMember,val ownerSignature:ByteArray,val coordinatorSignature:ByteArray) {
    override fun toString()="GroupAdmission(redacted)"
}

/** A snapshot is useful for transport, but a gap is accepted only with its complete signed chain. */
@Serializable data class GroupSnapshot(val finalState:GroupState,val chain:List<GroupTransition>) {
    override fun toString()="GroupSnapshot(redacted)"
}

/** P13.3/P13.5 must bind this context inside each authenticated Signal payload/descriptor. */
@Serializable data class GroupMessageContext(val groupId:String,val epoch:Long,
    val senderId:String,val messageId:String) {
    fun allowedBy(state:GroupState):Boolean=state.lifecycle==GroupLifecycle.ACTIVE &&
        groupId==state.groupId && epoch==state.epoch && GroupIds.valid(messageId) &&
        state.members.any {it.memberId==senderId}
}

/** Authoritative peer binding must be established by an accepted Signal contact in P13.2. */
fun interface GroupTrustedPeer { fun matches(member:GroupMember):Boolean }
fun interface GroupSigner { fun sign(statement:ByteArray):ByteArray }

object GroupStatements {
    const val MAX_MEMBERS=5
    const val MAX_EVENTS=512
    const val MAX_INVITES=256
    const val MAX_STATE_BYTES=4096
    const val MAX_EVENT_BYTES=8192
    const val MAX_INVITE_BYTES=2048
    const val MAX_LEDGER_BYTES=4_194_304
    private const val DOMAIN="GhostCloak.GroupState.v1"
    private const val GENESIS_DOMAIN="GhostCloak.GroupGenesis.v1"
    private const val INVITE_DOMAIN="GhostCloak.GroupInvite.v1"
    private const val ACCEPT_DOMAIN="GhostCloak.GroupAccept.v1"
    private const val ACTOR_DOMAIN="GhostCloak.GroupActor.v1"
    private const val COORDINATOR_DOMAIN="GhostCloak.GroupCoordinator.v1"
    private const val TRANSFER_DOMAIN="GhostCloak.GroupOwnerTransfer.v1"
    private const val ADMISSION_DOMAIN="GhostCloak.GroupAdmission.v1"
    private const val ADMISSION_PROPOSAL_V2_DOMAIN="GhostCloak.GroupAdmissionProposal.v2"
    private const val ADMISSION_APPROVAL_V2_DOMAIN="GhostCloak.GroupAdmissionApproval.v2"
    private val SIGNING_DOMAINS=setOf(GENESIS_DOMAIN,INVITE_DOMAIN,ACCEPT_DOMAIN,ACTOR_DOMAIN,
        COORDINATOR_DOMAIN,TRANSFER_DOMAIN,ADMISSION_DOMAIN,ADMISSION_PROPOSAL_V2_DOMAIN,
        ADMISSION_APPROVAL_V2_DOMAIN)
    private val zero=ByteArray(32)

    fun digest(state:GroupState):ByteArray=DeviceAuth.digest(bytes(state))
    fun digestMember(member:GroupMember):ByteArray=DeviceAuth.digest(NetworkCodec.encode(member))
    fun bytes(state:GroupState):ByteArray {
        validate(state)
        return NetworkCodec.encode(state).also {require(it.size<=MAX_STATE_BYTES)}
    }
    fun decodeState(bytes:ByteArray):GroupState {
        require(bytes.size in 1..MAX_STATE_BYTES)
        return NetworkCodec.decode<GroupState>(bytes,MAX_STATE_BYTES).also(::validate)
    }
    fun decodeTransition(bytes:ByteArray):GroupTransition {
        require(bytes.size in 1..MAX_EVENT_BYTES)
        return NetworkCodec.decode<GroupTransition>(bytes,MAX_EVENT_BYTES).also(::validateEventShape)
    }
    fun decodeInvite(bytes:ByteArray):GroupInvite {
        require(bytes.size in 1..MAX_INVITE_BYTES)
        return NetworkCodec.decode<GroupInvite>(bytes,MAX_INVITE_BYTES).also(::validateInviteShape)
    }
    fun validate(state:GroupState) {
        require(GroupIds.valid(state.groupId) && state.revision in 1..MAX_EVENTS.toLong() && state.epoch in 1..state.revision)
        require(state.previousDigest.size==32 && state.profileDigest.size==32 && state.profileRevision>=0)
        require(state.members.size in 1..MAX_MEMBERS)
        require(state.members.map {it.memberId}==state.members.map {it.memberId}.sorted() &&
            state.members.map {it.memberId}.distinct().size==state.members.size)
        require(state.members.map {it.accountId}.distinct().size==state.members.size &&
            state.members.map {it.deviceId}.distinct().size==state.members.size)
        state.members.forEach {member ->
            require(GroupIds.valid(member.memberId) && RandomIdentifiers.valid(member.accountId) &&
                RandomIdentifiers.valid(member.deviceId) && member.signalIdentityDigest.size==32 &&
                member.joinedEpoch in 1..state.epoch)
            DeviceAuth.publicKey(member.authPublicKey)
        }
        require(state.members.singleOrNull {it.role==GroupRole.OWNER}?.memberId==state.ownerId)
        require(state.members.any {it.memberId==state.coordinatorId && it.role!=GroupRole.MEMBER})
        DisappearingTimer.from(state.disappearingSeconds)
        if(state.revision==1L) require(state.epoch==1L && state.previousDigest.contentEquals(zero) &&
            state.ownerId==state.coordinatorId && state.members.size==1 && state.profileRevision==0L &&
            state.lifecycle==GroupLifecycle.ACTIVE)
        require(NetworkCodec.encode(state).size<=MAX_STATE_BYTES)
    }
    fun validateChange(change:GroupChange) {
        require(GroupIds.valid(change.eventId) && GroupIds.valid(change.actorId))
        change.targetId?.let {require(GroupIds.valid(it))}
        change.added?.let {require(GroupIds.valid(it.memberId))}
        require(change.newProfileDigest==null || change.newProfileDigest.size==32)
        change.invite?.let(::validateInviteShape)
        require(NetworkCodec.encode(change).size<=MAX_EVENT_BYTES)
    }
    fun validateEventShape(event:GroupTransition) {
        validate(event.next);validateChange(event.change)
        require(event.actorSignature.size in 8..80 && event.coordinatorSignature.size in 8..80 &&
            (event.targetSignature.isEmpty() || event.targetSignature.size in 8..80))
        require(NetworkCodec.encode(event).size<=MAX_EVENT_BYTES)
    }
    private fun validateInviteShape(invite:GroupInvite) {
        require(GroupIds.valid(invite.inviteId) && GroupIds.valid(invite.groupId) &&
            GroupIds.valid(invite.inviterId) && invite.parentDigest.size==32 && invite.revision in 1..MAX_EVENTS.toLong() &&
            invite.epoch in 1..invite.revision && invite.target.role==GroupRole.MEMBER &&
            invite.target.joinedEpoch==invite.epoch+1 && invite.inviterSignature.size in 8..80 &&
            invite.targetAcceptance.size in 8..80)
        require(GroupIds.valid(invite.target.memberId) && RandomIdentifiers.valid(invite.target.accountId) &&
            RandomIdentifiers.valid(invite.target.deviceId) && invite.target.signalIdentityDigest.size==32)
        DeviceAuth.publicKey(invite.target.authPublicKey)
        require(NetworkCodec.encode(invite).size<=MAX_INVITE_BYTES)
    }
    private fun statement(domain:String,vararg fields:ByteArray):ByteArray=ByteArrayOutputStream().also {out ->
        DataOutputStream(out).use {data ->
            data.writeInt(1)
            (listOf(domain.toByteArray(Charsets.US_ASCII))+fields).forEach {field ->
                data.writeInt(field.size);data.write(field)
            }
        }
    }.toByteArray()
    private fun number(value:Long)=ByteBuffer.allocate(8).putLong(value).array()
    private fun text(value:String)=value.toByteArray(Charsets.UTF_8)
    fun admissionProposalV2(groupId:String,parentDigest:ByteArray,revision:Long,inviteId:String,
        inviterId:String,candidateDigest:ByteArray,eventId:String):ByteArray =
        statement(ADMISSION_PROPOSAL_V2_DOMAIN,text(groupId),parentDigest,number(revision),text(inviteId),
            text(inviterId),candidateDigest,text(eventId))
    fun admissionApprovalV2(proposalDigest:ByteArray,parentDigest:ByteArray,
        approverId:String,candidateDigest:ByteArray,inviteId:String):ByteArray =
        statement(ADMISSION_APPROVAL_V2_DOMAIN,proposalDigest,parentDigest,text(approverId),
            candidateDigest,text(inviteId))
    fun isGroupSigningStatement(bytes:ByteArray):Boolean {
        if(bytes.size !in 16..MAX_EVENT_BYTES) return false
        val header=ByteBuffer.wrap(bytes)
        if(header.int!=1) return false
        val size=header.int
        if(size !in 1..64 || size>header.remaining()) return false
        val domain=ByteArray(size).also { header.get(it) }.toString(Charsets.US_ASCII)
        return domain in SIGNING_DOMAINS
    }
    fun genesis(state:GroupState):ByteArray=statement(GENESIS_DOMAIN,bytes(state))
    fun transition(previous:GroupState,change:GroupChange,next:GroupState):ByteArray {
        validateChange(change)
        require(next.groupId==previous.groupId)
        return statement(DOMAIN,text("transition"),text(previous.groupId),number(previous.revision),
            number(next.revision),number(previous.epoch),number(next.epoch),text(change.actorId),
            NetworkCodec.encode(change),digest(previous),digest(next))
    }
    fun actor(previous:GroupState,change:GroupChange,next:GroupState)=
        statement(ACTOR_DOMAIN,transition(previous,change,next))
    fun coordinator(previous:GroupState,change:GroupChange,next:GroupState)=
        statement(COORDINATOR_DOMAIN,transition(previous,change,next))
    fun transferAcceptance(previous:GroupState,change:GroupChange,next:GroupState)=
        statement(TRANSFER_DOMAIN,transition(previous,change,next))
    fun admission(state:GroupState,inviteId:String,target:GroupMember):ByteArray {
        require(GroupIds.valid(inviteId) && GroupIds.valid(target.memberId))
        return statement(ADMISSION_DOMAIN,digest(state),text(inviteId),digestMember(target))
    }
    fun validateOfferShape(invite:GroupInvite) {
        require(invite.targetAcceptance.isEmpty() && invite.inviterSignature.size in 8..80)
        validateInviteShape(invite.copy(targetAcceptance=ByteArray(8)))
        require(NetworkCodec.encode(invite).size<=MAX_INVITE_BYTES)
    }
    fun verifyAdmission(proof:GroupAdmission,trusted:GroupTrustedPeer):Boolean {
        val state=proof.state
        if(runCatching {
                validate(state)
                require(GroupIds.valid(proof.inviteId) && GroupIds.valid(proof.target.memberId) &&
                    RandomIdentifiers.valid(proof.target.accountId) && RandomIdentifiers.valid(proof.target.deviceId) &&
                    proof.target.role==GroupRole.MEMBER && proof.target.joinedEpoch==state.epoch+1 &&
                    proof.target.signalIdentityDigest.size==32)
                DeviceAuth.publicKey(proof.target.authPublicKey)
                require(proof.ownerSignature.size in 8..80 && proof.coordinatorSignature.size in 8..80)
            }.isFailure) return false
        val owner=state.members.singleOrNull {it.memberId==state.ownerId} ?: return false
        val coordinator=state.members.singleOrNull {it.memberId==state.coordinatorId} ?: return false
        if(!trusted.matches(owner) || !trusted.matches(coordinator)) return false
        val body=admission(state,proof.inviteId,proof.target)
        return verify(owner.authPublicKey,body,proof.ownerSignature) &&
            verify(coordinator.authPublicKey,body,proof.coordinatorSignature)
    }
    fun invite(invite:GroupInvite):ByteArray=statement(INVITE_DOMAIN,text(invite.inviteId),text(invite.groupId),
        invite.parentDigest,number(invite.revision),number(invite.epoch),text(invite.inviterId),NetworkCodec.encode(invite.target))
    fun acceptance(invite:GroupInvite):ByteArray=statement(ACCEPT_DOMAIN,DeviceAuth.digest(invite(invite)),invite.inviterSignature)
    fun verify(publicKey:ByteArray,statement:ByteArray,signature:ByteArray):Boolean {
        if(signature.size !in 8..80) return false
        return try {Signature.getInstance("SHA256withECDSA").run {
            initVerify(DeviceAuth.publicKey(publicKey));update(statement);verify(signature)
        }} catch(_:GeneralSecurityException) {false}
    }
    fun verifyInvite(invite:GroupInvite,previous:GroupState,used:Set<String>):Boolean {
        validateInviteShape(invite)
        if(invite.inviteId in used || invite.groupId!=previous.groupId ||
            invite.revision!=previous.revision || invite.epoch!=previous.epoch ||
            !invite.parentDigest.contentEquals(digest(previous))) return false
        val inviter=previous.members.singleOrNull {it.memberId==invite.inviterId && it.role!=GroupRole.MEMBER} ?: return false
        return verify(inviter.authPublicKey,invite(invite),invite.inviterSignature) &&
            verify(invite.target.authPublicKey,acceptance(invite),invite.targetAcceptance)
    }
    fun verifyOffer(invite:GroupInvite,previous:GroupState,trusted:GroupTrustedPeer):Boolean {
        if(runCatching {validateOfferShape(invite)}.isFailure ||
            invite.groupId!=previous.groupId || invite.revision!=previous.revision ||
            invite.epoch!=previous.epoch || !invite.parentDigest.contentEquals(digest(previous))) return false
        val inviter=previous.members.singleOrNull {it.memberId==invite.inviterId && it.role!=GroupRole.MEMBER}
            ?: return false
        return trusted.matches(inviter) && verify(inviter.authPublicKey,invite(invite),invite.inviterSignature)
    }
}
