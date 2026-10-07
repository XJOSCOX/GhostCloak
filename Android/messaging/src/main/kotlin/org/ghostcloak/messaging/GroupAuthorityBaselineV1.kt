package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** A5 evidence is independent of the frozen v1 membership wire formats. */
@Serializable data class GroupAuthorityBaselineMemberV1(val memberId:String,val memberDigest:ByteArray)
@Serializable data class GroupAuthorityBaselineProposalV1(
    val version:Int=1,
    val proposalId:String,
    val groupId:String,
    val state:GroupState,
    val stateDigest:ByteArray,
    val lifecycle:GroupLifecycle,
    val members:List<GroupAuthorityBaselineMemberV1>,
    val memberSetDigest:ByteArray,
    val ownerId:String,
    val coordinatorId:String,
    val coordinatorSignature:ByteArray,
) { override fun toString()="GroupAuthorityBaselineProposalV1(redacted)" }
@Serializable data class GroupAuthorityBaselineApprovalV1(
    val version:Int=1,
    val groupId:String,
    val stateRevision:Long,
    val stateDigest:ByteArray,
    val memberSetDigest:ByteArray,
    val proposalDigest:ByteArray,
    val approverId:String,
    val signature:ByteArray,
) { override fun toString()="GroupAuthorityBaselineApprovalV1(redacted)" }
@Serializable data class GroupAuthorityBaselineCertificateV1(
    val version:Int=1,
    val proposal:GroupAuthorityBaselineProposalV1,
    val approvals:List<GroupAuthorityBaselineApprovalV1>,
    val digest:ByteArray,
) { override fun toString()="GroupAuthorityBaselineCertificateV1(redacted)" }

@Serializable internal data class OwnBaselineApprovalV1(
    val groupId:String,val proposalId:String,val stateRevision:Long,
    val proposalDigest:ByteArray,val memberSetDigest:ByteArray,
    val approval:GroupAuthorityBaselineApprovalV1,
)
@Serializable internal data class PendingBaselineV1(
    val proposal:GroupAuthorityBaselineProposalV1,
    val approvals:List<GroupAuthorityBaselineApprovalV1>,
)
@Serializable internal data class BaselineActivationRecordV1(
    val version:Int=1,val groupId:String,val stateRevision:Long,
    val stateDigest:ByteArray,val certificateDigest:ByteArray,
    val memberSetDigest:ByteArray,
)
@Serializable internal data class HistoricalExistingMemberBaselineV1(
    val groupId:String,val member:GroupMember,val fromRevision:Long,
    val stateDigest:ByteArray,val certificateDigest:ByteArray,
)

