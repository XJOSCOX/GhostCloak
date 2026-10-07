package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Additive proof for the newly joining member only; the old checkpoint bytes are untouched. */
@Serializable data class GroupProfileCheckpointV1(
    val version:Int=1,val groupId:String,val inviteId:String,val checkpointId:String,
    val checkpointBodyDigest:ByteArray,val activationDigest:ByteArray,
    val parentSequence:Long,val parentHeadDigest:ByteArray,
    val profile:GroupProfileV1,val profileDigest:ByteArray,
    val ownerSignature:ByteArray=byteArrayOf(),
    val coordinatorSignature:ByteArray=byteArrayOf(),
) { override fun toString()="GroupProfileCheckpointV1(redacted)" }

internal object GroupProfileCheckpointRulesV1 {
    const val MAX_BYTES=1200
    private fun body(value:GroupProfileCheckpointV1)=NetworkCodec.encode(value.copy(
        ownerSignature=byteArrayOf(),coordinatorSignature=byteArrayOf()))
    private fun checkpointBodyDigest(value:GroupGovernanceInviteeCheckpointV1)=
        DeviceAuth.digest(NetworkCodec.encode(value.copy(ownerSignature=byteArrayOf(),
            coordinatorSignature=byteArrayOf())))
    fun unsigned(checkpoint:GroupGovernanceInviteeCheckpointV1,profile:GroupProfileV1)=
        GroupProfileCheckpointV1(groupId=checkpoint.groupId,inviteId=checkpoint.inviteId,
            checkpointId=checkpoint.checkpointId,
            checkpointBodyDigest=checkpointBodyDigest(checkpoint),
            activationDigest=checkpoint.activationDigest,
            parentSequence=checkpoint.parentSequence,parentHeadDigest=checkpoint.parentHeadDigest,
            profile=profile,profileDigest=GroupProfileRulesV1.digest(checkpoint.activationDigest,profile))
    fun ownerStatement(value:GroupProfileCheckpointV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceProfileCheckpointOwner.v1",body(value))
    fun coordinatorStatement(value:GroupProfileCheckpointV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceProfileCheckpointCoordinator.v1",body(value))
    fun verify(value:GroupProfileCheckpointV1,
        checkpoint:GroupGovernanceInviteeCheckpointV1,parent:GroupState,
        requireOwner:Boolean=true):Boolean=runCatching {
        require(value.version==1 && value.groupId==checkpoint.groupId &&
            value.groupId==parent.groupId && value.inviteId==checkpoint.inviteId &&
            value.checkpointId==checkpoint.checkpointId &&
            value.parentSequence==checkpoint.parentSequence &&
            MessageDigest.isEqual(value.checkpointBodyDigest,checkpointBodyDigest(checkpoint)) &&
            MessageDigest.isEqual(value.activationDigest,checkpoint.activationDigest) &&
            MessageDigest.isEqual(value.parentHeadDigest,checkpoint.parentHeadDigest) &&
            MessageDigest.isEqual(value.profileDigest,
                GroupProfileRulesV1.digest(value.activationDigest,value.profile)) &&
            NetworkCodec.encode(value).size<=MAX_BYTES)
        val coordinator=parent.members.single {it.memberId==parent.coordinatorId}
        val owner=parent.members.single {it.memberId==parent.ownerId}
        require(GroupStatements.verify(coordinator.authPublicKey,
            coordinatorStatement(value),value.coordinatorSignature))
        if(requireOwner) require(GroupStatements.verify(owner.authPublicKey,
            ownerStatement(value),value.ownerSignature))
        true
    }.getOrDefault(false)
}

internal class GroupProfileCheckpointStoreV1(private val records:EndpointRecords) {
    private fun key(id:String,kind:String):String {
        require(GroupIds.valid(id));return "app/group/profile-checkpoint-v1/$kind/$id"
    }
    private fun load(id:String,kind:String)=records.read(key(id,kind))?.let {
        NetworkCodec.decode<GroupProfileCheckpointV1>(it,GroupProfileCheckpointRulesV1.MAX_BYTES)
    }
    fun pending(id:String)=records.transaction {load(id,"pending")}
    fun committed(id:String)=records.transaction {load(id,"committed")}
    fun join(id:String)=records.transaction {load(id,"join")}
    fun savePending(value:GroupProfileCheckpointV1)=records.transaction {
        require(NetworkCodec.encode(value).size<=GroupProfileCheckpointRulesV1.MAX_BYTES)
        records.write(key(value.groupId,"pending"),NetworkCodec.encode(value))
    }
    fun commit(id:String)=records.transaction {
        load(id,"pending")?.let {records.write(key(id,"committed"),NetworkCodec.encode(it))}
        records.remove(key(id,"pending"))
    }
    fun saveCommitted(value:GroupProfileCheckpointV1)=records.transaction {
        require(NetworkCodec.encode(value).size<=GroupProfileCheckpointRulesV1.MAX_BYTES)
        records.write(key(value.groupId,"committed"),NetworkCodec.encode(value))
    }
    fun saveJoin(value:GroupProfileCheckpointV1)=records.transaction {
        require(NetworkCodec.encode(value).size<=GroupProfileCheckpointRulesV1.MAX_BYTES)
        records.write(key(value.groupId,"join"),NetworkCodec.encode(value))
    }
    fun clearPending(id:String)=records.transaction {records.remove(key(id,"pending"))}
}
