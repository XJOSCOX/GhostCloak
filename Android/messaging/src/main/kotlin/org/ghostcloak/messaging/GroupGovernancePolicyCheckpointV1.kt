package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Additive, invitee-only proof; frozen A6.3 checkpoint bytes remain unchanged. */
@Serializable data class GroupGovernancePolicyCheckpointV1(
    val version:Int=1,val groupId:String,val inviteId:String,val checkpointId:String,
    val checkpointBodyDigest:ByteArray,val activationDigest:ByteArray,
    val parentSequence:Long,val parentHeadDigest:ByteArray,
    val policy:GroupGovernancePolicyV1,val policyDigest:ByteArray,
    val ownerSignature:ByteArray=byteArrayOf(),
    val coordinatorSignature:ByteArray=byteArrayOf(),
) { override fun toString()="GroupGovernancePolicyCheckpointV1(redacted)" }

internal object GroupGovernancePolicyCheckpointRulesV1 {
    const val MAX_BYTES=1536
    private fun body(value:GroupGovernancePolicyCheckpointV1)=NetworkCodec.encode(value.copy(
        ownerSignature=byteArrayOf(),coordinatorSignature=byteArrayOf()))
    private fun checkpointBodyDigest(checkpoint:GroupGovernanceInviteeCheckpointV1)=
        DeviceAuth.digest(NetworkCodec.encode(checkpoint.copy(ownerSignature=byteArrayOf(),
            coordinatorSignature=byteArrayOf())))
    fun unsigned(checkpoint:GroupGovernanceInviteeCheckpointV1,
        policy:GroupGovernancePolicyV1)=GroupGovernancePolicyCheckpointV1(
        groupId=checkpoint.groupId,inviteId=checkpoint.inviteId,
        checkpointId=checkpoint.checkpointId,
        checkpointBodyDigest=checkpointBodyDigest(checkpoint),
        activationDigest=checkpoint.activationDigest,parentSequence=checkpoint.parentSequence,
        parentHeadDigest=checkpoint.parentHeadDigest,policy=policy,
        policyDigest=GroupGovernancePolicyRulesV1.digest(checkpoint.activationDigest,policy))
    fun ownerStatement(value:GroupGovernancePolicyCheckpointV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernancePolicyCheckpointOwner.v1",body(value))
    fun coordinatorStatement(value:GroupGovernancePolicyCheckpointV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernancePolicyCheckpointCoordinator.v1",body(value))
    fun verify(value:GroupGovernancePolicyCheckpointV1,
        checkpoint:GroupGovernanceInviteeCheckpointV1,parent:GroupState,
        requireOwner:Boolean=true):Boolean=runCatching {
        require(value.version==1 && value.groupId==checkpoint.groupId &&
            value.groupId==parent.groupId && value.inviteId==checkpoint.inviteId &&
            value.checkpointId==checkpoint.checkpointId &&
            value.parentSequence==checkpoint.parentSequence &&
            MessageDigest.isEqual(value.checkpointBodyDigest,checkpointBodyDigest(checkpoint)) &&
            MessageDigest.isEqual(value.activationDigest,checkpoint.activationDigest) &&
            MessageDigest.isEqual(value.parentHeadDigest,checkpoint.parentHeadDigest) &&
            MessageDigest.isEqual(value.policyDigest,
                GroupGovernancePolicyRulesV1.digest(value.activationDigest,value.policy)) &&
            NetworkCodec.encode(value).size<=MAX_BYTES)
        GroupGovernancePolicyRulesV1.validate(value.policy,parent)
        val coordinator=parent.members.single {it.memberId==parent.coordinatorId}
        val owner=parent.members.single {it.memberId==parent.ownerId}
        require(GroupStatements.verify(coordinator.authPublicKey,
            coordinatorStatement(value),value.coordinatorSignature))
        if(requireOwner) require(GroupStatements.verify(owner.authPublicKey,
            ownerStatement(value),value.ownerSignature))
        true
    }.getOrDefault(false)
}

internal class GroupGovernancePolicyCheckpointStoreV1(private val records:EndpointRecords) {
    private fun key(id:String,kind:String):String {
        require(GroupIds.valid(id));return "app/group/governance-policy-checkpoint-v1/$kind/$id"
    }
    private fun load(id:String,kind:String)=records.read(key(id,kind))?.let {
        NetworkCodec.decode<GroupGovernancePolicyCheckpointV1>(it,
            GroupGovernancePolicyCheckpointRulesV1.MAX_BYTES)
    }
    fun pending(id:String)=records.transaction {load(id,"pending")}
    fun savePending(value:GroupGovernancePolicyCheckpointV1)=records.transaction {
        val bytes=NetworkCodec.encode(value)
        require(bytes.size<=GroupGovernancePolicyCheckpointRulesV1.MAX_BYTES)
        records.write(key(value.groupId,"pending"),bytes)
    }
    fun clearPending(id:String)=records.transaction {records.remove(key(id,"pending"))}
    fun committed(id:String)=records.transaction {load(id,"committed")}
    fun saveCommitted(value:GroupGovernancePolicyCheckpointV1)=records.transaction {
        val bytes=NetworkCodec.encode(value)
        require(bytes.size<=GroupGovernancePolicyCheckpointRulesV1.MAX_BYTES)
        records.write(key(value.groupId,"committed"),bytes)
    }
    fun join(id:String)=records.transaction {
        GovernedAdmissionStore(records).joinV2(id)?.policyProof
    }
}