object GroupAuthorityBaselineV1 {
    const val MAX_PROPOSAL_BYTES=5_600
    const val MAX_APPROVAL_BYTES=512
    const val MAX_CERTIFICATE_BYTES=9_600
    private fun same(a:ByteArray,b:ByteArray)=MessageDigest.isEqual(a,b)
    fun memberSet(state:GroupState):List<GroupAuthorityBaselineMemberV1> =
        state.members.map {GroupAuthorityBaselineMemberV1(it.memberId,GroupStatements.digestMember(it))}
    fun memberSetDigest(members:List<GroupAuthorityBaselineMemberV1>):ByteArray =
        DeviceAuth.digest(NetworkCodec.encode(members))
    fun proposalDigest(value:GroupAuthorityBaselineProposalV1):ByteArray =
        DeviceAuth.digest(NetworkCodec.encode(value))
    fun proposalStatement(value:GroupAuthorityBaselineProposalV1):ByteArray =
        GroupStatements.baselineProposal(value.groupId,value.state.revision,value.stateDigest,
            value.memberSetDigest,value.proposalId)
    fun approvalStatement(value:GroupAuthorityBaselineApprovalV1):ByteArray =
        GroupStatements.baselineApproval(value.groupId,value.stateRevision,value.stateDigest,
            value.memberSetDigest,value.proposalDigest,value.approverId)
    fun unsignedProposal(state:GroupState,proposalId:String):GroupAuthorityBaselineProposalV1 {
        GroupStatements.validate(state)
        val members=memberSet(state)
        return GroupAuthorityBaselineProposalV1(proposalId=proposalId,groupId=state.groupId,
            state=state,stateDigest=GroupStatements.digest(state),lifecycle=state.lifecycle,
            members=members,memberSetDigest=memberSetDigest(members),ownerId=state.ownerId,
            coordinatorId=state.coordinatorId,coordinatorSignature=byteArrayOf())
    }
    fun verifyProposal(value:GroupAuthorityBaselineProposalV1,current:GroupState):Boolean=runCatching {
        require(value.version==1 && GroupIds.valid(value.proposalId) &&
            value.groupId==current.groupId && value.state.groupId==current.groupId &&
            current.lifecycle==GroupLifecycle.ACTIVE && value.lifecycle==current.lifecycle &&
            value.state.revision==current.revision && value.ownerId==current.ownerId &&
            value.coordinatorId==current.coordinatorId &&
            same(value.stateDigest,GroupStatements.digest(current)) &&
            same(GroupStatements.digest(value.state),value.stateDigest))
        val canonical=memberSet(current)
        require(value.members.size==canonical.size && value.members.map {it.memberId}==
            canonical.map {it.memberId} && value.members.zip(canonical).all {(a,b) ->
                same(a.memberDigest,b.memberDigest)
            } && same(value.memberSetDigest,memberSetDigest(canonical)) &&
            NetworkCodec.encode(value).size<=MAX_PROPOSAL_BYTES)
        val coordinator=current.members.single {it.memberId==current.coordinatorId}
        require(GroupStatements.verify(coordinator.authPublicKey,proposalStatement(value),
            value.coordinatorSignature))
        true
    }.getOrDefault(false)
    fun unsignedApproval(proposal:GroupAuthorityBaselineProposalV1,memberId:String)=
        GroupAuthorityBaselineApprovalV1(groupId=proposal.groupId,
            stateRevision=proposal.state.revision,stateDigest=proposal.stateDigest,
            memberSetDigest=proposal.memberSetDigest,proposalDigest=proposalDigest(proposal),
            approverId=memberId,signature=byteArrayOf())
    fun verifyApproval(value:GroupAuthorityBaselineApprovalV1,
        proposal:GroupAuthorityBaselineProposalV1):Boolean=runCatching {
        require(value.version==1 && value.groupId==proposal.groupId &&
            value.stateRevision==proposal.state.revision &&
            same(value.stateDigest,proposal.stateDigest) &&
            same(value.memberSetDigest,proposal.memberSetDigest) &&
            same(value.proposalDigest,proposalDigest(proposal)) &&
            NetworkCodec.encode(value).size<=MAX_APPROVAL_BYTES)
        val member=proposal.state.members.single {it.memberId==value.approverId}
        require(GroupStatements.verify(member.authPublicKey,approvalStatement(value),value.signature))
        true
    }.getOrDefault(false)
    fun certificate(proposal:GroupAuthorityBaselineProposalV1,
        approvals:Collection<GroupAuthorityBaselineApprovalV1>):GroupAuthorityBaselineCertificateV1 {
        val sorted=approvals.sortedBy {it.approverId}
        return GroupAuthorityBaselineCertificateV1(proposal=proposal,approvals=sorted,
            digest=DeviceAuth.digest(NetworkCodec.encode(proposal)+NetworkCodec.encode(sorted)))
    }
    fun verifyCertificate(value:GroupAuthorityBaselineCertificateV1,current:GroupState):Boolean=runCatching {
        require(value.version==1 && verifyProposal(value.proposal,current) &&
            value.approvals.size==current.members.size &&
            value.approvals.map {it.approverId}==current.members.map {it.memberId}.sorted() &&
            value.approvals.all {verifyApproval(it,value.proposal)} &&
            same(value.digest,certificate(value.proposal,value.approvals).digest) &&
            NetworkCodec.encode(value).size<=MAX_CERTIFICATE_BYTES)
        true
    }.getOrDefault(false)
}

