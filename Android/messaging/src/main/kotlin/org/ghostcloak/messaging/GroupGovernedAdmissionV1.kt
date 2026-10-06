package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** The A3 certificate still signs its frozen parent state; this signs its exact governance head. */
@Serializable data class GovernedAdmissionBindingV1(
    val version:Int=1,val groupId:String,val inviteId:String,val eventId:String,
    val candidateDigest:ByteArray,val certificateDigest:ByteArray,
    val activationDigest:ByteArray,val parentSequence:Long,val parentHeadDigest:ByteArray,
    val parentRevision:Long,val parentStateDigest:ByteArray,
    val coordinatorSignature:ByteArray,
) { override fun toString()="GovernedAdmissionBindingV1(redacted)" }

/** Attests only the invitee's join point. Existing members cannot use it to skip history. */
@Serializable data class GroupGovernanceInviteeCheckpointV1(
    val version:Int=1,val checkpointId:String,val groupId:String,val inviteId:String,
    val candidateDigest:ByteArray,val admissionDigest:ByteArray,val certificateDigest:ByteArray,
    val bindingDigest:ByteArray,val activationDigest:ByteArray,
    val activationStateRevision:Long,val activationStateDigest:ByteArray,
    val baselineCertificateDigest:ByteArray,
    val parentSequence:Long,val parentHeadDigest:ByteArray,
    val parentRevision:Long,val parentStateDigest:ByteArray,val memberSetDigest:ByteArray,
    val ownerId:String,val coordinatorId:String,
    val ownerSignature:ByteArray=byteArrayOf(),
    val coordinatorSignature:ByteArray=byteArrayOf(),
) { override fun toString()="GroupGovernanceInviteeCheckpointV1(redacted)" }

@Serializable internal data class GovernedAdmissionPendingV1(
    val binding:GovernedAdmissionBindingV1,val invite:GroupInvite,
    val proof:GroupAdmission,val certificate:AdmissionCertificateV2,
    val checkpoint:GroupGovernanceInviteeCheckpointV1?=null,
)
@Serializable internal data class GovernedAdmissionReservationV1(
    val inviteId:String,val head:GovernanceHeadFoundationV1,
)
@Serializable internal data class GovernedJoinEvidenceV1(
    val checkpoint:GroupGovernanceInviteeCheckpointV1,
    val binding:GovernedAdmissionBindingV1,
    val proof:GroupAdmission,
    val certificate:AdmissionCertificateV2,
)

