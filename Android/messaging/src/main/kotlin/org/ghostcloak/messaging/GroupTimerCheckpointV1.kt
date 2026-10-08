package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Only a newly joining member may begin at this owner/coordinator-attested current timer. */
@Serializable data class GroupTimerCheckpointV1(
    val version:Int=1,val groupId:String,val inviteId:String,val checkpointId:String,
    val checkpointBodyDigest:ByteArray,val activationDigest:ByteArray,
    val parentSequence:Long,val parentHeadDigest:ByteArray,
    val timer:GroupDisappearingPolicyV1,val timerDigest:ByteArray,
    val ownerSignature:ByteArray=byteArrayOf(),
    val coordinatorSignature:ByteArray=byteArrayOf(),
) { override fun toString()="GroupTimerCheckpointV1(redacted)" }

internal object GroupTimerCheckpointRulesV1 {
    const val MAX_BYTES=900
    private fun body(value:GroupTimerCheckpointV1)=NetworkCodec.encode(value.copy(
        ownerSignature=byteArrayOf(),coordinatorSignature=byteArrayOf()))
    private fun checkpointBodyDigest(value:GroupGovernanceInviteeCheckpointV1)=
        DeviceAuth.digest(NetworkCodec.encode(value.copy(ownerSignature=byteArrayOf(),
            coordinatorSignature=byteArrayOf())))
    fun unsigned(checkpoint:GroupGovernanceInviteeCheckpointV1,timer:GroupDisappearingPolicyV1)=
        GroupTimerCheckpointV1(groupId=checkpoint.groupId,inviteId=checkpoint.inviteId,
            checkpointId=checkpoint.checkpointId,checkpointBodyDigest=checkpointBodyDigest(checkpoint),
            activationDigest=checkpoint.activationDigest,parentSequence=checkpoint.parentSequence,
            parentHeadDigest=checkpoint.parentHeadDigest,timer=timer,
            timerDigest=GroupDisappearingRulesV1.digest(checkpoint.activationDigest,timer))
    fun ownerStatement(value:GroupTimerCheckpointV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceTimerCheckpointOwner.v1",body(value))
    fun coordinatorStatement(value:GroupTimerCheckpointV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceTimerCheckpointCoordinator.v1",body(value))
    fun verify(value:GroupTimerCheckpointV1,checkpoint:GroupGovernanceInviteeCheckpointV1,
        parent:GroupState,requireOwner:Boolean=true):Boolean=runCatching {
        require(value.version==1 && value.groupId==checkpoint.groupId &&
            value.groupId==parent.groupId && value.inviteId==checkpoint.inviteId &&
            value.checkpointId==checkpoint.checkpointId &&
            value.parentSequence==checkpoint.parentSequence &&
            MessageDigest.isEqual(value.checkpointBodyDigest,checkpointBodyDigest(checkpoint)) &&
            MessageDigest.isEqual(value.activationDigest,checkpoint.activationDigest) &&
            MessageDigest.isEqual(value.parentHeadDigest,checkpoint.parentHeadDigest) &&
            MessageDigest.isEqual(value.timerDigest,
                GroupDisappearingRulesV1.digest(value.activationDigest,value.timer)) &&
            checkpoint.timerDigest?.let {MessageDigest.isEqual(it,value.timerDigest)}==true &&
            value.timer.seconds!=0 &&
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

internal class GroupTimerCheckpointStoreV1(private val records:EndpointRecords) {
    private fun key(id:String,kind:String):String {
        require(GroupIds.valid(id));return "app/group/timer-checkpoint-v1/$kind/$id"
    }
    private fun load(id:String,kind:String)=records.read(key(id,kind))?.let {
        NetworkCodec.decode<GroupTimerCheckpointV1>(it,GroupTimerCheckpointRulesV1.MAX_BYTES)
    }
    fun pending(id:String)=records.transaction {load(id,"pending")}
    fun committed(id:String)=records.transaction {load(id,"committed")}
    fun join(id:String)=records.transaction {load(id,"join")}
    fun savePending(value:GroupTimerCheckpointV1)=records.transaction {
        require(NetworkCodec.encode(value).size<=GroupTimerCheckpointRulesV1.MAX_BYTES)
        records.write(key(value.groupId,"pending"),NetworkCodec.encode(value))
    }
    fun commit(id:String)=records.transaction {
        load(id,"pending")?.let {records.write(key(id,"committed"),NetworkCodec.encode(it))}
        records.remove(key(id,"pending"))
    }
    fun saveCommitted(value:GroupTimerCheckpointV1)=records.transaction {
        require(NetworkCodec.encode(value).size<=GroupTimerCheckpointRulesV1.MAX_BYTES)
        records.write(key(value.groupId,"committed"),NetworkCodec.encode(value))
    }
    fun saveJoin(value:GroupTimerCheckpointV1)=records.transaction {
        require(NetworkCodec.encode(value).size<=GroupTimerCheckpointRulesV1.MAX_BYTES)
        records.write(key(value.groupId,"join"),NetworkCodec.encode(value))
    }
    fun clearPending(id:String)=records.transaction {records.remove(key(id,"pending"))}
}
