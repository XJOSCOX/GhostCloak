package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Separate from every frozen P13.1/P13.2 v1 canonical object. */
@Serializable data class AdmissionProposalV2(
    val version:Int=2,
    val groupId:String,
    val parent:GroupState,
    val parentDigest:ByteArray,
    val inviteId:String,
    val inviterId:String,
    val candidate:GroupMember,
    val candidateDigest:ByteArray,
    val eventId:String,
    val inviterSignature:ByteArray,
) { override fun toString()="AdmissionProposalV2(redacted)" }

@Serializable data class AdmissionApprovalV2(
    val version:Int=2,
    val proposalDigest:ByteArray,
    val parentDigest:ByteArray,
    val approverId:String,
    val candidateDigest:ByteArray,
    val inviteId:String,
    val signature:ByteArray,
) { override fun toString()="AdmissionApprovalV2(redacted)" }

@Serializable data class AdmissionCertificateV2(
    val version:Int=2,
    val proposal:AdmissionProposalV2,
    val approvals:List<AdmissionApprovalV2>,
    val digest:ByteArray,
) { override fun toString()="AdmissionCertificateV2(redacted)" }

/** Signed approval is retained before transmission; there is no unsigned approval flag. */
@Serializable internal data class OwnAdmissionApprovalV2(
    val proposalDigest:ByteArray,
    val parentDigest:ByteArray,
    val parentRevision:Long,
    val inviteId:String,
    val candidateDigest:ByteArray,
    val ownMemberId:String,
    val verificationVersion:Int=2,
    val approval:AdmissionApprovalV2,
)

/** Historical keys cannot satisfy GroupCurrentAuthority.current or direct-contact checks. */
@Serializable internal data class HistoricalGroupAuthorityV2(
    val groupId:String,
    val member:GroupMember,
    val parentRevision:Long,
    val parentDigest:ByteArray,
    val inviteId:String,
    val proposalDigest:ByteArray,
    val certificateDigest:ByteArray,
    val addEventId:String,
    val addRevision:Long,
    val addStateDigest:ByteArray,
)
@Serializable internal data class HistoricalAdmissionBaselineV2(
    val groupId:String,
    val member:GroupMember,
    val admissionDigest:ByteArray,
    val joinParentRevision:Long,
    val joinParentDigest:ByteArray,
)

object AdmissionV2 {
    const val MAX_PROPOSAL_BYTES=5_200
    const val MAX_APPROVAL_BYTES=512
    const val MAX_CERTIFICATE_BYTES=8_800
    private fun same(a:ByteArray,b:ByteArray)=MessageDigest.isEqual(a,b)
    fun proposalDigest(value:AdmissionProposalV2)=DeviceAuth.digest(NetworkCodec.encode(value))
    fun candidateDigest(value:GroupMember)=GroupStatements.digestMember(value)
    fun proposalStatement(value:AdmissionProposalV2)=GroupStatements.admissionProposalV2(
        value.groupId,value.parentDigest,value.parent.revision,value.inviteId,value.inviterId,
        value.candidateDigest,value.eventId)
    fun approvalStatement(value:AdmissionApprovalV2)=GroupStatements.admissionApprovalV2(
        value.proposalDigest,value.parentDigest,value.approverId,value.candidateDigest,value.inviteId)

    fun verifyProposal(value:AdmissionProposalV2,parent:GroupState):Boolean=runCatching {
        require(value.version==2 && GroupIds.valid(value.groupId) && value.groupId==parent.groupId &&
            value.parent.groupId==value.groupId && GroupIds.valid(value.inviteId) && GroupIds.valid(value.eventId) &&
            GroupIds.valid(value.inviterId) && value.parent.revision==parent.revision &&
            same(GroupStatements.digest(value.parent),GroupStatements.digest(parent)) &&
            same(value.parentDigest,GroupStatements.digest(parent)) &&
            same(value.candidateDigest,candidateDigest(value.candidate)) &&
            value.candidate.memberId !in parent.members.map {it.memberId} &&
            value.candidate.accountId !in parent.members.map {it.accountId} &&
            value.candidate.deviceId !in parent.members.map {it.deviceId} &&
            value.candidate.role==GroupRole.MEMBER && value.candidate.joinedEpoch==parent.epoch+1 &&
            parent.lifecycle==GroupLifecycle.ACTIVE && parent.members.size<GroupStatements.MAX_MEMBERS &&
            NetworkCodec.encode(value).size<=MAX_PROPOSAL_BYTES)
        val inviter=parent.members.single {it.memberId==value.inviterId}
        require(inviter.memberId==parent.coordinatorId &&
            GroupStatements.verify(inviter.authPublicKey,proposalStatement(value),value.inviterSignature))
        true
    }.getOrDefault(false)

    fun approval(proposal:AdmissionProposalV2,approverId:String,signature:ByteArray):AdmissionApprovalV2 =
        AdmissionApprovalV2(proposalDigest=proposalDigest(proposal),parentDigest=proposal.parentDigest,
            approverId=approverId,candidateDigest=proposal.candidateDigest,inviteId=proposal.inviteId,
            signature=signature)

