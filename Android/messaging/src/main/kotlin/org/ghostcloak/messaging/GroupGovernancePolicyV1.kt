package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Separate from every frozen GroupState and GroupTransition v1 encoding. */
@Serializable enum class GroupPostingModeV1 { EVERYONE, ADMINS_ONLY }
@Serializable enum class GroupPolicyActionV1 { SET_POSTING_MODE, RESTRICT_MEMBER, UNRESTRICT_MEMBER }
@Serializable data class GroupGovernancePolicyV1(
    val version:Int=1,
    val postingMode:GroupPostingModeV1=GroupPostingModeV1.EVERYONE,
    val restrictedMemberIds:List<String> = emptyList(),
) { override fun toString()="GroupGovernancePolicyV1(redacted)" }

/** Policy-only action in the same signed governance sequence as wrapped v1 transitions. */
@Serializable data class GroupGovernancePolicyEntryV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,val sequence:Long,
    val previousHeadDigest:ByteArray,val eventId:String,val stateRevision:Long,
    val stateDigest:ByteArray,val actorId:String,val action:GroupPolicyActionV1,
    val targetMemberId:String?=null,val postingMode:GroupPostingModeV1?=null,
    val prePolicyDigest:ByteArray,val postPolicyDigest:ByteArray,
    val actorSignature:ByteArray,val coordinatorSignature:ByteArray,
) { override fun toString()="GroupGovernancePolicyEntryV1(redacted)" }

internal object GroupGovernancePolicyRulesV1 {
    const val MAX_ENTRY_BYTES=2048
    private val initial=GroupGovernancePolicyV1()
    fun initial():GroupGovernancePolicyV1=initial
    fun validate(value:GroupGovernancePolicyV1,state:GroupState) {
        require(value.version==1 && value.restrictedMemberIds.size<=GroupStatements.MAX_MEMBERS &&
            value.restrictedMemberIds==value.restrictedMemberIds.sorted() &&
            value.restrictedMemberIds.distinct().size==value.restrictedMemberIds.size &&
            value.restrictedMemberIds.all {id -> GroupIds.valid(id) && id!=state.ownerId &&
                state.members.any {it.memberId==id}})
    }
    /** The signed activation digest is part of even the initial EVERYONE policy digest. */
    fun digest(activationDigest:ByteArray,value:GroupGovernancePolicyV1):ByteArray {
        require(activationDigest.size==32 && value.version==1 &&
            value.restrictedMemberIds==value.restrictedMemberIds.sorted() &&
            value.restrictedMemberIds.distinct().size==value.restrictedMemberIds.size)
        return DeviceAuth.digest("GhostCloak.GroupGovernancePolicy.v1".encodeToByteArray()+
            activationDigest+NetworkCodec.encode(value))
    }
    fun afterTransition(value:GroupGovernancePolicyV1,next:GroupState):GroupGovernancePolicyV1 {
        val members=next.members.map {it.memberId}.toSet()
        return value.copy(restrictedMemberIds=value.restrictedMemberIds.filter {
            it in members && it!=next.ownerId
        })
    }
    /** No-op policy entries are rejected to keep one canonical effect per signed sequence. */
    fun apply(value:GroupGovernancePolicyV1,state:GroupState,actorId:String,
        action:GroupPolicyActionV1,targetId:String?,mode:GroupPostingModeV1?):GroupGovernancePolicyV1 {
        validate(value,state)
        require(state.lifecycle==GroupLifecycle.ACTIVE)
        val actor=state.members.single {it.memberId==actorId }
        require(actor.role!=GroupRole.MEMBER)
        val next=when(action) {
            GroupPolicyActionV1.SET_POSTING_MODE -> {
                require(targetId==null && mode!=null && mode!=value.postingMode)
                value.copy(postingMode=mode)
            }
            GroupPolicyActionV1.RESTRICT_MEMBER,GroupPolicyActionV1.UNRESTRICT_MEMBER -> {
                require(mode==null && targetId!=null && targetId!=actorId)
                val target=state.members.single {it.memberId==targetId}
                require(target.role!=GroupRole.OWNER &&
                    (actor.role==GroupRole.OWNER || target.role==GroupRole.MEMBER))
                val restricted=targetId in value.restrictedMemberIds
                require(restricted==(action==GroupPolicyActionV1.UNRESTRICT_MEMBER))
                value.copy(restrictedMemberIds=if(restricted)
                    value.restrictedMemberIds.filterNot {it==targetId}
                else (value.restrictedMemberIds+targetId).sorted())
            }
        }
        validate(next,state)
        return next
    }
    fun canSend(value:GroupGovernancePolicyV1,state:GroupState,memberId:String):Boolean {
        val member=state.members.singleOrNull {it.memberId==memberId} ?: return false
        return state.lifecycle==GroupLifecycle.ACTIVE && memberId !in value.restrictedMemberIds &&
            (value.postingMode==GroupPostingModeV1.EVERYONE || member.role!=GroupRole.MEMBER)
    }
    private fun body(value:GroupGovernancePolicyEntryV1)=NetworkCodec.encode(value.copy(
        actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf()))
    fun actorStatement(value:GroupGovernancePolicyEntryV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernancePolicyActor.v1",body(value))
    fun coordinatorStatement(value:GroupGovernancePolicyEntryV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernancePolicyCoordinator.v1",body(value))
    fun entryDigest(value:GroupGovernancePolicyEntryV1)=DeviceAuth.digest(NetworkCodec.encode(value))
    fun verifyEntry(value:GroupGovernancePolicyEntryV1,state:GroupState,
        head:GovernanceHeadFoundationV1,policy:GroupGovernancePolicyV1):Boolean=runCatching {
        require(value.version==1 && value.groupId==state.groupId &&
            MessageDigest.isEqual(value.activationDigest,head.activationDigest) &&
            value.sequence==head.sequence+1 &&
            MessageDigest.isEqual(value.previousHeadDigest,head.headDigest) &&
            value.stateRevision==state.revision &&
            MessageDigest.isEqual(value.stateDigest,GroupStatements.digest(state)) &&
            GroupIds.valid(value.eventId) && value.prePolicyDigest.size==32 &&
            value.postPolicyDigest.size==32 &&
            MessageDigest.isEqual(value.prePolicyDigest,digest(head.activationDigest,policy)))
        val next=apply(policy,state,value.actorId,value.action,value.targetMemberId,value.postingMode)
        require(MessageDigest.isEqual(value.postPolicyDigest,digest(head.activationDigest,next)) &&
            NetworkCodec.encode(value).size<=MAX_ENTRY_BYTES)
        val actor=state.members.single {it.memberId==value.actorId}
        val coordinator=state.members.single {it.memberId==state.coordinatorId}
        require(GroupStatements.verify(actor.authPublicKey,actorStatement(value),value.actorSignature) &&
            GroupStatements.verify(coordinator.authPublicKey,coordinatorStatement(value),
                value.coordinatorSignature))
        true
    }.getOrDefault(false)
}