internal object GroupGovernedAdmissionV1 {
    const val MAX_BINDING_BYTES=1024
    const val MAX_CHECKPOINT_BYTES=1536
    private fun same(a:ByteArray,b:ByteArray)=MessageDigest.isEqual(a,b)
    fun bindingDigest(value:GovernedAdmissionBindingV1)=DeviceAuth.digest(NetworkCodec.encode(value))
    fun bindingStatement(value:GovernedAdmissionBindingV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceAdmissionBinding.v1",
        NetworkCodec.encode(value.copy(coordinatorSignature=byteArrayOf())))
    fun unsignedBinding(certificate:AdmissionCertificateV2,
        head:GovernanceHeadFoundationV1)=GovernedAdmissionBindingV1(
        groupId=certificate.proposal.groupId,inviteId=certificate.proposal.inviteId,
        eventId=certificate.proposal.eventId,candidateDigest=certificate.proposal.candidateDigest,
        certificateDigest=certificate.digest,activationDigest=head.activationDigest,
        parentSequence=head.sequence,parentHeadDigest=head.headDigest,
        parentRevision=head.stateRevision,parentStateDigest=head.stateDigest,
        coordinatorSignature=byteArrayOf())
    fun verifyBinding(value:GovernedAdmissionBindingV1,parent:GroupState,
        certificate:AdmissionCertificateV2,head:GovernanceHeadFoundationV1?=null):Boolean=runCatching {
        require(value.version==1 && value.groupId==parent.groupId &&
            GroupIds.valid(value.inviteId) && GroupIds.valid(value.eventId) &&
            AdmissionV2.verifyCertificate(certificate,parent) &&
            value.inviteId==certificate.proposal.inviteId &&
            value.eventId==certificate.proposal.eventId &&
            same(value.candidateDigest,certificate.proposal.candidateDigest) &&
            same(value.certificateDigest,certificate.digest) &&
            value.activationDigest.size==32 && value.parentSequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong() &&
            value.parentHeadDigest.size==32 && value.parentRevision==parent.revision &&
            same(value.parentStateDigest,GroupStatements.digest(parent)) &&
            (head==null || (value.parentSequence==head.sequence &&
                same(value.activationDigest,head.activationDigest) &&
                same(value.parentHeadDigest,head.headDigest) &&
                value.parentRevision==head.stateRevision &&
                same(value.parentStateDigest,head.stateDigest))) &&
            NetworkCodec.encode(value).size<=MAX_BINDING_BYTES)
        val coordinator=parent.members.single {it.memberId==parent.coordinatorId}
        require(GroupStatements.verify(coordinator.authPublicKey,
            bindingStatement(value),value.coordinatorSignature))
        true
    }.getOrDefault(false)
    fun checkpointDigest(value:GroupGovernanceInviteeCheckpointV1)=DeviceAuth.digest(NetworkCodec.encode(value))
    private fun body(value:GroupGovernanceInviteeCheckpointV1)=NetworkCodec.encode(value.copy(
        ownerSignature=byteArrayOf(),coordinatorSignature=byteArrayOf()))
    fun ownerStatement(value:GroupGovernanceInviteeCheckpointV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceInviteeCheckpointOwner.v1",body(value))
    fun coordinatorStatement(value:GroupGovernanceInviteeCheckpointV1)=GroupStatements.governanceStatement(
        "GhostCloak.GroupGovernanceInviteeCheckpointCoordinator.v1",body(value))
    fun unsignedCheckpoint(binding:GovernedAdmissionBindingV1,proof:GroupAdmission,
        barrier:GovernanceBarrierV1,parent:GroupState,checkpointId:String)=
        GroupGovernanceInviteeCheckpointV1(checkpointId=checkpointId,groupId=parent.groupId,
            inviteId=binding.inviteId,candidateDigest=binding.candidateDigest,
            admissionDigest=DeviceAuth.digest(NetworkCodec.encode(proof)),
            certificateDigest=binding.certificateDigest,bindingDigest=bindingDigest(binding),
            activationDigest=barrier.activationDigest,
            activationStateRevision=barrier.activationStateRevision,
            activationStateDigest=barrier.activationStateDigest,
            baselineCertificateDigest=barrier.baselineCertificateDigest,
            parentSequence=binding.parentSequence,parentHeadDigest=binding.parentHeadDigest,
            parentRevision=parent.revision,parentStateDigest=GroupStatements.digest(parent),
            memberSetDigest=GroupAuthorityBaselineV1.memberSetDigest(
                GroupAuthorityBaselineV1.memberSet(parent)),
            ownerId=parent.ownerId,coordinatorId=parent.coordinatorId)
    fun verifyCheckpoint(value:GroupGovernanceInviteeCheckpointV1,parent:GroupState,
        proof:GroupAdmission,certificate:AdmissionCertificateV2,
        binding:GovernedAdmissionBindingV1,requireOwner:Boolean=true):Boolean=runCatching {
        require(value.version==1 && GroupIds.valid(value.checkpointId) &&
            value.groupId==parent.groupId && value.inviteId==proof.inviteId &&
            proof.target.memberId==certificate.proposal.candidate.memberId &&
            GroupStatements.verifyAdmission(proof,GroupTrustedPeer {true}) &&
            same(GroupStatements.digest(proof.state),GroupStatements.digest(parent)) &&
            verifyBinding(binding,parent,certificate) &&
            value.inviteId==binding.inviteId &&
            same(value.candidateDigest,binding.candidateDigest) &&
            same(value.admissionDigest,DeviceAuth.digest(NetworkCodec.encode(proof))) &&
            same(value.certificateDigest,certificate.digest) &&
            same(value.bindingDigest,bindingDigest(binding)) &&
            same(value.activationDigest,binding.activationDigest) &&
            value.activationStateRevision in 1..parent.revision &&
            value.activationStateDigest.size==32 && value.baselineCertificateDigest.size==32 &&
            value.parentSequence==binding.parentSequence &&
            same(value.parentHeadDigest,binding.parentHeadDigest) &&
            value.parentRevision==parent.revision &&
            same(value.parentStateDigest,GroupStatements.digest(parent)) &&
            same(value.memberSetDigest,GroupAuthorityBaselineV1.memberSetDigest(
                GroupAuthorityBaselineV1.memberSet(parent))) &&
            value.ownerId==parent.ownerId && value.coordinatorId==parent.coordinatorId &&
            NetworkCodec.encode(value).size<=MAX_CHECKPOINT_BYTES)
        val owner=parent.members.single {it.memberId==parent.ownerId}
        val coordinator=parent.members.single {it.memberId==parent.coordinatorId}
        require(GroupStatements.verify(coordinator.authPublicKey,
            coordinatorStatement(value),value.coordinatorSignature))
        if(requireOwner) require(GroupStatements.verify(owner.authPublicKey,
            ownerStatement(value),value.ownerSignature))
        true
    }.getOrDefault(false)
}

internal class GovernedAdmissionStore(private val records:EndpointRecords) {
    private fun key(id:String,kind:String):String {
        require(GroupIds.valid(id));return "app/group/governed-admission-v1/$kind/$id"
    }
    fun binding(id:String):GovernedAdmissionBindingV1?=records.transaction {
        records.read(key(id,"binding"))?.let {NetworkCodec.decode<GovernedAdmissionBindingV1>(it,1024)}
    }
    fun reservation(id:String):GovernedAdmissionReservationV1?=records.transaction {
        records.read(key(id,"reservation"))?.let {
            NetworkCodec.decode<GovernedAdmissionReservationV1>(it,768)
        }
    }
    fun saveReservation(id:String,value:GovernedAdmissionReservationV1)=records.transaction {
        val bytes=NetworkCodec.encode(value);require(bytes.size<=768)
        records.write(key(id,"reservation"),bytes)
    }
    fun clearReservation(id:String)=records.transaction {records.remove(key(id,"reservation"))}
    fun saveBinding(value:GovernedAdmissionBindingV1)=records.transaction {
        val bytes=NetworkCodec.encode(value);require(bytes.size<=1024)
        records.write(key(value.groupId,"binding"),bytes)
    }
    fun pending(id:String):GovernedAdmissionPendingV1?=records.transaction {
        records.read(key(id,"pending"))?.let {NetworkCodec.decode<GovernedAdmissionPendingV1>(it,32_768)}
    }
    fun savePending(value:GovernedAdmissionPendingV1)=records.transaction {
        val bytes=NetworkCodec.encode(value);require(bytes.size<=32_768)
        records.write(key(value.binding.groupId,"pending"),bytes)
    }
    fun clearPending(id:String)=records.transaction {records.remove(key(id,"pending"))}
    fun pendingGroups():List<String> = records.transaction {
        records.keys("app/group/governed-admission-v1/pending/").take(64)
            .map {it.removePrefix("app/group/governed-admission-v1/pending/")}.filter(GroupIds::valid)
    }
    fun checkpoint(id:String):GroupGovernanceInviteeCheckpointV1?=records.transaction {
        records.read(key(id,"checkpoint"))?.let {
            NetworkCodec.decode<GroupGovernanceInviteeCheckpointV1>(it,1536)
        }
    }
    fun saveCheckpoint(value:GroupGovernanceInviteeCheckpointV1)=records.transaction {
        val bytes=NetworkCodec.encode(value);require(bytes.size<=1536)
        records.write(key(value.groupId,"checkpoint"),bytes)
    }
    fun join(id:String):GovernedJoinEvidenceV1?=records.transaction {
        records.read(key(id,"join"))?.let {NetworkCodec.decode<GovernedJoinEvidenceV1>(it,32_768)}
    }
    fun saveJoin(id:String,value:GovernedJoinEvidenceV1)=records.transaction {
        val bytes=NetworkCodec.encode(value);require(bytes.size<=32_768)
        check(records.read(key(id,"join"))==null)
        records.write(key(id,"join"),bytes)
    }
}