    fun verifyApproval(value:AdmissionApprovalV2,proposal:AdmissionProposalV2):Boolean=runCatching {
        require(value.version==2 && same(value.proposalDigest,proposalDigest(proposal)) &&
            same(value.parentDigest,proposal.parentDigest) && same(value.candidateDigest,proposal.candidateDigest) &&
            value.inviteId==proposal.inviteId && NetworkCodec.encode(value).size<=MAX_APPROVAL_BYTES)
        val approver=proposal.parent.members.single {it.memberId==value.approverId}
        require(GroupStatements.verify(approver.authPublicKey,approvalStatement(value),value.signature))
        true
    }.getOrDefault(false)

    fun certificate(proposal:AdmissionProposalV2,approvals:Collection<AdmissionApprovalV2>):AdmissionCertificateV2 {
        val sorted=approvals.sortedBy {it.approverId}
        val digest=DeviceAuth.digest(NetworkCodec.encode(proposal)+NetworkCodec.encode(sorted))
        return AdmissionCertificateV2(proposal=proposal,approvals=sorted,digest=digest)
    }

    fun verifyCertificate(value:AdmissionCertificateV2,parent:GroupState):Boolean=runCatching {
        require(value.version==2 && verifyProposal(value.proposal,parent) &&
            value.approvals.size==parent.members.size &&
            value.approvals.map {it.approverId}==parent.members.map {it.memberId}.sorted() &&
            value.approvals.all {verifyApproval(it,value.proposal)} &&
            same(value.digest,certificate(value.proposal,value.approvals).digest) &&
            NetworkCodec.encode(value).size<=MAX_CERTIFICATE_BYTES)
        true
    }.getOrDefault(false)
}

/** Evidence is encrypted in the same endpoint store as the signed group ledger. */
internal class HistoricalGroupAuthority(private val records:EndpointRecords) {
    private fun key(groupId:String,memberId:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(memberId))
        return "app/group/historical-authority/$groupId/$memberId"
    }
    fun record(parent:GroupState,event:GroupTransition,certificate:AdmissionCertificateV2) {
        require(event.change.action==GroupAction.ADD && AdmissionV2.verifyCertificate(certificate,parent))
        val candidate=event.change.added ?: error("candidate_missing")
        require(MessageDigest.isEqual(AdmissionV2.candidateDigest(candidate),certificate.proposal.candidateDigest) &&
            event.change.eventId==certificate.proposal.eventId &&
            event.change.invite?.inviteId==certificate.proposal.inviteId)
        records.write(key(parent.groupId,candidate.memberId),NetworkCodec.encode(HistoricalGroupAuthorityV2(
            parent.groupId,candidate,parent.revision,certificate.proposal.parentDigest,
            certificate.proposal.inviteId,AdmissionV2.proposalDigest(certificate.proposal),
            certificate.digest,event.change.eventId,event.next.revision,GroupStatements.digest(event.next))))
    }
    fun anchorBaseline(proof:GroupAdmission,member:GroupMember) {
        require(proof.state.members.any {it.memberId==member.memberId &&
            MessageDigest.isEqual(GroupStatements.digestMember(it),GroupStatements.digestMember(member))})
        records.write("app/group/historical-baseline/${proof.state.groupId}/${member.memberId}",
            NetworkCodec.encode(HistoricalAdmissionBaselineV2(proof.state.groupId,member,
                DeviceAuth.digest(NetworkCodec.encode(proof)),proof.state.revision,
                GroupStatements.digest(proof.state))))
    }
    /** A5 is prospective from this exact state; it says nothing about earlier events. */
    fun anchorExistingBaseline(state:GroupState,member:GroupMember,certificateDigest:ByteArray) {
        require(certificateDigest.size==32 && state.members.any {
            it.memberId==member.memberId &&
                MessageDigest.isEqual(GroupStatements.digestMember(it),GroupStatements.digestMember(member))
        })
        records.write("app/group/historical-existing-baseline/${state.groupId}/${member.memberId}",
            NetworkCodec.encode(HistoricalExistingMemberBaselineV1(state.groupId,member,
                state.revision,GroupStatements.digest(state),certificateDigest)))
    }
    /** Only replay of an event with this parent revision may consult historical evidence. */
    fun matchesAt(groupId:String,member:GroupMember,eventParentRevision:Long):Boolean {
        if(eventParentRevision<1) return false
        val value=records.read(key(groupId,member.memberId))?.let {
            runCatching {NetworkCodec.decode<HistoricalGroupAuthorityV2>(it,1024)}.getOrNull()
        }
        val baseline=records.read("app/group/historical-baseline/$groupId/${member.memberId}")?.let {
            runCatching {NetworkCodec.decode<HistoricalAdmissionBaselineV2>(it,1024)}.getOrNull()
        }
        val existing=records.read("app/group/historical-existing-baseline/$groupId/${member.memberId}")?.let {
            runCatching {NetworkCodec.decode<HistoricalExistingMemberBaselineV1>(it,1024)}.getOrNull()
        }
        fun same(anchor:GroupMember):Boolean=anchor.memberId==member.memberId &&
            anchor.accountId==member.accountId && anchor.deviceId==member.deviceId &&
            MessageDigest.isEqual(anchor.authPublicKey,member.authPublicKey) &&
            MessageDigest.isEqual(anchor.signalIdentityDigest,member.signalIdentityDigest)
        return (value?.groupId==groupId && eventParentRevision>=value.addRevision &&
            same(value.member)) ||
            (baseline?.groupId==groupId && eventParentRevision>=baseline.joinParentRevision &&
                same(baseline.member)) ||
            (existing?.groupId==groupId && eventParentRevision>=existing.fromRevision &&
                same(existing.member))
    }
}