/** Bounded per-group evidence; activation and all anchors share one EndpointRecords transaction. */
internal class GroupAuthorityBaselineStore(private val records:EndpointRecords) {
    private fun key(groupId:String,suffix:String):String {
        require(GroupIds.valid(groupId));return "app/group/baseline-v1/$suffix/$groupId"
    }
    fun pending(groupId:String):PendingBaselineV1?=records.transaction {
        records.read(key(groupId,"pending"))?.let {NetworkCodec.decode(it,12_000)}
    }
    fun pendingGroups():List<String> = records.transaction {
        records.keys("app/group/baseline-v1/pending/").take(64).mapNotNull {
            it.removePrefix("app/group/baseline-v1/pending/").takeIf(GroupIds::valid)
        }
    }
    fun savePending(value:PendingBaselineV1)=records.transaction {
        require(value.approvals.size<=GroupStatements.MAX_MEMBERS)
        val bytes=NetworkCodec.encode(value);require(bytes.size<=12_000)
        val key=key(value.proposal.groupId,"pending")
        require(records.read(key)!=null || records.keys("app/group/baseline-v1/pending/").size<64)
        records.write(key,bytes)
    }
    fun clearPending(groupId:String)=records.transaction {records.remove(key(groupId,"pending"))}
    fun own(groupId:String):OwnBaselineApprovalV1?=records.transaction {
        records.read(key(groupId,"own"))?.let {NetworkCodec.decode(it,1024)}
    }
    fun saveOwn(value:OwnBaselineApprovalV1)=records.transaction {
        val bytes=NetworkCodec.encode(value);require(bytes.size<=1024)
        val key=key(value.groupId,"own")
        require(records.read(key)!=null || records.keys("app/group/baseline-v1/own/").size<64)
        records.write(key,bytes)
    }
    fun clearOwn(groupId:String)=records.transaction {records.remove(key(groupId,"own"))}
    private fun sentKey(groupId:String,proposalId:String,phase:String,deviceId:String):String {
        require(GroupIds.valid(proposalId) && phase in setOf("proposal","approval","certificate"))
        return key(groupId,"sent")+"/$proposalId/$phase/$deviceId"
    }
    fun queued(groupId:String,proposalId:String,phase:String,deviceId:String):Boolean=records.transaction {
        records.read(sentKey(groupId,proposalId,phase,deviceId))!=null
    }
    fun queuedOutbox(groupId:String,proposalId:String,phase:String,deviceId:String):String?=
        records.transaction {records.read(sentKey(groupId,proposalId,phase,deviceId))?.decodeToString()}
    fun markQueued(groupId:String,proposalId:String,phase:String,deviceId:String,outboxId:String)=
        records.transaction {
            val key=sentKey(groupId,proposalId,phase,deviceId)
            require(records.read(key)!=null ||
                records.keys("app/group/baseline-v1/sent/$groupId/").size<15)
            records.write(key,outboxId.toByteArray())
        }
    fun active(groupId:String):BaselineActivationRecordV1?=records.transaction {
        records.read(key(groupId,"active"))?.let {NetworkCodec.decode(it,512)}
    }
    fun certificate(groupId:String):GroupAuthorityBaselineCertificateV1?=records.transaction {
        records.read(key(groupId,"certificate"))?.let {
            NetworkCodec.decode(it,GroupAuthorityBaselineV1.MAX_CERTIFICATE_BYTES)
        }
    }
    fun activate(current:GroupState,certificate:GroupAuthorityBaselineCertificateV1,
        localId:String)=records.transaction {
        require(GroupAuthorityBaselineV1.verifyCertificate(certificate,current))
        val own=own(current.groupId) ?: error("own_baseline_approval_missing")
        val proposal=certificate.proposal
        val signed=certificate.approvals.single {it.approverId==localId}
        require(own.proposalId==proposal.proposalId && own.stateRevision==current.revision &&
            MessageDigest.isEqual(own.proposalDigest,GroupAuthorityBaselineV1.proposalDigest(proposal)) &&
            MessageDigest.isEqual(own.memberSetDigest,proposal.memberSetDigest) &&
            NetworkCodec.encode(own.approval).contentEquals(NetworkCodec.encode(signed)))
        val existing=active(current.groupId)
        if(existing!=null) {
            require(MessageDigest.isEqual(existing.certificateDigest,certificate.digest))
            return@transaction
        }
        val activation=BaselineActivationRecordV1(groupId=current.groupId,
            stateRevision=current.revision,stateDigest=proposal.stateDigest,
            certificateDigest=certificate.digest,memberSetDigest=proposal.memberSetDigest)
        require(NetworkCodec.encode(activation).size<=512)
        val historical=HistoricalGroupAuthority(records)
        current.members.forEach {historical.anchorExistingBaseline(current,it,certificate.digest)}
        records.write(key(current.groupId,"certificate"),NetworkCodec.encode(certificate))
        records.write(key(current.groupId,"active"),NetworkCodec.encode(activation))
        clearPending(current.groupId)
    }
}
